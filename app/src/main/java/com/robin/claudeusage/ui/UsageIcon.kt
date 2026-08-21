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
 * The small monochrome usage icon, shared by the status bar and the Quick Settings
 * tile. Everything is drawn white and the two-tone look (faint track + solid fill)
 * rides on the alpha channel, because both surfaces tint the icon themselves — they
 * treat it as an alpha mask, so the level can only be conveyed through fill, never
 * through colour.
 *
 * CCRM-48 (Status-Bar Gauge) adds the pace mark. On this surface fill and mark are
 * the same ink, so a tick drawn *over* the band would vanish on the fill — the mark
 * is instead a slot **erased through** the band (PorterDuff.CLEAR), the Mac gauge's
 * cleared-gap tick with nothing redrawn inside; the bar shows through the cut. Same
 * honesty rule as every other ring: no reset clock → no cut, never a guessed
 * position ([RingGeometry.showTick]). And because the tint also strips the severity
 * ladder, ≥100% is marked by shape instead: a notch opens at 12 o'clock, so a
 * closed ring reads as *closed* rather than merely long.
 */
object UsageIcon {

    /** Styles offered in settings. */
    const val RING = "ring"

    /** Both windows as concentric rings: 7-day outside, 5-hour inside. */
    const val TWIN = "twin"

    /**
     * [left] is CCRM-22 (Used or Left): it flips only the "number" style's digits
     * (rev B — every numeric readout follows the token). The fills — ring arc, pie
     * slice, battery liquid — always draw the used fraction, and the ≥100% "!!"
     * overflow glyph keys on used in both modes.
     *
     * [sessionElapsed] / [weeklyElapsed] are the windows' time-elapsed percents;
     * they position the pace cuts on the ring/twin styles. [weeklyPct] feeds the
     * twin style's outer ring. All defaulted, so call sites that predate CCRM-48
     * (Status-Bar Gauge) keep compiling and keep today's cutless drawing.
     */
    fun draw(
        context: Context,
        pct: Double?,
        style: String,
        left: Boolean = false,
        sessionElapsed: Double? = null,
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
            TWIN -> {
                // CCRM-48 (Status-Bar Gauge): both windows, concentric, each ring as
                // large as the 24 dp square allows so the pace cuts stay legible —
                // 7-day outside (23/24 across), 5-hour inside (14.5/24), 2.6 stroke.
                val cx = size / 2f
                val u = size / 24f
                windowRing(c, cx, 10.2f * u, 2.6f * u, weeklyPct, weeklyElapsed)
                windowRing(c, cx, 5.95f * u, 2.6f * u, pct, sessionElapsed)
            }
            else -> { // "ring" (default) — faint full ring + solid arc from the top
                val cx = size / 2f
                windowRing(c, cx, size * 0.38f, size * 0.14f, pct, sessionElapsed)
            }
        }
        return bmp
    }

    /**
     * One pace-marked window ring, centred at ([cx],[cx]) with band-centre radius
     * [r] and stroke [sw], all in px. Draw order: track · fill · ≥100 notch · pace
     * cut — the cut erases last so it survives everything under it, exactly the
     * [RingGeometry] ordering with CLEAR standing in for the neutral tick colour.
     */
    private fun windowRing(
        c: Canvas,
        cx: Float,
        r: Float,
        sw: Float,
        pct: Double?,
        elapsed: Double?,
    ) {
        val white = Color.WHITE
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw; color = white; alpha = 90
        }
        // No data ≠ 0%: a null percent leaves the bare track and nothing else.
        c.drawCircle(cx, cx, r, track)
        if (pct != null) {
            val fraction = (pct / 100.0).coerceIn(0.0, 1.0).toFloat()
            val sweep = if (fraction > 0f) fraction.coerceAtLeast(0.09f) else 0f
            if (sweep > 0f) {
                val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = sw
                    strokeCap = Paint.Cap.ROUND; color = white
                }
                c.drawArc(RectF(cx - r, cx - r, cx + r, cx + r), -90f, 360f * sweep, false, arc)
            }
            // Severity has no colour on a tinted mask, so ≥100 is shape: a notch at
            // 12 o'clock makes a closed ring read as closed rather than merely long.
            if (pct >= 100.0) eraseSlot(c, cx, r, sw, 0f, sw * 0.7f)
        }
        // The pace cut. Honesty rule shared with every other ring surface: both a
        // percent and an elapsed, or no mark at all — never a guessed position.
        if (RingGeometry.showTick(pct, elapsed)) {
            eraseSlot(c, cx, r, sw, RingGeometry.tickSweep(elapsed!!), 0.6f * sw)
        }
    }

    /**
     * Erases a radial slot through the ring band at [sweepDeg] past 12 o'clock,
     * clockwise — real transparency, so whatever the icon sits on shows through.
     */
    private fun eraseSlot(c: Canvas, cx: Float, r: Float, sw: Float, sweepDeg: Float, width: Float) {
        val rad = Math.toRadians((sweepDeg - 90f).toDouble())
        // A hair past each band edge, so antialiased fringes can't survive the cut.
        val overhang = 0.15f * sw
        val rIn = r - sw / 2f - overhang
        val rOut = r + sw / 2f + overhang
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        c.drawLine(
            cx + rIn * cos(rad).toFloat(), cx + rIn * sin(rad).toFloat(),
            cx + rOut * cos(rad).toFloat(), cx + rOut * sin(rad).toFloat(),
            clear,
        )
    }

    private fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        )
}
