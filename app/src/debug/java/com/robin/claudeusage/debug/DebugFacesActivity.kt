package com.robin.claudeusage.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robin.claudeusage.ui.BarRenderer
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.ui.RingRenderer
import com.robin.claudeusage.widget.ChartBitmap

/**
 * Debug-only contact sheet: the real RingRenderer/BarRenderer/ChartBitmap pipeline
 * over the handover fixtures, light and dark — the widget preview harness. Launch:
 * `adb shell am start -n com.robin.claudeusage/.debug.DebugFacesActivity`.
 * Compare against design/widget-wireframes.html and the Mac renders.
 */
class DebugFacesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF888888))
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                for (dark in listOf(false, true)) {
                    Text(
                        if (dark) "DARK" else "LIGHT",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Sheet(dark)
                }
            }
        }
    }
}

// The handover §6 fixtures: percent to elapsed. Null percent = the noData ring.
private val RING_FIXTURES = listOf(
    "72% session, over" to (72.0 to 55.3),
    "41% weekly, under" to (41.0 to 54.8),
    "84% cap, over" to (84.0 to 54.8),
    "96% orange" to (96.0 to 80.7),
    "100% red" to (100.0 to 80.7),
    "noData" to (null to null),
)

/**
 * The bar states from the CCRM-43 (Bar Pace Marks) wireframe §2, in the order the
 * wireframe shows them. This is also the CCRM-15 (Above-Pace Verification) residual
 * for bars: the over-pace and dead-zone states are the ones that shipped unobserved
 * on the rings, and every one of them is now a thing you can look at on a device.
 */
private val BAR_FIXTURES = listOf<Triple<String, Double?, Double?>>(
    Triple("under pace 42/70", 42.0, 70.0),
    Triple("over pace 78/70", 78.0, 70.0),
    Triple("dead zone 72/70", 72.0, 70.0),
    Triple("boundary not over 73/70", 73.0, 70.0),
    Triple("boundary over 73.5/70", 73.5, 70.0),
    Triple("fill covers tick 96/40", 96.0, 40.0),
    Triple("at 100% 100/62", 100.0, 62.0),
    Triple("tick at start 4/2", 4.0, 2.0),
    Triple("tick at end 99/100", 99.0, 100.0),
    Triple("no percent", null, 70.0),
    Triple("no reset clock 55/-", 55.0, null),
    Triple("credits 61/-", 61.0, null),
)

@Composable
private fun Sheet(dark: Boolean) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val accent = if (dark) Color(0xFFE59980) else Color(0xFFD97757)
    val face = if (dark) Color(0xFF242428) else Color.White

    Column(Modifier.background(face).padding(12.dp)) {
        // Hero rings + mini-rings, drawn by the exact widget code path. The second
        // pass has the red toggled off, which is what the widgets setting does — the
        // tick must survive it, and the fill must keep its severity colour.
        for (showOverPace in listOf(true, false)) {
            for (ringDp in listOf(128f, 56f)) {
                Row {
                    for ((label, fx) in RING_FIXTURES) {
                        val (pct, elapsed) = fx
                        Column(Modifier.padding(4.dp)) {
                            Image(
                                bitmap = RingRenderer.draw(
                                    sizePx = (ringDp * density).toInt(),
                                    strokePx = (if (ringDp > 100f) 8f else 5.5f) * density,
                                    percent = pct,
                                    elapsedPercent = elapsed,
                                    accent = accent,
                                    dark = dark,
                                    showOverPace = showOverPace,
                                ).asImageBitmap(),
                                contentDescription = label,
                                modifier = Modifier.size(ringDp.dp),
                            )
                            if (ringDp > 100f) Text(
                                if (showOverPace) label else "$label · red off",
                                fontSize = 8.sp,
                                color = if (dark) Color.White else Color.Black,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // The bars, through the real BarRenderer: every wireframe §2 state at both
        // widget heights, then the same over-pace states with the red switched off.
        for (barDp in listOf(12f, 14f)) {
            for ((label, pct, elapsed) in BAR_FIXTURES) {
                Text(
                    "${barDp.toInt()}dp · $label",
                    fontSize = 8.sp,
                    color = if (dark) Color.White else Color.Black,
                )
                Image(
                    bitmap = BarRenderer.draw(
                        widthPx = 300 * density,
                        heightPx = barDp * density,
                        percent = pct,
                        elapsedPercent = elapsed,
                        accent = accent,
                        dark = dark,
                    ).asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.width(300.dp).height((barDp * 1.6f).dp),
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        for ((label, pct, elapsed) in BAR_FIXTURES.filter { it.second != null && it.third != null }) {
            Text(
                "12dp · $label · red off",
                fontSize = 8.sp,
                color = if (dark) Color.White else Color.Black,
            )
            Image(
                bitmap = BarRenderer.draw(
                    widthPx = 300 * density,
                    heightPx = 12f * density,
                    percent = pct,
                    elapsedPercent = elapsed,
                    accent = accent,
                    dark = dark,
                    showOverPace = false,
                ).asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.width(300.dp).height((12f * 1.6f).dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        // The narrow case: at 150 dp the smallest segment the dead zone allows is a
        // few dp wide, and it is drawn true to scale rather than padded out.
        for ((label, pct, elapsed) in BAR_FIXTURES.filter { it.second != null && it.third != null }) {
            Text(
                "150dp wide · $label",
                fontSize = 8.sp,
                color = if (dark) Color.White else Color.Black,
            )
            Image(
                bitmap = BarRenderer.draw(
                    widthPx = 150 * density,
                    heightPx = 12f * density,
                    percent = pct,
                    elapsedPercent = elapsed,
                    accent = accent,
                    dark = dark,
                ).asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.width(150.dp).height((12f * 1.6f).dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        // The chart, over- and under-pace plus refused and empty.
        val now = System.currentTimeMillis()
        val hour = 60 * 60_000L
        val charts = listOf(
            "5h over" to Triple(
                (0..10).map { now - 166 * 60_000L + it * 16 * 60_000L to it * 7.2 },
                now - 166 * 60_000L to now + 134 * 60_000L,
                (now + 91 * 60_000L) to 100.0,
            ),
            "7d under" to Triple(
                (0..8).map { now - 92 * hour + it * 11 * hour to it * 5.1 },
                now - 92 * hour to now + 76 * hour,
                (now + 76 * hour) to 78.0,
            ),
            "refused" to Triple(
                listOf(now - 9 * 60_000L to 2.0, now - 2 * 60_000L to 3.0),
                now - 9 * 60_000L to now + 291 * 60_000L,
                null,
            ),
            "empty" to Triple(emptyList(), now - hour to now + 4 * hour, null),
        )
        for ((label, c) in charts) {
            val (samples, window, proj) = c
            val pct = samples.lastOrNull()?.second
            Text(label, fontSize = 9.sp, color = if (dark) Color.White else Color.Black)
            Image(
                bitmap = ChartBitmap.draw(
                    context = context,
                    widthPx = (300 * density).toInt(),
                    heightPx = (150 * density).toInt(),
                    samples = samples,
                    windowStartMs = window.first,
                    windowEndMs = window.second,
                    projectedEnd = proj,
                    color = Palette.barColor(pct, accent, dark),
                    accent = accent,
                    dark = dark,
                    use24h = false,
                ).asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.width(300.dp).height(150.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}
