package com.edd1e.nevoplay.orchestration

/**
 * Whether a rebuild that follows [reason] should hand the live Wi-Fi hotspot to the next
 * controller instead of removing it.
 *
 * A phone that walked away - the AirPlay session ended, the transport reported an error, or the iAP
 * tunnel under the session went away - is coming back to the network it already knows. Tearing that
 * group down to build an identical one is what leaves the framework answering BUSY for the next
 * several attempts, which is how a driver who simply left the car with the phone ends up reading a
 * Wi-Fi hint thirty seconds later.
 *
 * A stack that failed on its own gets a clean group instead: a P2P group that could not be created,
 * a hotspot with no usable host address, or an MFi refusal means the live group may be exactly what
 * is broken, and the retention check in [com.edd1e.nevoplay.network.WirelessHotspotRetention] would
 * discard it anyway when the identity no longer matches.
 */
fun keepsHotspotAcrossRebuild(reason: String): Boolean {
    val text = reason.lowercase()
    return text.contains("session ended") ||
        text.contains("transport error") ||
        text.contains("iap tunnel")
}
