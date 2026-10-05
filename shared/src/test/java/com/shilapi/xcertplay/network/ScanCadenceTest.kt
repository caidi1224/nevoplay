package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanCadenceTest {

    private val nowUs = 1_000_000_000L

    @Test
    fun nothingVisibleIsReportedAsNothing() {
        val cadence = scanCadence(emptyList(), nowUs, windowMs = 60_000)
        assertEquals(0, cadence.scans)
        assertEquals(0, cadence.results)
        assertEquals(-1L, cadence.newestAgeMs)
        assertEquals(-1L, cadence.intervalMs)
    }

    @Test
    fun oneScanStampsAllOfItsResults() {
        // Three access points, one scan: equal timestamps must not read as three scans.
        val cadence = scanCadence(listOf(nowUs - 1_000, nowUs - 1_000, nowUs - 1_000), nowUs, 60_000)
        assertEquals(1, cadence.scans)
        assertEquals(3, cadence.results)
        assertEquals(1L, cadence.newestAgeMs)
        assertEquals(-1L, cadence.intervalMs)
    }

    @Test
    fun theIntervalComesOutInMilliseconds() {
        // The peer project's measured cadence: a scan every ten seconds.
        val stamps = listOf(0L, 10_000_000L, 20_000_000L, 30_000_000L).map { nowUs - 30_000_000 + it }
        val cadence = scanCadence(stamps, nowUs, windowMs = 60_000)
        assertEquals(4, cadence.scans)
        assertEquals(10_000L, cadence.intervalMs)
        assertEquals(0L, cadence.newestAgeMs)
    }

    @Test
    fun resultsOutsideTheWindowDoNotCountAsScansButStillCountAsResults() {
        val stamps = listOf(nowUs - 600_000_000, nowUs - 1_000_000)
        val cadence = scanCadence(stamps, nowUs, windowMs = 60_000)
        assertEquals(1, cadence.scans)
        assertEquals(2, cadence.results)
        assertEquals(1_000L, cadence.newestAgeMs)
    }
}
