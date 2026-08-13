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
     */
    fun redSegment(percent: Double?, elapsedPercent: Double?): Pair<Float, Float>? {
        percent ?: return null
        elapsedPercent ?: return null
        if (percent <= elapsedPercent + PACE_DEAD_ZONE) return null
        val start = tickSweep(elapsedPercent)
        val end = fillSweep(percent)
        if (end <= start) return null
        return start to (end - start)
    }
}
