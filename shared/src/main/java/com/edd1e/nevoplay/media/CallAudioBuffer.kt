package com.edd1e.nevoplay.media

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

    private const val BYTES_PER_PCM_16_SAMPLE = 2

    /**
     * Bytes to queue before a call or assistant track starts, never below what the platform asks for.
     */
    fun startThresholdBytes(sampleRate: Int, channelCount: Int, minBufferBytes: Int): Int {
        if (sampleRate <= 0 || channelCount <= 0) return minBufferBytes
        val wanted = sampleRate.toLong() * channelCount * BYTES_PER_PCM_16_SAMPLE * START_BUFFER_MS / 1000
        val clamped = wanted.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return maxOf(minBufferBytes, clamped)
    }

}
