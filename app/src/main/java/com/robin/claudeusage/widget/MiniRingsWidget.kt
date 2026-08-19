package com.robin.claudeusage.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.robin.claudeusage.MainActivity
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Snapshot
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.WidgetPrefs
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.elapsedPercent
import com.robin.claudeusage.work.Polling

class MiniRingsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MiniRingsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Polling.scheduleWidgetRedrawTick(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = WidgetPrefs(context)
        for (id in appWidgetIds) prefs.remove(id)
    }
}

/**
 * CCRM-40 (Mini-Rings Widget): every window of one profile as battery-style
 * mini-rings — session, weekly, then model caps, **capped at three columns**.
 * Each ring carries **its own** pace tick (each window has its own clock), which
 * is how 41% can read calm next to 84% reading hot in the same glance. Ignores
 * the configured window; binds the configured profile. Mac provenance:
 * CCRM-18 [Desktop] medium face + CCM-49 [Desktop].
 *
 * The ring's size comes from [miniRingsLayout], not a constant: a fixed 56 dp
 * left two windows marooned in an empty card and overflowed the face at its own
 * declared minimum — CCBG-10 (Mini-Rings Emptiness).
 */
class MiniRingsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val profile = WidgetPrefs(context).profileFor(appWidgetId)
        val cache = UsageCache(context)
        val snapshot = cache.snapshot(profile)
        val use24h = cache.use24hTime()
        val usageLeft = cache.usageLeft()
        val themeName = cache.themeColorName()
        // Three, not four (CCBG-10 (Mini-Rings Emptiness)): fewer columns is what
        // lets each ring be big enough to read as a gauge at a glance.
        val rows = snapshot.data?.let { windowRows(it, max = 3) } ?: emptyList()
        rows.mapNotNull { it.window.resetsAt?.toEpochMilli() }.minOrNull()
            ?.let { Polling.armResetRedraw(context, it) }
        // Widgets read UsageCache directly; there is no composition to hoist into here.
        val showOverPace = cache.paceOverOnWidgets()
        provideContent {
            GlanceTheme {
                MiniRingsFace(
                    profile, cache.profileLabel(profile), snapshot, rows, use24h, usageLeft,
                    themeName, showOverPace,
                )
            }
        }
    }
}

@Composable
private fun MiniRingsFace(
    profile: Profile,
    profileLabel: String,
    snapshot: Snapshot,
    rows: List<WindowRow>,
    @Suppress("UNUSED_PARAMETER") use24h: Boolean, // no prose reset line on this face
    usageLeft: Boolean,
    themeName: String,
    showOverPace: Boolean = true,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val state = faceState(snapshot, hasData = rows.isNotEmpty())
    val accent = widgetThemeColor(themeName)
    val dark = widgetIsDark()

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(widgetCornerRadius())
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                profileLabel.uppercase(),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.padding(end = 8.dp).defaultWeight(),
            )
            val pill = pillText(state, snapshot)
            if (pill != null) FacePill(pill)
            else Text(
                "Updated ${Fmt.ago(snapshot.fetchedAt)}",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(8.dp))
            RefreshGlyph(profile, sizeDp = 16.dp)
        }
        Spacer(GlanceModifier.height(6.dp))
        when (state) {
            FaceState.NOT_SIGNED_IN -> Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { NotSignedInFace() }
            FaceState.NO_DATA -> Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No data yet",
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                    )
                    Text(
                        "Waiting for first fetch",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
            else -> {
                // The ring takes the room the face has instead of a hardcoded 56 dp,
                // and the columns stop dividing the full width, so one or two rings
                // centre as a group rather than sitting at the quarter points
                // (CCBG-10 (Mini-Rings Emptiness)). On a short face the text stack
                // gives way before the ring does.
                val l = miniRingsLayout(size.width.value, size.height.value, rows.size)
                Row(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    for (row in rows) {
                        Column(
                            modifier = GlanceModifier.width(l.columnDp.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val percent = row.window.percent
                            val elapsed = elapsedPercent(row.window, row.windowLengthMs)
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    provider = ImageProvider(
                                        ringBitmap(
                                            context, l.ringDp, l.strokeDp,
                                            percent, elapsed, accent, dark, showOverPace,
                                        )
                                    ),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(l.ringDp.dp),
                                )
                                Text(
                                    // CCRM-22 rev B: the bore flips too; no spare line
                                    // here, so the bare number carries it.
                                    if (percent == null) "—"
                                    else "${Fmt.usageInt(percent, usageLeft)}%",
                                    style = TextStyle(
                                        color = if (percent == null) GlanceTheme.colors.onSurfaceVariant
                                        else GlanceTheme.colors.onSurface,
                                        fontSize = l.percentSp.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                )
                            }
                            if (l.showTitle) {
                                Spacer(GlanceModifier.height(3.dp))
                                Text(
                                    row.title,
                                    style = TextStyle(
                                        color = GlanceTheme.colors.onSurface,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    maxLines = 1,
                                )
                            }
                            if (l.showCountdown) {
                                Text(
                                    if (percent == null) "no data" else widgetCountdown(row.window.resetsAt),
                                    style = TextStyle(
                                        color = GlanceTheme.colors.onSurfaceVariant,
                                        fontSize = 9.sp,
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
