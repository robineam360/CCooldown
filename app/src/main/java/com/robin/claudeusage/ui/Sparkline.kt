package com.robin.claudeusage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Usage-over-time curve for one window instance: x spans the window's full
 * lifetime (start → reset), y spans 0–100%. The observed fetches draw solid;
 * [projectedEnd] (the burn-rate extrapolation) draws as a dashed tail.
 */
@Composable
fun UsageSparkline(
    samples: List<Pair<Long, Double>>, // (epochMillis, percent), ascending
    windowStartMs: Long,
    windowEndMs: Long,
    projectedEnd: Pair<Long, Double>?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (samples.size < 2 || windowEndMs <= windowStartMs) return
    val guideColor = color.copy(alpha = 0.25f)

    Canvas(modifier = modifier) {
        val stroke = 2.dp.toPx()
        val spanMs = (windowEndMs - windowStartMs).toDouble()
        fun x(t: Long) = ((t - windowStartMs) / spanMs).coerceIn(0.0, 1.0).toFloat() * size.width
        fun y(pct: Double) = (1.0 - (pct / 100.0).coerceIn(0.0, 1.0)).toFloat() *
            (size.height - stroke) + stroke / 2

        // 100% ceiling guide.
        drawLine(
            color = guideColor,
            start = Offset(0f, y(100.0)),
            end = Offset(size.width, y(100.0)),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
        )

        val path = Path()
        samples.forEachIndexed { i, (t, pct) ->
            if (i == 0) path.moveTo(x(t), y(pct)) else path.lineTo(x(t), y(pct))
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))

        projectedEnd?.let { (t, pct) ->
            val (lastT, lastPct) = samples.last()
            if (t > lastT) {
                drawLine(
                    color = color.copy(alpha = 0.6f),
                    start = Offset(x(lastT), y(lastPct)),
                    end = Offset(x(t), y(pct)),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f)),
                )
            }
        }
    }
}
