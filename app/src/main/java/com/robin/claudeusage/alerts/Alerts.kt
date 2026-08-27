package com.robin.claudeusage.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.robin.claudeusage.MainActivity
import com.robin.claudeusage.R
import com.robin.claudeusage.data.AuthState
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.SessionLog
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.diag.AppLog
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.notify.Conditions
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

    /**
     * The widest kind in use — [PACE_WEEKLY_KIND]. Bounds the per-slot ID stride below and
     * the range [cancelAllFor] sweeps, so both follow the kinds automatically.
     */
    const val MAX_KIND = PACE_WEEKLY_KIND

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
        for (profile in cache.registry().all()) {
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
            // CCRM-44 (One Surface): folded, re-auth is a derived condition strip on the
            // pinned panel — no notification. The flag resets so that switching the
            // pinned notification off mid-episode posts the standalone alert once, just
            // as if the episode had started then.
            if (Conditions.foldedInto(cache)) {
                NotificationManagerCompat.from(context).cancel(notifId(profile, 3))
                cache.setReauthNotified(profile, false)
            } else if (cache.authAlertsEnabled() && !cache.reauthNotified(profile)) {
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
                title = { pct -> "5-hour window at $pct%" },
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
                title = { pct -> "7-day window at $pct%" },
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
                title = { pct -> "${cap.modelName} 7-day cap at $pct%" },
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
        // CCBG-12 (Status Icon Swap): when the pinned notification is showing this profile
        // it draws the expiry as a condition strip instead, and a second notification here
        // would be the very thing that costs us the live status-bar meter. Any copy posted
        // before the fold — or before the pinned notification was switched on — is cleared.
        if (Conditions.foldedInto(cache)) {
            NotificationManagerCompat.from(context).cancel(notifId(profile, 6))
            return
        }
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
        // CCBG-12 (Status Icon Swap): folded into the pinned notification's panel when it is
        // showing this profile — see [checkUpcomingExpiry] for why.
        if (Conditions.foldedInto(cache)) {
            if (cache.staleNotified(profile)) {
                NotificationManagerCompat.from(context).cancel(notifId(profile, 7))
                cache.setStaleNotified(profile, false)
            }
            return
        }
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
                val id = notifId(profile, if (windowName == "Session") 4 else 5)
                // Unprefixed — see [checkThresholds]'s `title` and CCBG-16.
                val title = "$windowLabel window reset"
                val text = "Usage is back at ${pct.toInt()}%. Next reset ${Fmt.relIn(window.resetsAt)}."
                if (Conditions.foldedInto(cache)) {
                    // CCRM-44 (One Surface): "you're back" is momentary, so the strip
                    // takes a fixed half hour rather than the alert-lifetime setting.
                    foldEvent(context, cache, profile, "reset.$windowName", id, title, text,
                        expiresAt = System.currentTimeMillis() + RESET_STRIP_MS)
                } else {
                    notify(
                        context, profile, id, CHANNEL_RESET,
                        "${cache.profileLabel(profile)}: $title", text,
                        // Under "auto", lives until the fresh window resets in turn — after
                        // which "your window reset" is about a window two generations old.
                        timeoutMs = eventTimeout(cache, window.resetsAt),
                    )
                }
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
        /**
         * The alert sentence for a percentage, **without** the account-name prefix —
         * CCBG-16 (Stale Strip Label). A folded strip stores it as-is and gets its label
         * at draw time; the standalone notification prefixes it here, where nothing else
         * names the account.
         */
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

        if (Conditions.foldedInto(cache)) {
            // CCRM-44 (One Surface): a strip instead of a notification. The dedup state
            // advances the same either way, so flipping the pinned notification off
            // later never replays a threshold this window already crossed.
            foldEvent(context, cache, profile, keyName, notificationId,
                title(pct), resetLine(window.resetsAt, use24h),
                expiresAt = System.currentTimeMillis() + eventTimeout(cache, window.resetsAt))
        } else {
            notify(
                context, profile, notificationId, CHANNEL_USAGE,
                "${cache.profileLabel(profile)}: ${title(pct)}", resetLine(window.resetsAt, use24h),
                timeoutMs = eventTimeout(cache, window.resetsAt),
            )
        }
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
            val (title, text) = paceCopy(headline, windowLabel, window, usedPct, estimate, use24h)
            // CCRM-44 (One Surface): folding always "delivers" — the strip write is a
            // pref, it cannot fail the way a notification post can.
            val posted = if (Conditions.foldedInto(cache)) {
                foldEvent(context, cache, profile, "pace.$windowName", notificationId, title, text,
                    expiresAt = System.currentTimeMillis() + eventTimeout(cache, window.resetsAt))
                true
            } else notify(
                context, profile, notificationId, CHANNEL_PACE,
                "${cache.profileLabel(profile)}: $title", text,
                timeoutMs = eventTimeout(cache, window.resetsAt),
            )
            if (posted) {
                val delivered = step.fire.fold(0) { acc, m -> acc or m.bit }
                state = state.copy(firedMask = state.firedMask or delivered)
            }
        }
        cache.setPaceState(profile, windowName, state)
    }

    private fun paceCopy(
        milestone: Projection.PaceMilestone,
        windowLabel: String,
        window: UsageWindow,
        usedPct: Double,
        estimate: Projection.Estimate?,
        use24h: Boolean,
    ): Pair<String, String> = when (milestone) {
        Projection.PaceMilestone.WILL_RUN_OUT -> {
            val hits = estimate?.hitsLimitAtMs
            "$windowLabel window will run out early" to if (hits != null) {
                "At this pace, 100% around ${Fmt.dayTime(Instant.ofEpochMilli(hits), use24h)} — " +
                    "${Fmt.span(window.resetsAt!!.toEpochMilli() - hits)} before the reset."
            } else {
                // SPENT without a projection: it already happened.
                "Nothing left at ${usedPct.toInt()}%. ${resetLine(window.resetsAt, use24h)}"
            }
        }
        Projection.PaceMilestone.CUTTING_IT_CLOSE ->
            "cutting it close on the $windowLabel window" to
                "Projected ~${Math.round(estimate?.pctAtReset ?: usedPct)}% by the reset. " +
                resetLine(window.resetsAt, use24h)
        Projection.PaceMilestone.ALMOST_OUT ->
            "$windowLabel window almost out" to
                "Under 10% left. ${resetLine(window.resetsAt, use24h)}"
    }

    /**
     * CCRM-6 (Multi-Account): the ID scheme is `kind + slot * 100`. Kinds top out at
     * [MAX_KIND] = 31, so the ×100 stride can't collide however many accounts exist — and
     * slots 0/1 reproduce the pre-CCRM-6 IDs exactly (`kind` and `kind + 100`), so nothing
     * churns on upgrade. It replaces a `+100 only for WORK` test under which every third
     * account silently overwrote Personal's notifications.
     */
    fun notifId(profile: Profile, kind: Int): Int = kind + profile.slot * 100

    /**
     * Dismisses every notification this profile could have posted — its whole ID range.
     * Used by account removal (CCRM-6 phase 4), which has to happen while the slot is still
     * known or an orphan sits in the shade forever.
     */
    fun cancelAllFor(context: Context, profile: Profile) {
        val nm = NotificationManagerCompat.from(context)
        for (kind in 1..MAX_KIND) nm.cancel(notifId(profile, kind))
    }

    private fun resetLine(resetsAt: Instant?, use24h: Boolean): String {
        resetsAt ?: return ""
        val abs = Fmt.dayTime(resetsAt, use24h)
        val rel = Fmt.relIn(resetsAt)
        return if (rel == "any moment") "Resets any moment ($abs)" else "Resets $rel — $abs"
    }

    /** Passed as [notify]'s timeout when an alert must stay until resolved or dismissed. */
    const val NO_TIMEOUT = -1L

    /** How long a folded "window reset" strip lives — the moment passes quickly. */
    private const val RESET_STRIP_MS = 30 * 60_000L

    /**
     * CCRM-44 (One Surface): records an event as a pinned-panel strip instead of a
     * notification, and clears any standalone copy of the same alert left over from
     * before the pinned notification was switched on. The panel re-renders at the
     * end of [evaluate], so the strip is visible on the same poll that fired it.
     *
     * [title] arrives **unprefixed** and the owning [Profile.key] is stored beside it, so
     * the account name is composed when the panel draws — CCBG-16 (Stale Strip Label).
     * Storing finished text was what froze the old name into the strip.
     */
    private fun foldEvent(
        context: Context,
        cache: UsageCache,
        profile: Profile,
        kind: String,
        notificationId: Int,
        title: String,
        text: String,
        expiresAt: Long,
    ) {
        cache.addFoldedEvent(
            profile,
            UsageCache.FoldedEvent(
                kind = kind, profileKey = profile.key, title = title, detail = text,
                firedAt = System.currentTimeMillis(), expiresAt = expiresAt,
            ),
        )
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    /**
     * CCBG-12 (Status Icon Swap): how long a one-off event alert should live, honouring
     * the user's "keep alerts for" setting.
     *
     * @param windowResetsAt when the window this alert is about resets — what "auto"
     *   keys off. Null for an event with no window behind it (the update notice), which
     *   falls back to an hour rather than never expiring.
     */
    fun eventTimeout(cache: UsageCache, windowResetsAt: Instant?): Long {
        com.robin.claudeusage.notify.StripRules.explicitLifetimeMs(cache.alertLifetime())
            ?.let { return it }
        // "auto" — until the window this alert is about resets.
        val left = windowResetsAt?.toEpochMilli()?.minus(System.currentTimeMillis())
        // A window already past would mean "expire immediately", which would eat
        // the alert before it could be read. An hour is the floor either way.
        return if (left == null || left <= 0) 60 * 60_000L else left
    }

    /**
     * Whether alerts should be drawn with our own compact type scale rather than the
     * platform's. The pinned notification's "big" style renders its own view at 13sp/12sp;
     * every other style uses the platform's sizes. Matching whichever is in play keeps one
     * type scale in the shade instead of two. See `notif_alert.xml`.
     */
    private fun useCompactScale(cache: UsageCache): Boolean =
        cache.pinnedEnabled() && cache.pinnedStyle() == "big"

    /** @return whether the notification was actually posted — pace alerts roll back on false. */
    private fun notify(
        context: Context,
        profile: Profile,
        id: Int,
        channel: String,
        title: String,
        text: String,
        timeoutMs: Long = NO_TIMEOUT,
    ): Boolean {
        // Request code = notification id (already unique per profile+kind), so
        // a Work alert's intent isn't recycled with the Personal extra.
        val openApp = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).putExtra("profile", profile.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_bars)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
        if (timeoutMs != NO_TIMEOUT) builder.setTimeoutAfter(timeoutMs)

        // Content title/text stay set above regardless: they are what the heads-up,
        // the lock screen and accessibility read, and what a launcher badge shows.
        // The custom views only replace the shade row's own text block.
        if (useCompactScale(UsageCache(context))) {
            builder.setCustomContentView(alertView(context, R.layout.notif_alert, title, text))
            builder.setCustomBigContentView(
                alertView(context, R.layout.notif_alert_expanded, title, text)
            )
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
        }

        return try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
            // CCRM-34 (Diagnostics Log): titles carry no secrets — percentages and
            // window names only.
            AppLog.log(context, AppLog.Level.INFO, "alerts", profile, "posted [$channel] $title")
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently skip.
            AppLog.log(
                context, AppLog.Level.WARN, "alerts", profile,
                "post blocked — notifications permission missing",
            )
            false
        }
    }

    private fun alertView(context: Context, layout: Int, title: String, text: String) =
        RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.sub, text)
        }
}
