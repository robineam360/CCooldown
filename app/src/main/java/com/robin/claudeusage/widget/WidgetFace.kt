package com.robin.claudeusage.widget

import com.robin.claudeusage.alerts.Alerts
import com.robin.claudeusage.data.AuthState
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.Snapshot
import com.robin.claudeusage.data.UsageData
import com.robin.claudeusage.data.UsageWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared state/copy logic for the three ring/pace faces (CCRM-39 (Ring Widget),
 * CCRM-40 (Mini-Rings Widget), CCRM-41 (Pace Widget)) — pure functions so the
 * handover's state table and copy are pinned by unit tests, not by eyeballing.
 *
 * The Mac spec has a sixth state, `needsAppUpdate` (widget binary older than
 * the snapshot it reads). It is deliberately absent here: Glance widgets render
 * in the app process from the same APK, so reader and writer are replaced
 * atomically by every update and the skew that state exists for is structurally
 * impossible on Android. If widgets ever move to a separate process reading a
 * versioned snapshot file, add it back.
 */
enum class FaceState { OK, STALE, FETCH_ERROR, NOT_SIGNED_IN, NO_DATA }

/**
 * Which face to draw, per the handover §4 table. [hasData] is face-specific:
 * the small/large faces pass "does my window exist", the medium face passes
 * "is there any window at all" — a single missing cap column degrades in place
 * instead of blanking the whole face.
 */
fun faceState(
    snapshot: Snapshot,
    hasData: Boolean,
    nowMs: Long = System.currentTimeMillis(),
): FaceState = when {
    snapshot.authState == AuthState.NO_CREDENTIALS -> FaceState.NOT_SIGNED_IN
    !hasData -> FaceState.NO_DATA
    // Stale outranks fetchError: "6h old" says more than the last poll's excuse.
    snapshot.fetchedAt > 0 && nowMs - snapshot.fetchedAt > Alerts.STALE_DATA_MS -> FaceState.STALE
    snapshot.lastStatus != "OK" -> FaceState.FETCH_ERROR
    else -> FaceState.OK
}

/**
 * Compact two-unit countdown for a widget face: "3d 4h" · "2h 14m" · "9m",
 * softening to **"soon" inside five minutes** — a live per-second countdown on
 * a widget is noise and a refresh-budget hole, and "soon" is what makes the
 * sparse update cadence honest. Em dash when the window hasn't started.
 */
fun widgetCountdown(resetsAt: Instant?, nowMs: Long = System.currentTimeMillis()): String {
    resetsAt ?: return "—"
    val mins = (resetsAt.toEpochMilli() - nowMs) / 60_000
    if (mins < 5) return "soon"
    val d = mins / (24 * 60)
    val h = (mins / 60) % 24
    val m = mins % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

/**
 * The exact reset moment, shown on every face alongside the countdown (the
 * "reset shown both ways" rule): "today at 7:34 PM" · "tomorrow at 9:00 AM" ·
 * "Aug 16 at 9:00 AM". Never a weekday-only label — a 7-day window starts and
 * ends on the same weekday, which is the exact bug the chart already hit once.
 */
fun resetMoment(
    resetsAt: Instant?,
    use24h: Boolean,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    resetsAt ?: return ""
    val time = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a")
        .withZone(zone).format(resetsAt)
    val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    return when (LocalDate.ofInstant(resetsAt, zone)) {
        today -> "today at $time"
        today.plusDays(1) -> "tomorrow at $time"
        else -> DateTimeFormatter.ofPattern("MMM d").withZone(zone).format(resetsAt) + " at $time"
    }
}

/**
 * The large face's verdict sentence. Copy is the handover's: when the pace
 * hits the limit early it names the moment and the shortfall; otherwise the
 * projected landing. A refused projection **prints why** — a silently missing
 * chart is indistinguishable from a broken one.
 */
fun paceSentence(
    estimate: Projection.Estimate?,
    resetAtMs: Long,
    use24h: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    estimate ?: return "Usage hasn't moved enough yet to project a pace"
    val hits = estimate.hitsLimitAtMs
    if (hits != null) {
        val at = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a")
            .withZone(zone).format(Instant.ofEpochMilli(hits))
        return "At this pace: 100% at $at — ${spanShort(resetAtMs - hits)} before the reset"
    }
    return "At this pace: ${estimate.pctAtReset.toInt()}% by the reset"
}

/** Two-unit duration for the pace sentence: "43m" · "2h 5m" · "1d 3h". */
private fun spanShort(ms: Long): String {
    val mins = (ms / 60_000).coerceAtLeast(0)
    val d = mins / (24 * 60)
    val h = (mins / 60) % 24
    val m = mins % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

/**
 * One mini-ring column on the medium face. [windowLengthMs] is what the pace
 * tick is measured against: 5 h for the session, **7 days for weekly and every
 * model cap** — each window carries its own clock.
 */
data class WindowRow(
    val title: String,
    val window: UsageWindow,
    val windowLengthMs: Long,
)

/**
 * The medium face's columns in payload order — session, weekly, then model
 * caps — **capped at four** so the two fixed windows always survive a payload
 * with many caps.
 */
fun windowRows(data: UsageData): List<WindowRow> {
    val rows = mutableListOf<WindowRow>()
    data.session?.let { rows += WindowRow("5h", it, Projection.SESSION_MS) }
    data.weekly?.let { rows += WindowRow("7d", it, Projection.WEEKLY_MS) }
    for (cap in data.modelCaps) rows += WindowRow(cap.modelName, cap.window, Projection.WEEKLY_MS)
    return rows.take(4)
}
