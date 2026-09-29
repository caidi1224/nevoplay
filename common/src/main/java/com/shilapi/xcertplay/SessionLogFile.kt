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
}

internal class FileSessionLogSink(private val file: File) : SessionLogSink {
    override val displayPath: String = file.absolutePath

    override fun openTruncating(): OutputStream {
        file.parentFile?.mkdirs()
        return file.outputStream()
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

    /** Reuses the document written by an earlier session so the log stays one file. */
    private fun resolveDocument(resolver: ContentResolver): Uri {
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(LOG_FILE_NAME, "$relativePath%"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
        if (existing != null) return existing

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

    fun reset(header: String) {
        synchronized(lock) {
            if (closed) return
            output?.close()
            output = sink.openTruncating().buffered()
            bytesWritten = 0L
            writeLine(header)
        }
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
        var bytes = line.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size >= MAX_BYTES) {
            var start = bytes.size - (MAX_BYTES - 1)
            while (start < bytes.size && bytes[start].toInt() and 0xc0 == 0x80) start++
            bytes = bytes.copyOfRange(start, bytes.size)
        }
        if (bytesWritten + bytes.size + 1 > MAX_BYTES) {
            output?.close()
            output = sink.openTruncating().buffered()
            bytesWritten = 0L
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
        const val MAX_BYTES = 10 * 1024 * 1024
        const val MAX_PENDING_LINES = 1024
    }
}
