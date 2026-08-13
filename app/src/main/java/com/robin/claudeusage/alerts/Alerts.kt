package com.robin.claudeusage.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.robin.claudeusage.MainActivity
import com.robin.claudeusage.R
import com.robin.claudeusage.data.AuthState
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.SessionLog
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.ui.Fmt
import java.time.Instant

/**
 * Notifications: threshold warnings before a limit is hit, "window reset"
 * pings, and re-auth alerts — all per profile, all deduped per window instance.
 */
object Alerts {

    private const val CHANNEL_USAGE = "usage_alerts"
    private const val CHANNEL_RESET = "reset_alerts"
    private const val CHANNEL_AUTH = "auth_alerts"
    private const val CHANNEL_HEALTH = "health_alerts"

    /**
     * Its own channel so a user who wants window pings but not their noise can mute
     * exactly this. Only ever used for failures — a ping that works says nothing.
     */
    private const val CHANNEL_PING = "ping_alerts"

    /**
     * Pace alerts (CCRM-21) — "moving too fast", as opposed to CHANNEL_USAGE's
     * "close to the wall". Its own channel so either signal can be muted alone.
     */
    private const val CHANNEL_PACE = "pace_alerts"

    /**
     * App updates (CCRM-28). Non-private: the poster lives in
     * `notify/UpdateNotification.kt`, app-global rather than per-profile.
     */
    const val CHANNEL_UPDATE = "update_alerts"

    /** Pace notification id kinds — one per window, so an escalation replaces in place. */
    private const val PACE_SESSION_KIND = 30
    private const val PACE_WEEKLY_KIND = 31

    /** Notification-id kinds 1–7 are fixed; per-model caps use this base + index. */
    private const val MODEL_CAP_KIND_BASE = 10

