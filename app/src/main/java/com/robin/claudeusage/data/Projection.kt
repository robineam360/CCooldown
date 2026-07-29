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
