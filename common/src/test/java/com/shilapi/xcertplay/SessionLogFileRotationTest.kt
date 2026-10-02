package com.shilapi.xcertplay

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotation behaviour at the size cap, including the destination that refuses to move the finished
 * file aside - the case a device log showed, where thirteen "rotations" produced one 14 MB file.
 */
class SessionLogFileRotationTest {
    private companion object {
        /** Small enough to exercise the cap in a few lines. */
        const val CAP_BYTES = 8L * 1024
    }

    @Test
    fun aRefusedRotationContinuesInADatedFile() {
        val sink = InMemorySink(refuseRenames = true)
        val log = SessionLogFile(sink, maxBytes = CAP_BYTES)
        log.startSession("session header")
        writeFiller(log)
        log.close()

        val fallbackNames = sink.additionalNames.distinct()
        assertEquals(1, fallbackNames.size)
        val fallbackName = fallbackNames.single()
        assertTrue(
            "unexpected fallback name: $fallbackName",
            fallbackName.startsWith("xcertplay-") && fallbackName.endsWith(".log"),
        )
        assertTrue(
            "the fallback file should say where the log continued",
            sink.text(fallbackName)
                .contains("log rotation was refused; continuing in $fallbackName"),
        )
        // Nothing was archived, so the original file still holds the earlier content.
        assertTrue(sink.text("xcertplay.log").contains("session header"))
    }

    @Test
    fun aRotationThatWorksStillSplitsTheFile() {
        val sink = InMemorySink(refuseRenames = false)
        val log = SessionLogFile(sink, maxBytes = CAP_BYTES)
        log.startSession("session header")
        writeFiller(log)
        log.close()

        assertTrue("no fallback expected", sink.additionalNames.isEmpty())
        assertTrue(
            "the split must move the earlier content out of the active file",
            !sink.text("xcertplay.log").contains("session header"),
        )
        assertTrue(
            "the earlier generation must still be readable",
            sink.allText().contains("session header"),
        )
        assertTrue(sink.text("xcertplay.log").contains("log rotated: reached ${CAP_BYTES / 1024} KiB"))
    }

    /** Enough lines to cross [CAP_BYTES]; the redactor caps one line at 700 characters. */
    private fun writeFiller(log: SessionLogFile) {
        val line = "x".repeat(600)
        repeat(40) { log.append(line) }
    }

    private class InMemorySink(private val refuseRenames: Boolean) : SessionLogSink {
        override val displayPath = "Download/xcertplay/xcertplay.log"
        private val files = linkedMapOf<String, ByteArrayOutputStream>()
        val additionalNames = mutableListOf<String>()

        private fun file(name: String): ByteArrayOutputStream =
            files.getOrPut(name) { ByteArrayOutputStream() }

        override fun openTruncating(): OutputStream = file("xcertplay.log").also { it.reset() }

        override fun openAppending(): OutputStream = file("xcertplay.log")

        override fun sizeBytes(): Long = file("xcertplay.log").size().toLong()

        override fun renameArchived(from: String, to: String): Boolean {
            if (refuseRenames) return false
            val source = files.remove(from) ?: return false
            files[to] = source
            return true
        }

        override fun openAdditional(name: String): OutputStream {
            additionalNames += name
            return file(name)
        }

        fun text(name: String): String =
            files[name]?.toString(Charsets.UTF_8.name()).orEmpty()

        fun allText(): String = files.values.joinToString("") { it.toString(Charsets.UTF_8.name()) }
    }
}
