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
import com.robin.claudeusage.data.AuthState
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.UpdateCheck
import com.robin.claudeusage.data.UpdateInfo
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageRepository
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

        SectionLabel("About")
        AboutCard(debugUnlocked, onDebugUnlock)

        if (debugUnlocked) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("Debug")
            TrendDiagnostics(repo, use24h)
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

/** Opens the sign-in URL in a specific browser (full external app, not an in-app tab). */
private fun openInBrowser(context: android.content.Context, url: String, pkg: String?) {
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
    val tokenExpiresAt = remember(stateKey) { repo.tokenExpiresAt(profile) }
    val refreshExpiresAt = remember(stateKey) { repo.refreshExpiresAt(profile) }
    val refreshEstimated = remember(stateKey) { repo.refreshExpiryEstimated(profile) }
    val lastRenewedAt = remember(stateKey) { repo.lastRenewedAt(profile) }
    val backoffUntil = remember(stateKey) { repo.cacheSettings().backoffUntil(profile) }

    // Open a sign-in URL, letting the user pick a browser when they have more than
    // one (so they can route Work vs Personal through different browsers). Always
    // opens a real external browser, never an in-app tab.
    fun openWithPicker(url: String) {
        authUrl = url
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
        openWithPicker(repo.startSignIn(profile))
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
                    PlanChip(plan)
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
                if (refreshExpiresAt > now) {
                    Text(
                        if (refreshEstimated)
                            "Sign-in expires around ${Fmt.dateTime(refreshExpiresAt, use24h)} · ~${Fmt.dhm(refreshExpiresAt)} left"
                        else
                            "Sign-in valid until ${Fmt.dateTime(refreshExpiresAt, use24h)} · ${Fmt.dhm(refreshExpiresAt)} to go",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            title = { Text("Open sign-in with") },
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
private fun PlanChip(plan: String) {
    val color = MaterialTheme.colorScheme.primary
    Text(
        plan.replaceFirstChar { it.uppercase() },
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

@Composable
private fun AboutCard(debugUnlocked: Boolean, onDebugUnlock: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var taps by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateUi?>(null) }
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
            // Flows rather than a Row: the two labels together need ~325dp, which
            // a settings column on a foldable doesn't have. Given a Row the second
            // button wraps its own label to two lines; here it drops to its own
            // line instead, and on a phone the pair still sits side by side.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                        updateResult = UpdateUi.Message(
                            "Email me at $FEEDBACK_EMAIL"
                        )
                    }
                }) { Text("Share feedback") }
                OutlinedButton(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            updateResult = try {
                                val info = withContext(Dispatchers.IO) {
                                    UpdateCheck.fetchLatest(versionName)
                                }
                                UpdateUi.Ok(info)
                            } catch (e: Exception) {
                                UpdateUi.Message(
                                    "Couldn't check for updates. Check your connection and try again."
                                )
                            }
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
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Not affiliated with or endorsed by Anthropic",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
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
                        } else {
                            Text("You're running the latest version (v${info.currentVersion}).")
                        }
                    }
                },
                confirmButton = {
                    if (info.updateAvailable && info.releaseUrl.isNotBlank()) {
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl))
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
                    val bound = Projection.sessionSamples(points, session, 5 * 60 * 60_000L).size
                    val distinct = points.map { it.sessionResetAt }.filter { it > 0 }.distinct().size
                    add("5-hour resets_at ${Fmt.dayTime(java.time.Instant.ofEpochMilli(session), use24h)}")
                    add("  bound to it: $bound · distinct in history: $distinct")
                } else add("5-hour: no resets_at in the payload")
                if (weekly != null) {
                    val bound = Projection.weeklySamples(points, weekly, 7 * 24 * 60 * 60_000L).size
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
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
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
