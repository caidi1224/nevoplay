package com.edd1e.nevoplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtpSequenceTrackerTest {

    @Test
    fun theFirstPacketIsNotAGap() {
        val tracker = RtpSequenceTracker()
        assertNull(tracker.onPacket(1000))
        assertEquals(0, tracker.gapEvents)
    }

    @Test
    fun packetsInOrderAreNotGaps() {
        val tracker = RtpSequenceTracker()
        tracker.onPacket(1000)
        (1001..1050).forEach { assertNull(tracker.onPacket(it)) }
        assertEquals(0, tracker.gapEvents)
    }

    @Test
    fun aSkippedSequenceIsCounted() {
        val tracker = RtpSequenceTracker()
        tracker.onPacket(1000)
        val gap = tracker.onPacket(1002)
        assertEquals(RtpSequenceTracker.Gap(lost = 1, previous = 1000, current = 1002), gap)
        assertEquals(1, tracker.gapEvents)
        assertEquals(1, tracker.gapPackets)
        assertEquals(1, tracker.gapMax)
    }

    @Test
    fun aBurstIsCountedAsOneEventAndItsSize() {
        val tracker = RtpSequenceTracker()
        tracker.onPacket(1000)
        // The shape a loss burst takes in the peer project's log: one event spanning many packets.
        val gap = tracker.onPacket(1019)
        assertEquals(18, gap?.lost)
        assertEquals(1, tracker.gapEvents)
        assertEquals(18, tracker.gapPackets)
        assertEquals(18, tracker.gapMax)
    }

    @Test
    fun theWrapAroundIsNotABurstOfSixtyFiveThousand() {
        val tracker = RtpSequenceTracker()
        tracker.onPacket(65534)
        val gap = tracker.onPacket(1)
        assertEquals(2, gap?.lost)
        assertEquals(2, tracker.gapPackets)
    }

    @Test
    fun aReorderedOrRepeatedPacketIsNotALoss() {
        val tracker = RtpSequenceTracker()
        tracker.onPacket(1000)
        assertNull(tracker.onPacket(1000))
        assertNull(tracker.onPacket(999))
        assertEquals(0, tracker.gapEvents)
    }

    @Test
    fun aRestartedStreamIsNotAGapOfThirtyThousand() {
        val tracker = RtpSequenceTracker()
        tracker.onPacket(40000)
        // A new session's sequence space, far forward but not a credible loss.
        assertNull(tracker.onPacket(500))
        assertEquals(0, tracker.gapEvents)
    }

    @Test
    fun theWorstGapIsRemembered() {
        val tracker = RtpSequenceTracker()
        tracker.onPacket(1000)
        tracker.onPacket(1003)
        tracker.onPacket(1004)
        tracker.onPacket(1024)
        // 1000 -> 1003 loses 1001 and 1002; 1004 is in order; 1004 -> 1024 loses 19.
        assertEquals(2, tracker.gapEvents)
        assertEquals(2 + 19, tracker.gapPackets)
        assertEquals(19, tracker.gapMax)
    }
}
