package com.robin.claudeusage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import kotlin.math.cos
import kotlin.math.sin

/**
 * The small usage icon, shared by the status bar and the Quick Settings tile.
 *
 * **The two surfaces disagree about colour, and that shapes everything here.**
 * Measured on a Fold 7 (CCRM-49 (Glyph Legibility)): the status bar reproduces a
 * bitmap's colours exactly, while the QS tile flattens it to a single tint. So
 * colour can only ever be an *enhancement* — alpha and shape carry the level and the
 * pace mark, because that is all a tinting surface will show. [draw]'s `fillArgb`
 * picks the mode: null for the alpha-mask rendering, a colour for the rest.
 *
 * **Size is the other hard fact.** The bitmap is 24 dp but the status bar fits it
 * into a ~15 dp slot *by width*, so it lands around 14 dp — which is why this draws
 * one window at full size rather than two small ones, and why a wide bitmap buys
 * nothing (it keeps the width and loses the height).
 *
 * CCRM-48 (Status-Bar Gauge) added the pace mark and CCRM-49 made it readable. The
 * mark is a slot **erased through** the band (PorterDuff.CLEAR) so it shows on fill
 * and track alike; in colour a cool line is drawn inside that slot. Honesty rule as
 * everywhere: no reset clock → no mark, never a guessed position
 * ([RingGeometry.showTick]).
 */
object UsageIcon {

    /** Styles offered in settings. */
    const val RING = "ring"

