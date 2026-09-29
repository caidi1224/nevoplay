package com.shilapi.xcertplay

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Where a session log is written. The app-private directory
 * (`Android/data/<package>/files/logs`) cannot be opened by file managers or over
 * MTP on Android 11+, so the shared Downloads collection is preferred: it needs no
 * permission and is reachable from a head unit's file browser or over USB.
 */
internal interface SessionLogSink {
    /** Human-readable destination, reported in the log header and in Settings. */
    val displayPath: String

    /** Opens the destination, discarding anything already written there. */
    fun openTruncating(): OutputStream

    /**
     * Opens the destination for appending.
     *
     * Sessions must survive an app restart: a freeze usually ends with the app being force-stopped,
     * and truncating on every start destroyed exactly the evidence needed to explain it.
     */
    fun openAppending(): OutputStream

    /** Bytes already present, or 0 when unknown. */
    fun sizeBytes(): Long

    /**
     * Renames an archived generation by name, independent of which document the sink currently
     * points at. Returns false when that name does not exist.
     */
    fun renameArchived(from: String, to: String): Boolean
}

internal class FileSessionLogSink(private val file: File) : SessionLogSink {
    override val displayPath: String = file.absolutePath

    override fun openTruncating(): OutputStream {
        file.parentFile?.mkdirs()
        return file.outputStream()
    }

    override fun openAppending(): OutputStream {
        file.parentFile?.mkdirs()
        return FileOutputStream(file, true)
    }

    override fun sizeBytes(): Long = if (file.isFile) file.length() else 0L

    override fun renameArchived(from: String, to: String): Boolean {
        val source = File(file.parentFile, from)
        if (!source.isFile) return false
        return runCatching { source.renameTo(File(file.parentFile, to)) }.getOrDefault(false)
    }
}

/** Writes to `Download/xcertplay/xcertplay.log` through the media store. */
@RequiresApi(Build.VERSION_CODES.Q)
internal class MediaStoreSessionLogSink(private val context: Context) : SessionLogSink {
    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$LOG_DIRECTORY_NAME"
    private var documentUri: Uri? = null

    override val displayPath: String = "$relativePath/$LOG_FILE_NAME"

    override fun openTruncating(): OutputStream {
        val resolver = context.contentResolver
        val uri = documentUri ?: resolveDocument(resolver).also { documentUri = it }
        return resolver.openOutputStream(uri, "rwt")
            ?: throw IOException("Log destination $displayPath could not be opened for writing")
    }

    override fun openAppending(): OutputStream {
        val resolver = context.contentResolver
        val uri = documentUri ?: resolveDocument(resolver).also { documentUri = it }
        return resolver.openOutputStream(uri, "wa")
            ?: throw IOException("Log destination $displayPath could not be opened for appending")
    }

    override fun renameArchived(from: String, to: String): Boolean {
        val resolver = context.contentResolver
        val uri = findDocument(resolver, from) ?: return false
        val renamed = runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, to) },
                null,
                null,
            )
        }.getOrDefault(0) > 0
        // The current session may have been renamed out from under this sink.
        if (renamed && from == LOG_FILE_NAME) documentUri = null
        return renamed
    }

    /** Finds an existing document by name without creating one. */
    private fun findDocument(resolver: ContentResolver, name: String): Uri? =
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "$relativePath%"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }

    override fun sizeBytes(): Long {
        val resolver = context.contentResolver
        val uri = documentUri ?: resolveDocument(resolver).also { documentUri = it }
        return runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    /** Reuses the document written by an earlier session so the log stays one file. */
    private fun resolveDocument(resolver: ContentResolver): Uri {
        findDocument(resolver, LOG_FILE_NAME)?.let { return it }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, LOG_FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        return resolver.insert(collection, values)
            ?: throw IOException("Log destination $displayPath could not be created")
    }

    private val collection: Uri
        get() = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    private companion object {
        const val LOG_DIRECTORY_NAME = "xcertplay"
        const val LOG_FILE_NAME = "xcertplay.log"
    }
}

internal class SessionLogFile(private val sink: SessionLogSink) : Closeable {
    /** Destination as shown to the user, e.g. `Download/xcertplay/xcertplay.log`. */
    val destination: String = sink.displayPath

    private val lock = Any()
    private val writerExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_LINES),
        { task -> Thread(task, "xcertplay-log-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private var output: BufferedOutputStream? = null
    private var bytesWritten = 0L
    private var closed = false
    private val lineFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Begins a session. The file is appended to rather than replaced, so a session that ended in a
     * force-stop is still readable afterwards; it is only restarted from scratch once the file has
     * reached [MAX_BYTES], which the writer already enforces while running.
     */
    fun startSession(header: String) {
        synchronized(lock) {
            if (closed) return
            output?.close()
            output = null
            // Rotate rather than replace. A freeze usually ends with the app being force-stopped, and
            // a log that starts over on every launch (or at the size cap) throws away exactly the
            // session that needs explaining. Old generations stay readable instead.
            rotate()
            output = runCatching { sink.openAppending().buffered() }
                .onFailure { append("log destination unavailable: ${it.message}") }
                .getOrNull()
            bytesWritten = 0L
            writeLine(header)
        }
    }

    /** Shifts the archive chain, oldest first, then moves the just-finished session into it. */
    private fun rotate() {
        for (index in ARCHIVE_NAMES.indices.reversed()) {
            val to = ARCHIVE_NAMES.getOrNull(index + 1) ?: continue
            runCatching { sink.renameArchived(ARCHIVE_NAMES[index], to) }
        }
        runCatching { sink.renameArchived(LOG_FILE_NAME, ARCHIVE_NAMES.first()) }
    }

    fun append(line: String) = enqueue { line }

    fun appendTimestamped(message: String, timestampMillis: Long) = enqueue {
        "${lineFormatter.format(Date(timestampMillis))}  $message"
    }

    private fun enqueue(line: () -> String) {
        synchronized(lock) {
            if (closed) return
            writerExecutor.execute {
                try {
                    writeLine(line())
                } catch (_: IOException) {
                    runCatching { output?.close() }
                    output = null
                }
            }
        }
    }

    private fun writeLine(line: String) {
        val redacted = DiagnosticRedactor.redact(line) ?: return
        var bytes = redacted.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size >= MAX_BYTES) {
            var start = bytes.size - (MAX_BYTES - 1)
            while (start < bytes.size && bytes[start].toInt() and 0xc0 == 0x80) start++
            bytes = bytes.copyOfRange(start, bytes.size)
        }
        if (bytesWritten + bytes.size + 1 > MAX_BYTES) {
            output?.close()
            output = null
            // Rotate instead of starting over: the previous generation stays on disk.
            rotate()
            output = runCatching { sink.openAppending().buffered() }.getOrNull() ?: return
            bytesWritten = 0L
            writeLine("log rotated: reached ${MAX_BYTES / 1024} KiB")
        }
        val activeOutput = output ?: return
        activeOutput.write(bytes)
        activeOutput.write('\n'.code)
        activeOutput.flush()
        bytesWritten += bytes.size + 1
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            writerExecutor.execute {
                runCatching { output?.close() }
                output = null
            }
            writerExecutor.shutdown()
        }
        writerExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    private companion object {
        /** Per-file cap. With rotation this bounds one generation, not the whole history. */
        const val MAX_BYTES = 1024 * 1024
        const val LOG_FILE_NAME = "xcertplay.log"
        val ARCHIVE_NAMES = listOf("xcertplay.previous.log") +
            (2..7).map { "xcertplay.previous-$it.log" }
        const val MAX_PENDING_LINES = 1024
    }
}
