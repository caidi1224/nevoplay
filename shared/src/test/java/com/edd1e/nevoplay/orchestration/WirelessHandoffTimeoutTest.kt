package com.edd1e.nevoplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessHandoffTimeoutTest {
    @Test
    fun anEstablishedSessionWithoutVideoStillFails() {
        assertEquals(
            WirelessHandoffTimeoutOutcome.FAIL,
            outcome(sessionActive = true),
        )
    }

    @Test
    fun aRenderedFramePreservesTheSessionWhenTheTunnelNeverOpened() {
        assertEquals(
            WirelessHandoffTimeoutOutcome.PRESERVE_RENDERED_SESSION,
            outcome(sessionActive = true, renderedFrame = true),
        )
    }

    @Test
    fun aTunnelThatBecameReadyWinsOverTheWatchdog() {
        assertEquals(
            WirelessHandoffTimeoutOutcome.IGNORE,
            outcome(sessionActive = true, renderedFrame = true, tunnelReady = true),
        )
    }

    @Test
    fun aHandoffThatAlreadyCompletedCannotBeSettledTwice() {
        assertEquals(
            WirelessHandoffTimeoutOutcome.IGNORE,
            outcome(sessionActive = true, renderedFrame = true, activeReported = true),
        )
    }

    @Test
    fun anEndedSessionCannotUseItsOldRenderedFrame() {
        assertEquals(
            WirelessHandoffTimeoutOutcome.FAIL,
            outcome(sessionActive = false, renderedFrame = true),
        )
    }

    @Test
    fun aWithdrawnHandoffRequestLeavesTheSessionAlone() {
        assertEquals(
            WirelessHandoffTimeoutOutcome.IGNORE,
            outcome(sessionActive = true, renderedFrame = true, handoffRequested = false),
        )
    }

    @Test
    fun aReportedFailureCannotBeTurnedBackIntoASession() {
        assertEquals(
            WirelessHandoffTimeoutOutcome.IGNORE,
            outcome(sessionActive = true, renderedFrame = true, failureReported = true),
        )
    }

    @Test
    fun anOldGenerationCannotCloseReplacementResources() {
        assertEquals(
            WirelessHandoffTimeoutOutcome.IGNORE,
            outcome(sessionActive = true, renderedFrame = true, generationCurrent = false),
        )
    }

    @Test
    fun aClosedControllerIgnoresItsWatchdog() {
        assertEquals(
            WirelessHandoffTimeoutOutcome.IGNORE,
            outcome(closed = true, sessionActive = true, renderedFrame = true),
        )
    }

    @Test
    fun aWiredRunHasNoWirelessHandoffToSettle() {
        assertEquals(
            WirelessHandoffTimeoutOutcome.IGNORE,
            outcome(wirelessPhase = false, sessionActive = true, renderedFrame = true),
        )
    }

    /** The facts as a live wireless handoff leaves them, before any guard is applied. */
    private fun outcome(
        closed: Boolean = false,
        wirelessPhase: Boolean = true,
        generationCurrent: Boolean = true,
        failureReported: Boolean = false,
        handoffRequested: Boolean = true,
        tunnelReady: Boolean = false,
        activeReported: Boolean = false,
        sessionActive: Boolean = false,
        renderedFrame: Boolean = false,
    ): WirelessHandoffTimeoutOutcome = wirelessHandoffTimeoutOutcome(
        WirelessHandoffFacts(
            closed = closed,
            wirelessPhase = wirelessPhase,
            generationCurrent = generationCurrent,
            failureReported = failureReported,
            handoffRequested = handoffRequested,
            tunnelReady = tunnelReady,
            activeReported = activeReported,
            sessionActive = sessionActive,
            renderedFrame = renderedFrame,
        ),
    )
}
