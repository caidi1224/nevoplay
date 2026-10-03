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
    fun survivesNonsenseRates() {
        assertEquals(1_024, CallAudioBuffer.startThresholdBytes(0, 1, 1_024))
        assertEquals(1_024, CallAudioBuffer.startThresholdBytes(48_000, 0, 1_024))
    }
}
