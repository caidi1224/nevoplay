package com.edd1e.nevoplay

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DarkModeTest {
    @Test
    fun darkModeIsDetectedWithUnrelatedConfigurationBits() {
        assertEquals(
            true,
            nightModeOrNull(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_CAR),
        )
    }

    @Test
    fun lightModeIsExplicitlyLight() {
        assertEquals(false, nightModeOrNull(Configuration.UI_MODE_NIGHT_NO))
    }

    @Test
    fun undefinedModeHasNoOpinion() {
        // A vehicle that reports undefined is not asking for a palette change; the caller keeps the
        // one already on screen.
        assertNull(nightModeOrNull(Configuration.UI_MODE_NIGHT_UNDEFINED))
    }
}
