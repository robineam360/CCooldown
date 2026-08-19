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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.robin.claudeusage.MainActivity
import com.robin.claudeusage.data.AuthState
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.Snapshot
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.data.WidgetPrefs
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.elapsedPercent
import com.robin.claudeusage.work.Polling

class RingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RingWidget()

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
 * CCRM-39 (Ring Widget): one window of one profile as a pace-marked ring gauge.
 * The percentage reads inside the ring and everything else sits outside it
 * ([ringFaceLayout]); no window title on this face (the config flow names it).
 * Mac provenance: CCRM-18 [Desktop] small face + CCM-49 [Desktop] pace marks.
 */
class RingWidget : GlanceAppWidget() {

    // Exact, not Responsive: the ring is a bitmap sized to the real face.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val prefs = WidgetPrefs(context)
        val profile = prefs.profileFor(appWidgetId)
        val windowKey = prefs.windowFor(appWidgetId)
        val cache = UsageCache(context)
        val snapshot = cache.snapshot(profile)
        val use24h = cache.use24hTime()
        val usageLeft = cache.usageLeft()
        val themeName = cache.themeColorName()
        // Single-account users don't need to be told which account.
        val multiProfile = Profile.entries.count {
            cache.snapshot(it).authState != AuthState.NO_CREDENTIALS
        } > 1
        val window = snapshot.data?.let { if (windowKey == "weekly") it.weekly else it.session }
        val windowLengthMs =
            if (windowKey == "weekly") Projection.WEEKLY_MS else Projection.SESSION_MS
        window?.resetsAt?.let { Polling.armResetRedraw(context, it.toEpochMilli()) }
        // Widgets read UsageCache directly; there is no composition to hoist into here.
        val showOverPace = cache.paceOverOnWidgets()
        provideContent {
            GlanceTheme {
                RingFace(
                    profile = profile,
                    profileLabel = if (multiProfile) cache.profileLabel(profile) else null,
                    snapshot = snapshot,
                    window = window,
                    windowLengthMs = windowLengthMs,
                    use24h = use24h,
                    usageLeft = usageLeft,
                    themeName = themeName,
                    showOverPace = showOverPace,
                )
            }
        }
    }
}

@Composable
private fun RingFace(
    profile: Profile,
    profileLabel: String?,
    snapshot: Snapshot,
    window: UsageWindow?,
    windowLengthMs: Long,
    use24h: Boolean,
    usageLeft: Boolean,
    themeName: String,
    showOverPace: Boolean = true,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val state = faceState(snapshot, hasData = window != null)
    val accent = widgetThemeColor(themeName)
    val dark = widgetIsDark()

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(widgetCornerRadius())
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        if (state == FaceState.NOT_SIGNED_IN) {
            NotSignedInFace()
        } else {
            // CCBG-11 (Ring Face Clutter): the bore holds the percentage and nothing
            // else. Profile, countdown and exact reset live *outside* the ring, which
            // is what frees the ring to reach the edge of the face and still be
            // legible at the declared 110×110 — where the old four-line stack didn't
            // merely crowd the bore, it overflowed it.
            val l = ringFaceLayout(size.width.value, size.height.value)
            val percent = window?.percent
            val elapsed = window?.let { elapsedPercent(it, windowLengthMs) }
            val gauge: @Composable () -> Unit = {
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
                        // Used truncates, Left floors — neither overstates (CCRM-22,
                        // rev B: the bore flips too; the stroke still draws the
                        // spend). An em dash for no data, never a fake 0%.
                        if (percent == null) "—" else "${Fmt.usageInt(percent, usageLeft)}%",
                        style = TextStyle(
                            color = if (percent == null) GlanceTheme.colors.onSurfaceVariant
                            else GlanceTheme.colors.onSurface,
                            fontSize = l.percentSp.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
            val nameLine: @Composable () -> Unit = {
                if (profileLabel != null) {
                    Text(
                        profileLabel.uppercase(),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 9.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
            val countdownLine: @Composable () -> Unit = {
                Text(
                    // The "left ·" prefix disambiguates the flipped bore number
                    // (CCRM-22 rev B) — the one spare line this face has.
                    when {
                        percent == null -> "Waiting"
                        usageLeft -> "left · ${widgetCountdown(window?.resetsAt)}"
                        else -> widgetCountdown(window?.resetsAt)
                    },
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
            }
            val momentLine: @Composable () -> Unit = {
                if (percent != null) {
                    Text(
                        resetMoment(window?.resetsAt, use24h),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }

            if (l.landscape) {
                // Wide and short: the lines read better beside the ring than under it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    gauge()
                    Spacer(GlanceModifier.width(12.dp))
                    Column {
                        nameLine()
                        countdownLine()
                        momentLine()
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (l.showName) nameLine()
                    gauge()
                    Spacer(GlanceModifier.height(4.dp))
                    countdownLine()
                    if (l.showResetMoment) momentLine()
                }
            }
        }
        // ↻ overlays the top-right corner (its own action; body tap opens the app).
        Box(
            modifier = GlanceModifier.fillMaxSize().padding(6.dp),
            contentAlignment = Alignment.TopEnd,
        ) { RefreshGlyph(profile) }
        // Stale / fetch error keep the ring and overlay one amber pill on its
        // bottom edge (rev-2 wireframe decision).
        pillText(state, snapshot)?.let { msg ->
            Box(
                modifier = GlanceModifier.fillMaxSize().padding(bottom = 9.dp),
                contentAlignment = Alignment.BottomCenter,
            ) { FacePill(msg) }
        }
    }
}
