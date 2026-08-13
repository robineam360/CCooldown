package com.robin.claudeusage.data

import android.content.Context
import android.content.SharedPreferences

enum class AuthState { NO_CREDENTIALS, OK, REAUTH_NEEDED }

data class Snapshot(
    val rawJson: String?,
    val fetchedAt: Long,        // epoch millis of last successful fetch, 0 = never
    val lastStatus: String,     // human-readable outcome of the last attempt
    val lastAttemptAt: Long,
    val authState: AuthState,
) {
    // Lazy, not get(): the UI reads this several times per composition and
    // on a 5-second tick — one parse per snapshot is plenty.
    val data: UsageData? by lazy { rawJson?.let { UsageParser.parse(it) } }
}

/**
 * Plain (non-secret) cache. Per-profile state (payload, status, backoff, alert
 * dedupe) is keyed with the profile prefix; app-wide settings are unprefixed.
 * Personal keys carry no prefix so v0.5 data migrates transparently.
 */
class UsageCache(context: Context) {

    companion object {
        const val RESET_OFF = "off"
        const val RESET_SMART = "smart"
        const val RESET_ALWAYS = "always"

        /** In smart mode a reset ping fires only if the window had reached this. */
        const val SMART_RESET_MIN_PCT = 80.0
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("usage_cache", Context.MODE_PRIVATE)

    private fun k(profile: Profile, name: String): String =
        if (profile == Profile.PERSONAL) name else "${profile.key}.$name"

    fun snapshot(profile: Profile): Snapshot = Snapshot(
        rawJson = prefs.getString(k(profile, "rawJson"), null),
        fetchedAt = prefs.getLong(k(profile, "fetchedAt"), 0L),
        lastStatus = prefs.getString(k(profile, "lastStatus"), "Never fetched") ?: "Never fetched",
        lastAttemptAt = prefs.getLong(k(profile, "lastAttemptAt"), 0L),
        authState = AuthState.valueOf(
            prefs.getString(k(profile, "authState"), AuthState.NO_CREDENTIALS.name)
                ?: AuthState.NO_CREDENTIALS.name
        ),
    )

    fun saveSuccess(profile: Profile, rawJson: String, now: Long) {
        prefs.edit()
            .putString(k(profile, "rawJson"), rawJson)
            .putLong(k(profile, "fetchedAt"), now)
            .putString(k(profile, "lastStatus"), "OK")
            .putLong(k(profile, "lastAttemptAt"), now)
            .putString(k(profile, "authState"), AuthState.OK.name)
            .putInt(k(profile, "consecutive429"), 0)
            .putLong(k(profile, "backoffUntil"), 0L)
            .putBoolean(k(profile, "staleNotified"), false)
            .apply()
    }

    fun saveFailure(profile: Profile, status: String, now: Long, authState: AuthState? = null) {
        val e = prefs.edit()
            .putString(k(profile, "lastStatus"), status)
            .putLong(k(profile, "lastAttemptAt"), now)
        if (authState != null) e.putString(k(profile, "authState"), authState.name)
        e.apply()
    }

    fun setAuthState(profile: Profile, state: AuthState) {
        prefs.edit().putString(k(profile, "authState"), state.name).apply()
    }

    // --- 429 backoff: 5 min * 2^n, capped at 60 min ---

    fun backoffUntil(profile: Profile): Long = prefs.getLong(k(profile, "backoffUntil"), 0L)

    fun bumpBackoff(profile: Profile, now: Long): Long {
        val n = prefs.getInt(k(profile, "consecutive429"), 0)
        val delayMs = (5L * 60_000L shl n.coerceAtMost(4)).coerceAtMost(60L * 60_000L)
        val until = now + delayMs
        prefs.edit()
            .putInt(k(profile, "consecutive429"), n + 1)
            .putLong(k(profile, "backoffUntil"), until)
            .apply()
        return until
    }

    // --- app-wide settings ---

    fun pollIntervalMinutes(): Long = prefs.getLong("pollIntervalMin", 15L)

    fun setPollIntervalMinutes(min: Long) {
        prefs.edit().putLong("pollIntervalMin", min.coerceAtLeast(5L)).apply()
    }

    fun alertsEnabled(): Boolean = prefs.getBoolean("alertsEnabled", true)

    fun setAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("alertsEnabled", enabled).apply()
    }

