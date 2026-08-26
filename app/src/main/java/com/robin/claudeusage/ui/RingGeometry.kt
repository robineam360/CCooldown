package com.robin.claudeusage.ui

import kotlin.math.max

/**
 * Pure geometry for the pace-marked rings (CCRM-39 (Ring Widget) / CCRM-40
 * (Mini-Rings Widget), ported from the Mac's CCM-49 [Desktop]).
 *
 * Angles are **sweep degrees from 12 o'clock, clockwise** — pass them to
 * `Canvas.drawArc` as `START_ANGLE + sweep`. Kept free of android.graphics like
 * [SparkGeometry], so every number here is JVM-testable; [RingRenderer] is the
 * thin painter on top.
 *
 * Draw order, bottom→top: track · fill · red segment · tick. The tick draws
 * last because it is the only pace signal left when the fill covers it.
 */
object RingGeometry {

    /** 12 o'clock in `drawArc` terms. */
    const val START_ANGLE = -90f

    fun fillSweep(percent: Double): Float =
        (percent.coerceIn(0.0, 100.0) / 100.0 * 360.0).toFloat()

    /** The even-pace tick's position: elapsedPercent mapped around the ring. */
    fun tickSweep(elapsedPercent: Double): Float =
        (elapsedPercent.coerceIn(0.0, 100.0) / 100.0 * 360.0).toFloat()

    /** Radial notch width ≈ 0.31 × stroke, floored so mini-rings keep a visible tick. */
    fun tickWidth(strokePx: Float): Float = max(2f, 0.31f * strokePx)

    /** How far the tick extends past *each* stroke edge. */
    fun tickOverhang(strokePx: Float): Float = 0.3f * strokePx

    /**
     * Honesty rule: no percent or no reset clock (elapsed derives from it) →
     * no tick at all. Never a guessed mark.
     */
    fun showTick(percent: Double?, elapsedPercent: Double?): Boolean =
        percent != null && elapsedPercent != null

    /**
     * The full-red over-pace segment, as (startSweep, sweepLength) — from the
     * tick to the fill tip. Non-null only when meaningfully over pace:
     * `percent > elapsed + PACE_DEAD_ZONE`, strict — the *identical* comparison
     * the chart wash and the pace sentence make, so ring, sentence and chart
     * can never disagree. A hairline overshoot draws the tick alone.
     *
     * [enabled] is the widgets' "Show red past the pace mark" toggle (CCRM-43 (Bar
     * Pace Marks)); it gates the segment only, never the tick. Defaulted, so every
     * existing call site keeps today's behaviour.
     */
    /**
     * The weekly flag dot's rungs — an escalation, drawn (CCRM-51 (Rails Gauge)
     * revised CCRM-50 (Weekly Flag)):
     *
     * [EMPTY] nothing used yet · [WITHIN] used, but not ahead of pace · [ABOVE] ahead
     * of pace · [SPENT] the week is gone.
     *
     * [ABOVE] and [WITHIN] split on the *identical* ±[PACE_DEAD_ZONE] comparison
     * behind the "above / on / below even pace" sentence, so the dot flips at the exact
     * poll the sentence does. [EMPTY] and [SPENT] key on the level alone — truncated,
     * like every ladder comparison — and so need no reset clock at all.
     *
     * **[WITHIN] is deliberately wider than CCRM-50's `ON_PACE`.** It covers *below*
     * pace as well as on it: the quiet rung says "nothing to flag", which is true of
     * both. CCRM-50 drew nothing below pace ("good news is silence"); that made "no
     * dot" mean two different things, and the [EMPTY] rung needs the distinction.
     */
    enum class WeeklyFlag { EMPTY, WITHIN, ABOVE, SPENT }

    /**
     * Null = **no dot, and it now means exactly one thing: no weekly reading.** An
     * alert never claims silence proves health, so a missing window says "no data" in
     * words rather than drawing a rung.
     *
     * Note what is *not* null any more: a week with usage but **no reset clock** used
     * to draw nothing, which under this ladder would falsely read as "no reading". It
     * rests on [WITHIN] instead — there is usage, so it is not [EMPTY]; pace cannot be
     * judged, so it may not be [ABOVE]. Never a guessed verdict, but never a lie
     * either.
     */
    fun weeklyFlag(percent: Double?, elapsedPercent: Double?): WeeklyFlag? {
        percent ?: return null
        if (percent.toInt() >= 100) return WeeklyFlag.SPENT
        if (percent <= 0.0) return WeeklyFlag.EMPTY
        elapsedPercent ?: return WeeklyFlag.WITHIN
        return if (percent - elapsedPercent > PACE_DEAD_ZONE) WeeklyFlag.ABOVE
        else WeeklyFlag.WITHIN
    }

    fun redSegment(
        percent: Double?,
        elapsedPercent: Double?,
        enabled: Boolean = true,
    ): Pair<Float, Float>? {
        if (!enabled) return null
        percent ?: return null
        elapsedPercent ?: return null
        if (percent <= elapsedPercent + PACE_DEAD_ZONE) return null
        val start = tickSweep(elapsedPercent)
        val end = fillSweep(percent)
        if (end <= start) return null
        return start to (end - start)
    }
}
