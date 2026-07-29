package com.robin.claudeusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `least squares ignores an early burst instead of extrapolating it`() {
        // 30% in the first half hour, then flat for four hours. The endpoints-only
        // slope was 30% over 4.5h; the fit over every point is far shallower.
        val samples = listOf(
            0L to 0.0,
            30 * 60_000L to 30.0,
            2 * hour to 30.5,
            3 * hour to 31.0,
            4 * hour to 31.5,
            4 * hour + 30 * 60_000L to 32.0,
        )
        val est = Projection.estimate(samples, 10 * hour)
        assertNotNull(est)
        val endpointsRate = 32.0 / 4.5
        assertTrue(
            "fit ${est!!.ratePctPerHour} should undercut the endpoints rate $endpointsRate",
            est.ratePctPerHour < endpointsRate,
        )
    }

    @Test
    fun `projection is anchored on the latest reading, not the fitted line`() {
        // Perfectly linear, so the fit passes through the last point: 10%/h with
        // two hours left lands exactly 20 points above the final 40%.
        val samples = listOf(0L to 20.0, 1 * hour to 30.0, 2 * hour to 40.0)
        val est = Projection.estimate(samples, 4 * hour)
        assertNotNull(est)
        assertEquals(10.0, est!!.ratePctPerHour, 0.01)
        assertEquals(60.0, est.pctAtReset, 0.01)
    }

    @Test
    fun `a burst then a decline projects nothing`() {
        val samples = listOf(0L to 5.0, 1 * hour to 60.0, 2 * hour to 30.0, 3 * hour to 10.0)
        assertNull(Projection.estimate(samples, 10 * hour))
    }

    // --- window binding (CCBG-2): resets_at drifts, so equality can't identify a window ---

    private val sessionLen = 5 * hour
    private val weeklyLen = 7 * 24 * hour

    @Test
    fun `session samples bind despite a drifting resets_at`() {
        // The server slid resets_at forward a minute at a time across these polls.
        val window = 10 * hour
        val history = listOf(
            HistoryPoint(at = 1, sessionPct = 5.0, sessionResetAt = window - 3 * 60_000L, weeklyPct = null, weeklyResetAt = 0),
            HistoryPoint(at = 2, sessionPct = 9.0, sessionResetAt = window - 60_000L, weeklyPct = null, weeklyResetAt = 0),
            HistoryPoint(at = 3, sessionPct = 12.0, sessionResetAt = window, weeklyPct = null, weeklyResetAt = 0),
        )
        assertEquals(
            listOf(1L to 5.0, 2L to 9.0, 3L to 12.0),
            Projection.sessionSamples(history, window, sessionLen),
        )
    }

    @Test
    fun `the previous window never leaks into the current one`() {
        // A genuine reset moves resets_at by a whole window — well past the tolerance.
        val current = 100 * hour
        val previous = current - sessionLen
        val history = listOf(
            HistoryPoint(at = 1, sessionPct = 98.0, sessionResetAt = previous, weeklyPct = null, weeklyResetAt = 0),
            HistoryPoint(at = 2, sessionPct = 3.0, sessionResetAt = current, weeklyPct = null, weeklyResetAt = 0),
        )
        assertEquals(listOf(2L to 3.0), Projection.sessionSamples(history, current, sessionLen))
    }

    @Test
    fun `weekly samples bind despite drift and skip missing percents`() {
        val window = 200 * hour
        val history = listOf(
            HistoryPoint(at = 1, sessionPct = null, sessionResetAt = 0, weeklyPct = 10.0, weeklyResetAt = window - 2 * hour),
            HistoryPoint(at = 2, sessionPct = null, sessionResetAt = 0, weeklyPct = null, weeklyResetAt = window),
            HistoryPoint(at = 3, sessionPct = null, sessionResetAt = 0, weeklyPct = 14.0, weeklyResetAt = window + hour),
            // Last week's window: a full 7 days away, so it stays out.
            HistoryPoint(at = 4, sessionPct = null, sessionResetAt = 0, weeklyPct = 91.0, weeklyResetAt = window - weeklyLen),
        )
        assertEquals(
            listOf(1L to 10.0, 3L to 14.0),
            Projection.weeklySamples(history, window, weeklyLen),
        )
    }

    @Test
    fun `a zero resets_at is never bound`() {
        // 0 means "window not started" in HistoryStore, not "resets at the epoch".
        val history = listOf(
            HistoryPoint(at = 1, sessionPct = 5.0, sessionResetAt = 0, weeklyPct = null, weeklyResetAt = 0),
        )
        assertEquals(emptyList<Pair<Long, Double>>(), Projection.sessionSamples(history, 0L, sessionLen))
    }

    @Test
    fun `tolerance is a quarter of the window`() {
        assertEquals(sessionLen / 4, Projection.tolerance(sessionLen))
        assertEquals(weeklyLen / 4, Projection.tolerance(weeklyLen))
    }
}
