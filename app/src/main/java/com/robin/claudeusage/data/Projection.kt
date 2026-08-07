package com.robin.claudeusage.data

import kotlin.math.abs

/**
 * Linear burn-rate estimate for one window instance.
 *
 * History points are matched to a window by its `resets_at`, but **not** by exact
 * equality: the server slides that timestamp forward on nearly every poll, so a
 * single live window accumulates hundreds of distinct values (measured: 561
 * distinct across 672 points for one 7-day window). Exact matching bound 1-2
 * samples out of hundreds, which silently killed the trend chart. Points are
 * bound by proximity instead — see [tolerance].
 */
object Projection {

    /** Don't extrapolate from less than this much observed time... */
    private const val MIN_SPAN_MS = 20L * 60_000L

    /** ...or less than this much observed movement. */
    private const val MIN_DELTA_PCT = 1.0

    /**
     * How far a point's `resets_at` may sit from the window's and still be counted
     * as the same window. A quarter of the window is deliberately generous for
     * drift while staying unambiguous: a *genuine* reset moves `resets_at` by a
     * full window length — four times this — so points from the previous window can
     * never leak into the current one.
     */
    fun tolerance(windowLengthMs: Long): Long = windowLengthMs / 4

    /** The two plan window lengths. Single source of truth — [tolerance] derives from them. */
    const val SESSION_MS: Long = 5 * 60 * 60_000L
    const val WEEKLY_MS: Long = 7 * 24 * 60 * 60_000L

    /**
     * Whether two `resets_at` readings (epoch millis) name the same window instance.
     *
     * **Never compare `resets_at` with `==`.** For the reason see this object's doc:
     * the server recomputes the timestamp per request, so one live window yields
     * hundreds of distinct values. Truncating to a unit is not a substitute either —
     * the drift straddles unit boundaries (measured 2026-07-30: five polls inside one
     * unchanged window spanned `09:19:59.625`–`09:20:00.950`, so minute-truncation
     * still flips between `09:19` and `09:20`).
     *
     * `0` means "nothing recorded yet" and matches no window.
     */
    fun sameWindow(a: Long, b: Long, windowLengthMs: Long): Boolean =
        a != 0L && b != 0L && abs(a - b) <= tolerance(windowLengthMs)

    data class Estimate(
        val ratePctPerHour: Double,
        /** When usage reaches 100% at the current pace; null = not before the reset. */
        val hitsLimitAtMs: Long?,
        /** Projected percent at the reset moment, capped at 100. */
        val pctAtReset: Double,
    )

    /**
     * [samples] are (epochMillis, percent) fetches for the current window, any
     * order. Returns null when there isn't enough signal to be honest.
     *
     * The rate is a least-squares fit over every sample, not the first-to-last
     * slope: with a full window's history, one early burst followed by idle time
     * would otherwise set the pace for the rest of the window. The projection is
     * still *anchored* on the latest reading rather than the fitted line, so the
     * dashed tail joins the point the chart actually draws.
     */
    fun estimate(samples: List<Pair<Long, Double>>, resetAtMs: Long): Estimate? {
        if (samples.size < 2) return null
        val sorted = samples.sortedBy { it.first }
        val (t0, p0) = sorted.first()
        val (t1, p1) = sorted.last()
        if (t1 - t0 < MIN_SPAN_MS || p1 - p0 < MIN_DELTA_PCT) return null

        val n = sorted.size
        // x measured from the first sample, so the products stay small.
        val meanX = sorted.sumOf { (it.first - t0).toDouble() } / n
        val meanY = sorted.sumOf { it.second } / n
        var sxy = 0.0
        var sxx = 0.0
        for ((t, pct) in sorted) {
            val dx = (t - t0).toDouble() - meanX
            sxy += dx * (pct - meanY)
            sxx += dx * dx
        }
        val ratePerMs =
            if (sxx > 0.0) sxy / sxx else (p1 - p0) / (t1 - t0).toDouble()
        // A fit can come out flat or negative even when the endpoints rose (a burst
        // then a long plateau). No honest projection to make from that.
        if (ratePerMs <= 0.0 || !ratePerMs.isFinite()) return null

        val msToLimit = (100.0 - p1) / ratePerMs
        val msToReset = (resetAtMs - t1).toDouble()
        return Estimate(
            ratePctPerHour = ratePerMs * 3_600_000.0,
            hitsLimitAtMs =
                if (msToLimit.isFinite() && msToLimit in 0.0..msToReset) t1 + msToLimit.toLong()
                else null,
            pctAtReset = (p1 + ratePerMs * msToReset).coerceAtMost(100.0),
        )
    }

    // --- Pace alerts (CCRM-21) -----------------------------------------------------
    //
    // Warn on where usage is *heading*, not just where it is. The severity ladder and
    // the transition rules live here as pure functions so they're unit-testable the
    // way PingSchedule is; Alerts.checkPace only does I/O around them.

    /** "Cutting it close": projected to land at or above this percent at the reset. */
    const val PACE_CLOSE_AT_RESET = 85.0

    /** "Almost out": less than 10% of the window's quota left. */
    const val PACE_ALMOST_OUT_USED = 90.0

    /**
     * Young-window suppression: no verdict until this much of the period has elapsed
     * (min 60s) — a 5-hour window at 2% after four minutes projects to 150% and means
     * nothing.
     */
    fun paceMinElapsedMs(windowLengthMs: Long): Long = maxOf(windowLengthMs / 100, 60_000L)

    /** ...and none at all under this much usage. */
    const val PACE_MIN_USED = 5.0

