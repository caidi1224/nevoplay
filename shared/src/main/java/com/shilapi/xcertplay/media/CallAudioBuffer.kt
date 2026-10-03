package com.shilapi.xcertplay.media

/**
 * Prebuffer for the streams that carry a conversation.
 *
 * Call and assistant audio used to start playing once 4 KiB were queued - about 43 ms at 48 kHz
 * mono. The expression that decided it had two identical branches, so the larger reserve the
 * condition was clearly written for was never applied to anyone.
 *
 * A device log shows what that costs. The telephony track sat at `bufferedMs=0` with an empty queue
 * while the phone delivered 45-51 packets/s against the 50/s a 20 ms Opus frame needs, and the
 * framework then disabled the track outright - `restartIfDisabled: releaseBuffer() track ... disabled
 * due to previous underrun, restarting` - which is what the dropouts sound like. The media stream,
 * which has 300 ms of room, was healthy in the same session.
 *
 * 150 ms is the trade: enough to bridge the dips in that log, and still inside a call's latency
 * budget. Latency is the cost, so it stays short of the media stream's 300 ms.
 */
internal object CallAudioBuffer {
    const val START_BUFFER_MS = 150

    /**
     * Track capacity for a call.
     *
     * The stock heuristic gave a call 240 ms at 48 kHz mono (23088 bytes), which is not enough room
     * above the band below: a reserve that reaches [HIGH_WATER_MS] has to fit while the track is
     * still allowed to hold what it already had.
     */
    const val TRACK_BUFFER_MS = 320

    /**
     * The band a call track is held in, once the driver asked for more than the first fix gave.
     *
     * The first fix reacted to an underrun, which is too late by definition: the click has already
     * happened, and the prebuffer that follows adds a second pause on top of it. Measured on the
     * vehicle, that removed the framework's disable-and-restart cascade entirely (14 events to 0) and
     * still left one underrun every 4.2 seconds with the track empty in 11 of 17 sampled windows.
     * Holding a band instead means pausing *before* the track reaches zero.
     *
     * The cost is latency, and it is paid on every call: a 200 ms reserve is 200 ms the other party
     * answers late. The wireless path's own jitter is what is being covered, so the band has to be
     * wider than the swing the device log shows (0 to 240 ms of buffered audio on a stream whose
     * sender budgets 80 ms).
     */
    const val LOW_WATER_MS = 100
    const val HIGH_WATER_MS = 200

    private const val BYTES_PER_PCM_16_SAMPLE = 2

    /**
     * Bytes to queue before a call or assistant track starts, never below what the platform asks for.
     */
    fun startThresholdBytes(sampleRate: Int, channelCount: Int, minBufferBytes: Int): Int =
        maxOf(minBufferBytes, bytesForMs(sampleRate, channelCount, START_BUFFER_MS))

    /** Buffer capacity for a call track, never below what the platform asks for. */
    fun trackBufferBytes(sampleRate: Int, channelCount: Int, minBufferBytes: Int): Int =
        maxOf(minBufferBytes, bytesForMs(sampleRate, channelCount, TRACK_BUFFER_MS))

    /** Exposed for the test that checks the band fits inside the track. */
    internal fun bytesForTest(sampleRate: Int, channelCount: Int, millis: Int): Int =
        bytesForMs(sampleRate, channelCount, millis)

    private fun bytesForMs(sampleRate: Int, channelCount: Int, millis: Int): Int {
        if (sampleRate <= 0 || channelCount <= 0) return 0
        val wanted = sampleRate.toLong() * channelCount * BYTES_PER_PCM_16_SAMPLE * millis / 1000
        return wanted.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
