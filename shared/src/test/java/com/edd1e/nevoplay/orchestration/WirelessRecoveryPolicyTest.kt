package com.edd1e.nevoplay.orchestration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessRecoveryPolicyTest {
    @Test
    fun aPhoneThatWalkedAwayKeepsTheHotspot() {
        assertTrue(keepsHotspotAcrossRebuild("AirPlay session ended"))
        assertTrue(keepsHotspotAcrossRebuild("AirPlay session ended; reconnecting"))
        assertTrue(keepsHotspotAcrossRebuild("CarPlay transport error: socket closed"))
        assertTrue(keepsHotspotAcrossRebuild("ERROR AirPlay iAP tunnel peer EOF"))
    }

    @Test
    fun aWirelessStackThatFailedGetsACleanGroup() {
        assertFalse(
            keepsHotspotAcrossRebuild(
                "ERROR Wi-Fi P2P createGroup failed: Wi-Fi P2P is busy",
            ),
        )
        assertFalse(
            keepsHotspotAcrossRebuild(
                "ERROR Wireless hotspot did not provide a usable host address",
            ),
        )
        assertFalse(keepsHotspotAcrossRebuild("ERROR MFi authentication failed"))
    }

    @Test
    fun theReasonIsMatchedWithoutRegardToCase() {
        assertTrue(keepsHotspotAcrossRebuild("AIRPLAY SESSION ENDED"))
        assertFalse(keepsHotspotAcrossRebuild("WI-FI P2P CREATEGROUP FAILED: BUSY"))
    }
}
