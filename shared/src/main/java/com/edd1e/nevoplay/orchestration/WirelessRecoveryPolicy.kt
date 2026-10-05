package com.edd1e.nevoplay.orchestration

/**
 * Whether a rebuild that follows [reason] should hand the live Wi-Fi hotspot to the next
 * controller instead of removing it.
 *
 * The live group is kept unless the failure is about the local wireless stack itself, because the
 * two cases want opposite things:
 *
 * A phone that went away - the session ended, the transport errored, the iAP tunnel went away, or
 * the Bluetooth bootstrap could not reach the phone at all - is coming back to the network it
 * already knows. A P2P group is created with a fresh SSID and passphrase, so removing the live one
 * hands a returning phone a network it has never seen: it cannot re-associate on its own, and a
 * driver who only left the car with the phone ends up reading a Wi-Fi hint and picking the car by
 * hand. Rebuilding the group on every attempt is also what the framework answers BUSY to.
 *
 * When the *local* stack is what failed - P2P could not create a group, the hotspot had no usable
 * host address, MFi refused - the live group may be exactly what is broken, so the next bring-up
 * gets a clean one. Retention is a no-op when nothing is live, so failures with no bearing on the
 * hotspot (a service that would not bind, a changed display) need no case of their own here.
 */
fun keepsHotspotAcrossRebuild(reason: String): Boolean {
    val text = reason.lowercase()
    val localStackFailed = text.contains("p2p") ||
        text.contains("hotspot") ||
        text.contains("mfi")
    return !localStackFailed
}
