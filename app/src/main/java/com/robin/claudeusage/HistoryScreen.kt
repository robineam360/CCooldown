package com.robin.claudeusage

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.robin.claudeusage.data.HistoryStats
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.SessionLog
import com.robin.claudeusage.data.UsageRepository
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.LocalWidthClass
import com.robin.claudeusage.ui.Palette
import com.robin.claudeusage.ui.twoPane
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Usage history as scrollable bars — one per window. In 5-hour mode, sessions
 * are grouped into calendar weeks you can page through; in 7-day mode, each bar
 * is a full weekly window. Everything is fed by [SessionLog] (closed windows)
 * plus the current open window, so it fills in going forward.
 */
@Composable
fun HistoryScreen(repo: UsageRepository, tick: Int) {
    val profiles = Profile.entries
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var weekly by rememberSaveable { mutableStateOf(false) }
    var weekIndex by rememberSaveable { mutableIntStateOf(0) }
    val profile = profiles[tab]
    val zone = remember { ZoneId.systemDefault() }
    val use24h = repo.cacheSettings().use24hTime()

    // Re-read when a fetch lands so a just-closed or growing window shows up.
    val fetchedAt = remember(tick, profile) { repo.snapshot(profile).fetchedAt }
    val records = remember(profile, fetchedAt) { repo.sessionLog().records(profile) }
    val data = remember(profile, fetchedAt) { repo.snapshot(profile).data }

    fun openBar(kind: String): HistoryStats.Bar? {
        val window = if (kind == SessionLog.WEEKLY) data?.weekly else data?.session
        val reset = window?.resetsAt?.toEpochMilli() ?: return null
        val name = if (kind == SessionLog.WEEKLY) "Weekly" else "Session"
        val peak = maxOf(repo.cacheSettings().windowPeak(profile, name), window.percent ?: 0.0)
        return HistoryStats.Bar(
            startMs = reset - HistoryStats.windowMs(kind),
            resetMs = reset,
            peakPct = peak,
            hitLimit = peak >= 99.5,
            current = true,
        )
    }

    // Wide enough and both window kinds fit at once, so the toggle between them
    // stops being a choice you have to make — the comparison is the useful part.
    val sideBySide = LocalWidthClass.current.twoPane

    TabRow(selectedTabIndex = tab) {
        profiles.forEachIndexed { index, p ->
            Tab(
                selected = tab == index,
                onClick = { tab = index },
                text = { Text(repo.cacheSettings().profileLabel(p)) },
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    if (!sideBySide) {
        Row {
            FilterChip(selected = !weekly, onClick = { weekly = false }, label = { Text("5-hour") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = weekly, onClick = { weekly = true }, label = { Text("7-day") })
        }
        Spacer(Modifier.height(12.dp))
    }

    val sessions: @Composable () -> Unit = {
        val bars = HistoryStats.bars(records, SessionLog.SESSION, openBar(SessionLog.SESSION))
        SessionWeekView(HistoryStats.weeks(bars, zone), weekIndex, zone, use24h) { weekIndex = it }
    }
    val weeks: @Composable () -> Unit = {
        WeeklyView(HistoryStats.bars(records, SessionLog.WEEKLY, openBar(SessionLog.WEEKLY)), zone, use24h)
    }

    if (sideBySide) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // With the chips gone, each pane has to say which window it's showing.
            Column(Modifier.weight(1f)) {
                PaneLabel("5-hour windows")
                sessions()
            }
            Column(Modifier.weight(1f)) {
                PaneLabel("7-day windows")
                weeks()
            }
        }
    } else if (weekly) {
        weeks()
    } else {
        sessions()
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "History is recorded as each window closes, so it fills in over time. " +
            "Peak %s are sampled at the polling interval.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SessionWeekView(
    weeks: List<HistoryStats.Week>,
    weekIndex: Int,
    zone: ZoneId,
    use24h: Boolean,
    onWeek: (Int) -> Unit,
) {
    if (weeks.isEmpty()) {
        EmptyHistory()
        return
    }
    val index = weekIndex.coerceIn(0, weeks.size - 1)
    if (index != weekIndex) onWeek(index)
    val week = weeks[index]
    val today = LocalDate.now(zone)
    val (title, range) = weekLabel(week.monday, today)
    val maxed = week.bars.count { it.hitLimit }

    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Newer weeks are at index 0, so "left/older" increments the index.
                IconButton(enabled = index < weeks.size - 1, onClick = { onWeek(index + 1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Older week")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(enabled = index > 0, onClick = { onWeek(index - 1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Newer week")
                }
            }
            Text(
                "${week.bars.size} session${if (week.bars.size == 1) "" else "s"}" +
                    if (maxed > 0) " · $maxed maxed out" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            for (bar in week.bars) {
                BarRow(
                    label = Fmt.dayTime(Instant.ofEpochMilli(bar.startMs), use24h),
                    bar = bar,
                )
            }
        }
    }
}

@Composable
private fun WeeklyView(bars: List<HistoryStats.Bar>, zone: ZoneId, use24h: Boolean) {
    if (bars.isEmpty()) {
        EmptyHistory()
        return
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            for (bar in bars) {
                val start = Instant.ofEpochMilli(bar.startMs).atZone(zone).toLocalDate()
                BarRow(label = rangeStr(start, start.plusDays(6)), bar = bar)
            }
        }
    }
}

@Composable
private fun BarRow(label: String, bar: HistoryStats.Bar) {
    val dark = isSystemInDarkTheme()
    val fill = Palette.barColor(bar.peakPct, MaterialTheme.colorScheme.primary, dark)
    val valueColor =
        if (bar.hitLimit) fill else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(76.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            if (bar.current) {
                Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(fill.copy(alpha = 0.22f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((bar.peakPct / 100.0).toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(fill),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "${bar.peakPct.toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}

/** Heading over one pane of the side-by-side layout. */
@Composable
private fun PaneLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyHistory() {
    Text(
        "No sessions recorded yet. History builds up as you use Claude and each " +
            "5-hour or 7-day window closes — check back later.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** "This week" / "Last week" / a date range, plus the Mon–Sun range as a subtitle. */
private fun weekLabel(monday: LocalDate, today: LocalDate): Pair<String, String> {
    val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val title = when (monday) {
        thisMonday -> "This week"
        thisMonday.minusWeeks(1) -> "Last week"
        else -> rangeStr(monday, monday.plusDays(6))
    }
    return title to rangeStr(monday, monday.plusDays(6))
}

/** "14–20 Jul" within a month, "30 Jun–6 Jul" across months. */
private fun rangeStr(start: LocalDate, end: LocalDate): String {
    val dayMonth = DateTimeFormatter.ofPattern("d MMM")
    val day = DateTimeFormatter.ofPattern("d")
    return if (start.month == end.month) "${start.format(day)}–${end.format(dayMonth)}"
    else "${start.format(dayMonth)}–${end.format(dayMonth)}"
}
