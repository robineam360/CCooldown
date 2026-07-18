package com.robin.claudeusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectionTest {

    private val hour = 3_600_000L

    @Test
    fun `steady burn that outruns the window projects a hit before reset`() {
        // 20%/h observed over two hours, with four hours of window still to go.
        val samples = listOf(0L to 0.0, 1 * hour to 20.0, 2 * hour to 40.0)
        val resetAt = 2 * hour + 4 * hour // plenty of window left
        val est = Projection.estimate(samples, resetAt)
        assertNotNull(est)
        assertEquals(20.0, est!!.ratePctPerHour, 0.01)
        // 60% left at 20%/h → hits 100% three hours after the last sample.
        assertEquals(2 * hour + 3 * hour, est.hitsLimitAtMs)
        assertEquals(100.0, est.pctAtReset, 0.01)
    }

    @Test
    fun `slow burn projects the percent at reset instead of a hit`() {
        val samples = listOf(0L to 10.0, 2 * hour to 20.0) // 5%/h
        val resetAt = 4 * hour // only 2h left → +10% → 30%
        val est = Projection.estimate(samples, resetAt)
        assertNotNull(est)
        assertNull(est!!.hitsLimitAtMs)
        assertEquals(30.0, est.pctAtReset, 0.01)
    }

    @Test
    fun `too short a span returns null`() {
        val samples = listOf(0L to 0.0, 10 * 60_000L to 50.0) // only 10 minutes
        assertNull(Projection.estimate(samples, 5 * hour))
    }

    @Test
    fun `flat usage returns null`() {
        val samples = listOf(0L to 42.0, 1 * hour to 42.5) // < 1% movement
        assertNull(Projection.estimate(samples, 5 * hour))
    }

    @Test
    fun `single sample returns null`() {
        assertNull(Projection.estimate(listOf(0L to 50.0), 5 * hour))
    }

    @Test
    fun `unsorted samples are handled`() {
        val samples = listOf(2 * hour to 40.0, 0L to 0.0, 1 * hour to 20.0)
        val est = Projection.estimate(samples, 10 * hour)
        assertNotNull(est)
        assertEquals(20.0, est!!.ratePctPerHour, 0.01)
    }

    @Test
    fun `session samples filter by window identity`() {
        val oldWindow = 111L
        val currentWindow = 222L
        val history = listOf(
            HistoryPoint(at = 1, sessionPct = 90.0, sessionResetAt = oldWindow, weeklyPct = null, weeklyResetAt = 0),
            HistoryPoint(at = 2, sessionPct = 5.0, sessionResetAt = currentWindow, weeklyPct = null, weeklyResetAt = 0),
            HistoryPoint(at = 3, sessionPct = null, sessionResetAt = currentWindow, weeklyPct = 10.0, weeklyResetAt = 333),
            HistoryPoint(at = 4, sessionPct = 15.0, sessionResetAt = currentWindow, weeklyPct = null, weeklyResetAt = 0),
        )
        val samples = Projection.sessionSamples(history, currentWindow)
        assertEquals(listOf(2L to 5.0, 4L to 15.0), samples)
    }

    @Test
    fun `weekly samples filter by window identity`() {
        val window = 333L
        val history = listOf(
            HistoryPoint(at = 1, sessionPct = 1.0, sessionResetAt = 111, weeklyPct = 10.0, weeklyResetAt = window),
            HistoryPoint(at = 2, sessionPct = 2.0, sessionResetAt = 111, weeklyPct = 12.0, weeklyResetAt = 999),
        )
        assertEquals(listOf(1L to 10.0), Projection.weeklySamples(history, window))
    }
}
