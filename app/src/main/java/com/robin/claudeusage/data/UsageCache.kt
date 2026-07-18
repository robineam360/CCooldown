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

    fun use24hTime(): Boolean = prefs.getBoolean("use24hTime", false)

    fun setUse24hTime(enabled: Boolean) {
        prefs.edit().putBoolean("use24hTime", enabled).apply()
    }

    fun themeColorName(): String = prefs.getString("themeColor", "Claude Orange") ?: "Claude Orange"

    fun setThemeColorName(name: String) {
        prefs.edit().putString("themeColor", name).apply()
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
