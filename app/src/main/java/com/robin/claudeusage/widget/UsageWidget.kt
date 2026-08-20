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
import androidx.glance.layout.ContentScale
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
import com.robin.claudeusage.R
import com.robin.claudeusage.data.AuthState
import com.robin.claudeusage.data.ErrorKind
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.Snapshot
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.data.WidgetPrefs
import com.robin.claudeusage.ui.BarRenderer
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.ui.elapsedPercent
import com.robin.claudeusage.work.Polling

class UsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // Launchers recycle widget ids, so a stale override would pre-fill the
        // reconfigure screen for a widget that no longer exists.
        val prefs = WidgetPrefs(context)
        for (id in appWidgetIds) prefs.remove(id)
    }
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
        val usageLeft = cache.usageLeft()
        val resetClock = cache.resetClock()
        val themeName = cache.themeColorName()
        // Both gates: the per-profile switch decides whether credits exist for this
        // account at all, the widget switch whether they're worth the height here.
        val showCredits = cache.creditsOnWidgets() && cache.creditsVisible(profile)
        // Widgets read UsageCache directly, the same way creditsOnWidgets does — there
        // is no composition to hoist state into out here (CCRM-43 (Bar Pace Marks)).
        val showOverPace = cache.paceOverOnWidgets()
        // Measured out here: LocalSize reports the breakpoint under SizeMode.Responsive,
        // and the bars are bitmaps now, so they need the width they will really occupy.
        val widthDp = widgetWidthDp(context, appWidgetId)
        provideContent {
            GlanceTheme {
                WidgetContent(
                    profile, cache.profileLabel(profile), snapshot, use24h, usageLeft,
                    resetClock, themeName, showCredits, showOverPace, widthDp,
                )
            }
        }
    }
}

/**
 * The widget's real width in dp, from the launcher's own options — the only figure
 * that is neither a breakpoint nor a minimum. `OPTION_APPWIDGET_MIN_WIDTH` is the
 * width in the current orientation, which is what the bar has to fill.
 *
 * Null when the launcher hasn't reported one yet (a freshly placed widget on some
 * launchers), so callers fall back to `LocalSize`.
 */
