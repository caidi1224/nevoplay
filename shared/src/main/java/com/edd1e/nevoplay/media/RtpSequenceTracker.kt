package com.edd1e.nevoplay.media

/**
 * Counts the packets an RTP sequence number says never arrived.
 *
 * An underrun and a loss sound identical from the driver's seat, and the counters beside them cannot
 * tell the two apart: `bufferedMs` falling to zero looks the same whether the audio was lost on the
 * way or merely arrived too late to be useful. This separates them. Lost audio cannot be buffered
 * back, so the distinction decides whether a buffer-shaped fix has any chance at all - which is the
 * lesson from a peer project that named periodic loss bursts on Wi-Fi Direct as the cause of the same
 * symptom (DiPlay #131), after this fork had already spent two attempts on buffering.
 *
 * The arithmetic is small and easy to get wrong at the wrap-around, which is why it lives here with
 * tests rather than inline in an AudioTrack-driven class.
 */
internal class RtpSequenceTracker {
    /**
     * The largest forward jump still treated as lost packets.
     *
     * 1024 frames of 20 ms is about twenty seconds of audio, far beyond any real burst, and far below
     * the point where a backwards jump would alias into a forward one.
     */
    private val MAX_PLAUSIBLE_GAP = 1024

    /** One reported gap: how many packets are missing and what the sequence did across it. */
    data class Gap(val lost: Int, val previous: Int, val current: Int)

    @Volatile
    var gapEvents: Int = 0
        private set

    @Volatile
    var gapPackets: Int = 0
        private set

    @Volatile
    var gapMax: Int = 0
        private set

    private var last: Int = -1

    /** Returns the gap this packet revealed, or null when nothing was skipped. */
    fun onPacket(sequence: Int): Gap? {
        val previous = last
        last = sequence and 0xffff
        if (previous < 0) return null
        val advance = (last - previous) and 0xffff
        // In order, a repeat, or something that is not a loss at all.
        //
        // The bound is what makes this usable rather than the signed arithmetic: a packet delivered
        // out of order moves the sequence backwards, and the unsigned difference renders that as a
        // huge forward jump - one late packet would report sixty-five thousand missing ones. A jump
        // past a thousand packets is likewise a restarted stream or a reordered packet, not a burst,
        // so it is left out rather than attributed. Real gaps are small: the peer project's log shows
        // a worst case of eighteen.
        if (advance <= 1 || advance > MAX_PLAUSIBLE_GAP) return null
        val lost = advance - 1
        gapEvents += 1
        gapPackets += lost
        gapMax = maxOf(gapMax, lost)
        return Gap(lost = lost, previous = previous, current = last)
    }
}