    /** Ordered — hysteresis and escalation compare by ordinal. */
    enum class PaceSeverity { UNTRACKED, HEALTHY, CLOSE, RUNNING_OUT, SPENT }

    /** Descending severity, so the first satisfied milestone is the headline. */
    enum class PaceMilestone(val bit: Int) {
        WILL_RUN_OUT(4), CUTTING_IT_CLOSE(2), ALMOST_OUT(1);

        companion object {
            fun fromMask(mask: Int): List<PaceMilestone> = entries.filter { mask and it.bit != 0 }
        }
    }

    /**
     * The ladder, from the same inputs the chart draws: the even-pace diagonal's
     * elapsed fraction and [estimate]'s projection — which is why the chart and the
     * alerts agree for free. A null estimate (not enough signal to be honest) can
     * still be SPENT, but never CLOSE or RUNNING_OUT: no projection, no verdict
     * about the future.
     */
    fun paceSeverity(
        usedPct: Double,
        elapsedMs: Long,
        windowLengthMs: Long,
        estimate: Estimate?,
    ): PaceSeverity = when {
        elapsedMs < paceMinElapsedMs(windowLengthMs) || usedPct < PACE_MIN_USED ->
            PaceSeverity.UNTRACKED
        usedPct >= 99.5 -> PaceSeverity.SPENT
        estimate?.hitsLimitAtMs != null -> PaceSeverity.RUNNING_OUT
        (estimate?.pctAtReset ?: 0.0) >= PACE_CLOSE_AT_RESET -> PaceSeverity.CLOSE
        else -> PaceSeverity.HEALTHY
    }

    /** Which milestones the current reading satisfies, as a [PaceMilestone.bit] mask. */
    fun paceSatisfied(severity: PaceSeverity, usedPct: Double): Int {
        if (severity == PaceSeverity.UNTRACKED) return 0
        var mask = 0
        if (severity >= PaceSeverity.RUNNING_OUT) mask = mask or PaceMilestone.WILL_RUN_OUT.bit
        if (severity >= PaceSeverity.CLOSE) mask = mask or PaceMilestone.CUTTING_IT_CLOSE.bit
        if (usedPct >= PACE_ALMOST_OUT_USED) mask = mask or PaceMilestone.ALMOST_OUT.bit
        return mask
    }

    /** Persisted per profile+window: the window identity and which milestones fired. */
    data class PaceState(val windowKey: Long, val firedMask: Int)

    /**
     * @property carry state to persist if nothing fires (or before delivery): the
     *   re-anchored key and the hysteresis-cleared fired mask. On delivery the caller
     *   ORs the delivered bits in; a failed delivery simply isn't ORed, so the
     *   milestone retries next poll (guard 5: rollback on delivery failure).
     * @property fire milestones to notify now, most severe first.
     */
    data class PaceStep(val carry: PaceState, val fire: List<PaceMilestone>)

    /**
     * One evaluation step. Encodes the guards that keep this feature from being
     * annoying:
     *  1. **Primed** — the first observation of a window never fires; whatever is
     *     already true is marked as known, so enabling the feature (or any state
     *     loss) can't dump a backlog for a window the user already knows about.
     *  2. **Dedupe by window identity** through [sameWindow], never `==` (CCBG-4),
     *     with the stored key re-anchored to the newest reading each step so drift
     *     can't accumulate past tolerance over a 7-day window.
     *  3. **Hysteresis** — a fired bit survives only while its milestone stays
     *     satisfied, so a window hovering on a boundary re-fires only after severity
     *     genuinely dropped below it and rose again.
     *
     * [satisfiedMask] should already be filtered to the user's enabled milestones.
     */
    fun paceStep(
        stored: PaceState?,
        windowKey: Long,
        windowLengthMs: Long,
        satisfiedMask: Int,
    ): PaceStep {
        if (stored == null || !sameWindow(stored.windowKey, windowKey, windowLengthMs)) {
            return PaceStep(PaceState(windowKey, satisfiedMask), emptyList())
        }
        val carried = stored.firedMask and satisfiedMask
        val toFire = satisfiedMask and carried.inv()
        return PaceStep(
            PaceState(windowKey, carried),
            PaceMilestone.fromMask(toFire),
        )
    }

    /** Session samples belonging to the window that resets around [resetAtMs]. */
    fun sessionSamples(
        history: List<HistoryPoint>,
        resetAtMs: Long,
        windowLengthMs: Long,
    ): List<Pair<Long, Double>> = bind(history, resetAtMs, windowLengthMs,
        pct = { it.sessionPct }, reset = { it.sessionResetAt })

    /** Weekly samples belonging to the window that resets around [resetAtMs]. */
    fun weeklySamples(
        history: List<HistoryPoint>,
        resetAtMs: Long,
        windowLengthMs: Long,
    ): List<Pair<Long, Double>> = bind(history, resetAtMs, windowLengthMs,
        pct = { it.weeklyPct }, reset = { it.weeklyResetAt })

    private fun bind(
        history: List<HistoryPoint>,
        resetAtMs: Long,
        windowLengthMs: Long,
        pct: (HistoryPoint) -> Double?,
        reset: (HistoryPoint) -> Long,
    ): List<Pair<Long, Double>> {
        val tol = tolerance(windowLengthMs)
        return history.mapNotNull { p ->
            val r = reset(p)
            val v = pct(p)
            if (v != null && r > 0L && abs(r - resetAtMs) <= tol) p.at to v else null
        }.sortedBy { it.first }
    }
}
