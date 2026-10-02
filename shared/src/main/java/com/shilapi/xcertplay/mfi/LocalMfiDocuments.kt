package com.shilapi.xcertplay.mfi

import android.content.Context
import android.content.res.AssetManager
import android.os.Environment
import java.io.File
import java.io.InputStream

/**
 * Where `MfiTarget.LOCAL_FILES` reads its certificate and private key when the two documents were
 * not chosen through the system picker, and how each source is checked before it is trusted.
 *
 * Three sources, in precedence order:
 *
 *  1. Documents compiled into the APK (`assets/mfi/mfi.p7b` + `mfi.pk8`). A build that was given the
 *     material at build time carries its own identity; nothing in the repository references it, and
 *     no storage permission is involved. This is the only source left on a head unit that can read
 *     neither the shared Downloads collection nor its own data directory.
 *  2. `Download/xcertplay/mfi.p7b` + `mfi.pk8` — next to the session log, so a file manager can see
 *     the material. Android hands out read access to documents another writer contributed only to
 *     apps holding the relevant storage access, so on newer versions this source can exist and still
 *     be refused.
 *  3. `Android/data/<package>/files/mfi/mfi.p7b` + `mfi.pk8` — the app's own external directory. No
 *     permission is involved, so a pair pushed here is always readable, on every Android version. It
 *     is hidden from file managers on Android 11+, which does not matter for an `adb push`.
 *
 * A source counts as usable only when both documents can actually be opened: scoped storage can
 * report a file as present and readable and still fail the open with EACCES, so readability is
 * proven by opening each document once.
 */
object LocalMfiDocuments {
    const val CERTIFICATE_FILE_NAME = "mfi.p7b"
    const val PRIVATE_KEY_FILE_NAME = "mfi.pk8"

    /** Shared Downloads collection, next to the session log. */
    const val DOWNLOAD_DIRECTORY_NAME = "xcertplay"

    /** App-specific external directory, readable without any storage permission. */
    const val APP_DIRECTORY_NAME = "mfi"

    /** Assets directory `common/build.gradle.kts` copies the material into when it is given it. */
    const val ASSET_DIRECTORY_NAME = "mfi"

    /** What a source looks like from the outside: absent, half there, or ready to sign. */
    enum class SourceState { MISSING, PARTIAL, USABLE }

    /** One place the pair can be read from. Streams returned here belong to the caller. */
    sealed interface Source {
        /** Short name for the session log. */
        val label: String

        /** Where this source looks, for the settings panel and the failure messages. */
        val displayPath: String

        /** The certificate document, or null when this source cannot provide it. */
        fun openCertificate(): InputStream?

        /** The private key document, or null when this source cannot provide it. */
        fun openPrivateKey(): InputStream?
    }

    /**
     * Documents compiled into this build. Only a build that was handed
     * `-Pxcertplay.mfi.certificate` and `-Pxcertplay.mfi.privateKey` has them; every other build
     * simply reports this source as missing.
     */
    class BundledSource(private val assets: AssetManager) : Source {
        override val label: String = "built-in documents"
        override val displayPath: String = "assets/$ASSET_DIRECTORY_NAME (built into this app)"

        override fun openCertificate(): InputStream? = openAsset(CERTIFICATE_FILE_NAME)

        override fun openPrivateKey(): InputStream? = openAsset(PRIVATE_KEY_FILE_NAME)

        private fun openAsset(name: String): InputStream? =
            runCatching { assets.open("$ASSET_DIRECTORY_NAME/$name") }.getOrNull()
    }

    /** A directory the deployment pushed the pair into. */
    class DirectorySource(
        val directory: File,
        override val label: String,
    ) : Source {
        override val displayPath: String get() = directory.absolutePath

        override fun openCertificate(): InputStream? = open(CERTIFICATE_FILE_NAME)

        override fun openPrivateKey(): InputStream? = open(PRIVATE_KEY_FILE_NAME)

        /** The documents this directory holds but cannot open, for the settings panel. */
        fun unreadableFileNames(): List<String> = buildList {
            if (!readable(CERTIFICATE_FILE_NAME)) add(CERTIFICATE_FILE_NAME)
            if (!readable(PRIVATE_KEY_FILE_NAME)) add(PRIVATE_KEY_FILE_NAME)
        }

        internal fun state(): SourceState = when {
            LocalMfiDocuments.isUsable(this) -> SourceState.USABLE
            isPresent(CERTIFICATE_FILE_NAME) || isPresent(PRIVATE_KEY_FILE_NAME) ->
                SourceState.PARTIAL
            else -> SourceState.MISSING
        }

        private fun open(name: String): InputStream? {
            if (!isPresent(name)) return null
            return runCatching { File(directory, name).inputStream() }.getOrNull()
        }

        private fun readable(name: String): Boolean = openAndClose { open(name) }

        private fun isPresent(name: String): Boolean = File(directory, name).let {
            it.isFile && it.length() > 0L
        }
    }

    /** Every source this build can read from, in precedence order. */
    fun sources(context: Context): List<Source> = buildList {
        add(BundledSource(context.assets))
        add(
            DirectorySource(
                directory = File(downloadDirectory(), DOWNLOAD_DIRECTORY_NAME),
                label = "Download/$DOWNLOAD_DIRECTORY_NAME",
            ),
        )
        appDirectory(context)?.let(::add)
    }

    /** The directory-based sources, for messages that tell the user where to put a file. */
    fun directories(context: Context): List<DirectorySource> =
        sources(context).filterIsInstance<DirectorySource>()

    /**
     * The app-owned directory. Unlike the shared Downloads collection this one needs no storage
     * access, so it is where the settings panel sends a user whose push to Downloads turned out to
     * be unreadable.
     */
    fun appDirectory(context: Context): DirectorySource? = context.getExternalFilesDir(null)?.let {
        DirectorySource(
            directory = File(it, APP_DIRECTORY_NAME),
            label = "app files/$APP_DIRECTORY_NAME",
        )
    }

    /** The first source that holds a readable pair, or null when none does. */
    fun resolve(context: Context): Source? = sources(context).firstOrNull(::isUsable)

    /** True when both documents of this source open. Closes what it opens. */
    fun isUsable(source: Source): Boolean =
        openAndClose(source::openCertificate) && openAndClose(source::openPrivateKey)

    fun state(source: Source): SourceState = when (source) {
        // An asset either made it into the APK or it did not: there is no half-present case worth
        // reporting, and one that cannot be opened means the build is broken, not the deployment.
        is BundledSource -> if (isUsable(source)) SourceState.USABLE else SourceState.MISSING
        is DirectorySource -> source.state()
    }

    /**
     * Proves readability by reading one byte: scoped storage can report a file as present and
     * readable and still fail the open with EACCES, which is exactly the case worth reporting.
     */
    private fun openAndClose(open: () -> InputStream?): Boolean =
        open()?.use { it.read() } != null

    @Suppress("DEPRECATION")
    private fun downloadDirectory(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
}
