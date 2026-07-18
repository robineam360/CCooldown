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

    private val SESSION_THRESHOLDS = listOf(95, 80)
    private const val WEEKLY_THRESHOLD = 90

    /** Notification-id kinds 1–7 are fixed; per-model caps use this base + index. */
    private const val MODEL_CAP_KIND_BASE = 10

    /** Warn this many days before the known sign-in (refresh token) expiry. */
    private val EXPIRY_WARN_DAYS = listOf(1, 3, 7)
    private const val STALE_DATA_MS = 6 * 60 * 60_000L

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
    }

    /** Called after every poll; works off the latest cached data and dedupes itself. */
    fun evaluate(context: Context, cache: UsageCache) {
        ensureChannels(context)
        for (profile in Profile.entries) {
            evaluateProfile(context, cache, profile)
        }
    }

    private fun evaluateProfile(context: Context, cache: UsageCache, profile: Profile) {
        val snapshot = cache.snapshot(profile)

        // Re-auth alert: once per failure episode, cleared (and the notification
        // dismissed) when auth recovers.
        if (snapshot.authState == AuthState.REAUTH_NEEDED) {
            if (cache.authAlertsEnabled() && !cache.reauthNotified(profile)) {
                notify(
                    context, profile, notifId(profile, 3), CHANNEL_AUTH,
                    "${profile.label}: Claude Cooldown needs re-auth",
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

        if (cache.authAlertsEnabled() && snapshot.authState == AuthState.OK) {
            checkUpcomingExpiry(context, cache, profile)
            checkStaleData(context, cache, profile, snapshot)
        }

        val data = snapshot.data ?: return
        val use24h = cache.use24hTime()

        // Window-reset detection: the window's identity is its resets_at. When it
        // changes, the previous window ended — tell the user Claude is fresh again.
        if (cache.resetAlertsEnabled()) {
            checkReset(context, cache, profile, "Session", "5-hour", data.session)
            checkReset(context, cache, profile, "Weekly", "7-day", data.weekly)
        } else {
            // Keep tracking identity silently so re-enabling doesn't false-fire.
            data.session?.resetsAt?.let { cache.setLastSeenWindowKey(profile, "Session", it.toEpochMilli()) }
            data.weekly?.resetsAt?.let { cache.setLastSeenWindowKey(profile, "Weekly", it.toEpochMilli()) }
        }

        if (!cache.alertsEnabled()) return

        data.session?.let { w ->
            checkThresholds(
                context, cache, profile, w,
                keyName = "sessionAlert",
                thresholds = SESSION_THRESHOLDS,
                notificationId = notifId(profile, 1),
                title = { pct -> "${profile.label}: 5-hour window at $pct%" },
                use24h = use24h,
            )
        }
        data.weekly?.let { w ->
            checkThresholds(
                context, cache, profile, w,
                keyName = "weeklyAlert",
                thresholds = listOf(WEEKLY_THRESHOLD),
                notificationId = notifId(profile, 2),
                title = { pct -> "${profile.label}: 7-day window at $pct%" },
                use24h = use24h,
            )
        }
        // Per-model 7-day caps (e.g. an Opus limit) — same machinery, keyed
        // by model name so caps appearing/disappearing dedupe independently.
        data.modelCaps.forEachIndexed { index, cap ->
            checkThresholds(
                context, cache, profile, cap.window,
                keyName = "modelAlert.${cap.modelName}",
                thresholds = listOf(WEEKLY_THRESHOLD),
                notificationId = notifId(profile, MODEL_CAP_KIND_BASE + index),
                title = { pct -> "${profile.label}: ${cap.modelName} 7-day cap at $pct%" },
                use24h = use24h,
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
            "${profile.label}: sign-in expires in ${Fmt.dhm(expiry)}",
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
                    "${profile.label}: usage data is stale",
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
    ) {
        val key = window?.resetsAt?.toEpochMilli() ?: return
        val lastSeen = cache.lastSeenWindowKey(profile, windowName)
        if (lastSeen != 0L && lastSeen != key && Instant.ofEpochMilli(lastSeen).isBefore(Instant.now())) {
            val pct = (window.percent ?: 0.0).toInt()
            notify(
                context, profile, notifId(profile, if (windowName == "Session") 4 else 5), CHANNEL_RESET,
                "${profile.label}: $windowLabel window reset",
                "Usage is back at $pct%. Next reset ${Fmt.relIn(window.resetsAt)}.",
            )
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
    ) {
        val pct = window.percent?.toInt() ?: return
        val windowKey = window.resetsAt?.toEpochMilli() ?: return

        if (cache.alertKey(profile, keyName) != windowKey) {
            cache.setAlertState(profile, keyName, windowKey, 0)
        }
        val alreadyNotified = cache.alertThreshold(profile, keyName)
        val crossed = thresholds.firstOrNull { pct >= it && alreadyNotified < it } ?: return

        notify(context, profile, notificationId, CHANNEL_USAGE, title(pct), resetLine(window.resetsAt, use24h))
        cache.setAlertState(profile, keyName, windowKey, crossed)
    }

    private fun notifId(profile: Profile, kind: Int): Int =
        kind + if (profile == Profile.WORK) 100 else 0

    private fun resetLine(resetsAt: Instant?, use24h: Boolean): String {
        resetsAt ?: return ""
        val abs = Fmt.dayTime(resetsAt, use24h)
        val rel = Fmt.relIn(resetsAt)
        return if (rel == "any moment") "Resets any moment ($abs)" else "Resets $rel — $abs"
    }

    private fun notify(
        context: Context,
        profile: Profile,
        id: Int,
        channel: String,
        title: String,
        text: String,
    ) {
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
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently skip.
        }
    }
}
