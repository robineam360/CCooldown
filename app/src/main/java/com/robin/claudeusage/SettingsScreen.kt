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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.robin.claudeusage.data.AuthState
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.UsageRepository
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.widget.BarWidgetReceiver
import com.robin.claudeusage.widget.UsageWidgetReceiver
import com.robin.claudeusage.work.Polling
import kotlinx.coroutines.launch

private const val FEEDBACK_URL = "mailto:robin@eam360.com"
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

    SectionLabel("Accounts")
    for (profile in Profile.entries) {
        TokenCard(repo, profile, use24h, onOpenGuide)
        Spacer(Modifier.height(10.dp))
    }
    Spacer(Modifier.height(14.dp))

    SectionLabel("Polling")
    PollingSection(repo)
    Spacer(Modifier.height(24.dp))

    SectionLabel("Notifications")
    SectionCard {
        var usageAlerts by remember { mutableStateOf(repo.cacheSettings().alertsEnabled()) }
        ToggleRow(
            title = "Usage alerts",
            subtitle = "Notify at 80% and 95% of the 5-hour window, and 90% of the 7-day window",
            checked = usageAlerts,
        ) {
            usageAlerts = it
            repo.cacheSettings().setAlertsEnabled(it)
        }
        RowDivider()
        var resetAlerts by remember { mutableStateOf(repo.cacheSettings().resetAlertsEnabled()) }
        ToggleRow(
            title = "Reset notifications",
            subtitle = "Notify when a window resets and Claude is fresh again",
            checked = resetAlerts,
        ) {
            resetAlerts = it
            repo.cacheSettings().setResetAlertsEnabled(it)
        }
        RowDivider()
        var authAlerts by remember { mutableStateOf(repo.cacheSettings().authAlertsEnabled()) }
        ToggleRow(
            title = "Token & data health alerts",
            subtitle = "Notify when a token expires soon or stops working, and when data stays stale for hours",
            checked = authAlerts,
        ) {
            authAlerts = it
            repo.cacheSettings().setAuthAlertsEnabled(it)
        }
        RowDivider()
        LinkRow("System notification settings") {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
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
        DebugSection(repo)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun TokenCard(
    repo: UsageRepository,
    profile: Profile,
    use24h: Boolean,
    onOpenGuide: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var stateKey by remember { mutableIntStateOf(0) }

    val hasToken = remember(stateKey) { repo.hasCredentials(profile) }
    val snapshot = remember(stateKey) { repo.snapshot(profile) }
    val addedAt = remember(stateKey) { repo.tokenAddedAt(profile) }
    val tail = remember(stateKey) { repo.tokenTail(profile) }
    val plan = remember(stateKey) { repo.plan(profile) }
    val tokenExpiresAt = remember(stateKey) { repo.tokenExpiresAt(profile) }
    val refreshExpiresAt = remember(stateKey) { repo.refreshExpiresAt(profile) }
    val lastRenewedAt = remember(stateKey) { repo.lastRenewedAt(profile) }
    val backoffUntil = remember(stateKey) { repo.cacheSettings().backoffUntil(profile) }

    fun saveToken(text: String) {
        scope.launch {
            busy = true
            message = null
            val result = repo.validateAndSave(profile, text)
            message = if (result.message == "OK") {
                Polling.schedulePeriodic(context, repo.cacheSettings().pollIntervalMinutes())
                "${profile.label} token added — usage fetched, polling started."
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
                Text(profile.label, style = MaterialTheme.typography.titleMedium)
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

            if (!hasToken) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "No token yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(enabled = !busy, onClick = { pasteAndSave() }) { Text("Paste from clipboard") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = !busy, onClick = { scanAndSave() }) { Text("Scan QR") }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onOpenGuide) { Text("How do I get my token?") }
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
                Row {
                    OutlinedButton(enabled = !busy, onClick = { pasteAndSave() }) { Text("Replace") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = !busy, onClick = { scanAndSave() }) { Text("Scan QR") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            repo.clearCredentials(profile)
                            message = "${profile.label} token cleared."
                            stateKey++
                        },
                    ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                }
            }

            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
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
    var taps by remember { mutableIntStateOf(0) }
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
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_URL)))
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
    Text(
        "The app reads your usage with the same sign-in Claude Code uses. " +
            "You need Claude Code installed and signed in on your computer.",
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
            "background — the account card shows the countdown and when it last renewed. " +
            "There's one catch: your phone holds a copy of the computer's sign-in. When " +
            "Claude Code on the computer renews itself (which happens during normal use), " +
            "Anthropic can rotate the tokens and the phone's copy stops working. The card " +
            "then shows \"Needs re-auth\" and you'll get a notification (if health alerts " +
            "are on) — the fix is the same four steps above.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Re-pasting often? Give the phone its own sign-in (one-time, works on every OS): " +
            "quit Claude Code and park the computer's sign-in — on Windows/Linux rename " +
            ".credentials.json to .credentials.backup.json; on a Mac back up the " +
            "\"Claude Code-credentials\" Keychain item to a file and delete the item. " +
            "Run claude, sign in again, and scan/paste that fresh token into this app. " +
            "Then restore the computer's original sign-in (rename the file back, or " +
            "delete the new Keychain item and re-add the backup). The phone then renews " +
            "independently of the computer, permanently. Don't run claude between the " +
            "scan and the restore. Full copy-paste commands are in the USER-GUIDE.",
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
