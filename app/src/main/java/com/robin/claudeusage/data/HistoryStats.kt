package com.robin.claudeusage.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns [SessionLog] records (plus the current open window) into the bars the
 * history screen draws. Pure math, no Android imports, so it's unit-testable.
 */
object HistoryStats {

    const val SESSION_MS = 5L * 60 * 60_000L
    const val WEEK_MS = 7L * 24 * 60 * 60_000L

    /** One bar: a single 5-hour or 7-day window. [current] marks the open window. */
    data class Bar(
        val startMs: Long,
        val resetMs: Long,
        val peakPct: Double,
        val hitLimit: Boolean,
        val current: Boolean,
    )

    /** Sessions grouped under the calendar week (Mon–Sun) they started in. */
    data class Week(val monday: LocalDate, val bars: List<Bar>)

    fun windowMs(kind: String): Long = if (kind == SessionLog.WEEKLY) WEEK_MS else SESSION_MS

    /**
     * All bars of one [kind], newest first. [current] (the still-open window, if
     * any) is merged in and de-duplicated against the log by reset time.
     */
    fun bars(records: List<SessionLog.Record>, kind: String, current: Bar?): List<Bar> {
        val len = windowMs(kind)
        val fromLog = records
            .filter { it.kind == kind }
            .map { Bar(it.resetAt - len, it.resetAt, it.peakPct, it.hitLimit, current = false) }
        return (fromLog + listOfNotNull(current))
            .distinctBy { it.resetMs }
            .sortedByDescending { it.startMs }
    }

    /** Groups session bars into calendar weeks, newest week first. */
    fun weeks(bars: List<Bar>, zone: ZoneId): List<Week> =
        bars.groupBy { mondayOf(it.startMs, zone) }
            .map { (monday, weekBars) -> Week(monday, weekBars.sortedByDescending { it.startMs }) }
            .sortedByDescending { it.monday }

    /** The Monday of the calendar week containing [ms], in [zone]. */
    fun mondayOf(ms: Long, zone: ZoneId): LocalDate {
        val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return date.minusDays((date.dayOfWeek.value - 1).toLong())
    }
}
