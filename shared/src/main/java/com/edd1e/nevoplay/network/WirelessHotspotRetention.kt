package com.edd1e.nevoplay.network

import android.util.Log

/**
 * One live Wi-Fi hotspot together with the identity that lets a later bring-up adopt it.
 */
internal class RetainedWirelessHotspot(
    val signature: String,
    val manager: WirelessHotspotManager,
    val info: WirelessHotspotInfo,
)

/**
 * Identity of one hotspot. Two bring-ups may share a live hotspot only when every part matches -
 * a changed MFi certificate, manual SSID, band, channel or security means the phone was told about
 * a different network and must be given a new one.
 *
 * The passphrase is part of the identity and must never be logged.
 */
internal fun wirelessHotspotSignature(
    backend: String,
    ssid: String? = null,
    passphrase: String? = null,
    band: String? = null,
    channel: Int? = null,
    security: String? = null,
): String = listOf(backend, ssid, passphrase, band, channel?.toString(), security)
    .joinToString("\u0000") { it.orEmpty() }

/**
 * Keeps one hotspot alive across a controller restart.
 *
 * A settings-driven handshake reset closes the controller, and with it the Wi-Fi group the iPhone
 * is associated with. Re-creating that group hands the phone a **new** group-owner address, so it
 * cannot reach the AirPlay listener until it re-associates and re-runs DHCP - and if it does not,
 * the new controller waits on the Bluetooth bootstrap with nothing to show for it. Handing the
 * same group to the next controller keeps the address, the channel and the phone's association,
 * so wireless CarPlay comes back on the address the phone already knows.
 *
 * The hotspot is only ever transferred, never shared: [take] removes the entry, so exactly one
 * owner can close it.
 */
internal class WirelessHotspotRetention {
    private val lock = Object()
    private var retained: RetainedWirelessHotspot? = null

    /** Hands [manager] over to the next controller, closing whatever it replaces. */
    fun retain(signature: String, manager: WirelessHotspotManager, info: WirelessHotspotInfo) {
        val replaced = synchronized(lock) {
            retained.also { retained = RetainedWirelessHotspot(signature, manager, info) }
        }
        if (replaced != null && replaced.manager !== manager) {
            closeQuietly(replaced.manager)
        }
    }

    /**
     * Takes the retained hotspot when it still matches [signature].
     *
     * A hotspot that does not match is removed instead of kept: the bring-up asking for another
     * network could not use it, and a second group next to a fresh one only makes the framework
     * answer BUSY. Either way the entry is gone, so exactly one owner is left.
     */
    fun take(signature: String): RetainedWirelessHotspot? {
        val current = synchronized(lock) {
            retained.also { retained = null }
        } ?: return null
        if (current.signature == signature) return current
        closeQuietly(current.manager)
        return null
    }

    /** Closes the retained hotspot; call this when the app is not coming back to it. */
    fun close() {
        val current = synchronized(lock) {
            retained.also { retained = null }
        }
        if (current != null) closeQuietly(current.manager)
    }

    private fun closeQuietly(manager: WirelessHotspotManager) {
        try {
            manager.close()
        } catch (error: Throwable) {
            Log.w(TAG, "Retained wireless hotspot teardown failed", error)
        }
    }

    companion object {
        private const val TAG = "nevoplay-wifi-hotspot"

        /** The one hotspot this process keeps alive across a settings-driven handshake reset. */
        val shared = WirelessHotspotRetention()
    }
}
