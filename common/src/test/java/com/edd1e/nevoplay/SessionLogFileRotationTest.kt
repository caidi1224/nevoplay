package com.edd1e.nevoplay

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotation behaviour at the size cap, including the destination that refuses to move the finished
 * file aside - the case a device log showed, where thirteen "rotations" produced one 14 MB file.
 *
 * Every file a rotation starts must carry the session header: it is the only line that names the
 * build, and a bundle of nine continuations without one cannot be matched to the APK that produced
 * them. That is not hypothetical - it happened, and the build was only recoverable because the
 * first file happened to be in the same upload.
 */
class SessionLogFileRotationTest {
    private companion object {
        /** Small enough to exercise the cap in a few lines. */
        const val CAP_BYTES = 8L * 1024

        /** Distinct from the filler, so "did this file come from the session?" is unambiguous. */
        const val HEADER = "nevoplay log started build=deadbee"
    }

    @Test
    fun aRefusedRotationContinuesInADatedFile() {
        val sink = InMemorySink(refuseRenames = true)
        val log = SessionLogFile(sink, maxBytes = CAP_BYTES)
        log.startSession(HEADER)
        writeFiller(log)
        log.close()

        val fallbackNames = sink.additionalNames.distinct()
        assertEquals(1, fallbackNames.size)
        val fallbackName = fallbackNames.single()
        assertTrue(
            "unexpected fallback name: $fallbackName",
            fallbackName.startsWith("nevoplay-") && fallbackName.endsWith(".log"),
        )
        assertTrue(
            "the fallback file should say where the log continued",
            sink.text(fallbackName)
                .contains("log rotation was refused; continuing in $fallbackName"),
        )
        assertTrue(
            "the continued file must still name the build",
            sink.text(fallbackName).contains(HEADER),
        )
        assertTrue(
            "the header has to come first, before the note explaining the rotation",
            sink.text(fallbackName).startsWith(HEADER),
        )
        // Nothing was archived, so the original file still holds the earlier content.
        assertTrue(sink.text("nevoplay.log").contains(HEADER))
    }

    @Test
    fun aRotationThatWorksStillSplitsTheFile() {
        val sink = InMemorySink(refuseRenames = false)
        val log = SessionLogFile(sink, maxBytes = CAP_BYTES)
        log.startSession(HEADER)
        writeFiller(log)
        log.close()

        assertTrue("no fallback expected", sink.additionalNames.isEmpty())
        assertTrue(sink.text("nevoplay.log").contains("log rotated: reached ${CAP_BYTES / 1024} KiB"))
        // The file the rotation opened is a new file, so it has to name the build itself: it starts
        // with the header rather than with the note explaining why it exists.
        assertTrue(
            "the file the rotation started must name the build as well",
            sink.text("nevoplay.log").startsWith(HEADER),
        )
        // Once in the generation that began the session, once more in the one the rotation opened.
        val occurrences = sink.allText().windowed(HEADER.length).count { it == HEADER }
        assertTrue("the earlier generation must still hold its copy, saw $occurrences", occurrences >= 2)
    }

    @Test
    fun aRefusedFallbackDoesNotRepeatTheHeaderInTheSameFile() {
        val sink = InMemorySink(refuseRenames = true, refuseAdditional = true)
        val log = SessionLogFile(sink, maxBytes = CAP_BYTES)
        log.startSession(HEADER)
        writeFiller(log)
        log.close()

        // Nothing new was opened, so the header must appear exactly once - appending a second copy
        // mid-file would misreport where the session actually began.
        val occurrences = sink.text("nevoplay.log").windowed(HEADER.length).count { it == HEADER }
        assertEquals(1, occurrences)
        assertTrue("no continuation file was opened", sink.additionalNames.isEmpty())
    }

    /** Enough lines to cross [CAP_BYTES]; the redactor caps one line at 700 characters. */
    private fun writeFiller(log: SessionLogFile) {
        val line = "x".repeat(600)
        repeat(40) { log.append(line) }
    }

    private class InMemorySink(
        private val refuseRenames: Boolean,
        private val refuseAdditional: Boolean = false,
    ) : SessionLogSink {
        override val displayPath = "Download/nevoplay/nevoplay.log"
        private val files = linkedMapOf<String, ByteArrayOutputStream>()
        val additionalNames = mutableListOf<String>()

        private fun file(name: String): ByteArrayOutputStream =
            files.getOrPut(name) { ByteArrayOutputStream() }

        override fun openTruncating(): OutputStream = file("nevoplay.log").also { it.reset() }

        override fun openAppending(): OutputStream = file("nevoplay.log")

        override fun sizeBytes(): Long = file("nevoplay.log").size().toLong()

        override fun renameArchived(from: String, to: String): Boolean {
            if (refuseRenames) return false
            val source = files.remove(from) ?: return false
            files[to] = source
            return true
        }

        override fun openAdditional(name: String): OutputStream? {
            if (refuseAdditional) return null
            additionalNames += name
            return file(name)
        }

        fun text(name: String): String =
            files[name]?.toString(Charsets.UTF_8.name()).orEmpty()

        fun allText(): String = files.values.joinToString("") { it.toString(Charsets.UTF_8.name()) }
    }
}
