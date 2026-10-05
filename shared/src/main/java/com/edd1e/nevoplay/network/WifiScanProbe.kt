package com.edd1e.nevoplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock

/**
 * What the Wi-Fi radio is being asked to do besides CarPlay.
 *
 * A peer project traced the same stutter this fork has been chasing to the scan schedule: on a
 * single-radio head unit the station and P2P interfaces share one channel, so a full-band scan takes
 * the radio off the CarPlay channel for its whole duration - and the firmware scanned every ten
 * seconds, for three to six seconds each, while the station interface was not joined to a network
 * (DiPlay #225, from `dumpsys wifiscanner` and the firmware's own `WifiConnectivityManager`). When the
 * station was joined, scans backed off to 30-160 s, which is why a hotspot backend looked clean.
 *
 * That suggests two things worth knowing about this vehicle, both answerable from inside the app:
 * whether its station interface is joined, and how often scan results are arriving. Neither needs a
 * shell, and neither changes anything - the app only reads.
 *
 * Whether Android 10+ lets an app see scans it did not request is not assumed here: the probe reports
 * the raw count it got, so an empty or frozen view says so instead of being read as "no scans".
 */
internal data class ScanCadence(
    /** Distinct scan batches whose newest result falls inside the window. */
    val scans: Int,
    /** Raw scan results seen, in or out of the window - the number is itself the evidence. */
    val results: Int,
    /** Age of the most recent scan, or -1 when nothing is visible. */
    val newestAgeMs: Long,
    /** Mean gap between visible scans, or -1 when fewer than two are visible. */
    val intervalMs: Long,
)

internal fun scanCadence(timestampsUs: List<Long>, nowUs: Long, windowMs: Long): ScanCadence {
    val cutoffUs = nowUs - windowMs * 1_000L
    val inWindow = timestampsUs.filter { it in cutoffUs..nowUs }
    if (inWindow.isEmpty()) return ScanCadence(0, timestampsUs.size, -1, -1)
    // One scan stamps every result it produced, so equal timestamps are one scan, not several.
    val batches = inWindow.distinct().sorted()
    val newestAgeMs = (nowUs - batches.last()) / 1_000L
    val intervalMs = if (batches.size < 2) {
        -1L
    } else {
        (batches.last() - batches.first()) / 1_000L / (batches.size - 1)
    }
    return ScanCadence(
        scans = batches.size,
        results = timestampsUs.size,
        newestAgeMs = newestAgeMs,
        intervalMs = intervalMs,
    )
}

internal class WifiScanProbe(context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    /** One line, or the reason there is none. Read-only: it never asks for a scan. */
    fun line(windowMs: Long): String {
        val manager = wifiManager ?: return "wifi scan probe unavailable"
        val state = runCatching { manager.wifiState }.getOrNull() ?: -1
        val connection = runCatching { manager.connectionInfo }.getOrNull()
        val networkId = connection?.networkId ?: -1
        val joined = networkId != -1
        val ssid = when {
            !joined -> "-"
            else -> connection?.ssid?.takeIf { it.isNotBlank() } ?: "-"
        }
        val results = runCatching { manager.scanResults }.getOrNull()
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        val cadence = scanCadence(results?.map { it.timestamp } ?: emptyList(), nowUs, windowMs)
        return "wifi scan probe state=$state stationJoined=$joined ssid=$ssid " +
            "scanResults=${cadence.results} scans=${cadence.scans} " +
            "newestAgeMs=${cadence.newestAgeMs} intervalMs=${cadence.intervalMs}"
    }
}
