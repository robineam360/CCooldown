package com.robin.claudeusage

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.browser.customtabs.CustomTabsIntent
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.robin.claudeusage.data.ApiClient
import com.robin.claudeusage.data.AuthState
import com.robin.claudeusage.data.OAuthSignIn
import com.robin.claudeusage.data.PingSchedule
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.SignInExpiry
import com.robin.claudeusage.data.UpdateCheck
import com.robin.claudeusage.data.UpdateGate
import com.robin.claudeusage.data.UpdateInfo
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageRepository
import com.robin.claudeusage.notify.UpdateNotification
import com.robin.claudeusage.ping.PingLog
import com.robin.claudeusage.ping.PingScheduler
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.ui.hasTwoColumns
import com.robin.claudeusage.widget.BarWidgetReceiver
import com.robin.claudeusage.widget.UsageWidgetReceiver
import com.robin.claudeusage.work.Polling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FEEDBACK_EMAIL = "robin@eam360.com"
private const val DEBUG_UNLOCK_TAPS = 7

// CCRM-26 (Quick Links) destinations. The status page is shared with the main
// screen's error state, so it isn't private to this file.
internal const val ANTHROPIC_STATUS_URL = "https://status.anthropic.com"
private const val USAGE_DASHBOARD_URL = "https://claude.ai/settings/usage"

