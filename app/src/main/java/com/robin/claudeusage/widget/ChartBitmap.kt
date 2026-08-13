package com.robin.claudeusage.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.PACE_DEAD_ZONE
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.ui.SparkGeometry
import com.robin.claudeusage.ui.evenPacePercent
import java.time.Instant

/**
 * The pace chart drawn to a bitmap for the large widget face (CCRM-41 (Pace
 * Widget)), since Glance has no Canvas.
 *
 * Deliberately NOT a pixel-level extraction of `UsageSparkline` — that surface
 * is Compose-bound (TextMeasurer, gestures, callout). What must not drift is
 * shared by construction instead: [SparkGeometry] (coordinates), [evenPacePercent]
 * + [PACE_DEAD_ZONE] (the wash gate, the exact expression from Sparkline's
 * `atOrAbovePace`), [Palette.barColor] (the ladder) and [Fmt] (stamps). This is
 * the CCRM-13 (Chart Widget) extraction decision, recorded in ROADMAP.md.
 *
 * No selection, no legend — the widget face's banner sentence carries the
 * verdict in words.
 */
object ChartBitmap {

    /** RemoteViews transports the bitmap; keep it comfortably bounded. */
    private const val MAX_W = 1000
    private const val MAX_H = 600

    fun draw(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        samples: List<Pair<Long, Double>>, // (epochMillis, percent), ascending
        windowStartMs: Long,
        windowEndMs: Long,
        projectedEnd: Pair<Long, Double>?,
        color: Color,
        accent: Color,
        dark: Boolean,
        use24h: Boolean,
    ): Bitmap {
        val w = widthPx.coerceIn(1, MAX_W)
        val h = heightPx.coerceIn(1, MAX_H)
        val density = context.resources.displayMetrics.density
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val geo = SparkGeometry(w.toFloat(), h.toFloat(), Density(density), windowStartMs, windowEndMs)
        if (!geo.usable) return bmp

        val fg = if (dark) Color(0xFFF2F2F4) else Color(0xFF1D1D1F)
        val muted = fg.copy(alpha = 0.55f)
        val warn80 = Palette.barColor(85.0, accent, dark)
        val warn90 = Palette.barColor(95.0, accent, dark)
        val warn100 = Palette.barColor(100.0, accent, dark)
        fun dp(v: Float) = v * density

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(9.5f) }

        // --- the warning wash, only once at-or-above pace (dead-zoned) ---
        val last = samples.lastOrNull()
        val atOrAbovePace = last != null &&
            last.second - evenPacePercent(last.first, windowStartMs, windowEndMs) > -PACE_DEAD_ZONE
        if (atOrAbovePace) {
            val wash = Path().apply {
                moveTo(0f, geo.y(0.0))
                lineTo(geo.plotRight, geo.y(100.0))
                lineTo(0f, geo.y(100.0))
                close()
            }
            fill.color = warn100.copy(alpha = if (dark) 0.10f else 0.07f).toArgb()
            canvas.drawPath(wash, fill)
        }

        // --- threshold guides, ladder colours, labels in the right gutter ---
        for ((pct, c) in listOf(100.0 to warn100, 90.0 to warn90, 80.0 to warn80)) {
            line.color = c.copy(alpha = 0.32f).toArgb()
            line.strokeWidth = dp(1f)
            line.pathEffect = DashPathEffect(floatArrayOf(5f, 7f), 0f)
            canvas.drawLine(0f, geo.y(pct), geo.plotRight, geo.y(pct), line)
            text.color = c.copy(alpha = 0.85f).toArgb()
            canvas.drawText("${pct.toInt()}%", geo.plotRight + dp(3f), geo.y(pct) + text.textSize / 3f, text)
        }

        // --- the even-pace diagonal ---
        line.color = warn80.copy(alpha = 0.9f).toArgb()
        line.strokeWidth = dp(2.5f)
        line.pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        canvas.drawLine(0f, geo.y(0.0), geo.plotRight, geo.y(100.0), line)
        line.pathEffect = null

