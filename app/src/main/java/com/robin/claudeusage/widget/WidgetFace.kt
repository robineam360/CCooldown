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
 * caps — **capped at [max]** so the two fixed windows always survive a payload
 * with many caps.
 *
 * The mini-rings face passes 3 (CCBG-10 (Mini-Rings Emptiness)): fewer columns
 * is what lets each ring be big enough to read as a gauge rather than a dot.
 */
fun windowRows(data: UsageData, max: Int = 4): List<WindowRow> {
    val rows = mutableListOf<WindowRow>()
    data.session?.let { rows += WindowRow("5h", it, Projection.SESSION_MS) }
    data.weekly?.let { rows += WindowRow("7d", it, Projection.WEEKLY_MS) }
    for (cap in data.modelCaps) rows += WindowRow(cap.modelName, cap.window, Projection.WEEKLY_MS)
    return rows.take(max)
}

/**
 * CCRM-54 (ChatGPT Account) part 2: what a Ring or Bar widget says when it is
 * configured on a window **this account does not have** — a ChatGPT Plus or Pro
 * account since OpenAI lifted the 5-hour limit on 2026-07-12, or any account whose
 * payload simply omits one.
 *
 * The distinction that matters is between *absent* and *not fetched yet*: both draw
 * a null window, but "—" reads as "wait a moment" and would never stop being wrong.
 * A null [data] is the not-fetched case and keeps the em dash; only a payload that
 * arrived and had no such window gets a sentence.
 *
 * Returns null when there is nothing to say — the window is present, or there is no
 * payload to judge it by.
 */
fun absentWindowMessage(data: UsageData?, weekly: Boolean): String? {
    if (data == null) return null
    val present = if (weekly) data.weekly != null else data.session != null
    if (present) return null
    return if (weekly) "No 7-day window on this account"
    else "No 5-hour window on this account"
}

// --- face layout (CCBG-10 (Mini-Rings Emptiness) / CCBG-11 (Ring Face Clutter)) ---
//
// Both faces used to size their rings with a constant, which is how one ended up
// marooned in an empty card and the other overflowed its own bore at the size the
// provider actually declares. These two functions compute the ring from the room
// the face has. They are pure so the wireframe's numbers are pinned by tests
// rather than by looking at a launcher.

/** How the mini-rings face lays out [count] columns in a [widthDp] × [heightDp] face. */
data class MiniRingsLayout(
    val ringDp: Float,
    val strokeDp: Float,
    val percentSp: Float,
    /** Column width — capped, so one or two rings centre instead of spreading. */
    val columnDp: Float,
    val showTitle: Boolean,
    val showCountdown: Boolean,
)

fun miniRingsLayout(widthDp: Float, heightDp: Float, count: Int): MiniRingsLayout {
    val n = count.coerceAtLeast(1)
    val contentW = widthDp - 2 * MINI_PAD_H
    val rowH = heightDp - 2 * MINI_PAD_V - MINI_HEADER_H - MINI_HEADER_GAP
    val perColumn = contentW / n - MINI_GUTTER

    fun ring(stackH: Float) =
        minOf(perColumn, rowH - stackH - MINI_AIR).coerceIn(MINI_RING_MIN, MINI_RING_MAX)

    var d = ring(MINI_TITLE_H + MINI_COUNTDOWN_H)
    val showCountdown = d >= MINI_COUNTDOWN_FLOOR
    // Reclaim the countdown's height *before* judging the title, or the title drops
    // on the strength of a diameter that no longer applies.
    if (!showCountdown) d = ring(MINI_TITLE_H)
    return MiniRingsLayout(
        ringDp = d,
        strokeDp = MINI_STROKE_RATIO * d,
        percentSp = (13f * d / 56f).coerceIn(11f, 20f),
        columnDp = minOf(contentW / n, d + MINI_COLUMN_SLACK),
        showTitle = d >= MINI_TITLE_FLOOR,
        showCountdown = showCountdown,
    )
}

private const val MINI_PAD_H = 14f
private const val MINI_PAD_V = 12f
private const val MINI_HEADER_H = 16f
private const val MINI_HEADER_GAP = 6f
private const val MINI_GUTTER = 12f
private const val MINI_AIR = 6f
private const val MINI_TITLE_H = 16f          // 10sp line + its 3dp gap
private const val MINI_COUNTDOWN_H = 12f      // 9sp line
private const val MINI_COUNTDOWN_FLOOR = 44f  // below this the countdown goes
private const val MINI_TITLE_FLOOR = 38f      // and below this the title follows
private const val MINI_COLUMN_SLACK = 28f
private const val MINI_RING_MIN = 36f
private const val MINI_RING_MAX = 88f
private const val MINI_STROKE_RATIO = 0.098f  // today's 5.5/56, kept

/**
 * How the single-ring face lays out a [widthDp] × [heightDp] placement.
 *
 * The rule that makes this work at the declared 110×110: **the bore holds the
 * percentage and nothing else.** Everything else — profile, countdown, exact
 * reset — lives outside the ring, which is what frees the ring to reach the edge
 * of the face and stay legible when the face is small.
 */
data class RingFaceLayout(
    val ringDp: Float,
    val strokeDp: Float,
    val percentSp: Float,
    /** Wide and short: the lines sit in a column beside the ring, not under it. */
    val landscape: Boolean,
    /** Profile name above the ring; dropped when the face is too short for it. */
    val showName: Boolean,
    /** The exact reset moment, the first line to go. */
    val showResetMoment: Boolean,
)

fun ringFaceLayout(widthDp: Float, heightDp: Float): RingFaceLayout {
    if (widthDp >= heightDp * RING_LANDSCAPE_RATIO) {
        val d = (heightDp - 2 * RING_INSET_LANDSCAPE).coerceIn(RING_MIN, RING_MAX)
        return RingFaceLayout(
            ringDp = d,
            strokeDp = d / RING_STROKE_DIVISOR,
            percentSp = (RING_PCT_RATIO * d).coerceIn(14f, 40f),
            landscape = true,
            showName = true,
            showResetMoment = true,
        )
    }
    val showMoment = heightDp >= RING_MOMENT_FLOOR
    val showName = heightDp >= RING_NAME_FLOOR
    val outside = RING_LINE_H + RING_LINE_GAP +
        (if (showMoment) RING_LINE_H else 0f) +
        (if (showName) RING_LINE_H else 0f)
    val d = (minOf(widthDp, heightDp - outside) - RING_INSET).coerceIn(RING_MIN, RING_MAX)
    return RingFaceLayout(
        ringDp = d,
        strokeDp = d / RING_STROKE_DIVISOR,
        percentSp = (RING_PCT_RATIO * d).coerceIn(14f, 40f),
        landscape = false,
        showName = showName,
        showResetMoment = showMoment,
    )
}

private const val RING_LANDSCAPE_RATIO = 1.6f
private const val RING_INSET = 12f
private const val RING_INSET_LANDSCAPE = 12f
private const val RING_LINE_H = 13f
private const val RING_LINE_GAP = 4f
private const val RING_NAME_FLOOR = 140f      // room above the ring for the account
private const val RING_MOMENT_FLOOR = 200f    // room below it for the exact reset
private const val RING_MIN = 48f
/** Past this a solo ring stops being a gauge and becomes a poster. */
private const val RING_MAX = 140f
private const val RING_STROKE_DIVISOR = 16f   // today's 8/128, kept
private const val RING_PCT_RATIO = 0.26f