internal fun widgetWidthDp(context: Context, appWidgetId: Int): Float? {
    val options = android.appwidget.AppWidgetManager.getInstance(context)
        ?.getAppWidgetOptions(appWidgetId) ?: return null
    val w = options.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
    return if (w > 0) w.toFloat() else null
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
    usageLeft: Boolean,
    resetClock: Boolean,
    themeName: String,
    showCredits: Boolean,
    showOverPace: Boolean = true,
    widgetWidthDp: Float? = null,
) {
    val size = LocalSize.current
    val large = size.height >= 190.dp
    val medium = size.height >= 110.dp
    // The bars are drawn as bitmaps, so they need the width they will occupy: the
    // widget's own width less this face's padding.
    val pad = if (large) 16.dp else 12.dp

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
                val credits = data.credits?.takeIf { showCredits && it.isReportable }
                SessionBlock(
                    profile, data.session, use24h, usageLeft, resetClock, theme, dark,
                    label = "5-hour · $profileLabel", barHeight = 14.dp,
                    showOverPace = showOverPace, contentPadding = pad,
                    widgetWidthDp = widgetWidthDp,
                )
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
                    // Every row here is a 7-day surface — the weekly window and each
                    // model cap alike — so all of them measure pace against 7 days.
                    LabeledBar(
                        "All models", data.weekly?.percent, "%", theme, dark,
                        left = usageLeft,
                        elapsedPercent = elapsedPercent(data.weekly, Projection.WEEKLY_MS),
                        showOverPace = showOverPace, contentPadding = pad,
                        widgetWidthDp = widgetWidthDp,
                    )
                    for (cap in data.modelCaps) {
                        LabeledBar(
                            cap.modelName, cap.window.percent, "%", theme, dark,
                            left = usageLeft,
                            elapsedPercent = elapsedPercent(cap.window, Projection.WEEKLY_MS),
                            showOverPace = showOverPace, contentPadding = pad,
                            widgetWidthDp = widgetWidthDp,
                        )
                    }
                    credits?.let {
                        val pct = it.percent
                        val limit = it.limitMinor
                        // No cap, no denominator — a bar would sit at 0% and imply
                        // headroom against a ceiling that doesn't exist (CCBG-9), so
                        // report the spend on a plain row instead.
                        if (pct == null || limit == null) {
                            // The trailing figure is the binding constraint: "no cap"
                            // until the server reports a balance, the balance once it
                            // does — with the cap off it is the only ceiling (CCBG-6).
                            val binding = it.bindingRemainingMinor
                            SubTextRow(
                                "Credits",
                                "${Fmt.money(it.usedMinor, it.exponent, it.currency)} spent · " +
                                    if (binding == null) {
                                        "no cap"
                                    } else {
                                        "${Fmt.money(binding, it.exponent, it.currency)} left"
                                    },
                            )
                        } else {
                            // No elapsed: credits are money, and money has no clock.
                            LabeledBar(
                                "Credits · ${Fmt.money(it.usedMinor, it.exponent, it.currency)} / " +
                                    Fmt.money(limit, it.exponent, it.currency),
                                pct, "%", theme, dark,
                                // Rounded display percent either way (CCRM-3).
                                valueText = Fmt.usageShort(it.percentDisplay?.toDouble(), usageLeft),
                                contentPadding = pad,
                                widgetWidthDp = widgetWidthDp,
                            )
                        }
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    FooterRow(snapshot, data.weekly, use24h, resetClock)
                }
            }
            medium -> {
                val data = snapshot.data!!
                SessionBlock(
                    profile, data.session, use24h, usageLeft, resetClock, theme, dark,
                    label = "5-hour · $profileLabel", barHeight = 12.dp,
                    showOverPace = showOverPace, contentPadding = pad,
                    widgetWidthDp = widgetWidthDp,
                )
                Spacer(GlanceModifier.height(8.dp))
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    HeaderRow(
                        profile, "7-day", data.weekly?.percent, "% used",
                        showRefresh = false, left = usageLeft,
                    )
                    WidgetBar(
                        percent = data.weekly?.percent,
                        theme = theme,
                        dark = dark,
                        height = 12.dp,
                        elapsedPercent = elapsedPercent(data.weekly, Projection.WEEKLY_MS),
                        showOverPace = showOverPace,
                        contentPadding = pad,
                        widgetWidthDp = widgetWidthDp,
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    FooterRow(snapshot, data.weekly, use24h, resetClock)
                }
            }
            else -> {
                val data = snapshot.data!!
                SessionBlock(
                    profile, data.session, use24h, usageLeft, resetClock, theme, dark,
                    label = "5h · $profileLabel", barHeight = 12.dp,
                    showOverPace = showOverPace, contentPadding = pad,
                    widgetWidthDp = widgetWidthDp,
                )
            }
        }
    }
}

@Composable
internal fun SessionBlock(
    profile: Profile,
    session: UsageWindow?,
    use24h: Boolean,
    usageLeft: Boolean,
    resetClock: Boolean,
    theme: Color,
    dark: Boolean,
    label: String,
    barHeight: androidx.compose.ui.unit.Dp,
    showOverPace: Boolean = true,
    contentPadding: androidx.compose.ui.unit.Dp = 12.dp,
    widgetWidthDp: Float? = null,
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        HeaderRow(profile, label, session?.percent, "% used", showRefresh = true, left = usageLeft)
        // Trimmed from 5.dp: the bar image now carries 0.3 h of transparent overhang
        // above the bar for the tick, so the visible gap is unchanged.
        Spacer(GlanceModifier.height(1.dp))
        WidgetBar(
            percent = session?.percent,
            theme = theme,
            dark = dark,
            height = barHeight,
            elapsedPercent = elapsedPercent(session, Projection.SESSION_MS),
            showOverPace = showOverPace,
            contentPadding = contentPadding,
            widgetWidthDp = widgetWidthDp,
        )
        // Trimmed from 4.dp for the same reason, on the underside.
        Spacer(GlanceModifier.height(1.dp))
        ResetSubText(session, use24h, resetClock)
    }
}

/**
 * Reset countdown row; a window with no reset time hasn't started yet.
 * CCRM-23 (Reset Display), Option A: the chosen form leads, the other keeps the
 * second slot — the token decides order, never presence.
 */
@Composable
internal fun ResetSubText(window: UsageWindow?, use24h: Boolean, resetClock: Boolean = false) {
    if (window?.resetsAt == null) {
        SubTextRow("Starts when a message is sent", "")
        return
    }
    val countdown = Fmt.relIn(window.resetsAt)
    val clock = Fmt.dayTime(window.resetsAt, use24h)
    if (resetClock) SubTextRow(clock, countdown) else SubTextRow(countdown, clock)
}

