package com.robin.claudeusage.ui

import kotlin.math.max

/**
 * Pure geometry for the pace-marked horizontal bars (CCRM-43 (Bar Pace Marks),
 * ported from the Mac's CCM-50 [Panel]).
 *
 * Fractions are 0..1 along the bar; multiply by the bar's pixel width at the call
 * site. Kept free of android.graphics like [RingGeometry], so every number here is
 * JVM-testable and the same values serve the in-app Compose bars, the widget
 * bitmaps and the notification.
 *
 * Draw order, bottom→top: track · fill · red segment · tick. The tick draws last
 * because it is the only pace signal left when the fill covers it.
 *
 * Two deliberate departures from the handover, from the CCRM-43 wireframe review
 * (rev B, 2026-08-13):
 *  - the segment has **no minimum width** — it starts exactly on the pace line, so
 *    a 3-point overshoot draws a 3-point sliver rather than being inflated to `h`
 *    and dragged back behind the tick;
 *  - the fill→red boundary is a straight vertical edge, not a capsule end. That is
 *    a painter concern ([BarRenderer] clips the red to the fill's shape), but it is
 *    the reason nothing here rounds or pads the segment's start.
 */
object BarGeometry {

    /** How much of the bar the usage fill covers. */
    fun fillFraction(percent: Double): Float =
        (percent.coerceIn(0.0, 100.0) / 100.0).toFloat()

    /** Where "even pace" sits right now, as a fraction along the bar. */
    fun tickFraction(elapsedPercent: Double): Float =
        (elapsedPercent.coerceIn(0.0, 100.0) / 100.0).toFloat()

    /** Tick capsule width ≈ 0.31 × bar height, floored so thin bars keep a visible tick. */
    fun tickWidth(heightPx: Float): Float = max(2f, 0.31f * heightPx)

    /** How far the tick extends past *each* long edge of the bar. */
    fun tickOverhang(heightPx: Float): Float = 0.3f * heightPx

    /**
     * Honesty rule: no percent or no reset clock (elapsed derives from it) → no
     * tick at all. Never a guessed mark. Credits rows pass a null elapsed, which
     * is how "money has no clock" is enforced.
     */
    fun showTick(percent: Double?, elapsedPercent: Double?): Boolean =
        percent != null && elapsedPercent != null

    /**
     * The full-red over-pace segment as (startFraction, endFraction) — from the
     * pace line to the fill's edge. Non-null only when meaningfully over pace:
     * `percent > elapsed + PACE_DEAD_ZONE`, strict — the *identical* comparison
     * the chart wash, the rings and the pace sentence make, so no two surfaces can
     * disagree about the verdict. A hairline overshoot draws the tick alone.
     *
     * [enabled] is the Settings toggle for this surface ("Show red past the pace
     * mark"). It gates the segment here rather than at each painter so that
     * toggle-off is unit-testable, and so the tick — which always draws — can't be
     * switched off by accident.
     */
    fun redSegment(
        percent: Double?,
        elapsedPercent: Double?,
        enabled: Boolean = true,
    ): Pair<Float, Float>? {
        if (!enabled) return null
        percent ?: return null
        elapsedPercent ?: return null
        if (percent <= elapsedPercent + PACE_DEAD_ZONE) return null
        val start = tickFraction(elapsedPercent)
        val end = fillFraction(percent)
        if (end <= start) return null
        return start to end
    }
}
