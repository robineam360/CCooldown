package com.robin.claudeusage.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Decides when to send a window ping (CCRM-17). Pure logic, no Android — the
 * scheduling rules are the part most likely to be wrong, so they're testable.
 *
 * A 5-hour window opens on your first billed message and its boundaries follow
 * that message, **not the clock** — measured `resets_at` was `09:20:00`, not an
 * hour mark. So the user's chosen times are a target, never a guarantee, and two
 * rules follow from it:
 *
 *  - **Chain off the observed `resets_at`, never anchor + 5h.** If the user starts
 *    work at 03:00 they own 03:00–08:00; a 04:00 ping would land inside that window
 *    and do nothing, and every later slot computed from the anchor would be an hour
 *    out of phase for the rest of the day. Following the real reset self-corrects.
 *  - **Never compare `resets_at` exactly** — the server recomputes it per request
 *    (CCBG-4). Window identity goes through [Projection.sameWindow]; "did the ping
 *    land" goes through [windowMoved].
 */
object PingSchedule {

    /**
     * A ping must move `resets_at` by at least this much to count as having opened a
     * window. Drift alone is ~1.3s (CCBG-4), and a real new window jumps ~5h, so a
     * minute cleanly separates "it worked" from "nothing happened". Comparing
     * exactly would report success for drift — the check would become the lie.
     */
    const val MOVED_THRESHOLD_MS = 60_000L

    /** How long after a failed attempt to try again, escalating. Then give up on that slot. */
    val RETRY_BACKOFF_MS = listOf(60_000L, 3 * 60_000L, 8 * 60_000L)

    data class Config(
        val enabled: Boolean,
        /** Minutes past local midnight for the first ping of the day, e.g. 240 = 04:00. */
        val firstPingMinuteOfDay: Int,
        /** Additional windows to open after the first. 0 = one window, no renewals. */
        val renewals: Int,
        /**
         * Minutes past local midnight after which we never ping. 0 = midnight, treated
         * as end-of-day (1440) so "never after 12:00 AM" means "not into tomorrow".
         */
        val cutoffMinuteOfDay: Int,
    )

    /** What the scheduler has already done today, so renewals are bounded. */
    data class DayState(
        /** Local date the counter belongs to; a new day resets it. */
        val day: LocalDate?,
        val windowsStarted: Int,
    )

    sealed interface Decision {
        /** Send a ping now. */
        data class Ping(val isFirstOfDay: Boolean) : Decision

        /**
         * Don't ping; wake at [atMs] and reconsider. [because] is for the status row.
         */
        data class Wait(val atMs: Long, val because: String) : Decision

        /** Nothing more to do today; wake at [atMs] for tomorrow's first ping. */
        data class DoneForToday(val atMs: Long) : Decision

        /** Feature off — cancel any alarm. */
        data object Off : Decision
    }

    /**
     * The whole decision, given the clock, the config, what we've done today and the
     * live session window (null when no window is open).
     */
    fun decide(
        nowMs: Long,
        zone: ZoneId,
        config: Config,
        day: DayState,
        sessionResetAtMs: Long?,
    ): Decision {
        if (!config.enabled) return Decision.Off

        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val startedToday = if (day.day == today) day.windowsStarted else 0
        val allowed = config.renewals + 1

        val firstPingMs = atMinuteOfDay(today, zone, config.firstPingMinuteOfDay)
        val cutoffMs = atMinuteOfDay(today, zone, effectiveCutoff(config.cutoffMinuteOfDay))
        val tomorrowFirstMs = atMinuteOfDay(today.plusDays(1), zone, config.firstPingMinuteOfDay)

        // A window we opened, or one the user opened by working — either way, don't
        // ping into it. Wake when it actually ends, not when we guessed it would.
        if (sessionResetAtMs != null && sessionResetAtMs > nowMs) {
            val next = sessionResetAtMs
            return if (startedToday >= allowed || next >= cutoffMs) {
                Decision.DoneForToday(maxOf(tomorrowFirstMs, next))
            } else {
                Decision.Wait(next, "window open until its reset")
            }
        }

        if (nowMs < firstPingMs) return Decision.Wait(firstPingMs, "waiting for the first ping")
        if (nowMs >= cutoffMs) return Decision.DoneForToday(tomorrowFirstMs)
        if (startedToday >= allowed) return Decision.DoneForToday(tomorrowFirstMs)

        return Decision.Ping(isFirstOfDay = startedToday == 0)
    }

    /**
     * Did the ping actually open a window? [before] may be null (no window at all),
     * in which case any window afterwards is proof. Otherwise the reset must have
     * moved by more than drift.
     */
    fun windowMoved(beforeMs: Long?, afterMs: Long?): Boolean {
        if (afterMs == null) return false
        if (beforeMs == null) return true
        return afterMs - beforeMs > MOVED_THRESHOLD_MS
    }

    /** Wall-clock delay between the intended fire time and the actual one, never negative. */
    fun latenessMs(intendedMs: Long, actualMs: Long): Long = (actualMs - intendedMs).coerceAtLeast(0L)

    /** 0 ("midnight") means the end of today rather than the start of it. */
    private fun effectiveCutoff(minuteOfDay: Int): Int = if (minuteOfDay <= 0) 1440 else minuteOfDay

    private fun atMinuteOfDay(date: LocalDate, zone: ZoneId, minuteOfDay: Int): Long =
        date.atStartOfDay(zone).plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli()
}
