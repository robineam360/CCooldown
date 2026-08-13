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
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.ui.RingRenderer
import com.robin.claudeusage.widget.ChartBitmap

/**
 * Debug-only contact sheet: the real RingRenderer/ChartBitmap pipeline over the
 * handover §6 fixtures, light and dark — the widget preview harness. Launch:
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

@Composable
private fun Sheet(dark: Boolean) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val accent = if (dark) Color(0xFFE59980) else Color(0xFFD97757)
    val face = if (dark) Color(0xFF242428) else Color.White

    Column(Modifier.background(face).padding(12.dp)) {
        // Hero rings + mini-rings, drawn by the exact widget code path.
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
                            ).asImageBitmap(),
                            contentDescription = label,
                            modifier = Modifier.size(ringDp.dp),
                        )
                        if (ringDp > 100f) Text(
                            label,
                            fontSize = 8.sp,
                            color = if (dark) Color.White else Color.Black,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
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
