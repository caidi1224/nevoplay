package com.edd1e.nevoplay

import android.content.res.Configuration

/**
 * The explicit night state carried by [uiMode], or null when the head unit reports
 * `UI_MODE_NIGHT_UNDEFINED`.
 *
 * Undefined must stay distinguishable from "day": a vehicle that reports it is not telling us the
 * theme changed, and treating it as light flips the panel to the light palette in the middle of a
 * dark drive. Callers keep their previous state on null.
 */
internal fun nightModeOrNull(uiMode: Int): Boolean? =
    when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> true
        Configuration.UI_MODE_NIGHT_NO -> false
        else -> null
    }
