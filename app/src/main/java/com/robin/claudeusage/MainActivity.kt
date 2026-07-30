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
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.UsageRepository
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.ui.ContentColumn
import com.robin.claudeusage.ui.ContentMaxWidth
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.LocalWidthClass
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.ui.ProvideWidthClass
import com.robin.claudeusage.ui.UsageSparkline
import com.robin.claudeusage.ui.WideMaxWidth
import com.robin.claudeusage.ui.hasTwoColumns
import com.robin.claudeusage.ui.PACE_DEAD_ZONE
import com.robin.claudeusage.ui.elapsedPercent
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
        val startProfile = Profile.fromKey(intent?.getStringExtra("profile"))
        // Measured at the root so every screen sees the same window width, and so
        // it re-measures on a fold/unfold without the activity being torn down.
        setContent { ProvideWidthClass { App(startProfile) } }
    }
}

private enum class Screen { MAIN, SETTINGS, GUIDE, HISTORY }

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
    var use24h by remember { mutableStateOf(cache.use24hTime()) }
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

    val dark = isSystemInDarkTheme()
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
                        ProfileTabs(repo, use24h, tick, startProfile, Modifier.padding(innerPadding))
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
                                onUse24h = { use24h = it },
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

@Composable
private fun ProfileTabs(
    repo: UsageRepository,
    use24h: Boolean,
    tick: Int,
    startProfile: Profile,
    modifier: Modifier,
) {
    val profiles = Profile.entries
    // Both accounts at once is the whole point of the app, and on a wide window
    // there's finally room for it — so the tabs and the swipe go away rather than
    // making you page between two things that now fit side by side.
    if (LocalWidthClass.current.twoPane) {
        ProfilePanes(repo, use24h, tick, modifier)
        return
    }

    val pagerState = rememberPagerState(
        initialPage = profiles.indexOf(startProfile).coerceAtLeast(0),
        pageCount = { profiles.size },
    )
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            profiles.forEachIndexed { index, profile ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(repo.cacheSettings().profileLabel(profile)) },
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
        ) { page ->
            ContentColumn {
                Spacer(Modifier.height(16.dp))
                ProfileScreen(repo, profiles[page], use24h, tick)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Every profile side by side, one scrolling pane each. Each pane carries its own
 * heading, which is what the tab strip used to do — without it the two columns of
 * near-identical cards are impossible to tell apart.
 */
@Composable
private fun ProfilePanes(
    repo: UsageRepository,
    use24h: Boolean,
    tick: Int,
    modifier: Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        for ((index, profile) in Profile.entries.withIndex()) {
            if (index > 0) VerticalDivider()
            // Keyed so each pane keeps its own scroll position rather than
            // inheriting its neighbour's by loop position.
            key(profile) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        repo.cacheSettings().profileLabel(profile),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    ProfileScreen(repo, profile, use24h, tick)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** Claude-style bar: light tint track, solid fill, fully rounded. */
@Composable
private fun UsageBarLine(percent: Double?, fillColor: Color, height: androidx.compose.ui.unit.Dp = 12.dp) {
    val fraction = ((percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
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
        )
    }
}

@Composable
private fun ResetRow(window: UsageWindow?, use24h: Boolean) {
    // A window with no reset time hasn't started yet (0% and idle).
    if (window?.resetsAt == null) {
        Text(
            "Starts when a message is sent",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Resets ${Fmt.relIn(window.resetsAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Resets at ${Fmt.dayTime(window.resetsAt, use24h)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun barFill(percent: Double?): Color =
    Palette.barColor(percent, MaterialTheme.colorScheme.primary, isSystemInDarkTheme())

@Composable
private fun ProfileScreen(repo: UsageRepository, profile: Profile, use24h: Boolean, tick: Int) {
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
                        "${(data.session?.percent ?: 0.0).toInt()}% used",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(8.dp))
                UsageBarLine(data.session?.percent, barFill(data.session?.percent))
                data.session?.let { w ->
                    TrendBlock(
                        window = w,
                        samples = w.resetsAt?.let {
                            Projection.sessionSamples(history, it.toEpochMilli(), SESSION_MS)
                        } ?: emptyList(),
                        windowLengthMs = SESSION_MS,
                        use24h = use24h,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ResetRow(data.session, use24h)
            }
        }
        Spacer(Modifier.height(12.dp))

        Card {
            Column(Modifier.padding(16.dp)) {
                Text("7-day window", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                SubBar("All models", data.weekly?.percent, "% used")
                for (cap in data.modelCaps) {
                    SubBar(cap.modelName, cap.window.percent, "% used")
                }
                data.weekly?.let { w ->
                    TrendBlock(
                        window = w,
                        samples = w.resetsAt?.let {
                            Projection.weeklySamples(history, it.toEpochMilli(), WEEKLY_MS)
                        } ?: emptyList(),
                        windowLengthMs = WEEKLY_MS,
                        use24h = use24h,
                    )
                }
                Spacer(Modifier.height(2.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                ResetRow(data.weekly, use24h)
            }
        }

        // Pay-as-you-go credits. Only meaningful once a budget exists — a plan with
        // no credits reports a zero limit, and "$0.00 of $0.00" tells nobody anything.
        val credits = data.credits
            ?.takeIf { it.limitMinor > 0L && repo.cacheSettings().creditsVisible(profile) }
        if (credits != null) {
            Spacer(Modifier.height(12.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    // Same shape as the 5-hour card: name on the left, the headline
                    // percentage on the right, bar underneath.
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Usage credits", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${Fmt.money(credits.usedMinor, credits.exponent, credits.currency)} / " +
                                Fmt.money(credits.limitMinor, credits.exponent, credits.currency),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${credits.percentDisplay}% used",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    UsageBarLine(credits.percent, barFill(credits.percent))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (credits.remainingMinor > 0L)
                            "${Fmt.money(credits.remainingMinor, credits.exponent, credits.currency)} left · " +
                                "covers you when you hit your plan limits"
                        else
                            "All credits spent — nothing left to cover plan overruns",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (credits.remainingMinor > 0L) MaterialTheme.colorScheme.onSurfaceVariant
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
        Text(
            "Status: ${snapshot.lastStatus}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
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
    UsageSparkline(
        samples = samples,
        windowStartMs = resetMs - windowLengthMs,
        windowEndMs = resetMs,
        projectedEnd = est?.let { e ->
            if (e.hitsLimitAtMs != null) e.hitsLimitAtMs to 100.0 else resetMs to e.pctAtReset
        },
        color = barFill(window.percent),
        use24h = use24h,
        modifier = Modifier.fillMaxWidth().height(192.dp),
    )
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
        Text(
            (if (hits != null)
                "At this pace: 100% at ${Fmt.dayTime(Instant.ofEpochMilli(hits), use24h)} — " +
                    "${Fmt.span(resetMs - hits)} before the reset"
            else
                "At this pace: ~${est.pctAtReset.toInt()}% when the window resets") + rate,
            style = MaterialTheme.typography.bodySmall,
            color = if (hits != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubBar(label: String, percent: Double?, suffix: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "${(percent ?: 0.0).toInt()}$suffix",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(4.dp))
    UsageBarLine(percent, barFill(percent))
    Spacer(Modifier.height(10.dp))
}
