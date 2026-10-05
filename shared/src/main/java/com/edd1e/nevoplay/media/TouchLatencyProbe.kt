package com.edd1e.nevoplay.media

/**
 * Diagnostic link between the touch uplink and the next delivered video frame.
 *
 * Dragging on the CarPlay map stutters, and the two candidate explanations need different fixes: the
 * phone taking a long time to render and encode a changed frame, or this device's touch/HID sending
 * competing with the decode path. Neither the frame counters nor the touch counters in this project
 * could tell them apart, because nothing measured the interval between the two.
 *
 * Only the first frame after a touch is sampled, and a sample older than [MAX_SAMPLE_NS] is dropped
 * so a touch cannot be paired with a frame that arrives long afterwards (for example after the
 * picture has been idle).
 */
internal object TouchLatencyProbe {
    @Volatile private var pendingTouchNs = 0L

    /** Longest time spent writing one HID report to the event channel, in nanoseconds. */
    @Volatile var maxSendNs = 0L

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (pendingTouchNs == 0L) pendingTouchNs = sentAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    /** Touch-to-frame latency in nanoseconds for the first frame after a touch, or -1. */
    fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs
        if (touch == 0L) return -1
        pendingTouchNs = 0L
        val latency = nowNs - touch
        return if (latency > MAX_SAMPLE_NS) -1 else latency
    }

    private const val MAX_SAMPLE_NS = 2_000_000_000L
}
