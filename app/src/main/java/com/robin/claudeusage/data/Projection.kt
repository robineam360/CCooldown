package com.robin.claudeusage.data

/**
 * Linear burn-rate estimate for one window instance. History points are
 * matched to the window by its identity — the resets_at timestamp — so a
 * reset never mixes old points into the new window's trend.
 */
object Projection {

    /** Don't extrapolate from less than this much observed time... */
    private const val MIN_SPAN_MS = 20L * 60_000L

    /** ...or less than this much observed movement. */
    private const val MIN_DELTA_PCT = 1.0

    data class Estimate(
        val ratePctPerHour: Double,
        /** When usage reaches 100% at the current pace; null = not before the reset. */
        val hitsLimitAtMs: Long?,
        /** Projected percent at the reset moment, capped at 100. */
        val pctAtReset: Double,
    )

    /**
     * [samples] are (epochMillis, percent) fetches for the current window,
     * any order. Returns null when there isn't enough signal to be honest.
     */
    fun estimate(samples: List<Pair<Long, Double>>, resetAtMs: Long): Estimate? {
        if (samples.size < 2) return null
        val sorted = samples.sortedBy { it.first }
        val (t0, p0) = sorted.first()
        val (t1, p1) = sorted.last()
        if (t1 - t0 < MIN_SPAN_MS || p1 - p0 < MIN_DELTA_PCT) return null
        val ratePerMs = (p1 - p0) / (t1 - t0)
        val hitAtMs = t1 + ((100.0 - p1) / ratePerMs).toLong()
        return Estimate(
            ratePctPerHour = ratePerMs * 3_600_000.0,
            hitsLimitAtMs = if (hitAtMs < resetAtMs) hitAtMs else null,
            pctAtReset = (p1 + ratePerMs * (resetAtMs - t1)).coerceAtMost(100.0),
        )
    }

    /** Session samples belonging to the window that resets at [resetAtMs]. */
    fun sessionSamples(history: List<HistoryPoint>, resetAtMs: Long): List<Pair<Long, Double>> =
        history.mapNotNull { p ->
            p.sessionPct?.takeIf { p.sessionResetAt == resetAtMs }?.let { p.at to it }
        }

    /** Weekly samples belonging to the window that resets at [resetAtMs]. */
    fun weeklySamples(history: List<HistoryPoint>, resetAtMs: Long): List<Pair<Long, Double>> =
        history.mapNotNull { p ->
            p.weeklyPct?.takeIf { p.weeklyResetAt == resetAtMs }?.let { p.at to it }
        }
}
