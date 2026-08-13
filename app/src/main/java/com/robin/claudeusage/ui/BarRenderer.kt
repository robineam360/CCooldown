package com.robin.claudeusage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Draws a pace-marked horizontal bar to a bitmap: track · fill · red over-pace
 * segment · even-pace tick, per [BarGeometry] (CCRM-43 (Bar Pace Marks), from the
 * Mac's CCM-50 [Panel]).
 *
 * This is the shared bar surface for everything that can only show an image: the
 * Glance widget faces (which have no drawable that can host a tick) and the pinned
 * notification's RemoteViews. The in-app bars stay Compose and draw the same
 * geometry with a Canvas overlay.
 *
 * Text is deliberately NOT baked in, the same rule [RingRenderer] follows — labels
 * and percentages stay real `Text`, so they follow the system font scale.
 *
 * Two things the bitmap is bigger than the bar for, both so the tick is never
 * clipped: `0.3h` above and below, and half a tick width at each end (a tick at 0%
 * or 100% is centred on the bar's end, so half of it falls outside the track).
 */
object BarRenderer {

    /**
     * The bitmap's height for a bar of [heightPx] — the bar plus the tick's
     * overhang top and bottom. Call sites need this to lay out around the image.
     */
    fun bitmapHeight(heightPx: Float): Int =
        (heightPx + 2f * BarGeometry.tickOverhang(heightPx)).toInt().coerceAtLeast(1)

    /** Horizontal padding at each end, so a tick at 0% / 100% isn't clipped. */
    fun sidePadding(heightPx: Float): Float = BarGeometry.tickWidth(heightPx) / 2f

    /**
     * @param widthPx the *track's* width; the bitmap is wider by [sidePadding] at
     *   each end.
     * @param percent null → bare track (the noData face) — never a fake 0%.
     * @param elapsedPercent null → no tick, no segment (no reset clock to derive it
     *   from, or a credits row, which has no clock by definition).
     * @param showOverPace the surface's "Show red past the pace mark" setting. Off
     *   keeps the tick and drops only the colour.
     */
    fun draw(
        widthPx: Float,
        heightPx: Float,
        percent: Double?,
        elapsedPercent: Double?,
        accent: Color,
        dark: Boolean,
        showOverPace: Boolean = true,
    ): Bitmap {
        val padX = sidePadding(heightPx)
        val over = BarGeometry.tickOverhang(heightPx)
        val w = (widthPx + 2f * padX).toInt().coerceAtLeast(1)
        val h = bitmapHeight(heightPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val left = padX
        val right = padX + widthPx
        val top = over
        val bottom = over + heightPx
        val radius = heightPx / 2f
        val fill = Palette.barColor(percent, accent, dark)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1 · track — the existing bar style: the fill colour at 25%, full width
        paint.color = fill.copy(alpha = 0.25f).toArgb()
        canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)

        var fillRight: Float? = null
        if (percent != null) {
            // 2 · fill — severity-ladder colour, min visual width = h (anti-sliver)
            val end = left + widthPx * BarGeometry.fillFraction(percent)
            if (end > left) {
                fillRight = end.coerceAtLeast(left + heightPx).coerceAtMost(right)
                paint.color = fill.toArgb()
                canvas.drawRoundRect(RectF(left, top, fillRight, bottom), radius, radius, paint)
            }
        }

        // 3 · red segment — the full red role, from the pace line to the fill's edge.
        // Clipped to the fill's own rounded rect: that is what makes the boundary
        // between the two colours a straight vertical edge instead of a second
        // capsule end, and lets the red cover the fill's rounded tip completely
        // (the CCRM-43 wireframe rev B rule). No minimum width — the segment starts
        // exactly on the mark, so a 3-point overshoot is a 3-point sliver.
        val segment = BarGeometry.redSegment(percent, elapsedPercent, showOverPace)
        if (segment != null && fillRight != null) {
            val segLeft = left + widthPx * segment.first
            if (fillRight > segLeft) {
                val clip = Path().apply {
                    addRoundRect(
                        RectF(left, top, fillRight, bottom), radius, radius, Path.Direction.CW,
                    )
                }
                canvas.save()
                canvas.clipPath(clip)
                paint.color = Palette.barColor(100.0, accent, dark).toArgb()
                canvas.drawRect(RectF(segLeft, top, fillRight, bottom), paint)
                canvas.restore()
            }
        }

        // 4 · tick — drawn last so it survives everything under it, and centred on
        // the pace line, so it straddles the boundary it marks
        if (BarGeometry.showTick(percent, elapsedPercent)) {
            val tickW = BarGeometry.tickWidth(heightPx)
            val cx = left + widthPx * BarGeometry.tickFraction(elapsedPercent!!)
            val fg = if (dark) Color(0xFFF2F2F4) else Color(0xFF1D1D1F)
            paint.color = fg.copy(alpha = if (dark) 0.60f else 0.48f).toArgb()
            canvas.drawRoundRect(
                RectF(cx - tickW / 2f, 0f, cx + tickW / 2f, h.toFloat()),
                tickW / 2f, tickW / 2f, paint,
            )
        }
        return bmp
    }
}
