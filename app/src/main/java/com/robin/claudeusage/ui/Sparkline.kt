package com.robin.claudeusage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/** Percent along the even-pace diagonal at [t] — 0% at the window start, 100% at reset. */
fun evenPacePercent(t: Long, windowStartMs: Long, windowEndMs: Long): Double {
    val span = (windowEndMs - windowStartMs).coerceAtLeast(1L)
    return ((t - windowStartMs).toDouble() / span * 100.0).coerceIn(0.0, 100.0)
}

/**
 * The plot's coordinate system, lifted out of the draw pass.
 *
 * It used to live entirely inside the `Canvas` lambda, which was fine while the chart
 * was only ever drawn. A touch has to run the mapping *backwards* — "which sample is
 * under x=340?" — and a gesture handler can't reach into a DrawScope. So both sides now
 * build one of these from the same inputs: the draw pass from its own `size`, the
 * gesture handler from the size reported by `onSizeChanged`. Same function of the same
 * arguments, so the two can't drift apart.
 *
 * Being free of DrawScope also makes [nearestSample] testable without a Compose UI test,
 * which is why this is public rather than internal — the unit-test source set isn't a
 * friend of the main one here, and the mapping is worth testing more than it's worth
 * hiding.
 */
class SparkGeometry(
    width: Float,
    height: Float,
    density: Density,
    private val windowStartMs: Long,
    private val windowEndMs: Long,
) {
    val plotRight: Float = width - with(density) { GUTTER.toPx() }
    val plotTop: Float = with(density) { HEADROOM.toPx() }
    val plotBottom: Float = height - with(density) { AXIS_HEIGHT.toPx() }

    /** False when the view is too small to plot into at all; callers draw nothing. */
    val usable: Boolean get() = plotRight > 0f && plotBottom > plotTop

    private val spanMs = (windowEndMs - windowStartMs).coerceAtLeast(1L)

    fun x(t: Long): Float =
        ((t - windowStartMs).toDouble() / spanMs).coerceIn(0.0, 1.0).toFloat() * plotRight

    fun y(pct: Double): Float =
        plotBottom - (pct / 100.0).coerceIn(0.0, 1.0).toFloat() * (plotBottom - plotTop)

    fun paceAt(t: Long): Double = evenPacePercent(t, windowStartMs, windowEndMs)

    /**
     * The sample plotted closest to [px] horizontally, or null if there are none.
     *
     * Snaps to a real fetch instead of interpolating along the line. The whole reason
     * this chart puts a dot on every sample is that polling gaps should stay visible —
     * reading a value out of the middle of a gap would report a percentage the app never
     * observed, which is the one thing the chart is built not to do.
     */
    fun nearestSample(px: Float, samples: List<Pair<Long, Double>>): Pair<Long, Double>? =
        samples.minByOrNull { abs(x(it.first) - px) }

    companion object {
        val GUTTER = 32.dp      // right: threshold labels
        val AXIS_HEIGHT = 15.dp // bottom: time labels
        val HEADROOM = 13.dp    // top: the value label above the newest dot
    }
}

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
 *
 * **Touch (CCRM-20).** Tap selects the nearest fetch and pins a callout on it; tapping
 * it again clears. Long-press then drag scrubs along the curve. The scrub is gated
 * behind the long press on purpose — a bare horizontal drag would fight the
 * `HorizontalPager` this chart sits inside for the pointer, and paging between profiles
 * matters more than a shortcut to scrubbing.
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
    val surface = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    // The alert ladder, taken from the same place the bars take it.
    val warn80 = Palette.barColor(85.0, color, dark)
    val warn90 = Palette.barColor(95.0, color, dark)
    val warn100 = Palette.barColor(100.0, color, dark)

    // Is the newest reading at or past the pace line? Drives the wash below.
    val lastSample = samples.last()
    val paceAtNow = evenPacePercent(lastSample.first, windowStartMs, windowEndMs)
    val atOrAbovePace = lastSample.second - paceAtNow > -PACE_DEAD_ZONE

    // Clock time for a window measured in hours; a date for one measured in days,
    // where the weekday repeats at both ends and reads as a duplicate label.
    val spanMs = windowEndMs - windowStartMs
    val compact = spanMs <= 12 * 60 * 60_000L
    fun stamp(ms: Long): String = Instant.ofEpochMilli(ms).let {
        if (compact) Fmt.timeOnly(it, use24h) else Fmt.dayMonth(it)
    }

    // --- selection ---
    // Held as the selected sample's *timestamp*, not its index: a poll appends a sample
    // every few minutes, and an index would quietly start pointing at a different point
    // underneath the user. An unknown timestamp resolves to null, so a selection also
    // disappears by itself when the window rolls over and the series is replaced.
    var selectedAt by remember { mutableStateOf<Long?>(null) }
    val selected = selectedAt?.let { at -> samples.firstOrNull { it.first == at } }

    // Set when the long press fires, cleared on the next touch down. Without it, a
    // long press released without moving would select a point and then have the tap
    // handler below toggle it straight back off — the finger never travelled, so that
    // release is indistinguishable from a tap by position alone. Ordering is
    // deterministic: down clears it, the long-press timeout sets it, the release reads
    // it.
    val longPressFired = remember { mutableStateOf(false) }

    var measured by remember { mutableStateOf(Size.Zero) }
    val hitTest: (Float) -> Pair<Long, Double>? = { px ->
        SparkGeometry(measured.width, measured.height, density, windowStartMs, windowEndMs)
            .takeIf { it.usable }
            ?.nearestSample(px, samples)
    }

    // A one-line spoken summary; the chart was previously invisible to a screen reader.
    val description = buildString {
        append("Usage chart. ")
        append("${lastSample.second.roundToInt()}% at ${stamp(lastSample.first)}, ")
        append(pacePhrase(lastSample.second - paceAtNow).lowercase())
        projectedEnd?.let { (t, pct) ->
            append(". Projected ${pct.roundToInt()}% by ${stamp(t)}")
        }
        append(". Tap a point for its value.")
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { measured = it.toSize() }
            .pointerInput(samples) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        longPressFired.value = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        hitTest(offset.x)?.let { selectedAt = it.first }
                    },
                    onDrag = { change, _ ->
                        hitTest(change.position.x)?.let { selectedAt = it.first }
                        change.consume()
                    },
                )
            }
            // Hand-rolled rather than `detectTapGestures`, which consumes the initial
            // down unconditionally. An ancestor sees pointer events only after its
            // descendants, so that one consume was enough to stop the HorizontalPager
            // ever starting — swiping across the chart just did nothing, while swiping
            // across the bar 20dp above it paged to the other profile. Gating the
            // scrub behind a long press was supposed to protect paging; a tap handler
            // that broke it anyway defeated the point.
            .pointerInput(samples) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    longPressFired.value = false
                    val up = waitForUpOrCancellation()
                    // Null means somebody else claimed the gesture — the pager, the
                    // vertical scroll, or this chart's own scrub above.
                    if (up != null &&
                        !longPressFired.value &&
                        (up.position - down.position).getDistance() <= viewConfiguration.touchSlop
                    ) {
                        val hit = hitTest(up.position.x)?.first
                        // Tapping the pinned point again is how you put the chart back
                        // the way you found it.
                        selectedAt = if (hit != null && hit == selectedAt) null else hit
                    }
                }
            }
            .semantics { contentDescription = description },
    ) {
        val geo = SparkGeometry(size.width, size.height, density, windowStartMs, windowEndMs)
        if (!geo.usable) return@Canvas
        val plotRight = geo.plotRight
        val plotTop = geo.plotTop
        val plotBottom = geo.plotBottom
        fun x(t: Long) = geo.x(t)
        fun y(pct: Double) = geo.y(pct)

        val stroke = 2.5.dp.toPx()
        val tiny = TextStyle(fontSize = 9.5.sp, color = muted)

        // A value label normally sits above its marker, but near the top of the plot
        // that crowds the 80/90/100% guides and their gutter labels — so up there it
        // flips underneath instead.
        val topThird = plotTop + (plotBottom - plotTop) / 3f
        fun labelY(markerY: Float, labelH: Int): Float =
            if (markerY < topThird) markerY + 5.dp.toPx()
            else (markerY - labelH - 5.dp.toPx()).coerceAtLeast(0f)

        // --- the selected point's callout, laid out first so the labels it would cover
        // --- can stand down before they're drawn
        val callout = selected?.let { (t, pct) ->
            val head = measurer.measure(
                if (compact) Fmt.timeOnly(Instant.ofEpochMilli(t), use24h)
                else Fmt.dayTime(Instant.ofEpochMilli(t), use24h),
                tiny,
            )
            val delta = pct - geo.paceAt(t)
            val body = measurer.measure(
                "${pct.roundToInt()}% · ${paceLabel(delta)}",
                TextStyle(
                    fontSize = 11.sp,
                    color = if (delta > PACE_DEAD_ZONE) warn90 else onSurface,
                    fontWeight = FontWeight.Bold,
                ),
            )
            val padH = 6.dp.toPx()
            val padV = 4.dp.toPx()
            val w = maxOf(head.size.width, body.size.width) + padH * 2
            val h = head.size.height + body.size.height + padV * 2
            val pointX = x(t)
            val gap = 8.dp.toPx()
            // Prefer the right of the crosshair; flip when that would run into the
            // threshold gutter, which is the one place the pill must not cover.
            val left =
                if (pointX + gap + w <= plotRight) pointX + gap
                else (pointX - gap - w).coerceAtLeast(0f)
            val top = (y(pct) - h / 2f).coerceIn(plotTop, (plotBottom - h).coerceAtLeast(plotTop))
            Callout(Rect(Offset(left, top), Size(w, h)), head, body, padH, padV, pointX, y(pct))
        }

        // --- the pace region ---
        val abovePace = Path().apply {
            moveTo(0f, y(0.0)); lineTo(plotRight, y(100.0)); lineTo(0f, y(100.0)); close()
        }
        val belowPace = Path().apply {
            moveTo(0f, y(0.0)); lineTo(plotRight, y(100.0)); lineTo(plotRight, y(0.0)); close()
        }
        // The wash only appears once usage has actually reached the line, so its
        // arrival is the signal. Drawn permanently it did the opposite: a window at
        // 0% — the safest state there is — came up two-thirds shaded red, and a
        // region that's always there can't warn about anything. It also goes first,
        // under the guides, so it can't dim them.
        if (atOrAbovePace) {
            drawPath(abovePace, warn100.copy(alpha = if (dark) 0.10f else 0.07f))
        }

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

        // --- the pace diagonal ---
        drawLine(
            color = warn80.copy(alpha = 0.9f),
            start = Offset(0f, y(0.0)),
            end = Offset(plotRight, y(100.0)),
            strokeWidth = 2.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
        )

        // --- observed: area (split at the diagonal), line, points ---
        val area = Path().apply {
            moveTo(x(samples.first().first), y(0.0))
            for ((t, pct) in samples) lineTo(x(t), y(pct))
            lineTo(x(samples.last().first), y(0.0))
            close()
        }
        // The overshoot is shaded in the warning colour so you can see exactly when
        // the curve crossed and by how much — a single colour for the whole curve
        // would flatten that into one verdict, and would also disagree with the
        // usage bar above the chart, which is coloured by absolute percentage.
        clipPath(belowPace) { drawPath(area, color.copy(alpha = if (dark) 0.20f else 0.18f)) }
        clipPath(abovePace) { drawPath(area, warn90.copy(alpha = if (dark) 0.34f else 0.30f)) }

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
        val nowLabelRect = Rect(
            Offset(
                (nowX - nowLabel.size.width - 4.dp.toPx()).coerceAtLeast(0f),
                labelY(y(nowPct), nowLabel.size.height),
            ),
            nowLabel.size.toSize(),
        )
        // The callout is the thing the user just asked for, so it outranks the standing
        // label — which says the same number anyway when the newest point is the one
        // selected.
        val nowLabelVisible = callout == null || !nowLabelRect.overlaps(callout.pill)
        if (nowLabelVisible) drawText(nowLabel, topLeft = nowLabelRect.topLeft)

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
            // Late in a window the projection endpoint sits close to the now marker
            // and the two value labels collide. The caption below spells this number
            // out ("At this pace: ~75% when the window resets"), so the marker keeps
            // its label and this one yields — as it now also does to the callout.
            val rect = Rect(
                Offset(
                    (endX - l.size.width).coerceIn(0f, plotRight - l.size.width),
                    labelY(endY, l.size.height),
                ),
                l.size.toSize(),
            )
            val clear = (!nowLabelVisible || !rect.overlaps(nowLabelRect)) &&
                (callout == null || !rect.overlaps(callout.pill))
            if (clear) drawText(l, topLeft = rect.topLeft)
        }

        // --- pace legend ---
        // Top-left is the one corner neither the curve nor the diagonal occupies,
        // since both start bottom-left. The exception is very heavy usage very
        // early, which is the only way the curve reaches up there — then it goes
        // bottom-right, which that same scenario leaves empty.
        val earlyAndHigh = samples.any { (t, pct) ->
            x(t) < plotRight * 0.45f && pct > 70.0
        }
        val swatchW = 14.dp.toPx()
        val legend = measurer.measure("even pace", tiny.copy(color = warn80.copy(alpha = 0.95f)))
        val legendW = swatchW + 4.dp.toPx() + legend.size.width
        val legendX = if (earlyAndHigh) plotRight - legendW else 0f
        val legendY = if (earlyAndHigh) plotBottom - legend.size.height - 2.dp.toPx() else plotTop
        drawLine(
            color = warn80.copy(alpha = 0.9f),
            start = Offset(legendX, legendY + legend.size.height / 2f),
            end = Offset(legendX + swatchW, legendY + legend.size.height / 2f),
            strokeWidth = 2.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
        )
        drawText(legend, topLeft = Offset(legendX + swatchW + 4.dp.toPx(), legendY))

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

        // --- the selection, last, so nothing can be drawn over what was asked for ---
        callout?.let { c ->
            // Heavier and in the series colour, so it doesn't read as a second "now".
            drawLine(
                color = color.copy(alpha = 0.55f),
                start = Offset(c.pointX, plotTop),
                end = Offset(c.pointX, plotBottom),
                strokeWidth = 1.5.dp.toPx(),
            )
            // A surface-coloured halo lifts the dot off the curve and the area fill.
            drawCircle(surface, radius = 6.5.dp.toPx(), center = Offset(c.pointX, c.pointY))
            drawCircle(color, radius = 4.5.dp.toPx(), center = Offset(c.pointX, c.pointY))

            drawRoundRect(
                color = surface.copy(alpha = 0.95f),
                topLeft = c.pill.topLeft,
                size = c.pill.size,
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
            drawRoundRect(
                color = color.copy(alpha = 0.55f),
                topLeft = c.pill.topLeft,
                size = c.pill.size,
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
            drawText(
                c.head,
                topLeft = Offset(c.pill.left + c.padH, c.pill.top + c.padV),
            )
            drawText(
                c.body,
                topLeft = Offset(
                    c.pill.left + c.padH,
                    c.pill.top + c.padV + c.head.size.height,
                ),
            )
        }
    }
}

/** Everything the selected-point callout needs, measured before anything is drawn. */
private class Callout(
    val pill: Rect,
    val head: TextLayoutResult,
    val body: TextLayoutResult,
    val padH: Float,
    val padV: Float,
    val pointX: Float,
    val pointY: Float,
)

/** The pace delta as a chart label — short, because it shares a line with the value. */
private fun paceLabel(delta: Double): String = when {
    delta > PACE_DEAD_ZONE -> "+${delta.roundToInt()} vs pace"
    delta < -PACE_DEAD_ZONE -> "${delta.roundToInt()} vs pace"
    else -> "on pace"
}

/** The same verdict in words, for the screen-reader summary. */
private fun pacePhrase(delta: Double): String = when {
    delta > PACE_DEAD_ZONE -> "${delta.roundToInt()} points above even pace"
    delta < -PACE_DEAD_ZONE -> "${(-delta).roundToInt()} points below even pace"
    else -> "On even pace"
}
