package com.robin.claudeusage.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import com.robin.claudeusage.MainActivity
import com.robin.claudeusage.data.AuthState
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.data.WidgetPrefs
import com.robin.claudeusage.ui.daysElapsedWindow

class BarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BarWidget()
}

/** Compact single-bar widget: one window of one profile, chosen at placement. */
class BarWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Single

    companion object {
        val BAR_OPTIONS = listOf(
            "session" to "5-hour",
            "weekly" to "7-day all models",
            "model" to "7-day per-model (e.g. Fable)",
            "days" to "Days elapsed",
        )
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val widgetPrefs = WidgetPrefs(context)
        val profile = widgetPrefs.profileFor(appWidgetId)
        val bar = widgetPrefs.barFor(appWidgetId)
        val cache = UsageCache(context)
        val snapshot = cache.snapshot(profile)
        val use24h = cache.use24hTime()
        val themeName = cache.themeColorName()
        provideContent {
            GlanceTheme {
                BarContent(profile, bar, snapshot, use24h, themeName)
            }
        }
    }
}

@Composable
private fun BarContent(
    profile: Profile,
    bar: String,
    snapshot: com.robin.claudeusage.data.Snapshot,
    use24h: Boolean,
    themeName: String,
) {
    val rootModifier = GlanceModifier
        .fillMaxSize()
        .background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(widgetCornerRadius())
        .padding(12.dp)
        .clickable(actionStartActivity<MainActivity>())

    val theme = widgetThemeColor(themeName)
    val dark = widgetIsDark()
    val data = snapshot.data

    val (window: UsageWindow?, label: String, suffix: String) = when (bar) {
        "weekly" -> Triple(data?.weekly, "7-day", "% used")
        "model" -> {
            val cap = data?.modelCaps?.firstOrNull()
            Triple(cap?.window, "${cap?.modelName ?: "Model"} · 7d", "% used")
        }
        "days" -> Triple(daysElapsedWindow(data?.weekly), "Days elapsed", "%")
        else -> Triple(data?.session, "5-hour", "% used")
    }

    Column(modifier = rootModifier, verticalAlignment = Alignment.CenterVertically) {
        when {
            snapshot.authState == AuthState.NO_CREDENTIALS ->
                CenteredMessage("${profile.label}: no token", "Tap to set up")
            snapshot.authState == AuthState.REAUTH_NEEDED ->
                CenteredMessage("${profile.label}: re-auth needed", "Tap to open app")
            data == null -> CenteredMessage("No data yet · ${profile.label}", "Tap to open app")
            else -> {
                HeaderRow(profile, "$label · ${profile.label}", window?.percent, suffix, showRefresh = true)
                Spacer(GlanceModifier.height(5.dp))
                WidgetBar(window?.percent, theme, dark, 12.dp)
                Spacer(GlanceModifier.height(4.dp))
                ResetSubText(window, use24h)
            }
        }
    }
}
