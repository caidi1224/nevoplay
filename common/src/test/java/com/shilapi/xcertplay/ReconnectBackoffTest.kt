package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun theFirstRetryStaysQuick() {
        assertEquals(2_000L, reconnectBackoffMillis(0))
        assertEquals(4_000L, reconnectBackoffMillis(1))
        assertEquals(8_000L, reconnectBackoffMillis(2))
        assertEquals(16_000L, reconnectBackoffMillis(3))
    }

    @Test
    fun repeatedFailuresBackOffToTheCapAndStayThere() {
        assertEquals(30_000L, reconnectBackoffMillis(4))
        assertEquals(30_000L, reconnectBackoffMillis(20))
    }

    @Test
    fun theDelayNeverDecreases() {
        var previous = 0L
        for (failures in 0..20) {
            val delay = reconnectBackoffMillis(failures)
            assertTrue("delay decreased at $failures", delay >= previous)
            assertTrue("delay above the cap at $failures", delay <= 30_000L)
            previous = delay
        }
    }
}
