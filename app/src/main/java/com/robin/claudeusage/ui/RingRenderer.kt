package com.robin.claudeusage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a pace-marked ring gauge to a bitmap: track · fill · red over-pace
 * segment · even-pace tick, per [RingGeometry] (CCM-49 [Desktop] rules).
 *
 * Text is deliberately NOT baked in — the Glance faces overlay real `Text`, so
 * the numbers follow the system font scale and the "a 100% ring never strikes
 * through its own numbers" rule is plain layout padding.
 *
 * This is the shared ring surface: `PinnedNotification.drawGauge` and
 * `UsageIcon`'s ring style should migrate onto it when next touched (the
 * CCRM-3 (Unified Theming) phase-3 extraction, done here for rings).
 */
object RingRenderer {

    fun draw(
        sizePx: Int,
        strokePx: Float,
        /** Null → bare track (the noData face) — never a fake 0%. */
        percent: Double?,
        /** Null → no tick, no segment (no reset clock to derive it from). */
        elapsedPercent: Double?,
        accent: Color,
        dark: Boolean,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fg = if (dark) Color(0xFFF2F2F4) else Color(0xFF1D1D1F)

        val overhang = RingGeometry.tickOverhang(strokePx)
        // Inset so the tick's overhang (and the round caps) never clip at the edge.
        val inset = strokePx / 2f + overhang
        val oval = RectF(inset, inset, sizePx - inset, sizePx - inset)
        val c = sizePx / 2f
        val r = c - inset

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }

        // 1 · track — foreground at 10% light / 14% dark, always the full circle
        stroke.color = fg.copy(alpha = if (dark) 0.14f else 0.10f).toArgb()
        stroke.strokeCap = Paint.Cap.BUTT
        canvas.drawArc(oval, 0f, 360f, false, stroke)

        if (percent != null) {
            // 2 · fill — severity-ladder colour, round caps, 12 o'clock clockwise
            val sweep = RingGeometry.fillSweep(percent)
            stroke.color = Palette.barColor(percent, accent, dark).toArgb()
            stroke.strokeCap = Paint.Cap.ROUND
            if (sweep >= 360f) canvas.drawArc(oval, 0f, 360f, false, stroke)
            else if (sweep > 0f) canvas.drawArc(oval, RingGeometry.START_ANGLE, sweep, false, stroke)

            // 3 · red segment — the full red role, only when meaningfully over pace.
            // At 100% it is invisible against the fill, which is fine.
            RingGeometry.redSegment(percent, elapsedPercent)?.let { (start, len) ->
                stroke.color = Palette.barColor(100.0, accent, dark).toArgb()
                canvas.drawArc(oval, RingGeometry.START_ANGLE + start, len, false, stroke)
            }
        }

        // 4 · tick — drawn last so it survives everything under it
        if (RingGeometry.showTick(percent, elapsedPercent)) {
            val rad = Math.toRadians(
                (RingGeometry.START_ANGLE + RingGeometry.tickSweep(elapsedPercent!!)).toDouble()
            )
            val rIn = r - strokePx / 2f - overhang
            val rOut = r + strokePx / 2f + overhang
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = RingGeometry.tickWidth(strokePx)
                strokeCap = Paint.Cap.ROUND
                color = fg.copy(alpha = if (dark) 0.60f else 0.48f).toArgb()
            }
            canvas.drawLine(
                c + rIn * cos(rad).toFloat(), c + rIn * sin(rad).toFloat(),
                c + rOut * cos(rad).toFloat(), c + rOut * sin(rad).toFloat(),
                tick,
            )
        }
        return bmp
    }
}