    fun resetAlertsEnabled(): Boolean = prefs.getBoolean("resetAlertsEnabled", true)

    fun setResetAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("resetAlertsEnabled", enabled).apply()
    }

    fun authAlertsEnabled(): Boolean = prefs.getBoolean("authAlertsEnabled", true)

    fun setAuthAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("authAlertsEnabled", enabled).apply()
    }

    /** Display name for a profile — the fixed key stays, only the label is editable. */
    fun profileLabel(profile: Profile): String =
        prefs.getString(k(profile, "customLabel"), null)?.takeIf { it.isNotBlank() }
            ?: profile.label

    fun setProfileLabel(profile: Profile, label: String) {
        val trimmed = label.trim().take(16)
        prefs.edit().apply {
            if (trimmed.isEmpty() || trimmed == profile.label) remove(k(profile, "customLabel"))
            else putString(k(profile, "customLabel"), trimmed)
        }.apply()
    }

    // --- granular alert settings ---

    fun sessionAlertThresholds(): Set<Int> = thresholdSet("sessionAlertThresholds", setOf(80, 95))

    fun setSessionAlertThresholds(values: Set<Int>) = putThresholdSet("sessionAlertThresholds", values)

    fun weeklyAlertThresholds(): Set<Int> = thresholdSet("weeklyAlertThresholds", setOf(90))

    fun setWeeklyAlertThresholds(values: Set<Int>) = putThresholdSet("weeklyAlertThresholds", values)

    fun modelCapAlertThresholds(): Set<Int> = thresholdSet("modelCapAlertThresholds", setOf(90))

    fun setModelCapAlertThresholds(values: Set<Int>) = putThresholdSet("modelCapAlertThresholds", values)

    private fun thresholdSet(name: String, default: Set<Int>): Set<Int> {
        // Until the chips are touched, the pre-granularity master switch decides.
        val raw = prefs.getString(name, null)
            ?: return if (alertsEnabled()) default else emptySet()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private fun putThresholdSet(name: String, values: Set<Int>) {
        prefs.edit().putString(name, values.sorted().joinToString(",")).apply()
    }

    /** Reset-ping behavior per window kind ("Session"/"Weekly"): off, smart, or always. */
    fun resetPingMode(window: String): String =
        prefs.getString("resetMode$window", null) ?: when {
            !resetAlertsEnabled() -> RESET_OFF // pre-granularity toggle carries over
            window == "Session" -> RESET_SMART
            else -> RESET_ALWAYS
        }

    fun setResetPingMode(window: String, mode: String) {
        prefs.edit().putString("resetMode$window", mode).apply()
    }

    fun profileAlertsEnabled(profile: Profile): Boolean =
        prefs.getBoolean(k(profile, "profileAlertsEnabled"), true)

    fun setProfileAlertsEnabled(profile: Profile, enabled: Boolean) {
        prefs.edit().putBoolean(k(profile, "profileAlertsEnabled"), enabled).apply()
    }

    fun healthAlertsEnabled(): Boolean = prefs.getBoolean("healthAlertsEnabled", authAlertsEnabled())

    fun setHealthAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("healthAlertsEnabled", enabled).apply()
    }

    // --- pace alerts (CCRM-21): projection-based milestones -------------------------

    fun paceAlertsEnabled(): Boolean = prefs.getBoolean("paceAlertsEnabled", true)

    fun setPaceAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("paceAlertsEnabled", enabled).apply()
    }

    /** Per-milestone toggle; [milestone] is a [Projection.PaceMilestone] name. */
    fun paceMilestoneEnabled(milestone: String): Boolean =
        prefs.getBoolean("paceMilestone.$milestone", true)

    fun setPaceMilestoneEnabled(milestone: String, enabled: Boolean) {
        prefs.edit().putBoolean("paceMilestone.$milestone", enabled).apply()
    }

    /**
     * Pace state per profile+window: the window identity plus which milestones have
     * fired in it. Null when nothing was recorded yet — the primed guard's signal.
     */
    fun paceState(profile: Profile, window: String): Projection.PaceState? {
        val key = prefs.getLong(k(profile, "pace${window}Key"), 0L)
        if (key == 0L) return null
        return Projection.PaceState(key, prefs.getInt(k(profile, "pace${window}Mask"), 0))
    }

    fun setPaceState(profile: Profile, window: String, state: Projection.PaceState) {
        prefs.edit()
            .putLong(k(profile, "pace${window}Key"), state.windowKey)
            .putInt(k(profile, "pace${window}Mask"), state.firedMask)
            .apply()
    }

    // --- highest percent seen in the current window instance (drives smart reset pings) ---

    fun windowPeak(profile: Profile, window: String): Double =
        prefs.getFloat(k(profile, "peak$window"), 0f).toDouble()

    fun setWindowPeak(profile: Profile, window: String, pct: Double) {
        prefs.edit().putFloat(k(profile, "peak$window"), pct.toFloat()).apply()
    }

    // --- pinned (ongoing) usage notification ---

    fun pinnedEnabled(): Boolean = prefs.getBoolean("pinnedEnabled", false)

    fun setPinnedEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("pinnedEnabled", enabled).apply()
    }

    fun pinnedProfile(): Profile = Profile.fromKey(prefs.getString("pinnedProfile", null))

    fun setPinnedProfile(profile: Profile) {
        prefs.edit().putString("pinnedProfile", profile.key).apply()
    }

    /** Status-bar icon style: "pie", "ring", "battery", or "number". */
    fun pinnedIconStyle(): String = prefs.getString("pinnedIconStyle", "ring") ?: "ring"

    fun setPinnedIconStyle(style: String) {
        prefs.edit().putString("pinnedIconStyle", style).apply()
    }

    /**
     * What the Quick Settings tile puts in its subtitle: "countdown" ("resets in
     * 2h 14m", the default) or "clock" ("resets 4:12 PM"). The tile only recomputes
     * when the shade opens, so the countdown drifts while the panel stays open —
     * the clock reading never does.
     */
    fun tileSubtitle(): String = prefs.getString("tileSubtitle", "countdown") ?: "countdown"

    fun setTileSubtitle(mode: String) {
        prefs.edit().putString("tileSubtitle", mode).apply()
    }

    /**
     * How the pinned notification renders the 5-hour percentage (CCRM-3 phase 1):
     * "gauge" (the original ring, default), "number" (a big number tile in the
     * large-icon slot), "progress" (system progress bar, percentage in the title),
     * or "big" (a custom view with the largest number the collapsed row allows).
     */
    fun pinnedStyle(): String = prefs.getString("pinnedStyle", "gauge") ?: "gauge"

    fun setPinnedStyle(style: String) {
        prefs.edit().putString("pinnedStyle", style).apply()
    }

    /**
     * Where tapping the notification body goes: "app" (this app's breakdown, the
     * default) or "claude" (the Claude app, falling back to us if it isn't there).
     */
    fun pinnedTapTarget(): String = prefs.getString("pinnedTapTarget", "app") ?: "app"

    fun setPinnedTapTarget(target: String) {
        prefs.edit().putString("pinnedTapTarget", target).apply()
    }

    // --- usage credits (CCRM-1) ---

    /**
     * Whether the pay-as-you-go credits section shows for this profile. Per-profile
     * because the two accounts can be on very different billing setups — credits may
     * be meaningful on one and noise on the other.
     */
    fun creditsVisible(profile: Profile): Boolean =
        prefs.getBoolean(k(profile, "creditsVisible"), true)

    fun setCreditsVisible(profile: Profile, visible: Boolean) {
        prefs.edit().putBoolean(k(profile, "creditsVisible"), visible).apply()
    }

    /**
     * Whether widgets carry credits too. Off by default: widget height is scarce and
     * the existing layouts are already full. Gated by [creditsVisible] as well, so
     * hiding a profile's credits hides them everywhere.
     */
    fun creditsOnWidgets(): Boolean = prefs.getBoolean("creditsOnWidgets", false)

    fun setCreditsOnWidgets(enabled: Boolean) {
        prefs.edit().putBoolean("creditsOnWidgets", enabled).apply()
    }

    fun use24hTime(): Boolean = prefs.getBoolean("use24hTime", false)

    fun setUse24hTime(enabled: Boolean) {
        prefs.edit().putBoolean("use24hTime", enabled).apply()
    }

    fun themeColorName(): String = prefs.getString("themeColor", "Claude Orange") ?: "Claude Orange"

    fun setThemeColorName(name: String) {
        prefs.edit().putString("themeColor", name).apply()
    }

    // --- automatic update checks (CCRM-28): app-global, deliberately not per-profile ---

    fun autoCheckUpdates(): Boolean = prefs.getBoolean("autoCheckUpdates", true)

    fun setAutoCheckUpdates(enabled: Boolean) {
        prefs.edit().putBoolean("autoCheckUpdates", enabled).apply()
    }

    /** Last **successful** check, epoch ms; 0 = never. A failure never advances it. */
    fun lastUpdateCheckAt(): Long = prefs.getLong("lastUpdateCheckAt", 0L)

    /** The settings line's outcome half, e.g. "up to date (v0.14)" / "v0.15 available". */
    fun lastUpdateCheckOutcome(): String? = prefs.getString("lastUpdateCheckOutcome", null)

    fun recordUpdateCheckSuccess(at: Long, outcome: String) {
        prefs.edit()
            .putLong("lastUpdateCheckAt", at)
            .putString("lastUpdateCheckOutcome", outcome)
            .remove("lastUpdateFailAt")
            .remove("lastUpdateFailReason")
            .apply()
    }

    fun lastUpdateFailAt(): Long = prefs.getLong("lastUpdateFailAt", 0L)

    fun lastUpdateFailReason(): String? = prefs.getString("lastUpdateFailReason", null)

    /** Deliberately leaves lastUpdateCheckAt alone, so the next poll retries. */
    fun recordUpdateCheckFailure(at: Long, reason: String) {
        prefs.edit()
            .putLong("lastUpdateFailAt", at)
            .putString("lastUpdateFailReason", reason)
            .apply()
    }

    /** Written only when the notification actually posted — once per version, ever. */
    fun lastNotifiedVersion(): String? = prefs.getString("lastNotifiedVersion", null)

    fun setLastNotifiedVersion(version: String) {
        prefs.edit().putString("lastNotifiedVersion", version).apply()
    }

    /** "Skip this version": silences exactly this version; a newer one still notifies. */
    fun dismissedUpdateVersion(): String? = prefs.getString("dismissedUpdateVersion", null)

    fun setDismissedUpdateVersion(version: String) {
        prefs.edit().putString("dismissedUpdateVersion", version).apply()
    }

    // --- alert dedupe state: one alert per threshold per window instance ---

    fun alertKey(profile: Profile, name: String): Long = prefs.getLong(k(profile, "${name}Key"), 0L)

    fun alertThreshold(profile: Profile, name: String): Int =
        prefs.getInt(k(profile, "${name}Threshold"), 0)

    fun setAlertState(profile: Profile, name: String, key: Long, threshold: Int) {
        prefs.edit()
            .putLong(k(profile, "${name}Key"), key)
            .putInt(k(profile, "${name}Threshold"), threshold)
            .apply()
    }

    fun reauthNotified(profile: Profile): Boolean =
        prefs.getBoolean(k(profile, "reauthNotified"), false)

    fun setReauthNotified(profile: Profile, notified: Boolean) {
        prefs.edit().putBoolean(k(profile, "reauthNotified"), notified).apply()
    }

    // --- token health metadata (informational fields from the pasted JSON) ---

    /** Stored at paste time; cleared when a renewal rotates the refresh token. */
    fun refreshExpiresAt(profile: Profile): Long = prefs.getLong(k(profile, "refreshExpiresAt"), 0L)

    fun plan(profile: Profile): String? = prefs.getString(k(profile, "plan"), null)

    fun setTokenMeta(profile: Profile, refreshExpiresAt: Long, plan: String?) {
        prefs.edit()
            .putLong(k(profile, "refreshExpiresAt"), refreshExpiresAt)
            .putString(k(profile, "plan"), plan)
            .apply()
    }

    fun clearRefreshExpiry(profile: Profile) {
        prefs.edit().putLong(k(profile, "refreshExpiresAt"), 0L).apply()
    }

    /**
     * True when the stored refresh-expiry is our own ~30-day estimate from a
     * native phone sign-in (the token response omits the real date), not an
     * exact value read from a pasted desktop token. Drives "expires around …".
     */
    fun refreshExpiryEstimated(profile: Profile): Boolean =
        prefs.getBoolean(k(profile, "refreshExpiryEstimated"), false)

    fun setRefreshExpiryEstimated(profile: Profile, estimated: Boolean) {
        prefs.edit().putBoolean(k(profile, "refreshExpiryEstimated"), estimated).apply()
    }

    /**
     * True when this profile was authenticated by native sign-in on the phone.
     * Its family rotates as normal healthy renewal, so — unlike a shared desktop
     * copy — a rotated refresh token must NOT clear the estimated expiry.
     */
    fun nativeSignIn(profile: Profile): Boolean =
        prefs.getBoolean(k(profile, "nativeSignIn"), false)

    fun setNativeSignIn(profile: Profile, native: Boolean) {
        prefs.edit().putBoolean(k(profile, "nativeSignIn"), native).apply()
    }

    fun lastRenewedAt(profile: Profile): Long = prefs.getLong(k(profile, "lastRenewedAt"), 0L)

    fun setLastRenewedAt(profile: Profile, at: Long) {
        prefs.edit().putLong(k(profile, "lastRenewedAt"), at).apply()
    }

    /** First moment the current streak of failed renewals started; 0 = no streak. */
    fun firstRefreshFailAt(profile: Profile): Long = prefs.getLong(k(profile, "firstRefreshFailAt"), 0L)

    fun setFirstRefreshFailAt(profile: Profile, at: Long) {
        prefs.edit().putLong(k(profile, "firstRefreshFailAt"), at).apply()
    }

    fun staleNotified(profile: Profile): Boolean = prefs.getBoolean(k(profile, "staleNotified"), false)

    fun setStaleNotified(profile: Profile, notified: Boolean) {
        prefs.edit().putBoolean(k(profile, "staleNotified"), notified).apply()
    }

    // --- reset detection: last seen window identity (its resets_at) per window kind ---

    fun lastSeenWindowKey(profile: Profile, window: String): Long =
        prefs.getLong(k(profile, "seen${window}Key"), 0L)

    fun setLastSeenWindowKey(profile: Profile, window: String, key: Long) {
        prefs.edit().putLong(k(profile, "seen${window}Key"), key).apply()
    }

    // --- window pings (CCRM-17): per profile, OFF unless the user turns it on ---

    /**
     * Default **false**, deliberately. A ping spends the user's own subscription quota
     * on an automated request, so it is never something the app starts doing on its own
     * — and this being per-profile is what keeps a Team account out of it by default.
     */
    fun pingEnabled(profile: Profile): Boolean = prefs.getBoolean(k(profile, "pingEnabled"), false)

    fun setPingEnabled(profile: Profile, enabled: Boolean) {
        prefs.edit().putBoolean(k(profile, "pingEnabled"), enabled).apply()
    }

    /** Minutes past local midnight, default 04:00. */
    fun pingFirstMinuteOfDay(profile: Profile): Int =
        prefs.getInt(k(profile, "pingFirstMinute"), 4 * 60)

    fun setPingFirstMinuteOfDay(profile: Profile, minuteOfDay: Int) {
        prefs.edit().putInt(k(profile, "pingFirstMinute"), minuteOfDay).apply()
    }

    /** Extra windows after the first, default 3 — the user's 4am/9am/2pm/7pm example. */
    fun pingRenewals(profile: Profile): Int = prefs.getInt(k(profile, "pingRenewals"), 3)

    fun setPingRenewals(profile: Profile, renewals: Int) {
        prefs.edit().putInt(k(profile, "pingRenewals"), renewals).apply()
    }

    /** Minutes past local midnight; 0 means end of day. Default 0 (don't run into tomorrow). */
    fun pingCutoffMinuteOfDay(profile: Profile): Int = prefs.getInt(k(profile, "pingCutoffMinute"), 0)

    fun setPingCutoffMinuteOfDay(profile: Profile, minuteOfDay: Int) {
        prefs.edit().putInt(k(profile, "pingCutoffMinute"), minuteOfDay).apply()
    }

    fun pingConfig(profile: Profile): PingSchedule.Config = PingSchedule.Config(
        enabled = pingEnabled(profile),
        firstPingMinuteOfDay = pingFirstMinuteOfDay(profile),
        renewals = pingRenewals(profile),
        cutoffMinuteOfDay = pingCutoffMinuteOfDay(profile),
    )

    /**
     * Windows opened today, so renewals are bounded. Stored as an ISO date string
     * rather than millis so a day rollover is unambiguous across time zones.
     */
    fun pingDayState(profile: Profile): PingSchedule.DayState = PingSchedule.DayState(
        day = prefs.getString(k(profile, "pingDay"), null)?.let {
            try {
                java.time.LocalDate.parse(it)
            } catch (_: Exception) {
                null
            }
        },
        windowsStarted = prefs.getInt(k(profile, "pingWindowsStarted"), 0),
    )

    fun recordPingWindowStarted(profile: Profile, day: java.time.LocalDate) {
        val current = pingDayState(profile)
        val started = if (current.day == day) current.windowsStarted + 1 else 1
        prefs.edit()
            .putString(k(profile, "pingDay"), day.toString())
            .putInt(k(profile, "pingWindowsStarted"), started)
            .apply()
    }

    /** Human-readable outcome of the last ping, for the settings status row. */
    fun pingLastResult(profile: Profile): String? = prefs.getString(k(profile, "pingLastResult"), null)

    fun pingLastAttemptAt(profile: Profile): Long = prefs.getLong(k(profile, "pingLastAttemptAt"), 0L)

    /** True when the last attempt failed, so the row can be styled as a problem. */
    fun pingLastFailed(profile: Profile): Boolean =
        prefs.getBoolean(k(profile, "pingLastFailed"), false)

    fun setPingOutcome(profile: Profile, at: Long, result: String, failed: Boolean) {
        prefs.edit()
            .putLong(k(profile, "pingLastAttemptAt"), at)
            .putString(k(profile, "pingLastResult"), result)
            .putBoolean(k(profile, "pingLastFailed"), failed)
            .putInt(k(profile, "pingRevision"), prefs.getInt(k(profile, "pingRevision"), 0) + 1)
            .apply()
    }

    /** Which **send**-failure retry step the current slot is on; reset once it resolves. */
    fun pingRetryIndex(profile: Profile): Int = prefs.getInt(k(profile, "pingRetryIndex"), 0)

    fun setPingRetryIndex(profile: Profile, index: Int) {
        prefs.edit().putInt(k(profile, "pingRetryIndex"), index).apply()
    }

    // --- deferred verification state (CCBG-5) ---

    /** When we last actually sent a ping. Backs [PingSchedule.tooSoonToSend]. */
    fun pingLastSentAt(profile: Profile): Long = prefs.getLong(k(profile, "pingLastSentAt"), 0L)

    /**
     * The `resets_at` observed immediately *before* the pending ping, so the deferred
     * check knows what "moved" means. -1 means "no window was open", which is distinct
     * from 0 ("nothing pending").
     */
    fun pingPendingBefore(profile: Profile): Long = prefs.getLong(k(profile, "pingPendingBefore"), 0L)

    fun pingVerifyAttempt(profile: Profile): Int = prefs.getInt(k(profile, "pingVerifyAttempt"), 0)

    fun startPingVerification(profile: Profile, sentAt: Long, beforeMs: Long?) {
        prefs.edit()
            .putLong(k(profile, "pingLastSentAt"), sentAt)
            .putLong(k(profile, "pingPendingBefore"), beforeMs ?: -1L)
            .putInt(k(profile, "pingVerifyAttempt"), 0)
            .apply()
    }

    fun setPingVerifyAttempt(profile: Profile, attempt: Int) {
        prefs.edit().putInt(k(profile, "pingVerifyAttempt"), attempt).apply()
    }

    fun clearPingVerification(profile: Profile) {
        prefs.edit()
            .remove(k(profile, "pingPendingBefore"))
            .remove(k(profile, "pingVerifyAttempt"))
            .apply()
    }

    /** Bumped whenever a ping outcome is written, so the settings row can observe it. */
    fun pingOutcomeRevision(profile: Profile): Int = prefs.getInt(k(profile, "pingRevision"), 0)
}

/** Per-widget configuration chosen in the setup screen when a widget is placed. */
class WidgetPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)

    fun profileFor(appWidgetId: Int): Profile =
        Profile.fromKey(prefs.getString("w$appWidgetId.profile", null))

    fun barFor(appWidgetId: Int): String =
        prefs.getString("w$appWidgetId.bar", "session") ?: "session"

    fun save(appWidgetId: Int, profile: Profile, bar: String?) {
        val e = prefs.edit().putString("w$appWidgetId.profile", profile.key)
        if (bar != null) e.putString("w$appWidgetId.bar", bar)
        e.apply()
    }

    fun remove(appWidgetId: Int) {
        prefs.edit()
            .remove("w$appWidgetId.profile")
            .remove("w$appWidgetId.bar")
            .apply()
    }
}
