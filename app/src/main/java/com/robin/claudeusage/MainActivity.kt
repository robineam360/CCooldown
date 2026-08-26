package com.robin.claudeusage

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.glance.appwidget.updateAll
import android.text.format.DateFormat
import com.robin.claudeusage.data.ErrorKind
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.ProfileRegistry
import com.robin.claudeusage.ping.PingScheduler
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.UsageRepository
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.ui.BarGeometry
import com.robin.claudeusage.ui.ChartColumnMaxWidth
import com.robin.claudeusage.ui.ContentColumn
import com.robin.claudeusage.ui.ContentMaxWidth
import com.robin.claudeusage.ui.EstimateLine
import com.robin.claudeusage.ui.ProvenanceNote
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.LocalWidthClass
import com.robin.claudeusage.ui.LocalWindowHeight
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.ui.ProvideWidthClass
import com.robin.claudeusage.ui.UsageSparkline
import com.robin.claudeusage.ui.WideMaxWidth
import com.robin.claudeusage.ui.chartHeight
import com.robin.claudeusage.ui.hasTwoColumns
import com.robin.claudeusage.ui.PACE_DEAD_ZONE
import com.robin.claudeusage.ui.elapsedPercent
import com.robin.claudeusage.ui.Motion
import com.robin.claudeusage.ui.LocalAppDark
import com.robin.claudeusage.ui.appDark
import com.robin.claudeusage.ui.resolve24h
import com.robin.claudeusage.ui.resolveDark
import com.robin.claudeusage.ui.twoPane
import com.robin.claudeusage.widget.BarWidget
import com.robin.claudeusage.widget.UsageWidget
import com.robin.claudeusage.work.Polling
import java.util.Locale
import kotlin.math.roundToInt
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Window pings (CCRM-17) are hard-disabled (ToS — see UsageCache.pingEnabled);
        // reschedule here so an alarm armed by an older build is cancelled on first
        // open instead of waiting for the next poll.
        PingScheduler.rescheduleAll(this)
        val startProfile =
            ProfileRegistry(this).resolve(intent?.getStringExtra("profile"))
        // CCRM-33 (App Shortcuts): the "Refresh now" shortcut opens the app with
        // this extra — a manual poll of every signed-in account, the same path
        // the widgets' ↻ takes.
        if (intent?.getBooleanExtra("refresh", false) == true) {
            Polling.refreshOnce(this, manual = true)
        }
        // Republish so shortcut labels track renamed profiles even when the
        // rename happened on another device restore or a cleared cache.
        Shortcuts.publish(this)
        // Measured at the root so every screen sees the same window width, and so
        // it re-measures on a fold/unfold without the activity being torn down.
        setContent { ProvideWidthClass { App(startProfile) } }
    }
}

private enum class Screen { MAIN, SETTINGS, GUIDE, HISTORY }

/**
 * CCRM-6 (Multi-Account): profile tab strips stay a fixed [TabRow] up to this many accounts
 * and become a [ScrollableTabRow] beyond it. Shared by the main screen and the History
 * screen so the two strips can never disagree. See the wireframe's state 5d–5f for the
 * measurement this comes from.
 */
internal const val FIXED_TAB_LIMIT = 3

/**
 * Window lengths, used both to scale the chart's x axis and to bind history to it.
 * Aliased from [Projection] so the lengths the drift tolerance derives from (CCBG-4)
 * have a single definition.
 */