@Composable
internal fun HeaderRow(
    profile: Profile,
    label: String,
    percent: Double?,
    suffix: String,
    showRefresh: Boolean,
    valueText: String? = null,
    /** CCRM-22 (Used or Left): Left overrides [suffix] — the word carries the flip. */
    left: Boolean = false,
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
            valueText
                ?: if (left) Fmt.usageShort(percent, true)
                else "${(percent ?: 0.0).toInt()}$suffix",
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

/**
 * One widget bar, with its pace marks (CCRM-43 (Bar Pace Marks)).
 *
 * A [BarRenderer] bitmap rather than Glance's `LinearProgressIndicator`: that
 * composable draws a track and a fill and nothing else, so there is no way to put a
 * tick or a second-colour segment inside it. Same move [ringBitmap] already makes
 * for the ring faces.
 *
 * The bitmap is drawn at the width the bar will actually occupy — [widgetWidthDp] less
 * the face's padding — so the capsules keep their proportions instead of being
 * stretched into place.
 *
 * [widgetWidthDp] has to be measured outside the composition: `LocalSize` reports the
 * *breakpoint* under `SizeMode.Responsive`, and the minimum under `SizeMode.Single`,
 * so neither is the real width. `provideGlance` reads it from the widget's options
 * (see [widgetWidthDp]) and passes it down. Null falls back to `LocalSize`, which is
 * exact under `SizeMode.Exact`.
 *
 * `FillBounds` rather than `Fit` deliberately: the bar must span the full width the
 * way `LinearProgressIndicator` did, so a wrong width estimate should cost the tick a
 * little of its thickness rather than leaving the bar visibly short of the edge.
 *
 * The image is `1.6 × height` tall, because the tick overhangs the bar by 0.3 h top
 * and bottom; call sites trim the adjacent spacers to keep the row's total height.
 */
@Composable
internal fun WidgetBar(
    percent: Double?,
    theme: Color,
    dark: Boolean,
    height: androidx.compose.ui.unit.Dp,
    elapsedPercent: Double? = null,
    showOverPace: Boolean = true,
    contentPadding: androidx.compose.ui.unit.Dp = 12.dp,
    widgetWidthDp: Float? = null,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val fullWidth = widgetWidthDp?.dp ?: LocalSize.current.width
    val widthDp = (fullWidth - contentPadding * 2).coerceAtLeast(24.dp)
    val bitmap = BarRenderer.draw(
        widthPx = widthDp.value * density,
        heightPx = height.value * density,
        percent = percent,
        elapsedPercent = elapsedPercent,
        accent = theme,
        dark = dark,
        showOverPace = showOverPace,
    )
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = null,
        modifier = GlanceModifier.fillMaxWidth().height(height * 1.6f),
        contentScale = ContentScale.FillBounds,
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
    /** CCRM-22 (Used or Left): Left overrides [suffix] — the word carries the flip. */
    left: Boolean = false,
    /** Null for a credits row: money has no clock, so it never carries a mark. */
    elapsedPercent: Double? = null,
    showOverPace: Boolean = true,
    contentPadding: androidx.compose.ui.unit.Dp = 12.dp,
    widgetWidthDp: Float? = null,
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
                valueText
                    ?: if (left) Fmt.usageShort(percent, true)
                    else "${(percent ?: 0.0).toInt()}$suffix",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        // Both spacers trimmed by the bar image's 0.3 h transparent overhang (4 dp at
        // this height), so the row keeps the height it had before the marks arrived.
        WidgetBar(
            percent = percent,
            theme = theme,
            dark = dark,
            height = 14.dp,
            elapsedPercent = elapsedPercent,
            showOverPace = showOverPace,
            contentPadding = contentPadding,
            widgetWidthDp = widgetWidthDp,
        )
        Spacer(GlanceModifier.height(3.dp))
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
internal fun FooterRow(
    snapshot: Snapshot,
    weekly: UsageWindow?,
    use24h: Boolean,
    resetClock: Boolean = false,
) {
    val failed = snapshot.lastStatus != "OK"
    val ageMinutes = (System.currentTimeMillis() - snapshot.fetchedAt) / 60_000L
    val stale = failed || snapshot.fetchedAt <= 0 || ageMinutes > 45
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        ResetSubText(weekly, use24h, resetClock)
        Spacer(GlanceModifier.height(2.dp))
        Text(
            // CCRM-27 (Error Taxonomy): the kind's short label, not the raw status —
            // the full remediation and detail live in-app.
            if (failed) {
                "updated ${Fmt.ago(snapshot.fetchedAt)} · ⚠ " +
                    ErrorKind.fromKey(snapshot.lastStatusKind).short
            } else "updated ${Fmt.ago(snapshot.fetchedAt)}",
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
