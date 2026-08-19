package com.robin.claudeusage.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.robin.claudeusage.MainActivity
import com.robin.claudeusage.data.HistoryStore
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.Snapshot
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.data.WidgetPrefs
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.PACE_DEAD_ZONE
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.ui.elapsedPercent
import com.robin.claudeusage.work.Polling

class PaceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PaceWidget()

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

val WINDOW_PARAM = ActionParameters.Key<String>("window")

/**
 * The large face's on-widget 5h/7d toggle. It writes the same per-widget key
 * the config screen writes, so the toggle's choice persists and "beats the
 * configured window" by simply being it. Runs in-process on a broadcast, so
 * flipping never needs the app to be open.
 */
class ToggleWindowAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val window = parameters[WINDOW_PARAM] ?: return
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        WidgetPrefs(context).saveWindow(appWidgetId, window)
        PaceWidget().update(context, glanceId)
    }
}

/**
 * CCRM-41 (Pace Widget): one window's pace story — big percent, the CCRM-12
 * (Trend Chart)/CCRM-20 (Wide Chart) chart as a bitmap, and the verdict
 * sentence. Mac provenance: CCRM-18 [Desktop] large face. Supersedes the
 * CCRM-13 (Chart Widget) sketch; extraction decision recorded in ChartBitmap.
 */
class PaceWidget : GlanceAppWidget() {

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
        val weekly = windowKey == "weekly"
        val window = snapshot.data?.let { if (weekly) it.weekly else it.session }
        val windowLengthMs = if (weekly) Projection.WEEKLY_MS else Projection.SESSION_MS
        val resetMs = window?.resetsAt?.toEpochMilli()
        val samples = if (resetMs != null) {
            val points = HistoryStore(context).points(profile)
            if (weekly) Projection.weeklySamples(points, resetMs, windowLengthMs)
            else Projection.sessionSamples(points, resetMs, windowLengthMs)
        } else emptyList()
        resetMs?.let { Polling.armResetRedraw(context, it) }
        provideContent {
            GlanceTheme {
                PaceFace(
                    profile, snapshot, window, windowKey, windowLengthMs, samples,
                    use24h, usageLeft, themeName,
                )
            }
        }
    }
}

@Composable
private fun PaceFace(
    profile: Profile,
    snapshot: Snapshot,
    window: UsageWindow?,
    windowKey: String,
    windowLengthMs: Long,
    samples: List<Pair<Long, Double>>,
    use24h: Boolean,
    usageLeft: Boolean,
    themeName: String,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val state = faceState(snapshot, hasData = window != null)
    val accent = widgetThemeColor(themeName)
    val dark = widgetIsDark()
    val resetMs = window?.resetsAt?.toEpochMilli()
    val est = if (resetMs != null) Projection.estimate(samples, resetMs) else null
    val percent = window?.percent
    val elapsed = window?.let { elapsedPercent(it, windowLengthMs) }
    // The identical dead-zoned gate the ring segment and the chart wash use.
    val above = percent != null && elapsed != null && percent > elapsed + PACE_DEAD_ZONE
    val atLimit = (percent ?: 0.0) >= 100.0

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(widgetCornerRadius())
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (state == FaceState.NOT_SIGNED_IN) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NotSignedInFace()
            }
            return@Column
        }

        // Header: window title + the on-face 5h/7d toggle.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (windowKey == "weekly") "7-day window" else "5-hour window",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.padding(end = 8.dp).defaultWeight(),
            )
            WindowChip("5h", "session", windowKey == "session", accent, dark)
            Spacer(GlanceModifier.width(4.dp))
            WindowChip("7d", "weekly", windowKey == "weekly", accent, dark)
        }
        Spacer(GlanceModifier.height(4.dp))

        // Stat line: percent huge left, countdown + exact reset stacked right.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // CCRM-22 (Used or Left): room for the word here, so it flips worded.
                if (percent == null) "—" else Fmt.usageShort(percent, usageLeft),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    widgetCountdown(window?.resetsAt),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
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
        Spacer(GlanceModifier.height(2.dp))

        // The chart. Chrome above/below is ~150dp; the plot gets the rest.
        val chartH = (size.height.value - 150f).coerceAtLeast(60f)
        val density = context.resources.displayMetrics.density
        val chartW = size.width.value - 28f
        Image(
            provider = ImageProvider(
                ChartBitmap.draw(
                    context = context,
                    widthPx = (chartW * density).toInt(),
                    heightPx = (chartH * density).toInt(),
                    samples = samples,
                    windowStartMs = (resetMs ?: 0L) - windowLengthMs,
                    windowEndMs = resetMs ?: 1L,
                    projectedEnd = est?.let { e ->
                        if (e.hitsLimitAtMs != null) e.hitsLimitAtMs to 100.0
                        else (resetMs ?: 0L) to e.pctAtReset
                    },
                    color = Palette.barColor(percent, accent, dark),
                    accent = accent,
                    dark = dark,
                    use24h = use24h,
                )
            ),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxWidth().height(chartH.dp),
        )
        Spacer(GlanceModifier.height(4.dp))

        // The verdict sentence; amber treatment when above pace. A refused
        // projection prints why. At 100% the sentence has nothing to project.
        val sentence = when {
            window == null || resetMs == null -> "Waiting for first fetch"
            atLimit -> "Window is spent — resets ${widgetCountdown(window.resetsAt)}"
            samples.size < 2 -> "Not enough history in this window yet to chart a pace"
            else -> paceSentence(est, resetMs, use24h)
        }
        Text(
            sentence,
            style = TextStyle(
                color = if (above) amberPillInk else GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
            ),
            maxLines = 2,
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(
                    if (above) amberPillBg
                    else androidx.glance.color.ColorProvider(
                        day = Color(0x0F1D1D1F),
                        night = Color(0x14F2F2F4),
                    )
                )
                .cornerRadius(10.dp)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        Spacer(GlanceModifier.defaultWeight())

        // Footer: freshness + ↻.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pill = pillText(state, snapshot)
            if (pill != null) FacePill(pill)
            else Text(
                "Updated ${Fmt.ago(snapshot.fetchedAt)}",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.defaultWeight())
            RefreshGlyph(profile, sizeDp = 16.dp)
        }
    }
}

@Composable
private fun WindowChip(label: String, key: String, selected: Boolean, accent: Color, dark: Boolean) {
    Text(
        label,
        style = TextStyle(
            color = if (selected) ColorProvider(accent) else GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        ),
        modifier = GlanceModifier
            .background(
                if (selected) ColorProvider(accent.copy(alpha = if (dark) 0.22f else 0.16f))
                else androidx.glance.color.ColorProvider(
                    day = Color(0x0F1D1D1F),
                    night = Color(0x14F2F2F4),
                )
            )
            .cornerRadius(99.dp)
            .padding(horizontal = 11.dp, vertical = 3.dp)
            .clickable(
                actionRunCallback<ToggleWindowAction>(actionParametersOf(WINDOW_PARAM to key))
            ),
    )
}
