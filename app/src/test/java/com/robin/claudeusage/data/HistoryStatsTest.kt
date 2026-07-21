package com.robin.claudeusage.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStatsTest {

    private val zone = ZoneOffset.UTC
    private val hour = 60L * 60_000L

    private fun rec(kind: String, resetAt: Long, peak: Double, hit: Boolean = peak >= 99.5) =
        SessionLog.Record(kind, resetAt, peak, hit)

    @Test
    fun `session bars derive start from reset and sort newest first`() {
        val records = listOf(
            rec(SessionLog.SESSION, 100 * hour, 40.0),
            rec(SessionLog.SESSION, 120 * hour, 100.0),
            rec(SessionLog.WEEKLY, 110 * hour, 55.0), // ignored for session kind
        )
        val bars = HistoryStats.bars(records, SessionLog.SESSION, current = null)
        assertEquals(2, bars.size)
        // Newest (reset 120h) first; start = reset - 5h.
        assertEquals(120 * hour, bars[0].resetMs)
        assertEquals(120 * hour - HistoryStats.SESSION_MS, bars[0].startMs)
        assertTrue(bars[0].hitLimit)
        assertFalse(bars[1].hitLimit)
    }

    @Test
    fun `current open window merges in and de-dupes by reset time`() {
        val records = listOf(rec(SessionLog.SESSION, 100 * hour, 40.0))
        val current = HistoryStats.Bar(
            startMs = 200 * hour - HistoryStats.SESSION_MS,
            resetMs = 200 * hour,
            peakPct = 12.0,
            hitLimit = false,
            current = true,
        )
        val bars = HistoryStats.bars(records, SessionLog.SESSION, current)
        assertEquals(2, bars.size)
        assertTrue(bars[0].current) // newest is the open one
        // A log record with the same reset as `current` must not double up.
        val dup = HistoryStats.bars(
            records + rec(SessionLog.SESSION, 200 * hour, 12.0), SessionLog.SESSION, current,
        )
        assertEquals(2, dup.size)
    }

    @Test
    fun `weeks group by monday and sort newest first`() {
        // Wed 2026-07-15 and Fri 2026-07-17 are the same week (Mon 13th);
        // Mon 2026-07-06 is the prior week.
        fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli() + 10 * hour
        val bars = listOf(
            HistoryStats.Bar(ms(LocalDate.of(2026, 7, 15)), 0, 30.0, false, false),
            HistoryStats.Bar(ms(LocalDate.of(2026, 7, 17)), 0, 80.0, false, false),
            HistoryStats.Bar(ms(LocalDate.of(2026, 7, 6)), 0, 50.0, false, false),
        )
        val weeks = HistoryStats.weeks(bars, zone)
        assertEquals(2, weeks.size)
        assertEquals(LocalDate.of(2026, 7, 13), weeks[0].monday)
        assertEquals(2, weeks[0].bars.size)
        assertEquals(LocalDate.of(2026, 7, 6), weeks[1].monday)
        assertEquals(1, weeks[1].bars.size)
    }
}