    /** Warn this many days before the known sign-in (refresh token) expiry. */
    private val EXPIRY_WARN_DAYS = listOf(1, 3, 7)
    /**
     * Public because the widget faces use the same threshold for their stale
     * treatment (CCRM-39/40/41) — one constant, so the amber pill and the
     * stale-data alert can never disagree about what "stale" means.
     */
    const val STALE_DATA_MS = 6 * 60 * 60_000L

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_USAGE, "Usage alerts", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Warnings when a Claude usage window is nearly exhausted" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESET, "Window resets", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "A usage window has reset — Claude is fresh again" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AUTH, "Sign-in problems", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Token expired and needs to be pasted again" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HEALTH, "Data freshness", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Usage data hasn't refreshed for hours" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PING, "Window pings", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "A scheduled window ping failed to start a window" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PACE, "Pace alerts", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Usage on pace to run out before the window resets" }
        )
        // IMPORTANCE_LOW: visible in the shade and status bar but silent — a new
        // release discovered at 3am has no claim to a sound.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATE, "App updates", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "A newer Claude Cooldown release is on GitHub" }
        )
    }

    /**
     * A scheduled ping failed after its retries. Deliberately IMPORTANCE_HIGH: the
     * user is relying on a window existing that now doesn't, and finding out at 8am
     * is the failure this feature exists to avoid.
     */
    fun notifyPingFailed(context: Context, profile: Profile, detail: String) {
        ensureChannels(context)
        val cache = UsageCache(context)
        notify(
            context, profile, notifId(profile, 8), CHANNEL_PING,
            "${cache.profileLabel(profile)}: window not started",
            detail,
        )
    }

    /** Called after every poll; works off the latest cached data and dedupes itself. */
    fun evaluate(context: Context, cache: UsageCache) {
        ensureChannels(context)
        for (profile in Profile.entries) {
            evaluateProfile(context, cache, profile)
        }
        // The always-on notification rides the same cadence so it stays live.
        com.robin.claudeusage.notify.PinnedNotification.update(context, cache)
    }

    private fun evaluateProfile(context: Context, cache: UsageCache, profile: Profile) {
        val snapshot = cache.snapshot(profile)
        val label = cache.profileLabel(profile)

        // Re-auth alert: once per failure episode, cleared (and the notification
        // dismissed) when auth recovers.
        if (snapshot.authState == AuthState.REAUTH_NEEDED) {
            if (cache.authAlertsEnabled() && !cache.reauthNotified(profile)) {
                notify(
                    context, profile, notifId(profile, 3), CHANNEL_AUTH,
                    "$label: Claude Cooldown needs re-auth",
                    "The saved token stopped working. Open the app and paste a fresh one.",
                )
                cache.setReauthNotified(profile, true)
            }
        } else {
            if (cache.reauthNotified(profile)) {
                NotificationManagerCompat.from(context).cancel(notifId(profile, 3))
            }
            cache.setReauthNotified(profile, false)
        }

        if (snapshot.authState == AuthState.OK) {
            if (cache.authAlertsEnabled()) checkUpcomingExpiry(context, cache, profile)
            if (cache.healthAlertsEnabled()) checkStaleData(context, cache, profile, snapshot)
        }

        val data = snapshot.data ?: return
        val use24h = cache.use24hTime()

        // Reset detection always runs (it also tracks window identity and peak);
        // the per-window ping mode and profile scope decide whether it notifies.
        checkReset(context, cache, profile, "Session", "5-hour", data.session, Projection.SESSION_MS)
        checkReset(context, cache, profile, "Weekly", "7-day", data.weekly, Projection.WEEKLY_MS)

        if (!cache.profileAlertsEnabled(profile)) return

        data.session?.let { w ->
            checkThresholds(
                context, cache, profile, w,
                keyName = "sessionAlert",
                thresholds = cache.sessionAlertThresholds().sortedDescending(),
                notificationId = notifId(profile, 1),
                title = { pct -> "$label: 5-hour window at $pct%" },
                use24h = use24h,
                windowLengthMs = Projection.SESSION_MS,
            )
        }
        data.weekly?.let { w ->
            checkThresholds(
                context, cache, profile, w,
                keyName = "weeklyAlert",
                thresholds = cache.weeklyAlertThresholds().sortedDescending(),
                notificationId = notifId(profile, 2),
                title = { pct -> "$label: 7-day window at $pct%" },
                use24h = use24h,
                windowLengthMs = Projection.WEEKLY_MS,
            )
        }
        // Pace alerts (CCRM-21): where usage is *heading*, from the same projection
        // the chart draws. Orthogonal to the absolute thresholds above — "moving too
        // fast" vs "close to the wall" — so both deliberately coexist.
        if (cache.paceAlertsEnabled()) {
            val history = com.robin.claudeusage.data.HistoryStore(context).points(profile)
            data.session?.let { w ->
                w.resetsAt?.toEpochMilli()?.let { reset ->
                    checkPace(
                        context, cache, profile, "Session", "5-hour", w,
                        Projection.SESSION_MS, notifId(profile, PACE_SESSION_KIND),
                        Projection.sessionSamples(history, reset, Projection.SESSION_MS), use24h,
                    )
                }
            }
            data.weekly?.let { w ->
                w.resetsAt?.toEpochMilli()?.let { reset ->
                    checkPace(
                        context, cache, profile, "Weekly", "7-day", w,
                        Projection.WEEKLY_MS, notifId(profile, PACE_WEEKLY_KIND),
                        Projection.weeklySamples(history, reset, Projection.WEEKLY_MS), use24h,
                    )
                }
            }
        }

        // Per-model 7-day caps (e.g. an Opus limit) — same machinery, keyed
        // by model name so caps appearing/disappearing dedupe independently.
        data.modelCaps.forEachIndexed { index, cap ->
            checkThresholds(
                context, cache, profile, cap.window,
                keyName = "modelAlert.${cap.modelName}",
                thresholds = cache.modelCapAlertThresholds().sortedDescending(),
                notificationId = notifId(profile, MODEL_CAP_KIND_BASE + index),
                title = { pct -> "$label: ${cap.modelName} 7-day cap at $pct%" },
                use24h = use24h,
                // Per-model caps are weekly_scoped, so they drift on the 7-day clock.
                windowLengthMs = Projection.WEEKLY_MS,
            )
        }
    }

    /**
     * Early warning before the known sign-in (refresh token) expiry, at 7/3/1
     * days out. The expiry date is only known from the pasted JSON; a rotation
     * clears it, so this is best-effort. Deduped per expiry instance.
     */
    private fun checkUpcomingExpiry(context: Context, cache: UsageCache, profile: Profile) {
        val expiry = cache.refreshExpiresAt(profile)
        if (expiry <= 0) return
        val now = System.currentTimeMillis()
        val msLeft = expiry - now
        if (msLeft <= 0) return // dead already — the re-auth path handles it
        val crossed = EXPIRY_WARN_DAYS.firstOrNull { msLeft <= it * 86_400_000L } ?: return

        if (cache.alertKey(profile, "expiryWarn") != expiry) {
            cache.setAlertState(profile, "expiryWarn", expiry, 0)
        }
        val already = cache.alertThreshold(profile, "expiryWarn")
        if (already != 0 && already <= crossed) return

        val use24h = cache.use24hTime()
        notify(
            context, profile, notifId(profile, 6), CHANNEL_AUTH,
            "${cache.profileLabel(profile)}: sign-in expires in ${Fmt.dhm(expiry)}",
            "Valid until ${Fmt.dateTime(expiry, use24h)}. Paste a fresh token when convenient.",
        )
        cache.setAlertState(profile, "expiryWarn", expiry, crossed)
    }

    /**
     * Silent-failure alert: polls are running but nothing has succeeded for
     * hours — the widget numbers are stale and nothing else would say so.
     */
    private fun checkStaleData(
        context: Context,
        cache: UsageCache,
        profile: Profile,
        snapshot: com.robin.claudeusage.data.Snapshot,
    ) {
        val now = System.currentTimeMillis()
        val stale = snapshot.fetchedAt > 0 &&
            now - snapshot.fetchedAt > STALE_DATA_MS &&
            snapshot.lastStatus != "OK"
        if (stale) {
            if (!cache.staleNotified(profile)) {
                notify(
                    context, profile, notifId(profile, 7), CHANNEL_HEALTH,
                    "${cache.profileLabel(profile)}: usage data is stale",
                    "Nothing fetched since ${Fmt.dayTimeWithAgo(snapshot.fetchedAt, cache.use24hTime())}. " +
                        "Last error: ${snapshot.lastStatus}",
                )
                cache.setStaleNotified(profile, true)
            }
        } else if (cache.staleNotified(profile)) {
            NotificationManagerCompat.from(context).cancel(notifId(profile, 7))
            cache.setStaleNotified(profile, false)
        }
    }

    private fun checkReset(
        context: Context,
        cache: UsageCache,
        profile: Profile,
        windowName: String,
        windowLabel: String,
        window: UsageWindow?,
        windowLengthMs: Long,
    ) {
        val key = window?.resetsAt?.toEpochMilli() ?: return
        val pct = window.percent ?: 0.0
        val lastSeen = cache.lastSeenWindowKey(profile, windowName)
        // Proximity, not equality (CCBG-4). Exact comparison also made this fire
        // spuriously when a poll landed within ~1s of the boundary and drift pushed
        // lastSeen just into the past.
        if (lastSeen != 0L && !Projection.sameWindow(lastSeen, key, windowLengthMs) &&
            Instant.ofEpochMilli(lastSeen).isBefore(Instant.now())
        ) {
            // The window rolled over. Log the window that just closed to the
            // long-term session log (its identity is lastSeen, its peak is what
            // we accumulated while it was open) for the history bars.
            val peak = cache.windowPeak(profile, windowName)
            SessionLog(context).record(
                profile,
                if (windowName == "Session") SessionLog.SESSION else SessionLog.WEEKLY,
                lastSeen, peak, peak >= 99.5,
            )
            // Smart mode only pings when the finished window had actually been
            // running hot — a reset nobody was waiting for is just noise.
            val mode = cache.resetPingMode(windowName)
            val wanted = mode == UsageCache.RESET_ALWAYS ||
                (mode == UsageCache.RESET_SMART && peak >= UsageCache.SMART_RESET_MIN_PCT)
            if (wanted && cache.profileAlertsEnabled(profile)) {
                notify(
                    context, profile, notifId(profile, if (windowName == "Session") 4 else 5), CHANNEL_RESET,
                    "${cache.profileLabel(profile)}: $windowLabel window reset",
                    "Usage is back at ${pct.toInt()}%. Next reset ${Fmt.relIn(window.resetsAt)}.",
                )
            }
            cache.setWindowPeak(profile, windowName, pct)
        } else {
            cache.setWindowPeak(profile, windowName, maxOf(cache.windowPeak(profile, windowName), pct))
        }
        cache.setLastSeenWindowKey(profile, windowName, key)
    }

    private fun checkThresholds(
        context: Context,
        cache: UsageCache,
        profile: Profile,
        window: UsageWindow,
        keyName: String,
        thresholds: List<Int>,
        notificationId: Int,
        title: (Int) -> String,
        use24h: Boolean,
        windowLengthMs: Long,
    ) {
        val pct = window.percent?.toInt() ?: return
        val windowKey = window.resetsAt?.toEpochMilli() ?: return

        val storedKey = cache.alertKey(profile, keyName)
        if (!Projection.sameWindow(storedKey, windowKey, windowLengthMs)) {
            // A genuinely new window — start its dedup state fresh.
            cache.setAlertState(profile, keyName, windowKey, 0)
        } else if (storedKey != windowKey) {
            // Same window, drifted timestamp (CCBG-4): re-anchor to the newest
            // reading, keeping the threshold already notified. Comparing against the
            // latest value rather than the first-seen one keeps each comparison over
            // a single poll interval, so the slide can never accumulate past
            // tolerance over a long window.
            cache.setAlertState(profile, keyName, windowKey, cache.alertThreshold(profile, keyName))
        }
        val alreadyNotified = cache.alertThreshold(profile, keyName)
        val crossed = thresholds.firstOrNull { pct >= it && alreadyNotified < it } ?: return

        notify(context, profile, notificationId, CHANNEL_USAGE, title(pct), resetLine(window.resetsAt, use24h))
        cache.setAlertState(profile, keyName, windowKey, crossed)
    }

    /**
     * Pace milestone evaluation for one window. The decisions are all in
     * [Projection.paceStep] (pure, tested); this does the I/O: reads the toggles and
     * the stored state, formats the copy, posts at most one notification (the most
     * severe newly-fired milestone — an escalation replaces in place, same id), and
     * persists. Delivered bits are only recorded when the post succeeded, so a failed
     * delivery retries next poll rather than being silently lost.
     */
    private fun checkPace(
        context: Context,
        cache: UsageCache,
        profile: Profile,
        windowName: String,
        windowLabel: String,
        window: UsageWindow,
        windowLengthMs: Long,
        notificationId: Int,
        samples: List<Pair<Long, Double>>,
        use24h: Boolean,
    ) {
        val resetMs = window.resetsAt?.toEpochMilli() ?: return
        val usedPct = window.percent ?: return
        val elapsedMs = System.currentTimeMillis() - (resetMs - windowLengthMs)
        val estimate = Projection.estimate(samples, resetMs)
        val severity = Projection.paceSeverity(usedPct, elapsedMs, windowLengthMs, estimate)

        var satisfied = Projection.paceSatisfied(severity, usedPct)
        for (m in Projection.PaceMilestone.entries) {
            if (!cache.paceMilestoneEnabled(m.name)) satisfied = satisfied and m.bit.inv()
        }

        val step = Projection.paceStep(
            cache.paceState(profile, windowName), resetMs, windowLengthMs, satisfied,
        )
        var state = step.carry
        step.fire.firstOrNull()?.let { headline ->
            val label = cache.profileLabel(profile)
            val (title, text) = paceCopy(headline, label, windowLabel, window, usedPct, estimate, use24h)
            if (notify(context, profile, notificationId, CHANNEL_PACE, title, text)) {
                val delivered = step.fire.fold(0) { acc, m -> acc or m.bit }
                state = state.copy(firedMask = state.firedMask or delivered)
            }
        }
        cache.setPaceState(profile, windowName, state)
    }

    private fun paceCopy(
        milestone: Projection.PaceMilestone,
        label: String,
        windowLabel: String,
        window: UsageWindow,
        usedPct: Double,
        estimate: Projection.Estimate?,
        use24h: Boolean,
    ): Pair<String, String> = when (milestone) {
        Projection.PaceMilestone.WILL_RUN_OUT -> {
            val hits = estimate?.hitsLimitAtMs
            "$label: $windowLabel window will run out early" to if (hits != null) {
                "At this pace, 100% around ${Fmt.dayTime(Instant.ofEpochMilli(hits), use24h)} — " +
                    "${Fmt.span(window.resetsAt!!.toEpochMilli() - hits)} before the reset."
            } else {
                // SPENT without a projection: it already happened.
                "Nothing left at ${usedPct.toInt()}%. ${resetLine(window.resetsAt, use24h)}"
            }
        }
        Projection.PaceMilestone.CUTTING_IT_CLOSE ->
            "$label: cutting it close on the $windowLabel window" to
                "Projected ~${Math.round(estimate?.pctAtReset ?: usedPct)}% by the reset. " +
                resetLine(window.resetsAt, use24h)
        Projection.PaceMilestone.ALMOST_OUT ->
            "$label: $windowLabel window almost out" to
                "Under 10% left. ${resetLine(window.resetsAt, use24h)}"
    }

    private fun notifId(profile: Profile, kind: Int): Int =
        kind + if (profile == Profile.WORK) 100 else 0

    private fun resetLine(resetsAt: Instant?, use24h: Boolean): String {
        resetsAt ?: return ""
        val abs = Fmt.dayTime(resetsAt, use24h)
        val rel = Fmt.relIn(resetsAt)
        return if (rel == "any moment") "Resets any moment ($abs)" else "Resets $rel — $abs"
    }

    /** @return whether the notification was actually posted — pace alerts roll back on false. */
    private fun notify(
        context: Context,
        profile: Profile,
        id: Int,
        channel: String,
        title: String,
        text: String,
    ): Boolean {
        // Request code = notification id (already unique per profile+kind), so
        // a Work alert's intent isn't recycled with the Personal extra.
        val openApp = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).putExtra("profile", profile.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_bars)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently skip.
            false
        }
    }
}