private const val SESSION_MS: Long = Projection.SESSION_MS
private const val WEEKLY_MS: Long = Projection.WEEKLY_MS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(startProfile: Profile) {
    val context = LocalContext.current
    val repo = remember { UsageRepository(context) }
    val cache = remember { repo.cacheSettings() }
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    // Deliberately not persisted: the debug easter egg re-locks on every launch.
    var debugUnlocked by remember { mutableStateOf(false) }
    var themeName by remember { mutableStateOf(cache.themeColorName()) }
    // CCRM-29 (Display Mode): the two three-way modes, hoisted so the Settings
    // chips recompose the whole app; the booleans every render site reads are
    // derived below.
    var themeMode by remember { mutableStateOf(cache.themeMode()) }
    var timeFormat by remember { mutableStateOf(cache.timeFormat()) }
    val use24h = resolve24h(timeFormat, DateFormat.is24HourFormat(context))
    // CCRM-22 (Used or Left): hoisted like use24h so flipping the chips in
    // Settings recomposes every readout behind them.
    var usageLeft by remember { mutableStateOf(cache.usageLeft()) }
    // CCRM-23 (Reset Display): same hoisting for which reset form leads.
    var resetClock by remember { mutableStateOf(cache.resetClock()) }
    // Hoisted like use24h so flipping the toggle in Settings recomposes the bars
    // behind it — CCRM-43 (Bar Pace Marks) gates the in-app red separately from the
    // widgets' and the notification's.
    var paceOverInApp by remember { mutableStateOf(cache.paceOverInApp()) }
    var tick by remember { mutableIntStateOf(0) }

    // Ticks every few seconds so "updated Xm ago" and background results stay fresh.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            tick++
        }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        if (repo.configuredProfiles().isNotEmpty()) {
            Polling.schedulePeriodic(context, cache.pollIntervalMinutes())
            val stalest = repo.configuredProfiles()
                .minOfOrNull { repo.snapshot(it).fetchedAt } ?: 0L
            if (System.currentTimeMillis() - stalest > 180_000) {
                Polling.refreshOnce(context, manual = false)
            }
        }
    }

    fun goBack() {
        screen = if (screen == Screen.GUIDE) Screen.SETTINGS else Screen.MAIN
    }

    // System back walks the screen stack instead of exiting the app.
    BackHandler(enabled = screen != Screen.MAIN) { goBack() }

    val dark = resolveDark(themeMode, isSystemInDarkTheme())
    // A forced theme diverges from the system theme that the manifest's
    // windowLightStatusBar attribute follows (CCBG-13), so the icons are set
    // programmatically from the resolved mode.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? android.app.Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = !dark
            }
        }
    }
    val scheme = when {
        themeName == Palette.DYNAMIC && dark -> dynamicDarkColorScheme(context)
        themeName == Palette.DYNAMIC -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Palette.color(themeName, true),
            onPrimary = Color(0xFF1F1F1F),
        )
        else -> lightColorScheme(
            primary = Palette.color(themeName, false),
            onPrimary = Color.White,
        )
    }

    CompositionLocalProvider(LocalAppDark provides dark) {
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                when (screen) {
                                    Screen.SETTINGS -> "Settings"
                                    Screen.GUIDE -> "Get your token"
                                    Screen.HISTORY -> "Usage history"
                                    Screen.MAIN -> "Claude Cooldown"
                                }
                            )
                        },
                        navigationIcon = {
                            if (screen != Screen.MAIN) {
                                IconButton(onClick = { goBack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        },
                        actions = {
                            if (screen == Screen.MAIN) {
                                IconButton(onClick = { screen = Screen.HISTORY }) {
                                    Icon(Icons.Filled.DateRange, contentDescription = "Usage history")
                                }
                                IconButton(onClick = { screen = Screen.SETTINGS }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                            }
                        },
                    )
                },
            ) { innerPadding ->
                // Settings and History lay their own content out in columns when
                // there's room, so they get the wider cap — but only when they'll
                // actually use it, or the cap would just stretch one column. The
                // guide is prose and stays at one readable measure at any size.
                val contentWidth = when (screen) {
                    Screen.SETTINGS -> if (hasTwoColumns()) WideMaxWidth else ContentMaxWidth
                    Screen.HISTORY -> if (LocalWidthClass.current.twoPane) WideMaxWidth else ContentMaxWidth
                    else -> ContentMaxWidth
                }
                when (screen) {
                    Screen.MAIN ->
                        ProfileTabs(
                            repo, use24h, usageLeft, resetClock, paceOverInApp, tick,
                            startProfile, { screen = Screen.SETTINGS },
                            Modifier.padding(innerPadding),
                        )
                    else -> ContentColumn(
                        modifier = Modifier.padding(innerPadding),
                        maxWidth = contentWidth,
                    ) {
                        Spacer(Modifier.height(8.dp))
                        if (screen == Screen.HISTORY) {
                            HistoryScreen(repo, tick)
                        } else if (screen == Screen.SETTINGS) {
                            SettingsScreen(
                                repo = repo,
                                use24h = use24h,
                                themeMode = themeMode,
                                onThemeMode = { themeMode = it },
                                timeFormat = timeFormat,
                                onTimeFormat = { timeFormat = it },
                                usageLeft = usageLeft,
                                onUsageLeft = { usageLeft = it },
                                resetClock = resetClock,
                                onResetClock = { resetClock = it },
                                paceOverInApp = paceOverInApp,
                                onPaceOverInApp = { paceOverInApp = it },
                                themeName = themeName,
                                onTheme = { themeName = it },
                                debugUnlocked = debugUnlocked,
                                onDebugUnlock = { debugUnlocked = true },
                                onOpenGuide = { screen = Screen.GUIDE },
                                refreshWidgets = {
                                    scope.launch {
                                        try {
                                            UsageWidget().updateAll(context)
                                            BarWidget().updateAll(context)
                                            com.robin.claudeusage.widget.RingWidget().updateAll(context)
                                            com.robin.claudeusage.widget.MiniRingsWidget().updateAll(context)
                                            com.robin.claudeusage.widget.PaceWidget().updateAll(context)
                                        } catch (_: Exception) {
                                        }
                                    }
                                },
                            )
                        } else {
                            TokenGuideScreen()
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun ProfileTabs(
    repo: UsageRepository,
    use24h: Boolean,
    usageLeft: Boolean,
    resetClock: Boolean,
    showOverPace: Boolean,
    tick: Int,
    startProfile: Profile,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    // CCRM-6 (Multi-Account): only accounts with a token get a tab. Someone with one
    // account sees no strip at all and gains its ~48 dp; a new account appears the moment
    // its token lands. Signing in stays in Settings, so a tab is never the route to it.
    // At zero we fall back to the registry's first account, which shows its own empty state.
    val profiles = repo.configuredProfiles().ifEmpty { listOf(repo.registry().first()) }
    // One profile at a time at every width, tabs and swipe included. A wide window used
    // to split into two side-by-side profile panes, on the theory that both accounts at
    // once was the point — but each pane then drew a chart no wider than the one on the
    // cover screen, so unfolding cost a gesture and bought nothing. The question you
    // open this app with is how much is left on the account you're about to spend, and
    // both-at-once already has a better home on the home screen: the widgets. See
    // CCRM-20.
    val pagerState = rememberPagerState(
        initialPage = profiles.indexOf(startProfile).coerceAtLeast(0),
        pageCount = { profiles.size },
    )
    // The list shrinks when an account is signed out or removed, and the pager's remembered
    // page outlives it. Clamp rather than index past the end.
    LaunchedEffect(profiles.size) {
        if (pagerState.currentPage > profiles.lastIndex) {
            pagerState.scrollToPage(profiles.lastIndex.coerceAtLeast(0))
        }
    }
    val scope = rememberCoroutineScope()
    // Read inside the click, never captured at composition: the user can flip the
    // system animation setting while the app is open, and the very next tab press
    // must honour the new answer.
    val motionContext = LocalContext.current

    Column(modifier = modifier.fillMaxSize()) {
        // One tab is no choice, so the strip disappears entirely rather than showing a lone
        // tab with nothing to switch to.
        if (profiles.size > 1) {
            // Clamped at the call site as well as in the effect above: the effect runs
            // after this composition, so for one frame after an account disappears the
            // pager's page can still be past the end — and TabRow's indicator indexes
            // straight into its tab positions with whatever it is handed.
            val selected = pagerState.currentPage.coerceIn(0, profiles.lastIndex)
            val tabs: @Composable () -> Unit = {
                profiles.forEachIndexed { index, profile ->
                    Tab(
                        selected = selected == index,
                        onClick = {
                            scope.launch {
                                // The zero-duration limit of the same page turn — identical
                                // landing, no travel — per CCRM-32 (Reduce Motion).
                                if (Motion.reduced(Motion.scale(motionContext))) {
                                    pagerState.scrollToPage(index)
                                } else {
                                    pagerState.animateScrollToPage(index)
                                }
                            }
                        },
                        text = { Text(repo.cacheSettings().profileLabel(profile)) },
                    )
                }
            }
            // CCRM-6 (Multi-Account): fixed up to three, scrollable at four or more, at
            // every width. Three tabs get ~133 dp on a 400 dp screen and a 16-character
            // label needs ~110, so it fits; four would get 82 dp on the cover screen and
            // every label — including the selected one — would truncate. One rule at both
            // widths deliberately, so the strip doesn't change shape when the phone unfolds.
            if (profiles.size <= FIXED_TAB_LIMIT) {
                TabRow(selectedTabIndex = selected) { tabs() }
            } else {
                ScrollableTabRow(selectedTabIndex = selected, edgePadding = 0.dp) { tabs() }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
        ) { page ->
            ContentColumn(maxWidth = ChartColumnMaxWidth) {
                Spacer(Modifier.height(16.dp))
                ProfileScreen(
                    repo, profiles[page], use24h, usageLeft, resetClock, showOverPace,
                    tick, onOpenSettings,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Claude-style bar: light tint track, solid fill, fully rounded — plus the pace
 * marks (CCRM-43 (Bar Pace Marks)): the neutral even-pace tick and, past the dead
 * zone, the red over-pace segment.
 *
 * [elapsedPercent] null → no marks at all: either there is no reset clock to derive
 * even pace from, or this is a credits row, which has no clock by definition.
 * [showOverPace] is the Settings toggle and gates only the red; the tick always
 * draws.
 *
 * The outer Box is deliberately *not* clipped and is taller than the bar: the tick
 * overhangs the bar by 0.3h top and bottom, so a clip here would shear it off. The
 * track and fill carry their own rounded clips instead.
 */
@Composable
private fun UsageBarLine(
    percent: Double?,
    fillColor: Color,
    height: androidx.compose.ui.unit.Dp = 12.dp,
    elapsedPercent: Double? = null,
    showOverPace: Boolean = true,
) {
    val fraction = ((percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val overhang = height * 0.3f
    val segment = BarGeometry.redSegment(percent, elapsedPercent, showOverPace)
    val tick = BarGeometry.showTick(percent, elapsedPercent)
    val tickColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (appDark()) 0.60f else 0.48f,
    )
    val redColor = Palette.barColor(100.0, fillColor, appDark())

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height + overhang * 2),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(fillColor.copy(alpha = 0.25f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height / 2))
                    .background(fillColor),
            ) {
                // The red rides *inside* the fill's clip, which is what makes the
                // boundary between the two colours a straight vertical edge and lets
                // the red cover the fill's rounded tip (wireframe rev B). Offsetting
                // by the segment's start keeps it beginning exactly on the pace line.
                if (segment != null) {
                    val (start, end) = segment
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (end > 0f) 1f - start / end else 0f)
                            .fillMaxHeight()
                            .align(Alignment.CenterEnd)
                            .background(redColor),
                    )
                }
            }
        }
        if (tick) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = BarGeometry.tickWidth(height.toPx())
                val cx = size.width * BarGeometry.tickFraction(elapsedPercent!!)
                drawRoundRect(
                    color = tickColor,
                    topLeft = Offset(cx - w / 2f, 0f),
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(w / 2f),
                )
            }
        }
    }
}

@Composable
private fun ResetRow(window: UsageWindow?, use24h: Boolean, resetClock: Boolean) {
    // A window with no reset time hasn't started yet (0% and idle).
    if (window?.resetsAt == null) {
        Text(
            "Starts when a message is sent",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // CCRM-23 (Reset Display), Option A: the chosen form leads, the other keeps
    // the second slot — the token decides order here, never presence.
    val countdown = "Resets ${Fmt.relIn(window.resetsAt)}"
    val clock = "Resets at ${Fmt.dayTime(window.resetsAt, use24h)}"
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            if (resetClock) clock else countdown,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (resetClock) countdown else clock,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun barFill(percent: Double?): Color =
    Palette.barColor(percent, MaterialTheme.colorScheme.primary, appDark())

@Composable
private fun ProfileScreen(
    repo: UsageRepository,
    profile: Profile,
    use24h: Boolean,
    usageLeft: Boolean,
    resetClock: Boolean,
    showOverPace: Boolean,
    tick: Int,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var snapshot by remember(profile) { mutableStateOf(repo.snapshot(profile)) }
    LaunchedEffect(tick, profile) { snapshot = repo.snapshot(profile) }
    val data = snapshot.data
    // Re-read the history file only when a new fetch lands, not on every tick.
    val history = remember(profile, snapshot.fetchedAt) { repo.history().points(profile) }

    if (!repo.hasCredentials(profile)) {
        val label = repo.cacheSettings().profileLabel(profile)
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("No $label account yet", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Open Settings and tap \"Sign in on this phone\" for the " +
                        "$label account. It opens Claude's sign-in in your " +
                        "browser — no computer needed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }

    if (data == null) {
        Text("No data yet — try Refresh now.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
    } else {
        Card {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("5-hour window", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        Fmt.usageWorded(data.session?.percent, usageLeft),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(8.dp))
                UsageBarLine(
                    percent = data.session?.percent,
                    fillColor = barFill(data.session?.percent),
                    elapsedPercent = elapsedPercent(data.session, SESSION_MS),
                    showOverPace = showOverPace,
                )
                data.session?.let { w ->
                    TrendBlock(
                        window = w,
                        samples = w.resetsAt?.let {
                            Projection.sessionSamples(history, it.toEpochMilli(), SESSION_MS)
                        } ?: emptyList(),
                        windowLengthMs = SESSION_MS,
                        use24h = use24h,
                        usageLeft = usageLeft,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ResetRow(data.session, use24h, resetClock)
            }
        }
        Spacer(Modifier.height(12.dp))

        Card {
            Column(Modifier.padding(16.dp)) {
                Text("7-day window", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                SubBar("All models", data.weekly, usageLeft, showOverPace)
                for (cap in data.modelCaps) {
                    SubBar(cap.modelName, cap.window, usageLeft, showOverPace)
                }
                data.weekly?.let { w ->
                    TrendBlock(
                        window = w,
                        samples = w.resetsAt?.let {
                            Projection.weeklySamples(history, it.toEpochMilli(), WEEKLY_MS)
                        } ?: emptyList(),
                        windowLengthMs = WEEKLY_MS,
                        use24h = use24h,
                        usageLeft = usageLeft,
                    )
                }
                Spacer(Modifier.height(2.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                ResetRow(data.weekly, use24h, resetClock)
            }
        }

        // Pay-as-you-go credits. Shown once there is either a cap or real spend to
        // report — an account with neither has no credit budget, and "$0.00 of $0.00"
        // tells nobody anything. The *limit* is not the existence test: switching the
        // monthly cap off leaves real spend with no ceiling (CCBG-9).
        val credits = data.credits
            ?.takeIf { it.isReportable && repo.cacheSettings().creditsVisible(profile) }
        if (credits != null) {
            val pct = credits.percent
            // The binding constraint, not the monthly remainder: identical while the
            // server reports no balance, but the day it does, "left" must mean the
            // smaller of the two ceilings (CCBG-6).
            val remaining = credits.bindingRemainingMinor
            Spacer(Modifier.height(12.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    var creditsProv by remember { mutableStateOf(false) }
                    // Same shape as the 5-hour card: name on the left, the headline
                    // percentage on the right, bar underneath. With no cap there is no
                    // percentage and no bar — just what has been spent.
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Usage credits", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (credits.limitMinor != null) {
                                "${Fmt.money(credits.usedMinor, credits.exponent, credits.currency)} / " +
                                    Fmt.money(credits.limitMinor, credits.exponent, credits.currency)
                            } else {
                                "${Fmt.money(credits.usedMinor, credits.exponent, credits.currency)} spent"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // CCRM-30 (Estimate Honesty): the percentage is computed
                        // locally, so it carries the marker — with a note that says
                        // it's *finer* than the server's figure, not a hedge.
                        Text(
                            // The rounded display percent, not the exact one — credits
                            // round where windows truncate (CCRM-3, deliberate).
                            if (pct != null) buildAnnotatedString {
                                append(Fmt.usageWorded(credits.percentDisplay?.toDouble(), usageLeft))
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.7f),
                                    )
                                ) { append(" ⓘ") }
                            } else AnnotatedString("No cap"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable(enabled = pct != null) {
                                creditsProv = !creditsProv
                            },
                        )
                    }
                    if (pct != null) {
                        Spacer(Modifier.height(8.dp))
                        // No elapsed, so no pace mark: credits are money, and money
                        // has no clock. Spending them faster than the month isn't a
                        // thing to be behind or ahead of.
                        UsageBarLine(pct, barFill(pct))
                    }
                    if (creditsProv) {
                        Spacer(Modifier.height(6.dp))
                        ProvenanceNote(
                            "Computed from the exact amounts — finer than the " +
                                "server's rounded figure, not an estimate.",
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            remaining == null ->
                                "No monthly spend limit — credits cover you when you hit your plan limits"
                            remaining > 0L ->
                                "${Fmt.money(remaining, credits.exponent, credits.currency)} left · " +
                                    if (credits.limitMinor != null) {
                                        "covers you when you hit your plan limits"
                                    } else {
                                        // Only reachable once the server reports a balance
                                        // for an uncapped account — the balance is then the
                                        // one ceiling that exists.
                                        "no monthly spend limit"
                                    }
                            else -> "All credits spent — nothing left to cover plan overruns"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remaining == null || remaining > 0L) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !refreshing,
            onClick = {
                scope.launch {
                    refreshing = true
                    message = null
                    val result = repo.refreshNow(profile, manual = true)
                    message = if (result.message == "OK") null else result.message
                    refreshing = false
                    snapshot = repo.snapshot(profile)
                }
            },
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Refresh now")
        }
        if (refreshing) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    message?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Last success: ${Fmt.dayTimeWithAgo(snapshot.fetchedAt, use24h)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Last attempt: ${Fmt.dayTimeWithAgo(snapshot.lastAttemptAt, use24h)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (snapshot.lastStatus != "OK") {
        Spacer(Modifier.height(6.dp))
        ErrorNotice(
            snapshot = snapshot,
            backoffUntil = repo.cacheSettings().backoffUntil(profile),
            use24h = use24h,
            onOpenSettings = onOpenSettings,
        )
    }
}

/**
 * CCRM-27 (Error Taxonomy): the typed failure as a tinted notice row — copy that
 * names the fix up top, the raw status as the small evidence line, and the one
 * action that actually helps for this kind. Renders *beside* the retained
 * last-good cards above, never in place of them.
 */
@Composable
private fun ErrorNotice(
    snapshot: com.robin.claudeusage.data.Snapshot,
    backoffUntil: Long,
    use24h: Boolean,
    onOpenSettings: () -> Unit,
) {
    val kind = ErrorKind.fromKey(snapshot.lastStatusKind)
    val context = LocalContext.current
    val tint = if (kind.severe) MaterialTheme.colorScheme.error else barFill(95.0)
    val detail = buildString {
        append(snapshot.lastStatus)
        // The retry moment comes from the backoff clock the app already keeps —
        // printed, not invented (CCRM-26 (Quick Links)'s honesty rule).
        if (kind == ErrorKind.RATE_LIMITED && backoffUntil > System.currentTimeMillis()) {
            append(" · next try ~${Fmt.timeOnly(Instant.ofEpochMilli(backoffUntil), use24h)}")
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.11f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                kind.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = tint.copy(alpha = 0.8f),
            )
            when (kind) {
                // CCRM-26 (Quick Links): the "is it me or is it them" escape stays,
                // now scoped to the kinds where it answers the question.
                ErrorKind.NETWORK, ErrorKind.SERVER ->
                    TextButton(onClick = { openInBrowser(context, ANTHROPIC_STATUS_URL, null) }) {
                        Text("Check Anthropic status")
                    }
                ErrorKind.AUTH ->
                    TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                ErrorKind.INVALID_RESPONSE ->
                    TextButton(onClick = onOpenSettings) { Text("Check for updates") }
                else -> {}
            }
        }
    }
}

/**
 * The burn-rate view for one window: a sparkline of this window instance's
 * fetches (dashed tail = extrapolation) and a plain-words projection line.
 *
 * When there isn't enough signal to project honestly, it says so rather than
 * rendering nothing — a silently missing chart is indistinguishable from a broken
 * one, which is exactly how it read before.
 */
@Composable
private fun TrendBlock(
    window: UsageWindow,
    samples: List<Pair<Long, Double>>,
    windowLengthMs: Long,
    use24h: Boolean,
    usageLeft: Boolean,
) {
    val resetMs = window.resetsAt?.toEpochMilli() ?: return
    if (samples.size < 2) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Not enough history in this window yet to chart a pace",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val est = Projection.estimate(samples, resetMs)
    val atLimit = (window.percent ?: 0.0) >= 100.0

    Spacer(Modifier.height(10.dp))
    // Measured here rather than derived from the window width: the chart sits inside a
    // card inside a capped column, so only this box knows what it actually got.
    val windowHeight = LocalWindowHeight.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        UsageSparkline(
            samples = samples,
            windowStartMs = resetMs - windowLengthMs,
            windowEndMs = resetMs,
            projectedEnd = est?.let { e ->
                if (e.hitsLimitAtMs != null) e.hitsLimitAtMs to 100.0 else resetMs to e.pctAtReset
            },
            color = barFill(window.percent),
            use24h = use24h,
            usageLeft = usageLeft,
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight(maxWidth, windowHeight)),
        )
    }
    // The pace readout: what the retired "Days elapsed" bar used to say, as a
    // number rather than a row. A ±3 point dead zone around the line stops it
    // flapping between above and below — with its colour — on every poll.
    elapsedPercent(window, windowLengthMs)?.let { elapsed ->
        val delta = (window.percent ?: 0.0) - elapsed
        val above = delta > PACE_DEAD_ZONE
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                delta > PACE_DEAD_ZONE -> "${delta.roundToInt()} points above even pace"
                delta < -PACE_DEAD_ZONE -> "${(-delta).roundToInt()} points below even pace"
                else -> "On even pace"
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (above) FontWeight.Bold else FontWeight.Normal,
            color = if (above) barFill(95.0) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (est == null && !atLimit) {
        Spacer(Modifier.height(2.dp))
        Text(
            "Usage hasn't moved enough yet to project a pace",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (est != null && !atLimit) {
        Spacer(Modifier.height(2.dp))
        val hits = est.hitsLimitAtMs
        val rate = " · ${String.format(Locale.US, "%.1f", est.ratePctPerHour)}%/h"
        // CCRM-30 (Estimate Honesty): the projection is inferred, so it carries
        // the marker and a tap-to-reveal provenance line.
        EstimateLine(
            text = (if (hits != null)
                "At this pace: 100% at ${Fmt.dayTime(Instant.ofEpochMilli(hits), use24h)} — " +
                    "${Fmt.span(resetMs - hits)} before the reset"
            else
                "At this pace: ~${est.pctAtReset.toInt()}% when the window resets") + rate,
            provenance = "Projected from this window's samples — a least-squares fit " +
                "anchored on the latest reading. It shifts as new polls land.",
            color = if (hits != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One 7-day row: All models, or a per-model cap. Takes the whole [window] rather
 * than a bare percent because the pace mark needs its reset time — and every row
 * under the 7-day card measures against 7 days, model caps included (they are
 * "· 7-day" surfaces).
 */
@Composable
private fun SubBar(
    label: String,
    window: UsageWindow?,
    usageLeft: Boolean,
    showOverPace: Boolean = true,
) {
    val percent = window?.percent
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            Fmt.usageWorded(percent, usageLeft),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(4.dp))
    UsageBarLine(
        percent = percent,
        fillColor = barFill(percent),
        elapsedPercent = elapsedPercent(window, WEEKLY_MS),
        showOverPace = showOverPace,
    )
    Spacer(Modifier.height(10.dp))
}
