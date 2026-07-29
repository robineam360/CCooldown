package com.robin.claudeusage.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
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
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
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
import androidx.glance.unit.ColorProvider
import com.robin.claudeusage.MainActivity
import com.robin.claudeusage.R
import com.robin.claudeusage.data.AuthState
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Snapshot
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.data.WidgetPrefs
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.work.Polling

class UsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageWidget()
}

val PROFILE_PARAM = ActionParameters.Key<String>("profile")

/** Refresh icon tap → immediate one-shot fetch for this widget's profile. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val profile = Profile.fromKey(parameters[PROFILE_PARAM])
        Polling.refreshOnce(context, manual = true, profile = profile)
    }
}

class UsageWidget : GlanceAppWidget() {

    companion object {
        // Same width on all buckets: layout is chosen purely by available height.
        private val SMALL = DpSize(110.dp, 48.dp)
        private val MEDIUM = DpSize(110.dp, 110.dp)
        private val LARGE = DpSize(110.dp, 190.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val profile = WidgetPrefs(context).profileFor(appWidgetId)
        val cache = UsageCache(context)
        val snapshot = cache.snapshot(profile)
        val use24h = cache.use24hTime()
        val themeName = cache.themeColorName()
        // Both gates: the per-profile switch decides whether credits exist for this
        // account at all, the widget switch whether they're worth the height here.
        val showCredits = cache.creditsOnWidgets() && cache.creditsVisible(profile)
        provideContent {
            GlanceTheme {
                WidgetContent(profile, cache.profileLabel(profile), snapshot, use24h, themeName, showCredits)
            }
        }
    }
}

internal val staleColor = androidx.glance.color.ColorProvider(
    day = Color(0xFF9A6700),
    night = Color(0xFFFFB865),
)

@Composable
internal fun widgetThemeColor(themeName: String): Color {
    val context = LocalContext.current
    return if (themeName == Palette.DYNAMIC) GlanceTheme.colors.primary.getColor(context)
    else Palette.color(themeName, widgetIsDark())
}

@Composable
internal fun widgetIsDark(): Boolean {
    val context = LocalContext.current
    return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
}

@Composable
internal fun widgetCornerRadius(): androidx.compose.ui.unit.Dp {
    val context = LocalContext.current
    return (context.resources.getDimension(
        android.R.dimen.system_app_widget_background_radius
    ) / context.resources.displayMetrics.density).dp
}

@Composable
private fun WidgetContent(
    profile: Profile,
    profileLabel: String,
    snapshot: Snapshot,
    use24h: Boolean,
    themeName: String,
    showCredits: Boolean,
) {
    val size = LocalSize.current
    val large = size.height >= 190.dp
    val medium = size.height >= 110.dp

    val needsSetup = snapshot.authState == AuthState.NO_CREDENTIALS
    val needsReauth = snapshot.authState == AuthState.REAUTH_NEEDED

    // Body tap opens the app; the refresh icon has its own action.
    val rootModifier = GlanceModifier
        .fillMaxSize()
        .background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(widgetCornerRadius())
        .padding(if (large) 16.dp else 12.dp)
        .clickable(actionStartActivity<MainActivity>())

    val theme = widgetThemeColor(themeName)
    val dark = widgetIsDark()

    Column(modifier = rootModifier, verticalAlignment = Alignment.CenterVertically) {
        when {
            needsSetup -> CenteredMessage("Claude Cooldown · $profileLabel", "Tap to set up")
            needsReauth -> CenteredMessage("$profileLabel: re-auth needed", "Tap to open app")
            snapshot.data == null -> CenteredMessage("No data yet · $profileLabel", "Tap to open app")
            // RemoteViews containers allow at most 10 children — every block below
            // is wrapped in its own Column so the root stays small.
            large -> {
                val data = snapshot.data!!
                // Only the large bucket has the height for a fourth bar.
                val credits = data.credits?.takeIf { showCredits && it.limitMinor > 0L }
                SessionBlock(profile, data.session, use24h, theme, dark, label = "5-hour · $profileLabel", barHeight = 14.dp)
                Spacer(GlanceModifier.height(12.dp))
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        "7-day",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    LabeledBar("All models", data.weekly?.percent, "%", theme, dark)
                    for (cap in data.modelCaps) {
                        LabeledBar(cap.modelName, cap.window.percent, "%", theme, dark)
                    }
                    credits?.let {
                        LabeledBar(
                            "Credits · ${Fmt.money(it.usedMinor, it.exponent, it.currency)} / " +
                                Fmt.money(it.limitMinor, it.exponent, it.currency),
                            it.percent, "%", theme, dark,
                            valueText = "${it.percentDisplay}%",
                        )
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    FooterRow(snapshot, data.weekly, use24h)
                }
            }
            medium -> {
                val data = snapshot.data!!
                SessionBlock(profile, data.session, use24h, theme, dark, label = "5-hour · $profileLabel", barHeight = 12.dp)
                Spacer(GlanceModifier.height(8.dp))
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    HeaderRow(profile, "7-day", data.weekly?.percent, "% used", showRefresh = false)
                    Spacer(GlanceModifier.height(4.dp))
                    WidgetBar(data.weekly?.percent, theme, dark, 12.dp)
                    Spacer(GlanceModifier.height(6.dp))
                    FooterRow(snapshot, data.weekly, use24h)
                }
            }
            else -> {
                val data = snapshot.data!!
                SessionBlock(profile, data.session, use24h, theme, dark, label = "5h · $profileLabel", barHeight = 12.dp)
            }
        }
    }
}

@Composable
internal fun SessionBlock(
    profile: Profile,
    session: UsageWindow?,
    use24h: Boolean,
    theme: Color,
    dark: Boolean,
    label: String,
    barHeight: androidx.compose.ui.unit.Dp,
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        HeaderRow(profile, label, session?.percent, "% used", showRefresh = true)
        Spacer(GlanceModifier.height(5.dp))
        WidgetBar(session?.percent, theme, dark, barHeight)
        Spacer(GlanceModifier.height(4.dp))
        ResetSubText(session, use24h)
    }
}

/** Reset countdown row; a window with no reset time hasn't started yet. */
@Composable
internal fun ResetSubText(window: UsageWindow?, use24h: Boolean) {
    if (window?.resetsAt == null) SubTextRow("Starts when a message is sent", "")
    else SubTextRow(Fmt.relIn(window.resetsAt), Fmt.dayTime(window.resetsAt, use24h))
}

