package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reserve a call track starts with. The expression this replaces had two identical branches, so
 * the value was never different from the 4 KiB the media path uses - about 43 ms, which the device
 * log shows is not enough.
 */
class CallAudioBufferTest {

    @Test
    fun holdsEnoughForADipAtFortyEightKilohertzMono() {
        // 48 kHz mono 16-bit for 150 ms = 14400 bytes, far above the 4 KiB the path used to use.
        val bytes = CallAudioBuffer.startThresholdBytes(
            sampleRate = 48_000,
            channelCount = 1,
            minBufferBytes = 4_096,
        )
        assertEquals(48_000 / 1000 * 150 * 2, bytes)
        assertTrue("must exceed the old 4 KiB reserve", bytes > 4_096)
    }

    @Test
    fun neverAsksForLessThanThePlatformMinimum() {
        val bytes = CallAudioBuffer.startThresholdBytes(
            sampleRate = 8_000,
            channelCount = 1,
            minBufferBytes = 9_999,
        )
        assertEquals(9_999, bytes)
    }

    @Test
    fun givesACallTrackRoomForItsBand() {
        // 48 kHz mono for 320 ms.
        val bytes = CallAudioBuffer.trackBufferBytes(
            sampleRate = 48_000,
            channelCount = 1,
            minBufferBytes = 4_096,
        )
        assertEquals(48_000 / 1000 * 320 * 2, bytes)
    }

    /**
     * The invariant the renderer depends on, and the reason it is asserted here rather than assumed:
     * a band hold pauses the track, and while it is paused nothing drains it. If the capacity were
     * smaller than the high-water mark, the write that is trying to reach that mark would find the
     * buffer full on a stopped track, block forever, and the call would never resume.
     */
    @Test
    fun theBandFitsInsideTheTrackWithRoomToSpare() {
        assertTrue(
            "track capacity must exceed the band's high-water mark",
            CallAudioBuffer.TRACK_BUFFER_MS > CallAudioBuffer.HIGH_WATER_MS,
        )
        val capacity = CallAudioBuffer.trackBufferBytes(48_000, 1, 4_096)
        val highWater = CallAudioBuffer.bytesForTest(48_000, 1, CallAudioBuffer.HIGH_WATER_MS)
        assertTrue("high-water $highWater must fit in $capacity", highWater < capacity)
        assertTrue(
            "the low-water mark must be below the high-water mark",
            CallAudioBuffer.LOW_WATER_MS < CallAudioBuffer.HIGH_WATER_MS,
        )
    }

    @Test
    fun survivesNonsenseRates() {
        assertEquals(1_024, CallAudioBuffer.startThresholdBytes(0, 1, 1_024))
        assertEquals(1_024, CallAudioBuffer.startThresholdBytes(48_000, 0, 1_024))
    }
}
