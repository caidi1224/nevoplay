package com.shilapi.xcertplay

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mirrors this process's logcat output into the session log.
 *
 * The session log is fed by the AirPlay layer's debug callback, while plenty of the media, wireless
 * and P2P code logs through [android.util.Log]. Those lines reached logcat only, which is
 * unreachable on a head unit without adb - so code paths like the Wi-Fi P2P group recovery or the
 * video decoder were invisible in exactly the log a bug report consists of. Three separate
 * investigations were misled by that gap before this was addressed at the source.
 *
 * `logcat --pid=<own pid>` is used deliberately: since Android 4.1 an app may read its own logs
 * without READ_LOGS, and filtering by pid keeps other processes out. If the command is unavailable
 * (some vendor builds restrict it) the failure is written to the session log rather than hidden.
 */
class LogcatTap(private val append: (String) -> Unit) : Closeable {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var process: Process? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread(::run, "xcertplay-logcat").apply {
            isDaemon = true
            start()
        }
    }

    private fun run() {
        try {
            val pid = Process.myPid()
            val child = ProcessBuilder("logcat", "-v", "threadtime", "--pid=$pid")
                .redirectErrorStream(true)
                .start()
            process = child
            append("logcat tap attached pid=$pid")
            child.inputStream.bufferedReader().forEachLine { line ->
                if (closed.get()) return@forEachLine
                append("logcat $line")
            }
            append("logcat tap ended")
        } catch (error: Exception) {
            append("logcat tap unavailable: ${error.message}")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { process?.destroy() }
        process = null
    }
}
