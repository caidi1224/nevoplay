package com.shilapi.xcertplay.mfi

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * The fixed locations `MfiTarget.LOCAL_FILES` reads when the two documents were not chosen through
 * the system picker.
 *
 * That case is the reason this exists: a head unit with no document provider has nothing to launch
 * for `ActivityResultContracts.OpenDocument`, so a certificate and key can only be provisioned
 * ahead of time. Two directories are tried, in this order:
 *
 *  1. `Download/xcertplay` — the shared collection the session log already uses, so
 *     `adb push mfi.p7b /sdcard/Download/xcertplay/` puts the material where a file manager can see
 *     it. Android 10 and newer hand out read access to documents another writer contributed only to
 *     apps with the relevant storage access, and even that is gone from Android 11 on; where it is
 *     refused, [state] reports [FixedState.PARTIAL] rather than pretending the files are absent.
 *  2. `Android/data/<package>/files/mfi` — the app's own external directory. No permission is
 *     involved, so a pair pushed here is always readable, on every Android version. It is hidden
 *     from file managers on Android 11+, which does not matter for a file pushed over adb.
 *
 * A source counts as usable only when both files exist, are non-empty and can actually be opened:
 * `File.canRead` answers true on scoped storage for files the process is then not allowed to open,
 * so readability is proven by opening each file once.
 */
object LocalMfiDocuments {
    const val CERTIFICATE_FILE_NAME = "mfi.p7b"
    const val PRIVATE_KEY_FILE_NAME = "mfi.pk8"

    /** Shared Downloads collection, next to the session log. */
    const val DOWNLOAD_DIRECTORY_NAME = "xcertplay"

    /** App-specific external directory, readable without any storage permission. */
    const val APP_DIRECTORY_NAME = "mfi"

    enum class FixedState {
        /** NEITHER file is there. */
        MISSING,

        /** Something is there, but the pair cannot be opened; the message says which file fails. */
        PARTIAL,

        /** Both files are present and readable. */
        USABLE,
    }

    /** One fixed directory and the two names looked for inside it. */
    data class FixedSource(
        val directory: File,
        val label: String,
    ) {
        val certificate: File get() = File(directory, CERTIFICATE_FILE_NAME)
        val privateKey: File get() = File(directory, PRIVATE_KEY_FILE_NAME)

        /** A path worth showing in the settings panel and the session log. */
        val displayPath: String get() = directory.absolutePath
    }

    /** Every fixed location this build knows, in precedence order, whether or not it exists. */
    fun candidates(context: Context): List<FixedSource> =
        listOfNotNull(downloadSource(), appSource(context))

    /**
     * The app-owned directory. Unlike the shared Downloads collection this one needs no storage
     * access at all, so it is where the settings panel sends a user whose push to Downloads turned
     * out to be unreadable.
     */
    fun appSource(context: Context): FixedSource? = context.getExternalFilesDir(null)?.let {
        FixedSource(
            directory = File(it, APP_DIRECTORY_NAME),
            label = "app files/$APP_DIRECTORY_NAME",
        )
    }

    private fun downloadSource(): FixedSource = FixedSource(
        directory = File(downloadDirectory(), DOWNLOAD_DIRECTORY_NAME),
        label = "Download/$DOWNLOAD_DIRECTORY_NAME",
    )

    /** The first fixed directory that holds a readable pair, or null when none does. */
    fun resolve(context: Context): FixedSource? =
        candidates(context).firstOrNull { state(it) == FixedState.USABLE }

    fun state(source: FixedSource): FixedState {
        val certificateReadable = readable(source.certificate)
        val privateKeyReadable = readable(source.privateKey)
        if (certificateReadable && privateKeyReadable) return FixedState.USABLE
        val present = isPresent(source.certificate) || isPresent(source.privateKey)
        return if (present) FixedState.PARTIAL else FixedState.MISSING
    }

    /** Which of the two files cannot be read, for the settings panel. */
    fun unreadableFileNames(source: FixedSource): List<String> = buildList {
        if (!readable(source.certificate)) {
            add(CERTIFICATE_FILE_NAME)
        }
        if (!readable(source.privateKey)) {
            add(PRIVATE_KEY_FILE_NAME)
        }
    }

    private fun isPresent(file: File): Boolean = file.isFile && file.length() > 0L

    /**
     * Proves readability by opening the file: scoped storage can report a file as present and
     * readable and still fail the open with EACCES, which is exactly the case worth reporting.
     */
    private fun readable(file: File): Boolean {
        if (!isPresent(file)) return false
        return runCatching { file.inputStream().use { it.read() } }.isSuccess
    }

    @Suppress("DEPRECATION")
    private fun downloadDirectory(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
}