        // --- x axis ---
        line.color = muted.copy(alpha = 0.18f).toArgb()
        line.strokeWidth = dp(1f)
        canvas.drawLine(0f, geo.plotBottom, geo.plotRight, geo.plotBottom, line)
        val compact = windowEndMs - windowStartMs <= 12 * 60 * 60_000L
        fun stamp(ms: Long) = Instant.ofEpochMilli(ms).let {
            if (compact) Fmt.timeOnly(it, use24h) else Fmt.dayMonth(it)
        }
        text.color = muted.toArgb()
        val axisY = geo.plotBottom + text.textSize + dp(2f)
        val startLabel = stamp(windowStartMs)
        val endLabel = stamp(windowEndMs)
        canvas.drawText(startLabel, 0f, axisY, text)
        canvas.drawText(endLabel, geo.plotRight - text.measureText(endLabel), axisY, text)

        if (samples.size < 2) {
            // The face's banner explains why; the plot itself stays honest-empty.
            text.color = muted.toArgb()
            text.textSize = dp(11f)
            val msg = "No data yet"
            canvas.drawText(
                msg,
                (geo.plotRight - text.measureText(msg)) / 2f,
                (geo.plotTop + geo.plotBottom) / 2f,
                text,
            )
            return bmp
        }

        // --- observed: line + one dot per real sample (polling gaps must show) ---
        line.color = color.toArgb()
        line.strokeWidth = dp(2.5f)
        line.strokeCap = Paint.Cap.ROUND
        val path = Path()
        samples.forEachIndexed { i, (t, pct) ->
            if (i == 0) path.moveTo(geo.x(t), geo.y(pct)) else path.lineTo(geo.x(t), geo.y(pct))
        }
        canvas.drawPath(path, line)
        fill.color = color.toArgb()
        for ((t, pct) in samples.dropLast(1)) {
            canvas.drawCircle(geo.x(t), geo.y(pct), dp(2.6f), fill)
        }

        // --- now: hairline + bigger marker + label ---
        val (nowT, nowPct) = samples.last()
        val nowX = geo.x(nowT)
        line.color = muted.copy(alpha = 0.28f).toArgb()
        line.strokeWidth = dp(1f)
        canvas.drawLine(nowX, geo.plotTop, nowX, geo.plotBottom, line)
        canvas.drawCircle(nowX, geo.y(nowPct), dp(4.5f), fill)
        text.color = fg.toArgb()
        text.textSize = dp(11f)
        text.isFakeBoldText = true
        val nowLabel = "${nowPct.toInt()}%"
        canvas.drawText(
            nowLabel,
            (nowX - text.measureText(nowLabel) - dp(4f)).coerceAtLeast(0f),
            (geo.y(nowPct) - dp(5f)).coerceAtLeast(text.textSize),
            text,
        )

        // --- projection: dashed tail to a hollow, labelled endpoint ---
        projectedEnd?.let { (t, pct) ->
            if (t <= nowT) return@let
            val ex = geo.x(t)
            val ey = geo.y(pct)
            line.color = color.copy(alpha = 0.65f).toArgb()
            line.strokeWidth = dp(2.5f)
            line.pathEffect = DashPathEffect(floatArrayOf(7f, 8f), 0f)
            canvas.drawLine(nowX, geo.y(nowPct), ex, ey, line)
            line.pathEffect = null
            line.color = color.copy(alpha = 0.8f).toArgb()
            line.strokeWidth = dp(1.8f)
            canvas.drawCircle(ex, ey, dp(4f), line)
            text.color = muted.toArgb()
            val l = "~${pct.toInt()}%"
            canvas.drawText(
                l,
                (ex - text.measureText(l)).coerceIn(0f, geo.plotRight - text.measureText(l)),
                (ey - dp(6f)).coerceAtLeast(text.textSize),
                text,
            )
        }
        return bmp
    }
}
