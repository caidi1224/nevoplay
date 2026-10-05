package com.edd1e.nevoplay

/**
 * Delay before the next attempt to rebuild a CarPlay stack, given how many attempts have already
 * failed in a row.
 *
 * The first retry stays quick, because most losses are a phone that comes straight back. A wedged
 * Wi-Fi P2P state machine is the opposite case: it refuses `createGroup` until the framework releases
 * the old group on its own, so retrying every two seconds for three and a half hours achieves nothing
 * except a 14 MB log and a head unit that is never ready. Doubling up to a 30 s poll keeps trying
 * without the noise.
 */
internal fun reconnectBackoffMillis(
    consecutiveFailures: Int,
    baseMillis: Long = 2_000L,
    maxMillis: Long = 30_000L,
): Long {
    var delay = baseMillis
    repeat(consecutiveFailures.coerceIn(0, 8)) {
        delay = minOf(delay * 2, maxMillis)
    }
    return delay
}
