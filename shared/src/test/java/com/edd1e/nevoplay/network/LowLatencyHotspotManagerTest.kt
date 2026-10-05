package com.edd1e.nevoplay.network

import com.edd1e.nevoplay.transport.Iap2WirelessSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing, which is where a lock bug lives: acquiring twice leaks, releasing twice throws, and
 * releasing a lock that was never held is a lie in the log.
 */
class LowLatencyHotspotManagerTest {

    private class FakeLock(private val grants: Boolean = true) : LowLatencyLock {
        var acquires = 0
        var releases = 0
        override fun acquire(): Boolean {
            acquires += 1
            return grants
        }

        override fun release() {
            releases += 1
        }
    }

    private class FakeHotspot(private val failClose: Boolean = false) : WirelessHotspotManager {
        var closes = 0
        var starts = 0
        override fun start(timeoutMillis: Long): WirelessHotspotInfo {
            starts += 1
            return WirelessHotspotInfo(
                ssid = "test",
                passphrase = "secret123",
                security = Iap2WirelessSecurity.WPA_WPA2,
                channel = 36,
                frequencyMHz = 5180,
                bssid = null,
                interfaceName = "p2p0",
                hostAddress = null,
                bandLabel = "5 GHz",
                backend = WirelessHotspotBackend.WIFI_P2P,
            )
        }

        override fun close() {
            closes += 1
            if (failClose) throw IllegalStateException("close failed")
        }
    }

    private fun manager(lock: FakeLock, hotspot: FakeHotspot = FakeHotspot()) =
        LowLatencyHotspotManager(hotspot, lock)

    @Test
    fun takesTheLockWhenAGroupComesUp() {
        val lock = FakeLock()
        val hotspot = FakeHotspot()
        manager(lock, hotspot).start(1_000)
        assertEquals(1, lock.acquires)
        assertEquals(1, hotspot.starts)
    }

    @Test
    fun releasesItWhenTheGroupGoesAway() {
        val lock = FakeLock()
        val hotspot = FakeHotspot()
        val manager = manager(lock, hotspot)
        manager.start(1_000)
        manager.close()
        assertEquals(1, lock.releases)
        assertEquals(1, hotspot.closes)
    }

    @Test
    fun aSecondStartDoesNotTakeASecondLock() {
        val lock = FakeLock()
        val manager = manager(lock)
        manager.start(1_000)
        manager.start(1_000)
        assertEquals(1, lock.acquires)
    }

    @Test
    fun closingWithoutAGroupDoesNotReleaseWhatWasNeverHeld() {
        val lock = FakeLock()
        manager(lock).close()
        assertEquals(0, lock.releases)
    }

    @Test
    fun closingTwiceReleasesOnce() {
        val lock = FakeLock()
        val manager = manager(lock)
        manager.start(1_000)
        manager.close()
        manager.close()
        assertEquals(1, lock.releases)
    }

    @Test
    fun aRefusedLockIsReportedAndNotReleasedLater() {
        val lock = FakeLock(grants = false)
        val logged = mutableListOf<String>()
        val manager = LowLatencyHotspotManager(FakeHotspot(), lock, logged::add)
        manager.start(1_000)
        manager.close()
        assertEquals(1, lock.acquires)
        assertEquals(0, lock.releases)
        assertTrue(logged.any { it.contains("held=false") })
    }

    @Test
    fun theDelegateIsClosedEvenWhenItThrows() {
        val lock = FakeLock()
        val manager = manager(lock, FakeHotspot(failClose = true))
        manager.start(1_000)
        runCatching { manager.close() }
        assertEquals(1, lock.releases)
        assertFalse(lock.releases > 1)
    }
}
