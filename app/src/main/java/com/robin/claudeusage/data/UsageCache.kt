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
    /** CCRM-27 (Error Taxonomy): the typed kind behind [lastStatus]. */
    val lastStatusKind: String = ErrorKind.INTERNAL.key,
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

        /**
         * Every fixed per-profile entry name, for [clearProfile]'s legacy path. Kept next to
         * the getters that write them: adding a `k(profile, "…")` key without adding it here
         * leaves residue behind on removal. The runtime-built families (`pace…`, `peak…`,
         * `seen…Key`, `modelAlert.…`) are handled separately in [clearProfile].
         */
        private val LEGACY_PROFILE_KEYS = listOf(
            "rawJson", "fetchedAt", "lastStatus", "lastStatusKind", "lastAttemptAt",
            "authState", "plan", "tier", "signInTokenKeys", "nativeSignIn",
            "refreshExpiresAt", "refreshExpiryEstimated", "lastRenewedAt",
            "firstRefreshFailAt", "backoffUntil", "consecutive429",
            "reauthNotified", "staleNotified", "foldedEvents",
            "profileAlertsEnabled", "creditsVisible", "customLabel",
            "sessionAlertKey", "sessionAlertThreshold",
            "weeklyAlertKey", "weeklyAlertThreshold",
            "pingEnabled", "pingFirstMinute", "pingCutoffMinute", "pingRenewals",
            "pingDay", "pingWindowsStarted", "pingRetryIndex", "pingLastSentAt",
            "pingLastAttemptAt", "pingLastResult", "pingLastFailed", "pingRevision",
            "pingPendingBefore", "pingVerifyAttempt",
        )
    }

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("usage_cache", Context.MODE_PRIVATE)

    /** The registry is the owner of labels and of the account list; see [profileLabel]. */
    private val registry: ProfileRegistry by lazy { ProfileRegistry(appContext) }

    fun registry(): ProfileRegistry = registry

    // The legacy exception, restated as a key comparison now that Profile is a value type
    // (CCRM-6 (Multi-Account)): v0.5 stored the single account's entries unprefixed, and
    // that key is a storage-format constant, so this test can never be dropped.
    private fun k(profile: Profile, name: String): String =
        if (profile.key == Profile.LEGACY_KEY) name else "${profile.key}.$name"

    fun snapshot(profile: Profile): Snapshot {
        val lastStatus =
            prefs.getString(k(profile, "lastStatus"), "Never fetched") ?: "Never fetched"
        return Snapshot(
            rawJson = prefs.getString(k(profile, "rawJson"), null),
            fetchedAt = prefs.getLong(k(profile, "fetchedAt"), 0L),
            lastStatus = lastStatus,
            lastAttemptAt = prefs.getLong(k(profile, "lastAttemptAt"), 0L),
            authState = AuthState.valueOf(
                prefs.getString(k(profile, "authState"), AuthState.NO_CREDENTIALS.name)
                    ?: AuthState.NO_CREDENTIALS.name
            ),
            // Pre-CCRM-27 installs have a status but no kind: guess from the
            // string once; the next failure writes the real kind.
            lastStatusKind = prefs.getString(k(profile, "lastStatusKind"), null)
                ?: ErrorKind.fromStatus(lastStatus).key,
        )
    }

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

    fun saveFailure(
        profile: Profile,
        status: String,
        now: Long,
        authState: AuthState? = null,
        kind: ErrorKind = ErrorKind.INTERNAL,
    ) {
        val e = prefs.edit()
            .putString(k(profile, "lastStatus"), status)
            .putString(k(profile, "lastStatusKind"), kind.key)
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

    /**
     * Display name for a profile — the key stays, only the label is editable.
     *
     * Still the read path ~40 sites use, but it resolves through the registry **by key**
     * rather than returning the [Profile]'s own field, so a rename is visible to a
     * `Profile` captured earlier in a composition, an intent extra or a widget's prefs.
     * Falls back to the captured label if the account has since been removed.
     */
    fun profileLabel(profile: Profile): String =
        registry.byKey(profile.key)?.label ?: profile.label

    fun setProfileLabel(profile: Profile, label: String) {
        registry.rename(profile.key, label)
    }

    /**
     * Forgets every per-profile entry for [profile] — CCRM-6 (Multi-Account) account
     * removal, step 4 of [UsageRepository.removeProfile]'s ordering.
     *
     * Two paths, because of the legacy exception in [k]. A prefixed profile can be
     * prefix-scanned, which is exhaustive by construction. The legacy `personal` profile's
     * entries share the bare namespace with every app-wide setting in this file — `snapshot`
     * lives at `"rawJson"`, the app's theme at `"themeMode"` — so a prefix scan there would
     * take the whole app's settings with it. Its names are enumerated instead, including the
     * three families whose names are built at runtime (per-window and per-model). The
     * `modelAlert.` scan is bounded to the `Key`/`Threshold` suffixes so it can never reach
     * the app-wide `sessionAlertThresholds`, which is one plural away from a per-profile key.
     */
    fun clearProfile(profile: Profile) {
        val e = prefs.edit()
        if (profile.key == Profile.LEGACY_KEY) {
            for (name in LEGACY_PROFILE_KEYS) e.remove(name)
            for (window in listOf("Session", "Weekly")) {
                e.remove("pace${window}Key")
                e.remove("pace${window}Mask")
                e.remove("peak$window")
                e.remove("seen${window}Key")
            }
            for (name in prefs.all.keys) {
                if (name.startsWith("modelAlert.") &&
                    (name.endsWith("Key") || name.endsWith("Threshold"))
                ) e.remove(name)
            }
        } else {
            val prefix = "${profile.key}."
            for (name in prefs.all.keys) if (name.startsWith(prefix)) e.remove(name)
        }
        e.apply()
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

    /**
     * CCBG-12 (Status Icon Swap): how long a one-off *event* alert — a reset, a
     * threshold, a pace warning, an update notice — stays in the shade before clearing
     * itself. One of "15m", "30m", "1h", "auto".
     *
     * The point is not tidiness. A second notification from this app makes Android
     * replace our live status-bar meter with the launcher icon, so an alert nobody
     * dismissed holds the status bar wrong for as long as it sits there. Expiring the
     * ones that have stopped being true gives the meter back.
     *
     * "auto" — the default — means "until the window this alert is about resets", which
     * is the only option that never expires an alert while it is still true.
     * Condition alerts (sign-in, data freshness) are not covered: they fold into the
     * pinned notification's panel and clear when the condition itself resolves.
     */
    fun alertLifetime(): String = prefs.getString("alertLifetime", "auto") ?: "auto"

    fun setAlertLifetime(value: String) {
        prefs.edit().putString("alertLifetime", value).apply()
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

    fun pinnedProfile(): Profile = registry.resolve(prefs.getString("pinnedProfile", null))

    fun setPinnedProfile(profile: Profile) {
        prefs.edit().putString("pinnedProfile", profile.key).apply()
    }

    /** Status-bar icon style: "pie", "ring", "battery", or "number". */
    /**
     * CCRM-49 (Glyph Legibility) withdrew the concentric "twin" style, so anyone left
     * holding it lands back on the default rather than on no selection at all.
     */
    fun pinnedIconStyle(): String =
        (prefs.getString("pinnedIconStyle", "ring") ?: "ring")
            .let { if (it == "twin") "ring" else it }

    fun setPinnedIconStyle(style: String) {
        prefs.edit().putString("pinnedIconStyle", style).apply()
    }

    /**
     * CCRM-23 (Reset Display): which reset form *leads* on every surface —
     * "countdown" ("resets in 2h 14m", the default) or "clock" ("resets 4:12 PM").
     * Grown from the tile-only `tileSubtitle` pref (CCRM-11), whose stored value is
     * migrated by the read-time fallback below; the tile keeps the behaviour and
     * stops owning the preference. Option A of the approved wireframe: surfaces
     * with a second slot keep the other form there — the countdown reads better,
     * the clock can't go stale, and the token only decides which one leads.
     */
    fun resetDisplay(): String =
        prefs.getString("resetDisplay", null)
            ?: prefs.getString("tileSubtitle", "countdown")
            ?: "countdown"

    fun setResetDisplay(mode: String) {
        prefs.edit().putString("resetDisplay", mode).apply()
    }

    /** The flag render sites actually branch on. */
    fun resetClock(): Boolean = resetDisplay() == "clock"

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

    // --- CCRM-43 (Bar Pace Marks): the red over-pace segment, per surface ---
    //
    // Three keys rather than one, by decision of 2026-08-13: the surfaces are read at
    // very different distances (a glance at a widget, a long look at the usage screen,
    // a notification you can't dismiss), so the appetite for red differs per surface.
    // All default ON — the behaviour approved in CCRM-39 (Ring Widget) and shipped —
    // and each gates *only* the segment. The neutral even-pace tick always draws, and
    // the 80/90/100 severity ladder is untouched: this is about pace, not severity.

    /** Bars *and* rings on the home screen: one home screen, one answer. */
    fun paceOverOnWidgets(): Boolean = prefs.getBoolean("paceOverOnWidgets", true)

    fun setPaceOverOnWidgets(enabled: Boolean) {
        prefs.edit().putBoolean("paceOverOnWidgets", enabled).apply()
    }

    fun paceOverInApp(): Boolean = prefs.getBoolean("paceOverInApp", true)

    fun setPaceOverInApp(enabled: Boolean) {
        prefs.edit().putBoolean("paceOverInApp", enabled).apply()
    }

    fun paceOverOnNotification(): Boolean = prefs.getBoolean("paceOverOnNotification", true)

    fun setPaceOverOnNotification(enabled: Boolean) {
        prefs.edit().putBoolean("paceOverOnNotification", enabled).apply()
    }

    // --- CCRM-29 (Display Mode) ---

    /**
     * "system" (default) / "light" / "dark". In-app screens only — widgets and
     * the notification follow the system, since their backdrop isn't ours.
     */
    fun themeMode(): String = prefs.getString("themeMode", "system") ?: "system"

    fun setThemeMode(mode: String) {
        prefs.edit().putString("themeMode", mode).apply()
    }

    /**
     * "system" / "12" / "24". Migration: an install that ever touched the old
     * `use24hTime` boolean keeps that explicit choice; only installs without the
     * old key get "system" — nobody's clock format flips on upgrade.
     */
    fun timeFormat(): String =
        prefs.getString("timeFormat", null)
            ?: if (prefs.contains("use24hTime")) {
                if (prefs.getBoolean("use24hTime", false)) "24" else "12"
            } else "system"

    fun setTimeFormat(mode: String) {
        prefs.edit().putString("timeFormat", mode).apply()
    }

    /** The resolved boolean every render site still reads, unchanged in shape. */
    fun use24hTime(): Boolean = when (timeFormat()) {
        "12" -> false
        "24" -> true
        else -> android.text.format.DateFormat.is24HourFormat(appContext)
    }

    // CCRM-22 (Used or Left): one app-wide display token. Every numeric usage
    // readout follows it (rev B — nothing is exempt); fills, pace ticks and the
    // warning-colour ladder never do, so a red bar can't sit beside "8% left"
    // and read as backwards.
    fun usageDisplay(): String = prefs.getString("usageDisplay", "used") ?: "used"

    fun setUsageDisplay(mode: String) {
        prefs.edit().putString("usageDisplay", mode).apply()
    }

    /** The flag render sites actually branch on. */
    fun usageLeft(): Boolean = usageDisplay() == "left"

    // CCRM-34 (Diagnostics Log): the app log's minimum level. "info" default;
    // "debug" only while someone is chasing something — a user-facing setting,
    // deliberately (OpenQuota's call, and the right one).
    fun logLevel(): String = prefs.getString("logLevel", "info") ?: "info"

    fun setLogLevel(level: String) {
        prefs.edit().putString("logLevel", level).apply()
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

    fun recordUpdateCheckSuccess(at: Long, outcome: String, latestVersion: String) {
        prefs.edit()
            .putLong("lastUpdateCheckAt", at)
            .putString("lastUpdateCheckOutcome", outcome)
            .putString("latestKnownVersion", latestVersion)
            .remove("lastUpdateFailAt")
            .remove("lastUpdateFailReason")
            .apply()
    }

    /**
     * The newest release version any check has seen, for the CCRM-44 (One Surface)
     * update strip — which persists while this is ahead of the installed version,
     * instead of the once-per-version notification.
     */
    fun latestKnownVersion(): String? = prefs.getString("latestKnownVersion", null)

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

    // --- folded event strips (CCRM-44): alerts carried by the pinned panel ---

    /**
     * One alert *event* folded into the pinned notification instead of posted
     * (CCRM-44 (One Surface)). Conditions are derived live; events are moments, so
     * they need this small persisted record to outlive the poll that fired them.
     * [kind] is the alert's dedup key (e.g. "sessionAlert", "pace.Session") — a new
     * event of the same kind replaces the old one, mirroring how the notification
     * ids replaced in place.
     */
    data class FoldedEvent(
        val kind: String,
        /**
         * Which account this event fired for — CCBG-16 (Stale Strip Label).
         *
         * Its presence is also the record's version marker, which is why it has no
         * default: a record carrying a key holds an **unprefixed** [title], to be labelled
         * at render time by
         * [StripRules.stripTitle][com.robin.claudeusage.notify.StripRules.stripTitle]; a
         * record written before the fix carries `""` and a title with the account name
         * already frozen into it. There is no migration — the old records are labelled
         * as they stand and age out through [effectiveExpiry].
         */
        val profileKey: String,
        /**
         * The alert sentence **without** the account-name prefix (see [profileKey]).
         * The prefix is composed at render time so it follows a rename.
         */
        val title: String,
        val detail: String,
        val firedAt: Long,
        val expiresAt: Long,
    )

    /**
     * Current (unexpired) folded events, newest first.
     *
     * CCBG-18 (Strip Lifetime Stamp): "keep alerts in the shade for" is applied **here**,
     * at read time, not only at the moment the event was folded. [FoldedEvent.expiresAt]
     * is stamped once by `Alerts.eventTimeout`, so before this a strip folded under the
     * default `auto` carried a multi-day expiry — until its 7-day window reset — that
     * choosing 15m afterwards could never reach. The chip reads as a display rule; it now
     * behaves like one.
     *
     * It can only ever **shorten**. `auto` keeps the stamped ceiling, and an explicit
     * choice is capped by it, so a longer chip never *extends* something deliberately
     * short — `Alerts.RESET_STRIP_MS`'s fixed half hour, in particular.
     *
     * Pruning the store still uses the hard stamp, so flipping back to `auto` finds the
     * events still there rather than deleted by a setting the user has since changed.
     */
    fun foldedEvents(profile: Profile): List<FoldedEvent> {
        val now = System.currentTimeMillis()
        val stored = readFoldedEvents(profile)
        val kept = stored.filter { it.expiresAt > now }
        if (kept.size != stored.size) writeFoldedEvents(profile, kept)
        return kept.filter { effectiveExpiry(it) > now }.sortedByDescending { it.firedAt }
    }

    /**
     * When [event]'s strip should leave the panel, honouring the current
     * [alertLifetime] — see [foldedEvents]. Never later than the stamped expiry.
     */
    fun effectiveExpiry(event: FoldedEvent): Long =
        com.robin.claudeusage.notify.StripRules.expiry(
            event.firedAt, event.expiresAt, alertLifetime(),
        )

    /**
     * The soonest any of [profile]'s live strips is due to leave, or 0 if none is —
     * what CCBG-18's expiry alarm is armed for.
     */
    fun nextStripExpiry(profile: Profile): Long =
        foldedEvents(profile).minOfOrNull { effectiveExpiry(it) } ?: 0L

    /** Adds an event, replacing any existing one of the same [FoldedEvent.kind]. */
    fun addFoldedEvent(profile: Profile, event: FoldedEvent) {
        val kept = readFoldedEvents(profile).filter {
            it.kind != event.kind && it.expiresAt > event.firedAt
        }
        writeFoldedEvents(profile, kept + event)
    }

    private fun readFoldedEvents(profile: Profile): List<FoldedEvent> = try {
        val arr = org.json.JSONArray(prefs.getString(k(profile, "foldedEvents"), "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            FoldedEvent(
                kind = o.optString("kind"),
                // Absent for a record stored before CCBG-16 (Stale Strip Label) — see
                // [FoldedEvent.profileKey]. Empty means "the title is already finished text".
                profileKey = o.optString("profileKey"),
                title = o.optString("title"),
                detail = o.optString("detail"),
                firedAt = o.optLong("firedAt"),
                expiresAt = o.optLong("expiresAt"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeFoldedEvents(profile: Profile, events: List<FoldedEvent>) {
        val arr = org.json.JSONArray()
        for (e in events) {
            arr.put(
                org.json.JSONObject()
                    .put("kind", e.kind)
                    .put("profileKey", e.profileKey)
                    .put("title", e.title)
                    .put("detail", e.detail)
                    .put("firedAt", e.firedAt)
                    .put("expiresAt", e.expiresAt)
            )
        }
        prefs.edit().putString(k(profile, "foldedEvents"), arr.toString()).apply()
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

    /** Raw rate-limit tier, e.g. "default_5x" — parsed at render time (CCRM-38). */
    fun tier(profile: Profile): String? = prefs.getString(k(profile, "tier"), null)

    fun setTokenMeta(profile: Profile, refreshExpiresAt: Long, plan: String?, tier: String?) {
        prefs.edit()
            .putLong(k(profile, "refreshExpiresAt"), refreshExpiresAt)
            .putString(k(profile, "plan"), plan)
            .putString(k(profile, "tier"), tier)
            .apply()
    }

    /**
     * Key names (never values) of the last sign-in's token response — a
     * debug-only instrument so whether `rate_limit_tier` actually appears in
     * *our* token response gets settled by the next real sign-in (CCRM-38).
     */
    fun signInTokenKeys(profile: Profile): String? =
        prefs.getString(k(profile, "signInTokenKeys"), null)

    fun setSignInTokenKeys(profile: Profile, keys: String?) {
        prefs.edit().putString(k(profile, "signInTokenKeys"), keys).apply()
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
     * Hard-disabled since 2026-08-18: an automated inference call from a third-party
     * client sits on the wrong side of Anthropic's ToS (CCRM-17 (Window Pings), Posture
     * paragraph in ROADMAP.md), and the downside is the user's account, not ours. The
     * stored per-profile pref is kept — [setPingEnabled] still writes it — so a user's
     * choice survives if the feature is ever sanctioned and re-enabled.
     *
     * (Original default was **false**, deliberately: a ping spends the user's own
     * subscription quota on an automated request, per-profile so a Team account stays
     * out of it by default.)
     */
    fun pingEnabled(profile: Profile): Boolean = false

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

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)

    /**
     * Whether this instance has a stored override. Prefs are only written on the
     * first confirm, so this is also the add-vs-reconfigure test for the config
     * screen — the getters below can't tell "unset" from the defaults.
     */
    fun has(appWidgetId: Int): Boolean = prefs.contains("w$appWidgetId.profile")

    /**
     * The account this instance shows. A widget whose account was removed resolves to
     * [ProfileRegistry.first] — and because slots are never reused it can never resolve to
     * a *different* new account. Removal repoints the stored key anyway (CCRM-6
     * (Multi-Account) phase 4); this is the belt to that braces.
     */
    fun profileFor(appWidgetId: Int): Profile =
        ProfileRegistry(appContext).resolve(prefs.getString("w$appWidgetId.profile", null))

    /** Repoints every instance aimed at a removed account. */
    fun repointFrom(deadKey: String, replacement: Profile) {
        val e = prefs.edit()
        for ((name, value) in prefs.all) {
            if (name.endsWith(".profile") && value == deadKey) {
                e.putString(name, replacement.key)
            }
        }
        e.apply()
    }

    fun barFor(appWidgetId: Int): String =
        prefs.getString("w$appWidgetId.bar", "session") ?: "session"

    /**
     * Which window a ring/pace face shows: "session" or "weekly". Doubles as the
     * large face's on-widget 5h/7d toggle state ([saveWindow]) — one key, last
     * writer wins, so the toggle "beats the configured window" trivially and the
     * reconfigure screen pre-fills with whatever the face actually shows.
     */
    fun windowFor(appWidgetId: Int): String =
        prefs.getString("w$appWidgetId.window", "session") ?: "session"

    fun save(appWidgetId: Int, profile: Profile, bar: String?, window: String? = null) {
        val e = prefs.edit().putString("w$appWidgetId.profile", profile.key)
        if (bar != null) e.putString("w$appWidgetId.bar", bar)
        if (window != null) e.putString("w$appWidgetId.window", window)
        e.apply()
    }

    /** The large face's toggle: flips the window without touching the profile. */
    fun saveWindow(appWidgetId: Int, window: String) {
        prefs.edit().putString("w$appWidgetId.window", window).apply()
    }

    fun remove(appWidgetId: Int) {
        prefs.edit()
            .remove("w$appWidgetId.profile")
            .remove("w$appWidgetId.bar")
            .remove("w$appWidgetId.window")
            .apply()
    }
}