    /**
     * [left] is CCRM-22 (Used or Left): it flips only the "number" style's digits
     * (rev B — every numeric readout follows the token). The fills — ring arc, pie
     * slice, battery liquid — always draw the used fraction, and the ≥100% "!!"
     * overflow glyph keys on used in both modes.
     *
     * [sessionElapsed] positions the ring's pace mark. [fillArgb] is the resolved
     * severity colour — pass `Palette.barColor(...)` so the glyph and the
     * notification's own gauge can never disagree — or null for the monochrome
     * alpha-mask rendering a tinting surface needs. [paceArgb] is the theme's
     * pace-line partner (`Palette.paceColor(...)`, CCRM-50 (Weekly Flag)); null
     * keeps the historical blue. [weeklyPct]/[weeklyElapsed] feed the weekly flag
     * dot in the ring's hollow — the pace verdicts, drawn ([RingGeometry.weeklyFlag]).
     * All defaulted, so older call sites keep compiling and keep their drawing.
     */
    fun draw(
        context: Context,
        pct: Double?,
        style: String,
        left: Boolean = false,
        sessionElapsed: Double? = null,
        fillArgb: Int? = null,
        dark: Boolean = true,
        paceArgb: Int? = null,
        weeklyPct: Double? = null,
        weeklyElapsed: Double? = null,
    ): Bitmap {
        val size = dp(context, 24f).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fraction = ((pct ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        // Clamp a non-zero fill to a small minimum so the icon still reads at 1-3%.
        val sweep = if (fraction > 0f) fraction.coerceAtLeast(0.09f) else 0f
        val white = Color.WHITE

        when (style) {
            "pie" -> {
                val r = size * 0.42f
                val cx = size / 2f
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE; strokeWidth = size * 0.09f
                    color = white; alpha = 90
                }
                c.drawCircle(cx, cx, r, ring)
                if (sweep > 0f) {
                    val slice = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
                    c.drawArc(RectF(cx - r, cx - r, cx + r, cx + r), -90f, 360f * sweep, true, slice)
                }
            }
            "battery" -> {
                val left = size * 0.3f
                val right = size * 0.7f
                val top = size * 0.12f
                val bottom = size * 0.9f
                val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE; strokeWidth = size * 0.07f; color = white
                }
                c.drawRoundRect(RectF(left, top, right, bottom), size * 0.08f, size * 0.08f, body)
                val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
                c.drawRoundRect(RectF(size * 0.42f, size * 0.05f, size * 0.58f, top), 2f, 2f, cap)
                val inset = size * 0.11f
                val fillTop = bottom - inset - (bottom - top - 2 * inset) * fraction
                val liquid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
                c.drawRect(left + inset, fillTop, right - inset, bottom - inset, liquid)
            }
            "number" -> {
                val label = when {
                    pct == null -> "–"
                    pct >= 100.0 -> "!!"
                    else -> Fmt.usageInt(pct, left).toString()
                }
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = white
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    // "!!" and Left mode's possible "100" both need the step-down.
                    textSize = if (label == "!!" || label.length >= 3) size * 0.5f else size * 0.62f
                }
                val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
                c.drawText(label, size / 2f, baseline, text)
            }
            else -> { // "ring" (default) — one window, as large as the square allows
                // CCRM-49 (Glyph Legibility): 22.4 dp across at a 4 dp stroke, sized so
                // the band plus the pace mark's overhang just fit the 24 dp box. The
                // status bar then renders the whole thing at ~14 dp (measured), which
                // is the size that actually has to be readable.
                val cx = size / 2f
                val u = size / 24f
                windowRing(c, cx, 9.2f * u, 4f * u, pct, sessionElapsed, fillArgb, dark, paceArgb)
                // CCRM-50 (Weekly Flag): the 7-day window as a *state*, not a level —
                // a 7.5 dp dot in the hollow the ring already leaves empty. Absent
                // when the weekly is below pace; a second level was measured
                // unreadable at this size, a flag costs the 5-hour ring nothing.
                RingGeometry.weeklyFlag(weeklyPct, weeklyElapsed)?.let { flag ->
                    val dot = Paint(Paint.ANTI_ALIAS_FLAG)
                    if (fillArgb == null) {
                        // Monochrome surfaces collapse the rungs to alpha: the quiet
                        // rung stays visibly quieter than the loud ones.
                        dot.color = Color.WHITE
                        dot.alpha = if (flag == RingGeometry.WeeklyFlag.ON_PACE) 115 else 255
                    } else {
                        // Rung hues match Palette.barColor's ladder; grey sits a step
                        // brighter than the ring track so it reads as deliberate.
                        dot.color = when (flag) {
                            RingGeometry.WeeklyFlag.SPENT ->
                                if (dark) 0xFFFF5252.toInt() else 0xFFC62828.toInt()
                            RingGeometry.WeeklyFlag.ABOVE ->
                                if (dark) 0xFFFDD663.toInt() else 0xFFF9A825.toInt()
                            RingGeometry.WeeklyFlag.ON_PACE ->
                                if (dark) 0xFFBDBDBD.toInt() else 0xFF757575.toInt()
                        }
                    }
                    c.drawCircle(cx, cx, 3.75f * u, dot)
                }
            }
        }
        return bmp
    }

    /**
     * One pace-marked window ring, centred at ([cx],[cx]) with band-centre radius
     * [r] and stroke [sw], all in px. Draw order: track · fill · ≥100 notch · pace
     * mark — the mark goes last so it survives everything under it, exactly the
     * [RingGeometry] ordering.
     *
     * [fillArgb] null means **monochrome**: white on alpha, for surfaces that tint
     * the bitmap and throw colour away. Non-null means the surface keeps colour, and
     * then used / remaining / pace get three different treatments instead of three
     * alphas of one ink.
     */
    private fun windowRing(
        c: Canvas,
        cx: Float,
        r: Float,
        sw: Float,
        pct: Double?,
        elapsed: Double?,
        fillArgb: Int?,
        dark: Boolean,
        paceArgb: Int? = null,
    ) {
        val colour = fillArgb != null
        val trackColor = if (colour) TRACK_NEUTRAL else Color.WHITE
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw; color = trackColor
            if (!colour) alpha = 90
        }
        // No data ≠ 0%: a null percent leaves the bare track and nothing else.
        c.drawCircle(cx, cx, r, track)
        if (pct != null) {
            val fraction = (pct / 100.0).coerceIn(0.0, 1.0).toFloat()
            val sweep = if (fraction > 0f) fraction.coerceAtLeast(0.09f) else 0f
            if (sweep > 0f) {
                val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = sw
                    strokeCap = Paint.Cap.ROUND
                    color = fillArgb ?: Color.WHITE
                }
                c.drawArc(RectF(cx - r, cx - r, cx + r, cx + r), -90f, 360f * sweep, false, arc)
            }
            // ≥100 is also marked by shape, not only by the red rung: a notch at 12
            // o'clock makes a closed ring read as *closed* rather than merely long.
            // Kept in colour too, because it is the only cue that survives a surface
            // which flattens the bitmap to one tint.
            if (pct >= 100.0) eraseSlot(c, cx, r, sw, 0f, sw * 0.7f)
        }
        // The pace mark. Honesty rule shared with every other ring surface: both a
        // percent and an elapsed, or no mark at all — never a guessed position.
        if (RingGeometry.showTick(pct, elapsed)) {
            val at = RingGeometry.tickSweep(elapsed!!)
            // The slot is erased in both modes — real transparency, so the mark reads
            // on fill and on track alike.
            val line = 0.40f * sw
            eraseSlot(c, cx, r, sw, at, if (colour) line + 0.5f * sw else 0.6f * sw)
            if (colour) {
                // …then a cool line inside the slot. A cool hue can never collide with
                // the warm severity ladder, so the mark stays "where am I in the
                // window" and never becomes "how bad is it". The slot is deliberately
                // wider than the line: that transparent margin is what still reads as
                // a mark on a surface that flattens us to one tint.
                drawRadial(c, cx, r, sw, at, paceArgb ?: PACE_COOL, line)
            }
        }
    }

    /**
     * The pace mark's fallback hue when no theme partner is passed — Claude Orange's
     * partner, kept so older call sites draw what they always drew. Themed callers
     * pass `Palette.paceColor(...)` instead (CCRM-50 (Weekly Flag)).
     */
    private const val PACE_COOL = 0xFF5BC8FF.toInt()

    /** Remaining, in colour mode: a mid neutral that holds up on a light or dark bar. */
    private val TRACK_NEUTRAL = Color.argb(150, 150, 150, 150)

    /**
     * Erases a radial slot through the ring band at [sweepDeg] past 12 o'clock,
     * clockwise — real transparency, so whatever the icon sits on shows through.
     */
    private fun eraseSlot(c: Canvas, cx: Float, r: Float, sw: Float, sweepDeg: Float, width: Float) {
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        radial(c, cx, r, sw, sweepDeg, clear)
    }

    /** Draws a radial stroke across the band, in [color] at [width]. */
    private fun drawRadial(
        c: Canvas, cx: Float, r: Float, sw: Float, sweepDeg: Float, color: Int, width: Float,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }
        radial(c, cx, r, sw, sweepDeg, p)
    }

    /**
     * The shared radial geometry: a line across the band at [sweepDeg] past 12
     * o'clock, clockwise, overhanging each edge just enough that antialiased fringes
     * can't survive a cut. Deliberately a small overhang — at this size a longer one
     * reads as a spike into the ring's hollow rather than a mark on the band.
     */
    private fun radial(c: Canvas, cx: Float, r: Float, sw: Float, sweepDeg: Float, paint: Paint) {
        val rad = Math.toRadians((sweepDeg - 90f).toDouble())
        val overhang = 0.12f * sw
        val rIn = r - sw / 2f - overhang
        val rOut = r + sw / 2f + overhang
        c.drawLine(
            cx + rIn * cos(rad).toFloat(), cx + rIn * sin(rad).toFloat(),
            cx + rOut * cos(rad).toFloat(), cx + rOut * sin(rad).toFloat(),
            paint,
        )
    }

    private fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        )
}
