package com.robin.claudeusage.data

import java.net.URI

/**
 * The pure decisions behind the automatic update check (CCRM-28): when a check is
 * due, whether a found release earns a notification, and how the surfaces render
 * what happened. All I/O — the fetch, the cache writes, the notification post —
 * lives in `notify/UpdateNotification.kt`; everything here is testable in isolation.
 */
object UpdateGate {

    /** Auto-checks ride the usage poll; this spaces the actual GitHub calls. */
    const val CHECK_INTERVAL_MS = 6 * 60 * 60_000L

    /** Expanded-notification budget for release notes: whichever runs out first. */
    const val MAX_NOTE_LINES = 4
    const val MAX_NOTE_CHARS = 300

    /** Where a tap lands when the release payload's own URL can't be trusted. */
    const val FALLBACK_RELEASE_URL = "https://github.com/robineam360/CCooldown/releases/latest"

    /**
     * Whether a poll should spend a GitHub call. [lastSuccessAtMs] is the last
     * *successful* check (0 = never) — a failure deliberately never advances it,
     * so a failed check retries on the very next poll. Worst case that is 12
     * unauthenticated calls/hour, well under GitHub's 60.
     */
    fun shouldCheckNow(autoEnabled: Boolean, nowMs: Long, lastSuccessAtMs: Long): Boolean =
        autoEnabled && nowMs - lastSuccessAtMs >= CHECK_INTERVAL_MS

    /**
     * Whether a fetched release gets the one notification a version is ever allowed.
     * Newer than installed, not the version already notified (a swipe = seen, no
     * re-remind), and newer than any skipped version — skip is per-version, so a
     * release *above* the skipped one notifies normally.
     */
    fun shouldNotify(
        latestVersion: String,
        currentVersion: String,
        lastNotifiedVersion: String?,
        dismissedVersion: String?,
    ): Boolean {
        val latest = UpdateCheck.normalize(latestVersion)
        if (UpdateCheck.compare(latest, UpdateCheck.normalize(currentVersion)) <= 0) return false
        if (lastNotifiedVersion != null && latest == UpdateCheck.normalize(lastNotifiedVersion)) return false
        if (dismissedVersion != null &&
            UpdateCheck.compare(latest, UpdateCheck.normalize(dismissedVersion)) <= 0
        ) return false
        return true
    }

    /** The stored outcome half of the settings line: "up to date (v0.14)" / "v0.15 available". */
    fun successOutcome(latestVersion: String, updateAvailable: Boolean): String {
        val v = UpdateCheck.normalize(latestVersion)
        return if (updateAvailable) "v$v available" else "up to date (v$v)"
    }

    /** True when the offered version is exactly the one the user skipped. */
    fun isSkipped(latestVersion: String, dismissedVersion: String?): Boolean =
        dismissedVersion != null &&
            UpdateCheck.normalize(latestVersion) == UpdateCheck.normalize(dismissedVersion)

    /**
     * The settings outcome line marks a skipped version so the quiet notification
     * isn't a mystery: "v0.15 available (skipped)". Derived rather than stored — a
     * skip can land after the check that wrote the outcome.
     */
    fun outcomeLine(outcome: String, dismissedVersion: String?): String =
        if (dismissedVersion != null &&
            outcome == "v${UpdateCheck.normalize(dismissedVersion)} available"
        ) "$outcome (skipped)" else outcome

    /**
     * Release notes for the expanded notification: the first [MAX_NOTE_LINES]
     * non-blank lines within [MAX_NOTE_CHARS], with leading markdown markers
     * stripped — headings vanish, list markers become the "·" the mockup draws.
     * Ends with "…" whenever anything was cut.
     */
    fun trimNotes(notes: String): String {
        val lines = notes.lines()
            .map { line ->
                line.trim()
                    .replace(Regex("^#+\\s*"), "")
                    .replace(Regex("^[*-]\\s+"), "· ")
            }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        var truncated = lines.size > MAX_NOTE_LINES
        var text = lines.take(MAX_NOTE_LINES).joinToString("\n")
        if (text.length > MAX_NOTE_CHARS) {
            text = text.take(MAX_NOTE_CHARS).trimEnd()
            truncated = true
        }
        return if (truncated) "$text…" else text
    }

    /**
     * The URL a tap opens. Only the release payload's own page qualifies — https
     * and exactly github.com — anything else falls back to the hardcoded releases
     * page. The app never downloads or installs an APK either way.
     */
    fun safeReleaseUrl(url: String?): String {
        if (url.isNullOrBlank()) return FALLBACK_RELEASE_URL
        return try {
            val parsed = URI(url)
            if (parsed.scheme == "https" && parsed.host == "github.com") url
            else FALLBACK_RELEASE_URL
        } catch (_: Exception) {
            FALLBACK_RELEASE_URL
        }
    }
}
