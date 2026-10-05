package com.edd1e.nevoplay.network

import com.edd1e.nevoplay.transport.Iap2WirelessSecurity
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessHotspotRetentionTest {
    @Test
    fun handsTheHotspotToTheNextHandshakeOnce() {
        val retention = WirelessHotspotRetention()
        val manager = FakeHotspotManager()
        val info = hotspotInfo()
        retention.retain("signature", manager, info)

        val taken = retention.take("signature")

        assertNotNull(taken)
        assertSame(manager, taken?.manager)
        assertSame(info, taken?.info)
        // Transfer, not share: nothing is left behind for a second owner to close.
        assertNull(retention.take("signature"))
        assertFalse(manager.closed)
    }

    @Test
    fun removesAHotspotTheNewBringUpCannotUse() {
        val retention = WirelessHotspotRetention()
        val manager = FakeHotspotManager()
        retention.retain("wi-fi p2p", manager, hotspotInfo())

        // A bring-up that needs another network cannot use this group, and a second one next to it
        // would only make Wi-Fi Direct answer BUSY: it is removed, not kept.
        assertNull(retention.take("manual hotspot"))
        assertTrue(manager.closed)
        assertNull(retention.take("wi-fi p2p"))
    }

    @Test
    fun closesTheHotspotItReplaces() {
        val retention = WirelessHotspotRetention()
        val replaced = FakeHotspotManager()
        retention.retain("wi-fi p2p", replaced, hotspotInfo())

        val replacement = FakeHotspotManager()
        retention.retain("wi-fi p2p", replacement, hotspotInfo())

        assertTrue(replaced.closed)
        assertFalse(replacement.closed)
        assertSame(replacement, retention.take("wi-fi p2p")?.manager)
    }

    @Test
    fun closeRemovesTheRetainedHotspot() {
        val retention = WirelessHotspotRetention()
        val manager = FakeHotspotManager()
        retention.retain("signature", manager, hotspotInfo())

        retention.close()

        assertTrue(manager.closed)
        assertNull(retention.take("signature"))
    }

    @Test
    fun closeWithoutARetainedHotspotDoesNothing() {
        WirelessHotspotRetention().close()
    }

    @Test
    fun signatureSeparatesEveryPartOfTheNetworkIdentity() {
        val base = wirelessHotspotSignature(
            backend = "WIFI_P2P",
            ssid = "nevoplay-0123456789",
            passphrase = "0123456789abcdef",
        )

        assertEquals(
            base,
            wirelessHotspotSignature(
                backend = "WIFI_P2P",
                ssid = "nevoplay-0123456789",
                passphrase = "0123456789abcdef",
            ),
        )
        assertNotEquals(
            base,
            wirelessHotspotSignature("MANUAL", "nevoplay-0123456789", "0123456789abcdef"),
        )
        assertNotEquals(
            base,
            wirelessHotspotSignature("WIFI_P2P", "nevoplay-abcdef", "0123456789abcdef"),
        )
        assertNotEquals(
            base,
            wirelessHotspotSignature("WIFI_P2P", "nevoplay-0123456789", "abcdef0123456789"),
        )
        assertNotEquals(
            base,
            wirelessHotspotSignature(
                backend = "WIFI_P2P",
                ssid = "nevoplay-0123456789",
                passphrase = "0123456789abcdef",
                channel = 149,
            ),
        )
        // The manual hotspot band and security take part in the identity as well.
        assertNotEquals(
            wirelessHotspotSignature("MANUAL", "car", "12345678", band = "AUTO"),
            wirelessHotspotSignature("MANUAL", "car", "12345678", band = "BAND_5_GHZ"),
        )
        assertNotEquals(
            wirelessHotspotSignature("MANUAL", "car", "12345678", security = "WPA2"),
            wirelessHotspotSignature("MANUAL", "car", "12345678", security = "WPA3"),
        )
        // A LocalOnlyHotspot has no configured identity, so its backend alone identifies it.
        assertEquals(
            wirelessHotspotSignature("LOCAL_ONLY_HOTSPOT"),
            wirelessHotspotSignature("LOCAL_ONLY_HOTSPOT", ssid = null, passphrase = null),
        )
    }

    private class FakeHotspotManager : WirelessHotspotManager {
        var closed = false

        override fun start(timeoutMillis: Long): WirelessHotspotInfo = hotspotInfo()

        override fun close() {
            closed = true
        }
    }

    private companion object {
        fun hotspotInfo(): WirelessHotspotInfo = WirelessHotspotInfo(
            ssid = "nevoplay-0123456789",
            passphrase = "0123456789abcdef",
            security = Iap2WirelessSecurity.WPA_WPA2,
            channel = 40,
            frequencyMHz = 5200,
            bssid = "02:00:00:00:00:01",
            interfaceName = "p2p0",
            hostAddress = InetAddress.getByName("192.168.214.159"),
            bandLabel = "5 GHz",
            backend = WirelessHotspotBackend.WIFI_P2P,
        )
    }
}