@Composable
fun SettingsScreen(
    repo: UsageRepository,
    use24h: Boolean,
    onUse24h: (Boolean) -> Unit,
    themeName: String,
    onTheme: (String) -> Unit,
    debugUnlocked: Boolean,
    onDebugUnlock: () -> Unit,
    onOpenGuide: () -> Unit,
    refreshWidgets: () -> Unit,
) {
    val context = LocalContext.current
    val cacheSettings = repo.cacheSettings()
    // Bumped when a profile is renamed so labels elsewhere on this screen refresh.
    var namesTick by remember { mutableIntStateOf(0) }
    val labels = remember(namesTick) { Profile.entries.associateWith { cacheSettings.profileLabel(it) } }

    // The sections split into two independent groups so a wide window — the
    // Fold's inner screen, a tablet, a freeform window — can run them as two
    // columns instead of one very long scroll. The split is by subject rather
    // than by length: what the accounts are on one side, how this device
    // surfaces them on the other.
    val accountSections: @Composable () -> Unit = {
        SectionLabel("Accounts")
        for (profile in Profile.entries) {
            TokenCard(repo, profile, use24h, onOpenGuide, label = labels.getValue(profile))
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(14.dp))

        SectionLabel("Profile names")
        SectionCard {
            for ((index, profile) in Profile.entries.withIndex()) {
                if (index > 0) Spacer(Modifier.height(10.dp))
                var name by remember { mutableStateOf(cacheSettings.profileLabel(profile)) }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(16)
                        cacheSettings.setProfileLabel(profile, name)
                        namesTick++
                        refreshWidgets()
                    },
                    label = { Text("${profile.label} profile") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Used everywhere — tabs, widgets, and notifications. Clear a field to go back to the default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))

        SectionLabel("Polling")
        PollingSection(repo)
        Spacer(Modifier.height(24.dp))

        SectionLabel("Notifications")
        SectionCard {
            Text("Usage warnings", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Notify when a window crosses these levels. Nothing selected = silent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            ThresholdChipsRow("5-hour window", listOf(80, 90, 95), cacheSettings.sessionAlertThresholds()) {
                cacheSettings.setSessionAlertThresholds(it)
            }
            ThresholdChipsRow("7-day window", listOf(75, 90), cacheSettings.weeklyAlertThresholds()) {
                cacheSettings.setWeeklyAlertThresholds(it)
            }
            ThresholdChipsRow("Per-model caps", listOf(75, 90), cacheSettings.modelCapAlertThresholds()) {
                cacheSettings.setModelCapAlertThresholds(it)
            }
            RowDivider()
            // Pace alerts (CCRM-21): the projection-based counterpart to the absolute
            // thresholds above — heading vs position, two deliberate signals.
            var paceEnabled by remember { mutableStateOf(cacheSettings.paceAlertsEnabled()) }
            ToggleRow(
                title = "Pace alerts",
                subtitle = "Warn on where usage is heading, not just where it is. " +
                    "First reading of a window never alerts.",
                checked = paceEnabled,
            ) {
                paceEnabled = it
                cacheSettings.setPaceAlertsEnabled(it)
            }
            Spacer(Modifier.height(4.dp))
            val milestones = listOf(
                Triple(
                    Projection.PaceMilestone.WILL_RUN_OUT.name,
                    "Will run out",
                    "Projected past 100% before the reset",
                ),
                Triple(
                    Projection.PaceMilestone.CUTTING_IT_CLOSE.name,
                    "Cutting it close",
                    "Projected to land at ${Projection.PACE_CLOSE_AT_RESET.toInt()}% or more",
                ),
                Triple(
                    Projection.PaceMilestone.ALMOST_OUT.name,
                    "Almost out",
                    "Under ${100 - Projection.PACE_ALMOST_OUT_USED.toInt()}% of the window left",
                ),
            )
            for ((key, title, subtitle) in milestones) {
                var on by remember { mutableStateOf(cacheSettings.paceMilestoneEnabled(key)) }
                ToggleRow(title = title, subtitle = subtitle, checked = on, enabled = paceEnabled) {
                    on = it
                    cacheSettings.setPaceMilestoneEnabled(key, it)
                }
            }
            Text(
                "Applies to the 5-hour and 7-day windows, on profiles with alerts enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RowDivider()
            Text("Reset pings", style = MaterialTheme.typography.bodyLarge)
            Text(
                "\"If busy\" pings only when that window had reached 80% before it reset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            ResetModeRow("5-hour reset", "Session", cacheSettings)
            Spacer(Modifier.height(8.dp))
            ResetModeRow("7-day reset", "Weekly", cacheSettings)
            RowDivider()
            for ((index, profile) in Profile.entries.withIndex()) {
                if (index > 0) RowDivider()
                var enabled by remember { mutableStateOf(cacheSettings.profileAlertsEnabled(profile)) }
                ToggleRow(
                    title = "${labels.getValue(profile)} alerts",
                    subtitle = "Usage warnings and reset pings for this profile",
                    checked = enabled,
                ) {
                    enabled = it
                    cacheSettings.setProfileAlertsEnabled(profile, it)
                }
            }
            RowDivider()
            var authAlerts by remember { mutableStateOf(cacheSettings.authAlertsEnabled()) }
            ToggleRow(
                title = "Sign-in alerts",
                subtitle = "Token expiring soon or no longer working",
                checked = authAlerts,
            ) {
                authAlerts = it
                cacheSettings.setAuthAlertsEnabled(it)
            }
            RowDivider()
            var healthAlerts by remember { mutableStateOf(cacheSettings.healthAlertsEnabled()) }
            ToggleRow(
                title = "Stale data alerts",
                subtitle = "Usage data hasn't refreshed for hours",
                checked = healthAlerts,
            ) {
                healthAlerts = it
                cacheSettings.setHealthAlertsEnabled(it)
            }
            RowDivider()
            LinkRow("System notification settings") {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        }
    }

    val deviceSections: @Composable () -> Unit = {
        SectionLabel("Pinned notification")
        SectionCard {
            var pinned by remember { mutableStateOf(cacheSettings.pinnedEnabled()) }
            var pinnedProfile by remember { mutableStateOf(cacheSettings.pinnedProfile()) }
            var iconStyle by remember { mutableStateOf(cacheSettings.pinnedIconStyle()) }
            var tapTarget by remember { mutableStateOf(cacheSettings.pinnedTapTarget()) }
            var pinnedStyle by remember { mutableStateOf(cacheSettings.pinnedStyle()) }
            fun refreshPinned() {
                com.robin.claudeusage.notify.PinnedNotification.update(context, cacheSettings)
            }
            ToggleRow(
                title = "Always-on usage notification",
                subtitle = "A silent, ongoing notification with a status-bar icon that fills as you use your 5-hour window",
                checked = pinned,
            ) {
                pinned = it
                cacheSettings.setPinnedEnabled(it)
                refreshPinned()
            }
            if (pinned) {
                RowDivider()
                Text("Show profile", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in Profile.entries) {
                        FilterChip(
                            selected = pinnedProfile == p,
                            onClick = {
                                pinnedProfile = p
                                cacheSettings.setPinnedProfile(p)
                                refreshPinned()
                            },
                            label = { Text(labels.getValue(p)) },
                        )
                    }
                }
                RowDivider()
                Text("Notification style", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                val styles = listOf(
                    "gauge" to "Gauge",
                    "number" to "Number tile",
                    "progress" to "Progress bar",
                    "big" to "Huge number",
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in styles.chunked(2)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((value, text) in row) {
                                FilterChip(
                                    selected = pinnedStyle == value,
                                    onClick = {
                                        pinnedStyle = value
                                        cacheSettings.setPinnedStyle(value)
                                        refreshPinned()
                                    },
                                    label = { Text(text) },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (pinnedStyle) {
                        "number" -> "The percentage fills the icon slot — about twice the size of the gauge."
                        "progress" -> "A plain system progress bar with the percentage in the title."
                        "big" -> "The largest number a collapsed notification allows. Uses a custom " +
                            "layout, so a few phone skins may style it differently."
                        else -> "A ring around the percentage, the original look."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowDivider()
                Text("Tapping the notification opens", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                val claudeInstalled = remember {
                    com.robin.claudeusage.notify.PinnedNotification.claudeLaunchIntent(context) != null
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, text) in listOf("app" to "Claude Cooldown", "claude" to "Claude app")) {
                        FilterChip(
                            selected = tapTarget == value,
                            onClick = {
                                tapTarget = value
                                cacheSettings.setPinnedTapTarget(value)
                                refreshPinned()
                            },
                            label = { Text(text) },
                        )
                    }
                }
                if (tapTarget == "claude" && !claudeInstalled) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The Claude app isn't installed — taps will open Claude Cooldown instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RowDivider()
                Text("Status-bar icon", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                val iconStyles = listOf(
                    "ring" to "Ring",
                    "pie" to "Pie",
                    "battery" to "Battery",
                    "number" to "Number",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, text) in iconStyles) {
                        FilterChip(
                            selected = iconStyle == value,
                            onClick = {
                                iconStyle = value
                                cacheSettings.setPinnedIconStyle(value)
                                refreshPinned()
                            },
                            label = { Text(text) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "The colored gauge and bars follow your theme and turn orange, then red, near the limit. The tiny status-bar icon is monochrome — Android renders it that way for every app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionLabel("Window pings")
        SectionCard {
            WindowPingsSection(repo = repo, labels = labels, use24h = use24h)
        }
        Spacer(Modifier.height(24.dp))

        SectionLabel("Quick Settings tile")
        SectionCard {
            Text(
                "The tile in the notification shade / Control Center. It shows the 5-hour " +
                    "percentage; this picks what sits under it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            var tileSubtitle by remember { mutableStateOf(cacheSettings.tileSubtitle()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, text) in listOf("countdown" to "Countdown", "clock" to "Clock time")) {
                    FilterChip(
                        selected = tileSubtitle == value,
                        onClick = {
                            tileSubtitle = value
                            cacheSettings.setTileSubtitle(value)
                        },
                        label = { Text(text) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (tileSubtitle == "clock")
                    "Shows \"resets 4:12 PM\". The tile only updates when you open the shade, " +
                        "so a clock time can't go stale while it sits open."
                else
                    "Shows \"resets in 2h 14m\". Reads more naturally, but drifts if the " +
                        "shade stays open a while.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "The tile icon fills as the window burns, following the status-bar icon " +
                    "style above. Android tints tile icons itself, so it can't carry the " +
                    "theme or warning colors.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))

        SectionLabel("Usage credits")
        SectionCard {
            Text(
                "Pay-as-you-go credits that cover you once a plan window runs out. The " +
                    "section only appears for accounts that actually have a credit budget.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            for (profile in Profile.entries) {
                RowDivider()
                var visible by remember { mutableStateOf(cacheSettings.creditsVisible(profile)) }
                ToggleRow(
                    title = "Show for ${labels.getValue(profile)}",
                    subtitle = "Credits card on this profile's screen",
                    checked = visible,
                ) {
                    visible = it
                    cacheSettings.setCreditsVisible(profile, it)
                    refreshWidgets()
                }
            }
            RowDivider()
            var creditsOnWidgets by remember { mutableStateOf(cacheSettings.creditsOnWidgets()) }
            ToggleRow(
                title = "Show on widgets",
                subtitle = "Adds a credits bar to the tall usage widget. Needs the room, " +
                    "so smaller widgets stay as they are.",
                checked = creditsOnWidgets,
            ) {
                creditsOnWidgets = it
                cacheSettings.setCreditsOnWidgets(it)
                refreshWidgets()
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionLabel("Appearance")
        SectionCard {
            ToggleRow(
                title = "24-hour time",
                subtitle = if (use24h) "Times shown like Thu 23:45" else "Times shown like Thu 11:45 PM",
                checked = use24h,
            ) {
                onUse24h(it)
                repo.cacheSettings().setUse24hTime(it)
                refreshWidgets()
            }
            RowDivider()
            Text("Theme color", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            ThemeColorPicker(themeName) {
                onTheme(it)
                repo.cacheSettings().setThemeColorName(it)
                refreshWidgets()
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionLabel("Widgets")
        SectionCard {
            var pinMessage by remember { mutableStateOf<String?>(null) }
            fun pin(receiver: Class<*>) {
                val awm = context.getSystemService(AppWidgetManager::class.java)
                val ok = awm.isRequestPinAppWidgetSupported &&
                    awm.requestPinAppWidget(ComponentName(context, receiver), null, null)
                if (!ok) pinMessage = "Your launcher doesn't support pinning — long-press the home screen and add it from the widget list."
            }
            LinkRow("Add usage widget to home screen") { pin(UsageWidgetReceiver::class.java) }
            RowDivider()
            LinkRow("Add single-bar widget to home screen") { pin(BarWidgetReceiver::class.java) }
            pinMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionLabel("Updates")
        UpdatesCard(cacheSettings)
        Spacer(Modifier.height(24.dp))

        SectionLabel("About")
        AboutCard(debugUnlocked, onDebugUnlock)

        if (debugUnlocked) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("Debug")
            TrendDiagnostics(repo, use24h)
            Spacer(Modifier.height(10.dp))
            PingLogCard()
            Spacer(Modifier.height(10.dp))
            DebugSection(repo)
        }
    }

    if (hasTwoColumns()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.weight(1f)) { accountSections() }
            Column(Modifier.weight(1f)) { deviceSections() }
        }
    } else {
        accountSections()
        Spacer(Modifier.height(24.dp))
        deviceSections()
    }
    Spacer(Modifier.height(8.dp))
}

private data class BrowserChoice(val label: String, val packageName: String)

/**
 * Installed browsers, for the sign-in "open with" picker. Uses QUERY_ALL_PACKAGES
 * (fine for a sideload app) so OEM skins that under-report a scoped <queries> still
 * list every browser. Signing a given account in the browser where that account is
 * logged in is the whole point — e.g. Work in Samsung Internet, Personal in Brave.
 */
private fun installedBrowsers(context: android.content.Context): List<BrowserChoice> {
    val pm = context.packageManager
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    return pm.queryIntentActivities(probe, android.content.pm.PackageManager.MATCH_ALL)
        .mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            BrowserChoice(ri.loadLabel(pm).toString(), pkg)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/**
 * Whether a URL is one the app will hand to a browser: plain web links only.
 * Every launch site uses a compile-time https constant today; this is the guard
 * that keeps a future refactor from sending an `intent://` or `javascript:`
 * payload through the same path.
 */
internal fun allowedLinkUrl(url: String): Boolean {
    val scheme = url.trim().substringBefore(':', missingDelimiterValue = "")
    return scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true)
}

/**
 * Opens a URL in a specific browser (full external app, not an in-app tab).
 * The single launch path for sign-in and the CCRM-26 (Quick Links) buttons —
 * shared with MainActivity so the [allowedLinkUrl] check guards every launch
 * through this path. (The release-notes dialog and the mailto feedback intent
 * build their own intents and are guarded at their own call sites.)
 */
internal fun openInBrowser(context: android.content.Context, url: String, pkg: String?) {
    if (!allowedLinkUrl(url)) return
    val uri = Uri.parse(url)
    if (pkg != null) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(pkg)
            )
            return
        } catch (_: Exception) {
            // Fall through to a generic open.
        }
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: Exception) {
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        } catch (_: Exception) {
            // Nothing on the device can open a web link.
        }
    }
}

@Composable
private fun TokenCard(
    repo: UsageRepository,
    profile: Profile,
    use24h: Boolean,
    onOpenGuide: () -> Unit,
    label: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var stateKey by remember { mutableIntStateOf(0) }

    // Sign-in completion state. awaitingCode survives config changes; if the
    // process was killed during the browser trip, a persisted pending sign-in
    // for this profile re-opens the completion step on its own.
    var awaitingCode by remember { mutableStateOf(repo.hasPendingSignIn(profile)) }
    var codeInput by remember { mutableStateOf("") }
    var authUrl by remember { mutableStateOf<String?>(null) }
    var showBackup by remember { mutableStateOf(false) }
    var showBrowserPicker by remember { mutableStateOf(false) }
    var pendingPickUrl by remember { mutableStateOf<String?>(null) }

    val hasToken = remember(stateKey) { repo.hasCredentials(profile) }
    val snapshot = remember(stateKey) { repo.snapshot(profile) }
    val addedAt = remember(stateKey) { repo.tokenAddedAt(profile) }
    val tail = remember(stateKey) { repo.tokenTail(profile) }
    val plan = remember(stateKey) { repo.plan(profile) }
    val tier = remember(stateKey) { repo.tier(profile) }
    val tokenExpiresAt = remember(stateKey) { repo.tokenExpiresAt(profile) }
    val refreshExpiresAt = remember(stateKey) { repo.refreshExpiresAt(profile) }
    val refreshEstimated = remember(stateKey) { repo.refreshExpiryEstimated(profile) }
    val lastRenewedAt = remember(stateKey) { repo.lastRenewedAt(profile) }
    val backoffUntil = remember(stateKey) { repo.cacheSettings().backoffUntil(profile) }
    val firstRefreshFailAt = remember(stateKey) { repo.cacheSettings().firstRefreshFailAt(profile) }

    // Open a URL, letting the user pick a browser when they have more than one
    // (so they can route Work vs Personal through different browsers). Always
    // opens a real external browser, never an in-app tab. Used for sign-in and
    // for the usage-dashboard quick link — same routing question either way.
    fun openWithPicker(url: String) {
        val browsers = installedBrowsers(context)
        if (browsers.size >= 2) {
            pendingPickUrl = url
            showBrowserPicker = true
        } else {
            openInBrowser(context, url, browsers.firstOrNull()?.packageName)
        }
    }

    fun beginSignIn() {
        message = null
        codeInput = ""
        awaitingCode = true
        val url = repo.startSignIn(profile)
        authUrl = url
        openWithPicker(url)
    }

    fun finishSignIn() {
        scope.launch {
            busy = true
            message = null
            val result = repo.completeSignIn(profile, codeInput)
            if (result.message == "OK") {
                Polling.schedulePeriodic(context, repo.cacheSettings().pollIntervalMinutes())
                message = "$label signed in — usage fetched, polling started."
                awaitingCode = false
                codeInput = ""
                showBackup = false
            } else {
                message = result.message
            }
            busy = false
            stateKey++
        }
    }

    fun saveToken(text: String) {
        scope.launch {
            busy = true
            message = null
            val result = repo.validateAndSave(profile, text)
            message = if (result.message == "OK") {
                Polling.schedulePeriodic(context, repo.cacheSettings().pollIntervalMinutes())
                showBackup = false
                "$label token added — usage fetched, polling started."
            } else {
                result.message
            }
            busy = false
            stateKey++
        }
    }

    fun pasteAndSave() {
        val text = clipboard.getText()?.text
        if (text.isNullOrBlank()) {
            message = "Clipboard is empty — copy the token JSON first, then tap again."
            return
        }
        saveToken(text)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        // Null contents = the user backed out of the scanner.
        result.contents?.let { saveToken(it) }
    }

    fun scanAndSave() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the QR code shown on your computer")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }

    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                if (hasToken) StatusChip(snapshot.authState)
                if (hasToken && plan != null) {
                    Spacer(Modifier.width(6.dp))
                    PlanChip(plan, tier)
                }
                Spacer(Modifier.weight(1f))
                if (hasToken && tail != null) {
                    Text(
                        "…$tail",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            // The sign-in completion step takes over the card while active, for
            // either a brand-new sign-in or a re-sign-in of an existing account.
            if (awaitingCode) {
                SignInCompletion(
                    busy = busy,
                    codeInput = codeInput,
                    onCodeChange = { codeInput = it },
                    onPaste = { clipboard.getText()?.text?.let { codeInput = it.trim() } },
                    onFinish = { finishSignIn() },
                    onReopen = { authUrl?.let { openWithPicker(it) } ?: beginSignIn() },
                    onCancel = {
                        repo.cancelSignIn()
                        awaitingCode = false
                        codeInput = ""
                        message = null
                    },
                )
                message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                return@Column
            }

            if (!hasToken) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Not signed in",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(enabled = !busy, onClick = { beginSignIn() }) {
                    Text("Sign in on this phone")
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Opens Claude's sign-in in your browser — no computer needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BackupOptions(
                    expanded = showBackup,
                    onToggle = { showBackup = !showBackup },
                    busy = busy,
                    onPaste = { pasteAndSave() },
                    onScan = { scanAndSave() },
                    onOpenGuide = onOpenGuide,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Last checked: ${Fmt.dayTimeWithAgo(snapshot.lastAttemptAt, use24h)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                message = null
                                val result = repo.refreshNow(profile, manual = true)
                                message = if (result.message == "OK") "Token checked — working." else result.message
                                busy = false
                                stateKey++
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Check token now",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                val now = System.currentTimeMillis()
                if (tokenExpiresAt > 0) {
                    Text(
                        if (tokenExpiresAt > now) "Auto-renews in ${Fmt.dhm(tokenExpiresAt)}"
                        else "Renewal due at the next check",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (lastRenewedAt > 0) {
                    Text(
                        "Last auto-renewed: ${Fmt.dayTimeWithAgo(lastRenewedAt, use24h)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // CCRM-16: once renewal is dead the ~30-day estimate must stop
                // rendering as a date — the fail-streak start is the best fix on
                // when it died, falling back to the failure's own timestamp.
                val deadAt = when {
                    firstRefreshFailAt > 0 -> firstRefreshFailAt
                    snapshot.lastAttemptAt > 0 -> snapshot.lastAttemptAt
                    else -> now
                }
                when (
                    val expiry = SignInExpiry.line(
                        snapshot.authState, refreshEstimated, refreshExpiresAt, addedAt, deadAt, now,
                    )
                ) {
                    is SignInExpiry.Line.RenewalDead -> {
                        val estDays = OAuthSignIn.ESTIMATED_FAMILY_MS / SignInExpiry.DAY_MS
                        val days = expiry.daysObserved
                        Text(
                            when {
                                days == null ->
                                    "Renewal has stopped working — re-sign in below."
                                expiry.earlierThanEstimate && days == 0L ->
                                    "Renewal stopped working within a day of sign-in — " +
                                        "earlier than the ~$estDays-day estimate. Re-sign in below."
                                expiry.earlierThanEstimate ->
                                    "Renewal stopped working $days day${if (days == 1L) "" else "s"} after sign-in — " +
                                        "earlier than the ~$estDays-day estimate. Re-sign in below."
                                else ->
                                    "Renewal stopped working ~$days days after sign-in — " +
                                        "the sign-in likely reached its age limit. Re-sign in below."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is SignInExpiry.Line.Estimated -> Text(
                        "Sign-in expires around ${Fmt.dateTime(expiry.expiresAt, use24h)} · ~${Fmt.dhm(expiry.expiresAt)} left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is SignInExpiry.Line.Exact -> Text(
                        "Sign-in valid until ${Fmt.dateTime(expiry.expiresAt, use24h)} · ${Fmt.dhm(expiry.expiresAt)} to go",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SignInExpiry.Line.None -> {}
                }
                Text(
                    "Added: ${if (addedAt > 0) Fmt.date(addedAt) else "before v0.7"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (backoffUntil > now) {
                    Text(
                        "Rate-limited — next try in ${Fmt.dhm(backoffUntil)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !busy, onClick = { beginSignIn() }) { Text("Re-sign in") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            repo.clearCredentials(profile)
                            message = "$label signed out."
                            showBackup = false
                            stateKey++
                        },
                    ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                }
                RowDivider()
                // Quick escapes, not account actions — hence below the divider.
                // Status goes to the default browser (account-independent); the
                // dashboard reuses the sign-in picker, because which browser holds
                // this profile's Claude session is the same question either way.
                // Flows rather than a Row so the second label drops to its own
                // line at large font scales instead of wrapping mid-label.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        openInBrowser(context, ANTHROPIC_STATUS_URL, null)
                    }) { Text("Anthropic status") }
                    TextButton(onClick = {
                        openWithPicker(USAGE_DASHBOARD_URL)
                    }) { Text("Usage dashboard") }
                }
                BackupOptions(
                    expanded = showBackup,
                    onToggle = { showBackup = !showBackup },
                    busy = busy,
                    onPaste = { pasteAndSave() },
                    onScan = { scanAndSave() },
                    onOpenGuide = onOpenGuide,
                    replaceLabels = true,
                )
            }

            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showBrowserPicker) {
        val browsers = remember { installedBrowsers(context) }
        AlertDialog(
            onDismissRequest = { showBrowserPicker = false },
            title = { Text("Open with") },
            text = {
                Column {
                    Text(
                        "Pick the browser where you're signed in to the $label " +
                            "Claude account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    for (b in browsers) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    showBrowserPicker = false
                                    openInBrowser(context, pendingPickUrl ?: authUrl ?: return@clickable, b.packageName)
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                        ) { Text(b.label, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBrowserPicker = false }) { Text("Cancel") }
            },
        )
    }
}

/** Inline "paste the code from the sign-in page" step shown after the browser trip. */
@Composable
private fun SignInCompletion(
    busy: Boolean,
    codeInput: String,
    onCodeChange: (String) -> Unit,
    onPaste: () -> Unit,
    onFinish: () -> Unit,
    onReopen: () -> Unit,
    onCancel: () -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Text("Finish signing in", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Text(
        "Sign in on the page that opened, then copy the code it shows and paste it here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = codeInput,
        onValueChange = onCodeChange,
        label = { Text("Paste the sign-in code") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            TextButton(onClick = onPaste, enabled = !busy) { Text("Paste") }
        },
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = !busy && codeInput.isNotBlank(), onClick = onFinish) {
            Text("Finish sign-in")
        }
        Spacer(Modifier.width(8.dp))
        TextButton(enabled = !busy, onClick = onReopen) { Text("Reopen page") }
        Spacer(Modifier.weight(1f))
        TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") }
    }
}

/** Collapsible "use a computer token instead" section holding the paste/QR path. */
@Composable
private fun BackupOptions(
    expanded: Boolean,
    onToggle: () -> Unit,
    busy: Boolean,
    onPaste: () -> Unit,
    onScan: () -> Unit,
    onOpenGuide: () -> Unit,
    replaceLabels: Boolean = false,
) {
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onToggle) {
        Text(if (expanded) "Hide computer-token options" else "Use a computer token instead")
    }
    if (expanded) {
        Text(
            "Backup method: copy the sign-in Claude Code uses on your computer. " +
                "Handy if this phone can't open the sign-in page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(enabled = !busy, onClick = onPaste) {
                Text(if (replaceLabels) "Replace by paste" else "Paste from clipboard")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = !busy, onClick = onScan) { Text("Scan QR") }
        }
        TextButton(onClick = onOpenGuide) { Text("How do I get my token?") }
    }
}

@Composable
private fun PlanChip(plan: String, tier: String?) {
    val color = MaterialTheme.colorScheme.primary
    // "Max 20x" when the tier parses, bare "Max" otherwise (CCRM-38). A tier
    // with no plan renders no chip at all — the caller's gate is on the plan.
    val multiplier = Fmt.tierMultiplier(tier)
    Text(
        plan.replaceFirstChar { it.uppercase() } + (multiplier?.let { " $it" } ?: ""),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun StatusChip(authState: AuthState) {
    val dark = isSystemInDarkTheme()
    val (label, color) = when (authState) {
        AuthState.REAUTH_NEEDED -> "Needs re-auth" to MaterialTheme.colorScheme.error
        else -> "Active" to if (dark) Color(0xFF81C995) else Color(0xFF188038)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun PollingSection(repo: UsageRepository) {
    val context = LocalContext.current
    val presets = listOf(5L, 15L, 30L, 60L)
    var interval by remember { mutableLongStateOf(repo.cacheSettings().pollIntervalMinutes()) }
    SectionCard {
        Text("Check usage every", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            presets.forEachIndexed { index, minutes ->
                SegmentedButton(
                    selected = interval == minutes,
                    onClick = {
                        interval = minutes
                        repo.cacheSettings().setPollIntervalMinutes(minutes)
                        Polling.schedulePeriodic(context, minutes)
                        if (minutes < 15) Polling.chainNext(context, minutes)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
                ) { Text("${minutes}m") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (interval < 15) "Short intervals use chained jobs; Android may delay them to save battery."
            else "Applies to all configured profiles. Widgets update after every check.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val lastAttempt = Profile.entries.maxOfOrNull { repo.snapshot(it).lastAttemptAt } ?: 0L
        if (lastAttempt > 0) {
            val next = lastAttempt + interval * 60_000
            Text(
                "Next automatic check: " +
                    if (next > System.currentTimeMillis()) "in ~${Fmt.dhm(next)}" else "due now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeColorPicker(themeName: String, onTheme: (String) -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val allOptions = listOf(Palette.DYNAMIC) + Palette.options.map { it.name }
    for (rowNames in allOptions.chunked(6)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (name in rowNames) {
                val dotColor = if (name == Palette.DYNAMIC) {
                    if (dark) dynamicDarkColorScheme(context).primary
                    else dynamicLightColorScheme(context).primary
                } else Palette.color(name, dark)
                val selected = name == themeName
                // The amber/yellow dots need a dark check for contrast.
                val checkTint = if (name == "Amber") Color(0xFF412402) else Color.White
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .then(
                            if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .clickable { onTheme(name) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "$name selected",
                            tint = checkTint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
    Text(
        "Selected: $themeName" + if (themeName == Palette.DYNAMIC) " (follows your wallpaper)" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The UPDATES section (CCRM-28): the auto-check toggle, the manual check button
 * (moved here from the About card), and the outcome line the background check
 * shares with it. Failures only ever surface here — never as a notification.
 */
@Composable
private fun UpdatesCard(cache: UsageCache) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(cache.autoCheckUpdates()) }
    var checking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateUi?>(null) }
    // Bumped when a manual check finishes so the outcome line re-reads the cache.
    var outcomeTick by remember { mutableIntStateOf(0) }
    val versionName = remember { UpdateNotification.installedVersion(context) }

    SectionCard {
        ToggleRow(
            title = "Check automatically",
            subtitle = "Checks GitHub for a newer release every 6 hours, riding the " +
                "usage poll. A new version notifies once; a failed check never notifies.",
            checked = auto,
        ) {
            auto = it
            cache.setAutoCheckUpdates(it)
        }
        RowDivider()
        OutlinedButton(
            enabled = !checking,
            onClick = {
                checking = true
                scope.launch {
                    // Manual checks ignore the toggle and the skip record; a success
                    // still refreshes the shared last-checked line below.
                    updateResult = try {
                        val info = withContext(Dispatchers.IO) {
                            UpdateCheck.fetchLatest(versionName)
                        }
                        cache.recordUpdateCheckSuccess(
                            System.currentTimeMillis(),
                            UpdateGate.successOutcome(info.latestVersion, info.updateAvailable),
                        )
                        UpdateUi.Ok(info)
                    } catch (_: Exception) {
                        cache.recordUpdateCheckFailure(System.currentTimeMillis(), "couldn't reach GitHub")
                        UpdateUi.Message(
                            "Couldn't check for updates. Check your connection and try again."
                        )
                    }
                    outcomeTick++
                    checking = false
                }
            },
        ) {
            if (checking) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Checking…")
            } else {
                Text("Check for updates")
            }
        }
        Spacer(Modifier.height(8.dp))
        val lastOkAt = remember(outcomeTick) { cache.lastUpdateCheckAt() }
        val outcome = remember(outcomeTick) { cache.lastUpdateCheckOutcome() }
        val failAt = remember(outcomeTick) { cache.lastUpdateFailAt() }
        val failReason = remember(outcomeTick) { cache.lastUpdateFailReason() }
        val dismissed = remember(outcomeTick) { cache.dismissedUpdateVersion() }
        when {
            failAt > lastOkAt -> {
                Text(
                    "Last check failed ${Fmt.ago(failAt)} — " +
                        "${failReason ?: "couldn't reach GitHub"}. Retries with the next poll.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (lastOkAt > 0 && outcome != null) {
                    Text(
                        "Last successful check ${Fmt.ago(lastOkAt)} — $outcome",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            lastOkAt > 0 && outcome != null -> Text(
                "Last checked ${Fmt.ago(lastOkAt)} — ${UpdateGate.outcomeLine(outcome, dismissed)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Text(
                "Not checked yet — the first check rides the next poll.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    when (val r = updateResult) {
        is UpdateUi.Message -> AlertDialog(
            onDismissRequest = { updateResult = null },
            confirmButton = {
                TextButton(onClick = { updateResult = null }) { Text("OK") }
            },
            text = { Text(r.text) },
        )
        is UpdateUi.Ok -> {
            val info = r.info
            AlertDialog(
                onDismissRequest = { updateResult = null },
                title = {
                    Text(
                        if (info.updateAvailable) "Update available"
                        else "You're up to date"
                    )
                },
                text = {
                    Column {
                        if (info.updateAvailable) {
                            Text("v${info.latestVersion} is available (you have v${info.currentVersion}).")
                            if (info.notes.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    info.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (UpdateGate.isSkipped(info.latestVersion, cache.dismissedUpdateVersion())) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "You skipped this version, so it isn't notifying.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text("You're running the latest version (v${info.currentVersion}).")
                        }
                    }
                },
                confirmButton = {
                    if (info.updateAvailable && info.releaseUrl.isNotBlank()) {
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(UpdateGate.safeReleaseUrl(info.releaseUrl)),
                                )
                            )
                            updateResult = null
                        }) { Text("Open GitHub") }
                    } else {
                        TextButton(onClick = { updateResult = null }) { Text("OK") }
                    }
                },
                dismissButton = {
                    if (info.updateAvailable) {
                        TextButton(onClick = { updateResult = null }) { Text("Later") }
                    }
                },
            )
        }
        null -> {}
    }
}

@Composable
private fun AboutCard(debugUnlocked: Boolean, onDebugUnlock: () -> Unit) {
    val context = LocalContext.current
    var taps by remember { mutableIntStateOf(0) }
    // Fallback message when no email app is configured to take the feedback intent.
    var emailFallback by remember { mutableStateOf<String?>(null) }
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
    Card {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painterResource(R.drawable.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text("Claude Cooldown", style = MaterialTheme.typography.titleMedium)
            Text(
                "Version $versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        if (!debugUnlocked && ++taps >= DEBUG_UNLOCK_TAPS) onDebugUnlock()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (debugUnlocked) {
                Text(
                    "Debug tools unlocked until the app is closed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Made by Robin Richard Rajan", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Built with Claude Code",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // Only button left here — "Check for updates" moved to the UPDATES
            // section above (CCRM-28), so a single Row fits every width class.
            OutlinedButton(onClick = {
                val email = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:$FEEDBACK_EMAIL")
                    putExtra(Intent.EXTRA_SUBJECT, "CCooldown feedback (v$versionName)")
                }
                try {
                    context.startActivity(
                        Intent.createChooser(email, "Share feedback")
                    )
                } catch (_: Exception) {
                    // No email app configured — surface the address instead.
                    emailFallback = "Email me at $FEEDBACK_EMAIL"
                }
            }) { Text("Share feedback") }
            Spacer(Modifier.height(12.dp))
            Text(
                "Not affiliated with or endorsed by Anthropic",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    emailFallback?.let { message ->
        AlertDialog(
            onDismissRequest = { emailFallback = null },
            confirmButton = {
                TextButton(onClick = { emailFallback = null }) { Text("OK") }
            },
            text = { Text(message) },
        )
    }
}

private sealed interface UpdateUi {
    /** A successful check with version details. */
    data class Ok(val info: UpdateInfo) : UpdateUi
    /** A plain message (error, or fallback when no email app is present). */
    data class Message(val text: String) : UpdateUi
}

/**
 * Why the trend chart is or isn't showing. `Projection` binds history points to a
 * window by exact `resets_at` equality, so if the server's `resets_at` drifts
 * mid-window every earlier point orphans and the chart goes quiet. The distinct
 * counts below are the test for that: more than one value for a live window means
 * drift, not missing data.
 */
@Composable
private fun TrendDiagnostics(repo: UsageRepository, use24h: Boolean) {
    SectionCard {
        Text("Trend samples", style = MaterialTheme.typography.bodyLarge)
        for (profile in Profile.entries) {
            val points = remember(profile) { repo.history().points(profile) }
            val data = repo.snapshot(profile).data
            Spacer(Modifier.height(8.dp))
            Text(
                repo.cacheSettings().profileLabel(profile),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            if (points.isEmpty()) {
                Text(
                    "no history points recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                continue
            }
            val lines = buildList {
                add("history points: ${points.size} · oldest ${Fmt.ago(points.first().at)}")
                val session = data?.session?.resetsAt?.toEpochMilli()
                val weekly = data?.weekly?.resetsAt?.toEpochMilli()
                if (session != null) {
                    val bound = Projection.sessionSamples(points, session, Projection.SESSION_MS).size
                    val distinct = points.map { it.sessionResetAt }.filter { it > 0 }.distinct().size
                    add("5-hour resets_at ${Fmt.dayTime(java.time.Instant.ofEpochMilli(session), use24h)}")
                    add("  bound to it: $bound · distinct in history: $distinct")
                } else add("5-hour: no resets_at in the payload")
                if (weekly != null) {
                    val bound = Projection.weeklySamples(points, weekly, Projection.WEEKLY_MS).size
                    val distinct = points.map { it.weeklyResetAt }.filter { it > 0 }.distinct().size
                    add("7-day resets_at ${Fmt.dayTime(java.time.Instant.ofEpochMilli(weekly), use24h)}")
                    add("  bound to it: $bound · distinct in history: $distinct")
                } else add("7-day: no resets_at in the payload")
            }
            for (line in lines) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "A chart needs 2+ bound samples, 20 min of span, and 1% of movement. " +
                "\"distinct in history\" above 1 for a live window means resets_at moved " +
                "and older points no longer match.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DebugSection(repo: UsageRepository) {
    var showDebug by remember { mutableStateOf(false) }
    var debugProfile by remember { mutableStateOf(Profile.PERSONAL) }
    SectionCard {
        // CCRM-16: the ~30-day family estimate has never been verified against a
        // real expiry — this age readout is how we learn the true number the first
        // time a family dies of old age rather than revocation.
        val signInAges = remember {
            val now = System.currentTimeMillis()
            Profile.entries.joinToString(" · ") { p ->
                val label = repo.cacheSettings().profileLabel(p)
                val added = repo.tokenAddedAt(p)
                when {
                    !repo.hasCredentials(p) || added <= 0 -> "$label —"
                    repo.refreshExpiryEstimated(p) ->
                        "$label ${(now - added) / SignInExpiry.DAY_MS}d " +
                            "(est. ~${OAuthSignIn.ESTIMATED_FAMILY_MS / SignInExpiry.DAY_MS}d)"
                    else -> "$label ${(now - added) / SignInExpiry.DAY_MS}d (exact)"
                }
            }
        }
        Text(
            "Sign-in age: $signInAges",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { showDebug = !showDebug }) {
                Text(if (showDebug) "Hide raw response" else "Show last raw response")
            }
            Spacer(Modifier.width(8.dp))
            if (showDebug) {
                OutlinedButton(onClick = {
                    debugProfile = if (debugProfile == Profile.PERSONAL) Profile.WORK else Profile.PERSONAL
                }) { Text(debugProfile.label) }
            }
        }
        if (showDebug) {
            Spacer(Modifier.height(8.dp))
            val raw = repo.snapshot(debugProfile).rawJson
                ?: "(nothing cached yet for ${debugProfile.label})"
            SelectionContainer {
                Text(
                    raw,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(12.dp),
                )
            }
            // Key names (never values) of the last sign-in's token response —
            // settles whether `rate_limit_tier` is in ours (CCRM-38 verify-first).
            Spacer(Modifier.height(8.dp))
            Text(
                "Sign-in token keys: " +
                    (repo.signInTokenKeys(debugProfile) ?: "(no native sign-in recorded yet)"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    EndpointProbe(repo)
}

/**
 * Paths worth trying first, in order. The first is a control: it is the endpoint we
 * already read successfully, so a non-200 there means the probe itself is broken and
 * nothing below it can be trusted. `bootstrap` is included because the org uuid the
 * balance path needs appears nowhere in our own payload.
 */
private val PROBE_PRESETS = listOf(
    "/api/oauth/usage",
    "/api/bootstrap",
    "/api/organizations",
    "/api/account",
)

/**
 * Endpoint probe (CCBG-6). GETs a path on an allowlisted host with a profile's token and
 * shows status + body.
 *
 * It exists because the credit **balance** the Claude app displays is not in the payload
 * we read: its APK fetches `organizations/{uuid}/usage` on `api.claude.ai`, while we read
 * `/api/oauth/usage` on `api.anthropic.com`, where `spend.balance` is permanently null.
 * The open question is whether our subscription OAuth token authenticates there at all.
 *
 * Deliberately GET-only, host-allowlisted, and non-caching — see `ApiClient.probe` and
 * `UsageRepository.probeEndpoint`. Request headers are never rendered, because the output
 * is meant to be copied out and the bearer token must not ride along with it.
 */
@Composable
private fun EndpointProbe(repo: UsageRepository) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(Profile.PERSONAL) }
    var host by remember { mutableStateOf(ApiClient.ProbeHost.CLAUDE_AI) }
    var path by remember { mutableStateOf(PROBE_PRESETS.first()) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    SectionCard {
        Text("Endpoint probe", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "GET only, on an allowlisted host, with this profile's token. Nothing is " +
                "parsed or cached. Skim the body for an org id or email before sharing it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                profile = if (profile == Profile.PERSONAL) Profile.WORK else Profile.PERSONAL
            }) { Text(profile.label) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                host = if (host == ApiClient.ProbeHost.CLAUDE_AI) {
                    ApiClient.ProbeHost.ANTHROPIC
                } else {
                    ApiClient.ProbeHost.CLAUDE_AI
                }
            }) { Text(host.origin.removePrefix("https://")) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("Path") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        FlowRow {
            PROBE_PRESETS.forEach { preset ->
                FilterChip(
                    selected = path == preset,
                    onClick = { path = preset },
                    label = { Text(preset, style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !running,
            onClick = {
                scope.launch {
                    running = true
                    result = "Probing ${host.origin}$path …"
                    val resp = repo.probeEndpoint(profile, host, path)
                    result = when (resp) {
                        null -> "No credentials for ${profile.label}"
                        else -> "GET ${host.origin}$path\nHTTP ${resp.code}\n\n${resp.body}"
                    }
                    running = false
                }
            },
        ) { Text(if (running) "Probing…" else "Probe") }
        result?.let { text ->
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(12.dp),
                )
            }
        }
    }
}

// --- Token guide screen ---

@Composable
fun TokenGuideScreen() {
    NoteCard(
        "Easiest way: on the account card, tap \"Sign in on this phone\". It opens " +
            "Claude's sign-in in your browser and needs no computer. This page is the " +
            "backup method — copying a token from a computer — for when that isn't handy.",
        positive = true,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        "The backup method reads your usage with the same sign-in Claude Code uses, " +
            "so you need Claude Code installed and signed in on your computer.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("macOS", "Windows", "Linux")
    TabRow(selectedTabIndex = tab) {
        tabs.forEachIndexed { index, label ->
            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
        }
    }
    Spacer(Modifier.height(16.dp))

    when (tab) {
        0 -> {
            GuideStep(1, "Open Terminal and run:")
            CodeBlock("security find-generic-password -s \"Claude Code-credentials\" -w")
            GuideStep(2, "Copy the JSON it prints (starts with {\"claudeAiOauth\": …).")
        }
        1 -> {
            GuideStep(1, "Press Win+R, paste this path, and press Enter:")
            CodeBlock("%USERPROFILE%\\.claude\\.credentials.json")
            GuideStep(2, "The file opens (pick Notepad if asked). Select all and copy.")
        }
        else -> {
            GuideStep(1, "Open a terminal and run:")
            CodeBlock("cat ~/.claude/.credentials.json")
            GuideStep(2, "Copy the JSON it prints.")
        }
    }
    GuideStep(3, "Get it to this phone — Link to Windows clipboard sync, Quick Share, KDE Connect, or any channel you trust.")
    GuideStep(4, "Come back to Settings and tap \"Paste from clipboard\" on the account you're setting up.")

    Spacer(Modifier.height(20.dp))
    Text("Faster: scan a QR code", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        "If the computer has Node.js, skip the copying entirely: show the token as a " +
            "QR code in the terminal and tap \"Scan QR\" on the account card. The token " +
            "goes straight from the screen to this phone — it never touches a clipboard " +
            "or a chat app.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    // Windows/Linux read .credentials.json, which can hold other logins too
    // (MCP tokens etc.) and outgrow a QR code's ~2.9 KB capacity — so those
    // commands extract just the claudeAiOauth object. Piping to stdin also
    // dodges PowerShell 5.1 mangling quotes in native-command arguments.
    CodeBlock(
        when (tab) {
            0 -> "npx -y qrcode-terminal \"$(security find-generic-password -s 'Claude Code-credentials' -w)\""
            1 -> "(Get-Content \"\$env:USERPROFILE\\.claude\\.credentials.json\" -Raw | ConvertFrom-Json).claudeAiOauth | ConvertTo-Json -Compress | npx -y qrcode-terminal"
            else -> "jq -c .claudeAiOauth ~/.claude/.credentials.json | npx -y qrcode-terminal"
        }
    )
    Text(
        when (tab) {
            1 -> "Run it in PowerShell. If the square is too big for the window, shrink the font (Ctrl+minus) until it all fits, then scan."
            2 -> "Needs jq (usually preinstalled). If the square is too big for the window, shrink the terminal font (Ctrl+minus) until it all fits, then scan."
            else -> "If the square is too big for the window, shrink the terminal font (Cmd+minus) until it all fits, then scan. " +
                "If you see \"code length overflow\", filter it through jq -c .claudeAiOauth first."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp),
    )

    Spacer(Modifier.height(20.dp))
    Text("When tokens expire", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        "The access token only lasts hours, but the app renews it automatically in the " +
            "background — the account card shows the countdown and when it last renewed.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "If you signed in on the phone, that sign-in is yours alone — nothing on a " +
            "computer can rotate it away. It lasts about a month, then the card shows " +
            "\"Needs re-auth\"; just tap \"Re-sign in\" and sign in again. A one-minute, " +
            "phone-only refresh.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "The backup (computer-token) method has a catch: the phone holds a copy of the " +
            "computer's sign-in, and when Claude Code on the computer renews itself, " +
            "Anthropic can rotate the tokens so the phone's copy stops working. If that " +
            "keeps happening, switch to \"Sign in on this phone\" — it avoids the problem " +
            "entirely.",
        style = MaterialTheme.typography.bodyMedium,
    )

    Spacer(Modifier.height(20.dp))
    NoteCard(
        "Your token stays on this device, encrypted with the Android Keystore. " +
            "It's sent only to Anthropic's API — there are no other servers.",
        positive = true,
    )
    Spacer(Modifier.height(8.dp))
    NoteCard(
        "Treat the token like a password — it grants access to your Claude account.",
        positive = false,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun GuideStep(number: Int, text: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CodeBlock(code: String) {
    SelectionContainer {
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, top = 2.dp, bottom = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(10.dp),
        )
    }
}

@Composable
private fun NoteCard(text: String, positive: Boolean) {
    val dark = isSystemInDarkTheme()
    val tint = if (positive) {
        if (dark) Color(0xFF81C995) else Color(0xFF188038)
    } else {
        if (dark) Color(0xFFFDD663) else Color(0xFF9A6700)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            modifier = Modifier.padding(12.dp),
        )
    }
}

// --- shared bits ---

/** One "window kind" line in Usage warnings: label left, multi-select percent chips right. */
@Composable
private fun ThresholdChipsRow(
    label: String,
    options: List<Int>,
    initial: Set<Int>,
    onChange: (Set<Int>) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        for (pct in options) {
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = pct in selected,
                onClick = {
                    selected = if (pct in selected) selected - pct else selected + pct
                    onChange(selected)
                },
                label = { Text("$pct%") },
            )
        }
    }
}

@Composable
private fun ResetModeRow(label: String, window: String, cache: UsageCache) {
    var mode by remember { mutableStateOf(cache.resetPingMode(window)) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        val options = listOf(
            UsageCache.RESET_OFF to "Off",
            UsageCache.RESET_SMART to "If busy",
            UsageCache.RESET_ALWAYS to "Always",
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, text) ->
                SegmentedButton(
                    selected = mode == value,
                    onClick = {
                        mode = value
                        cache.setResetPingMode(window, value)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) { Text(text) }
            }
        }
    }
}

/**
 * The window-ping trace (CCRM-17). Shows the tail in-app and, more usefully, tells you
 * where to pull the whole thing from — the interesting run is an unattended overnight
 * one, so it has to survive being read hours later.
 */
@Composable
private fun PingLogCard() {
    val context = LocalContext.current
    var tail by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        tail = withContext(Dispatchers.IO) {
            try {
                val f = PingLog.file(context)
                if (f.exists()) f.readLines().takeLast(12) else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    SectionCard {
        Text("Window ping log", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Every alarm, decision, send and verification, with Doze state at the moment " +
                "it fired. Pull the full file with:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "adb pull ${PingLog.file(context).absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        Spacer(Modifier.height(10.dp))
        if (tail.isEmpty()) {
            Text(
                "Nothing logged yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (line in tail) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refreshTick++ }) { Text("Reload") }
            OutlinedButton(onClick = {
                PingLog.clear(context)
                refreshTick++
            }) { Text("Clear") }
        }
    }
}

/**
 * Window pings (CCRM-17) — schedule the start of your own 5-hour windows.
 *
 * Off by default, per profile. A ping spends the user's subscription quota on an
 * automated request, so the copy says exactly what it sends and on whose account
 * rather than burying it.
 */
@Composable
private fun WindowPingsSection(
    repo: UsageRepository,
    labels: Map<Profile, String>,
    use24h: Boolean,
) {
    val context = LocalContext.current
    val cacheSettings = repo.cacheSettings()
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(Profile.PERSONAL) }
    var tick by remember { mutableIntStateOf(0) }

    var enabled by remember(profile, tick) { mutableStateOf(cacheSettings.pingEnabled(profile)) }
    var firstMinute by remember(profile, tick) {
        mutableIntStateOf(cacheSettings.pingFirstMinuteOfDay(profile))
    }
    var renewals by remember(profile, tick) { mutableIntStateOf(cacheSettings.pingRenewals(profile)) }
    var cutoff by remember(profile, tick) {
        mutableIntStateOf(cacheSettings.pingCutoffMinuteOfDay(profile))
    }
    var testing by remember { mutableStateOf(false) }
    var exactOk by remember(tick) { mutableStateOf(PingScheduler.canScheduleExact(context)) }

    // The outcome is written by an alarm in another process-entry, so nothing here would
    // otherwise recompose — the row sat on a day-old result while a ping came and went.
    // Poll the revision counter while the section is on screen.
    var outcomeRevision by remember(profile) {
        mutableIntStateOf(cacheSettings.pingOutcomeRevision(profile))
    }
    LaunchedEffect(profile) {
        while (true) {
            kotlinx.coroutines.delay(2_000)
            outcomeRevision = cacheSettings.pingOutcomeRevision(profile)
        }
    }

    fun rearm() = PingScheduler.reschedule(context, profile)

    Text(
        "Starts a 5-hour window when you choose, instead of whenever you happen to send " +
            "your first message. Sends a one-word message to Claude on the selected " +
            "account — it spends a token or two of that account's own quota.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    Text("Account", style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in Profile.entries) {
            FilterChip(
                selected = profile == p,
                onClick = { profile = p },
                label = {
                    Text(
                        if (cacheSettings.pingEnabled(p)) "${labels.getValue(p)} · on"
                        else labels.getValue(p)
                    )
                },
            )
        }
    }
    RowDivider()

    ToggleRow(
        title = "Schedule window pings",
        subtitle = "Off unless you turn it on, separately for each account. Turning it on " +
            "starts a window right away if none is open.",
        checked = enabled,
    ) {
        enabled = it
        cacheSettings.setPingEnabled(profile, it)
        rearm()
    }

    if (!repo.hasCredentials(profile)) {
        Spacer(Modifier.height(8.dp))
        Text(
            "${labels.getValue(profile)} isn't signed in yet, so pings can't run for it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (enabled) {
        RowDivider()
        MinuteOfDayRow("First ping", firstMinute, use24h) {
            firstMinute = it
            cacheSettings.setPingFirstMinuteOfDay(profile, it)
            rearm()
        }

        RowDivider()
        Text("Renewals", style = MaterialTheme.typography.bodyLarge)
        Text(
            "How many more windows to open after the first, each starting when the last one ends",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, text) in listOf(0 to "None", 1 to "1", 2 to "2", 3 to "3")) {
                FilterChip(
                    selected = renewals == value,
                    onClick = {
                        renewals = value
                        cacheSettings.setPingRenewals(profile, value)
                        rearm()
                    },
                    label = { Text(text) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            plannedWindows(firstMinute, renewals, cutoff, use24h),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RowDivider()
        MinuteOfDayRow("Never ping after", cutoff, use24h) {
            cutoff = it
            cacheSettings.setPingCutoffMinuteOfDay(profile, it)
            rearm()
        }
        Text(
            "A hard stop, so a chain that has slipped later in the day can't open a window overnight.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The times above are a target, not a promise: a window's boundaries follow the
        // message that opens it, so the real ones come from the server.
        val liveReset = repo.snapshot(profile).data?.session?.resetsAt
        if (liveReset != null) {
            RowDivider()
            Text(
                "Current window really ends ${Fmt.dayTime(liveReset, use24h)} — the app follows " +
                    "this, not the times above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!exactOk) {
            RowDivider()
            Text(
                "Exact alarms are off",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Pings may fire minutes late. A window starts when the ping lands, so being " +
                    "late shifts every window for the rest of the day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                openExactAlarmSettings(context)
                exactOk = PingScheduler.canScheduleExact(context)
            }) { Text("Allow exact alarms") }
        }

        RowDivider()
        @Suppress("UNUSED_EXPRESSION") outcomeRevision // read so the row recomposes
        val lastResult = cacheSettings.pingLastResult(profile)
        val lastAt = cacheSettings.pingLastAttemptAt(profile)
        if (lastResult != null && lastAt > 0) {
            Text(
                "${Fmt.dayTime(java.time.Instant.ofEpochMilli(lastAt), use24h)} — $lastResult",
                style = MaterialTheme.typography.bodySmall,
                color = if (cacheSettings.pingLastFailed(profile)) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(
            enabled = !testing && repo.hasCredentials(profile),
            onClick = {
                testing = true
                scope.launch {
                    val result = repo.sendWindowPing(profile)
                    // The window isn't visible on the usage endpoint for a minute or
                    // more, so confirmation arrives later on its own alarm (CCBG-5).
                    if (result.sent) {
                        PingScheduler.armVerify(
                            context,
                            profile,
                            System.currentTimeMillis() + PingSchedule.VERIFY_DELAY_MS,
                        )
                    }
                    testing = false
                    tick++
                    rearm()
                }
            },
        ) { Text(if (testing) "Pinging…" else "Test ping now") }
        Spacer(Modifier.height(6.dp))
        Text(
            "Sends one straight away. The window takes a minute or two to show up, so the " +
                "line above updates again once it's confirmed. If a window is already open " +
                "it will say so rather than starting another.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A time-of-day row that opens the platform picker; value is minutes past midnight. */
@Composable
private fun MinuteOfDayRow(title: String, minuteOfDay: Int, use24h: Boolean, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                android.app.TimePickerDialog(
                    context,
                    { _, hour, minute -> onChange(hour * 60 + minute) },
                    minuteOfDay / 60,
                    minuteOfDay % 60,
                    use24h,
                ).show()
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(formatMinuteOfDay(minuteOfDay, use24h), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatMinuteOfDay(minuteOfDay: Int, use24h: Boolean): String {
    val h = (minuteOfDay / 60) % 24
    val m = minuteOfDay % 60
    if (use24h) return "%02d:%02d".format(h, m)
    val suffix = if (h < 12) "AM" else "PM"
    val h12 = when (h % 12) {
        0 -> 12
        else -> h % 12
    }
    return "%d:%02d %s".format(h12, m, suffix)
}

/**
 * The slots the current settings would produce on a clean day. Explicitly a plan:
 * real boundaries follow whenever each ping actually lands.
 */
private fun plannedWindows(firstMinute: Int, renewals: Int, cutoff: Int, use24h: Boolean): String {
    val cutoffMinutes = if (cutoff <= 0) 1440 else cutoff
    val slots = mutableListOf<String>()
    var start = firstMinute
    for (i in 0..renewals) {
        val end = start + 300
        if (start >= cutoffMinutes) break
        slots += "${formatMinuteOfDay(start, use24h)}–${formatMinuteOfDay(end, use24h)}"
        start = end
    }
    if (slots.isEmpty()) return "Nothing would run — the first ping is after the cutoff."
    return "On a clean day: " + slots.joinToString(", ") + "."
}

/** Opens the per-app exact-alarm screen, falling back to app details on odd skins. */
private fun openExactAlarmSettings(context: android.content.Context) {
    val intents = listOf(
        Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
            .setData(android.net.Uri.parse("package:${context.packageName}")),
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}")),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Try the next one; some skins ship neither.
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card { Column(Modifier.fillMaxWidth().padding(16.dp), content = content) }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val dim = if (enabled) 1f else 0.38f
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
