package com.robin.claudeusage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant

/**
 * Usage-over-time curve for one window instance. x spans the window's full
 * lifetime (start → reset), y is a fixed 0–100% so slopes are comparable between
 * windows and never flattered by autoscaling.
 *
 * What's drawn, and why each earns its space:
 *  - dashed guides at 80/90/100% in the alert colours, so the y axis is readable
 *    and the chart lines up with when notifications actually fire;
 *  - an "even pace" diagonal from (start, 0%) to (reset, 100%) — above it means
 *    usage is outrunning the window;
 *  - the observed fetches as a filled area + line with a dot on every real
 *    sample, so gaps in polling are visible rather than smoothed away;
 *  - a marker on the latest sample, labelled, so the present is locatable;
 *  - the burn-rate extrapolation as a dashed tail to a hollow, labelled endpoint.
 *
 * One series, so there's no legend: the guides carry their own labels and only
 * the two points worth reading — now and the projection — get value labels.
 */
@Composable
fun UsageSparkline(
    samples: List<Pair<Long, Double>>, // (epochMillis, percent), ascending
    windowStartMs: Long,
    windowEndMs: Long,
    projectedEnd: Pair<Long, Double>?,
    color: Color,
    use24h: Boolean,
    modifier: Modifier = Modifier,
) {
    if (samples.size < 2 || windowEndMs <= windowStartMs) return

    val dark = isSystemInDarkTheme()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()

    // The alert ladder, taken from the same place the bars take it.
    val warn80 = Palette.barColor(85.0, color, dark)
    val warn90 = Palette.barColor(95.0, color, dark)
    val warn100 = Palette.barColor(100.0, color, dark)

    // Clock time for a window measured in hours; a date for one measured in days,
    // where the weekday repeats at both ends and reads as a duplicate label.
    val spanMs = windowEndMs - windowStartMs
    val compact = spanMs <= 12 * 60 * 60_000L
    fun stamp(ms: Long): String = Instant.ofEpochMilli(ms).let {
        if (compact) Fmt.timeOnly(it, use24h) else Fmt.dayMonth(it)
    }

    Canvas(modifier = modifier) {
        val stroke = 2.5.dp.toPx()
        val gutter = 32.dp.toPx()   // right: threshold labels
        val axisH = 15.dp.toPx()    // bottom: time labels
        val headroom = 13.dp.toPx() // top: the value label above the newest dot

        val plotRight = size.width - gutter
        val plotTop = headroom
        val plotBottom = size.height - axisH
        if (plotRight <= 0f || plotBottom <= plotTop) return@Canvas

        fun x(t: Long) = ((t - windowStartMs).toDouble() / spanMs).coerceIn(0.0, 1.0)
            .toFloat() * plotRight
        fun y(pct: Double) = plotBottom -
            (pct / 100.0).coerceIn(0.0, 1.0).toFloat() * (plotBottom - plotTop)

        val tiny = TextStyle(fontSize = 9.5.sp, color = muted)

        // --- threshold guides ---
        for ((pct, c) in listOf(100.0 to warn100, 90.0 to warn90, 80.0 to warn80)) {
            drawLine(
                color = c.copy(alpha = 0.32f),
                start = Offset(0f, y(pct)),
                end = Offset(plotRight, y(pct)),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 7f)),
            )
            val l = measurer.measure("${pct.toInt()}%", tiny.copy(color = c.copy(alpha = 0.85f)))
            drawText(l, topLeft = Offset(plotRight + 3.dp.toPx(), y(pct) - l.size.height / 2f))
        }

        // --- even-pace diagonal ---
        drawLine(
            color = muted.copy(alpha = 0.35f),
            start = Offset(0f, y(0.0)),
            end = Offset(plotRight, y(100.0)),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 6f)),
        )
        val paceLabel = measurer.measure("even pace", tiny)
        drawText(
            paceLabel,
            topLeft = Offset(
                (plotRight * 0.52f).coerceAtMost(plotRight - paceLabel.size.width),
                y(52.0) - paceLabel.size.height - 2.dp.toPx(),
            ),
        )

        // --- observed: area, line, points ---
        val area = Path().apply {
            moveTo(x(samples.first().first), y(0.0))
            for ((t, pct) in samples) lineTo(x(t), y(pct))
            lineTo(x(samples.last().first), y(0.0))
            close()
        }
        drawPath(area, color.copy(alpha = 0.14f))

        val line = Path()
        samples.forEachIndexed { i, (t, pct) ->
            if (i == 0) line.moveTo(x(t), y(pct)) else line.lineTo(x(t), y(pct))
        }
        drawPath(line, color, style = Stroke(width = stroke, cap = StrokeCap.Round))

        // Every fetch except the newest, which gets its own bigger marker below.
        for ((t, pct) in samples.dropLast(1)) {
            drawCircle(color, radius = 2.6.dp.toPx(), center = Offset(x(t), y(pct)))
        }

        // --- now marker ---
        val (nowT, nowPct) = samples.last()
        val nowX = x(nowT)
        drawLine(
            color = muted.copy(alpha = 0.28f),
            start = Offset(nowX, plotTop),
            end = Offset(nowX, plotBottom),
            strokeWidth = 1.dp.toPx(),
        )
        drawCircle(color, radius = 4.5.dp.toPx(), center = Offset(nowX, y(nowPct)))
        val nowLabel = measurer.measure(
            "${nowPct.toInt()}%",
            TextStyle(fontSize = 11.sp, color = onSurface, fontWeight = FontWeight.Bold),
        )
        drawText(
            nowLabel,
            topLeft = Offset(
                (nowX - nowLabel.size.width - 4.dp.toPx()).coerceAtLeast(0f),
                (y(nowPct) - nowLabel.size.height - 5.dp.toPx()).coerceAtLeast(0f),
            ),
        )

        // --- projection tail ---
        projectedEnd?.let { (t, pct) ->
            if (t <= nowT) return@let
            val endX = x(t)
            val endY = y(pct)
            drawLine(
                color = color.copy(alpha = 0.65f),
                start = Offset(nowX, y(nowPct)),
                end = Offset(endX, endY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 8f)),
            )
            drawCircle(
                color = color.copy(alpha = 0.8f),
                radius = 4.dp.toPx(),
                center = Offset(endX, endY),
                style = Stroke(width = 1.8.dp.toPx()),
            )
            val l = measurer.measure(
                "~${pct.toInt()}%",
                TextStyle(fontSize = 11.sp, color = muted, fontWeight = FontWeight.Bold),
            )
            drawText(
                l,
                topLeft = Offset(
                    (endX - l.size.width).coerceIn(0f, plotRight - l.size.width),
                    (endY - l.size.height - 5.dp.toPx()).coerceAtLeast(0f),
                ),
            )
        }

        // --- x axis ---
        drawLine(
            color = muted.copy(alpha = 0.18f),
            start = Offset(0f, plotBottom),
            end = Offset(plotRight, plotBottom),
            strokeWidth = 1.dp.toPx(),
        )
        val axisY = plotBottom + 2.dp.toPx()
        val startLabel = measurer.measure(stamp(windowStartMs), tiny)
        val endLabel = measurer.measure(stamp(windowEndMs), tiny)
        drawText(startLabel, topLeft = Offset(0f, axisY))
        drawText(endLabel, topLeft = Offset(plotRight - endLabel.size.width, axisY))

        // "now" only when it won't collide with either end label.
        val nowText = measurer.measure("now", tiny)
        val nowLeft = nowX - nowText.size.width / 2f
        val clearOfStart = nowLeft > startLabel.size.width + 6.dp.toPx()
        val clearOfEnd = nowLeft + nowText.size.width < plotRight - endLabel.size.width - 6.dp.toPx()
        if (clearOfStart && clearOfEnd) drawText(nowText, topLeft = Offset(nowLeft, axisY))
    }
}
