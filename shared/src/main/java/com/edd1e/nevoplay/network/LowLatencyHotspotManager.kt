package com.edd1e.nevoplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * A lock that asks the platform to keep the Wi-Fi radio out of power save and off the scanning
 * schedule for as long as a CarPlay session is up.
 *
 * The reason this exists is a measurement, not a theory. The audio stats line counts RTP sequence
 * gaps, and a device log shows a single music stream losing seventeen consecutive packets - about
 * 360 ms of audio, which is what the driver hears as a stutter - while the iAP2 control traffic on
 * the same link kept arriving on its half-second cadence throughout
 * (11:36:16.027 / 16.348 / 16.350 / 16.479). UDP audio vanished; TCP survived, because TCP
 * retransmits and UDP does not.
 *
 * That is the signature of a radio that briefly stopped listening: power save, or an off-channel
 * scan, both of which cost UDP bursts while hiding behind TCP's retransmissions. It also explains why
 * the loss appeared on both backends - the hotspot as well as Wi-Fi P2P, both on 5 GHz - so it is not
 * the P2P interface, and why a peer project's log on the same platform family shows the same burst
 * sizes (DiPlay #131, `seqGapMax=18`).
 *
 * [WIFI_MODE_FULL_LOW_LATENCY] is the platform's own answer for this: it is documented for
 * low-latency audio and video, and it also takes the interface off the scanning schedule.
 */
internal interface LowLatencyLock {
    /** Returns true when the lock is actually held; a vendor ROM may refuse it. */
    fun acquire(): Boolean

    fun release()
}

internal class WifiLowLatencyLock(context: Context) : LowLatencyLock {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.WifiLock? = null

    override fun acquire(): Boolean {
        val manager = wifiManager ?: return false
        if (lock != null) return true
        // LOW_LATENCY is API 29 and covers exactly this case; below it the older high-performance mode
        // is the closest thing the platform has.
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        return try {
            val created = manager.createWifiLock(mode, LOCK_TAG)
            created.acquire()
            lock = created
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "could not hold the low latency Wi-Fi lock", error)
            false
        }
    }

    override fun release() {
        val current = lock ?: return
        lock = null
        runCatching { current.release() }
    }

    private companion object {
        const val TAG = "nevoplay-usb"
        const val LOCK_TAG = "nevoplay:carplay-wireless"
    }
}

/**
 * Holds the radio lock for as long as a hotspot is up, and reports whether it was granted.
 *
 * Held across a whole group rather than only while starting one: the loss being addressed happens
 * mid-session, and a hotspot retained across a handshake reset keeps its lock with it. Every
 * acquire/release pair is logged, because "the platform refused the lock" and "the lock did not help"
 * are different results and only the log distinguishes them.
 */
internal class LowLatencyHotspotManager(
    private val delegate: WirelessHotspotManager,
    private val lock: LowLatencyLock,
    private val log: (String) -> Unit = {},
) : WirelessHotspotManager {
    private var held = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        val info = delegate.start(timeoutMillis)
        if (!held) {
            held = lock.acquire()
            log("wifi low latency lock held=$held backend=${info.backend.label}")
        }
        return info
    }

    override fun close() {
        try {
            delegate.close()
        } finally {
            if (held) {
                held = false
                lock.release()
                log("wifi low latency lock released")
            }
        }
    }
}
