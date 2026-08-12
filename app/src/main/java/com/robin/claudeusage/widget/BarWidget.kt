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
import com.robin.claudeusage.ui.Fmt

class BarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BarWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // Launchers recycle widget ids, so a stale override would pre-fill the
        // reconfigure screen for a widget that no longer exists.
        val prefs = WidgetPrefs(context)
        for (id in appWidgetIds) prefs.remove(id)
    }
}

/** Compact single-bar widget: one window of one profile, chosen at placement. */
class BarWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Single

    companion object {
        val BAR_OPTIONS = listOf(
            "session" to "5-hour",
            "weekly" to "7-day all models",
            "model" to "7-day per-model (e.g. Fable)",
            "credits" to "Usage credits (pay-as-you-go)",
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
        val profileLabel = cache.profileLabel(profile)
        provideContent {
            GlanceTheme {
                BarContent(profile, profileLabel, bar, snapshot, use24h, themeName)
            }
        }
    }
}

@Composable
private fun BarContent(
    profile: Profile,
    profileLabel: String,
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
        // "days" was the Days elapsed option, retired once the chart's even-pace
        // diagonal made it redundant. Widgets already placed with it land on the
        // 7-day window, which is what the figure was derived from.
        "weekly", "days" -> Triple(data?.weekly, "7-day", "% used")
        "model" -> {
            val cap = data?.modelCaps?.firstOrNull()
            Triple(cap?.window, "${cap?.modelName ?: "Model"} · 7d", "% used")
        }
        else -> Triple(data?.session, "5-hour", "% used")
    }

    // Credits were picked explicitly at placement, so they aren't gated on the
    // "show on widgets" setting — that one is about crowding *other* layouts.
    val credits = if (bar == "credits") data?.credits?.takeIf { it.isReportable } else null

    Column(modifier = rootModifier, verticalAlignment = Alignment.CenterVertically) {
        when {
            snapshot.authState == AuthState.NO_CREDENTIALS ->
                CenteredMessage("$profileLabel: no token", "Tap to set up")
            snapshot.authState == AuthState.REAUTH_NEEDED ->
                CenteredMessage("$profileLabel: re-auth needed", "Tap to open app")
            data == null -> CenteredMessage("No data yet · $profileLabel", "Tap to open app")
            bar == "credits" && credits == null ->
                CenteredMessage("No credits · $profileLabel", "This account has no credit budget")
            credits != null -> {
                val pct = credits.percent
                // Binding constraint, not the monthly remainder — identical until the
                // server reports a balance (CCBG-6).
                val remaining = credits.bindingRemainingMinor
                HeaderRow(
                    profile, "Credits · $profileLabel", pct, "%",
                    showRefresh = true,
                    // Uncapped: the headline becomes the amount, since there is no
                    // percentage to report (CCBG-9).
                    valueText = if (pct != null) {
                        "${credits.percentDisplay}%"
                    } else {
                        Fmt.money(credits.usedMinor, credits.exponent, credits.currency)
                    },
                )
                Spacer(GlanceModifier.height(5.dp))
                // A bar needs a denominator. Without one it would render empty and read
                // as "plenty left", so it is omitted entirely.
                if (pct != null) {
                    WidgetBar(pct, theme, dark, 12.dp)
                    Spacer(GlanceModifier.height(4.dp))
                }
                SubTextRow(
                    if (credits.limitMinor != null) {
                        "${Fmt.money(credits.usedMinor, credits.exponent, credits.currency)} / " +
                            Fmt.money(credits.limitMinor, credits.exponent, credits.currency)
                    } else {
                        "${Fmt.money(credits.usedMinor, credits.exponent, credits.currency)} spent"
                    },
                    when {
                        remaining == null -> "no monthly cap"
                        remaining > 0L ->
                            "${Fmt.money(remaining, credits.exponent, credits.currency)} left"
                        else -> "spent"
                    },
                )
            }
            else -> {
                HeaderRow(profile, "$label · $profileLabel", window?.percent, suffix, showRefresh = true)
                Spacer(GlanceModifier.height(5.dp))
                WidgetBar(window?.percent, theme, dark, 12.dp)
                Spacer(GlanceModifier.height(4.dp))
                ResetSubText(window, use24h)
            }
        }
    }
}