@Composable
internal fun HeaderRow(
    profile: Profile,
    label: String,
    percent: Double?,
    suffix: String,
    showRefresh: Boolean,
    valueText: String? = null,
) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Weighted label: it truncates on narrow widgets so the percentage and
        // refresh icon always stay visible.
        Text(
            label,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.padding(end = 8.dp).defaultWeight(),
            maxLines = 1,
        )
        Text(
            valueText ?: "${(percent ?: 0.0).toInt()}$suffix",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (showRefresh) {
            Spacer(GlanceModifier.width(10.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Refresh",
                modifier = GlanceModifier.size(20.dp).clickable(
                    actionRunCallback<RefreshAction>(actionParametersOf(PROFILE_PARAM to profile.key))
                ),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

@Composable
internal fun WidgetBar(percent: Double?, theme: Color, dark: Boolean, height: androidx.compose.ui.unit.Dp) {
    val fill = Palette.barColor(percent, theme, dark)
    LinearProgressIndicator(
        progress = ((percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
        modifier = GlanceModifier.fillMaxWidth().height(height).cornerRadius(height / 2),
        color = ColorProvider(fill),
        backgroundColor = ColorProvider(fill.copy(alpha = 0.25f)),
    )
}

/**
 * One labelled bar. [valueText] overrides the default truncated percentage — credits
 * pass a rounded one, since their percentage is computed from money.
 */
@Composable
internal fun LabeledBar(
    label: String,
    percent: Double?,
    suffix: String,
    theme: Color,
    dark: Boolean,
    valueText: String? = null,
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                modifier = GlanceModifier.padding(end = 8.dp).defaultWeight(),
                maxLines = 1,
            )
            Text(
                valueText ?: "${(percent ?: 0.0).toInt()}$suffix",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        Spacer(GlanceModifier.height(3.dp))
        WidgetBar(percent, theme, dark, 14.dp)
        Spacer(GlanceModifier.height(7.dp))
    }
}

@Composable
internal fun SubTextRow(left: String, right: String) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            left,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            maxLines = 1,
            modifier = GlanceModifier.padding(end = 8.dp),
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(right, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
    }
}

@Composable
internal fun FooterRow(snapshot: Snapshot, weekly: UsageWindow?, use24h: Boolean) {
    val failed = snapshot.lastStatus != "OK"
    val ageMinutes = (System.currentTimeMillis() - snapshot.fetchedAt) / 60_000L
    val stale = failed || snapshot.fetchedAt <= 0 || ageMinutes > 45
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        ResetSubText(weekly, use24h)
        Spacer(GlanceModifier.height(2.dp))
        Text(
            if (failed) "updated ${Fmt.ago(snapshot.fetchedAt)} · ${snapshot.lastStatus}"
            else "updated ${Fmt.ago(snapshot.fetchedAt)}",
            style = TextStyle(
                color = if (stale) staleColor else GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
            ),
            maxLines = 1,
        )
    }
}

@Composable
internal fun CenteredMessage(title: String, subtitle: String) {
    Column(
        modifier = GlanceModifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            subtitle,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
        )
    }
}
