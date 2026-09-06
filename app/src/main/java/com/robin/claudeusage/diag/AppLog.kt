package com.robin.claudeusage.diag

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Provider
import com.robin.claudeusage.data.UsageCache
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * CCRM-34 (Diagnostics Log): the levelled, categorised app log, grown from
 * CCRM-17 (Window Pings)' `PingLog`. Everything the app does in the background —
 * polls, alert posts, token renewals, ping alarms — is invisible unless it's
 * attached to logcat; for a sideload-only app with an email feedback channel,
 * "share your log" is the only realistic way to diagnose someone else's phone.
 *
 * Written to the **external** files dir so a release-signed build's log can be
 * pulled without `run-as`:
 *
 * ```
 * adb pull /sdcard/Android/data/com.robin.claudeusage/files/app-log.txt
 * ```
 *
 * The minimum level is a user-facing setting (`UsageCache.logLevel`, default
 * Info — Debug only while someone is chasing something).
 *
 * **Hard rule (the v0.14 history scrub is the precedent): never log tokens,
 * authorization headers, or the `code_verifier`.** This is a public repo and
 * logs get pasted into emails. Log outcomes and status codes, never payloads.
 */
object AppLog {

    enum class Level(val tag: String, val rank: Int) {
        DEBUG("D", 0), INFO("I", 1), WARN("W", 2), ERROR("E", 3);

        companion object {
            /** Tolerant decode of the stored pref; garbage lands on INFO. */
            fun fromKey(key: String?): Level =
                entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: INFO
        }
    }

    private const val FILE_NAME = "app-log.txt"

    /** Trim to roughly this when it grows past it. Small enough to share by email. */
    private const val MAX_BYTES = 256 * 1024L
    internal const val KEEP_LINES = 600

    /** Several background paths write now (polls, alerts, alarms) — one lock. */
    private val lock = Any()

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun log(
        context: Context,
        level: Level,
        category: String,
        profile: Profile? = null,
        event: String,
    ) {
        try {
            if (!shouldLog(level, Level.fromKey(UsageCache(context).logLevel()))) return
            // [poll][chatgpt:p3] — prefix the key with the provider only when it isn't
            // Claude, so every existing Claude log line stays byte-identical.
            val keyLabel = profile?.let {
                if (it.provider == Provider.CLAUDE) it.key else "${it.provider.key}:${it.key}"
            }
            val line = formatLine(stamp.format(Instant.now()), level, category, keyLabel, event)
            synchronized(lock) {
                val f = file(context)
                f.parentFile?.mkdirs()
                f.appendText(line + "\n")
                if (f.length() > MAX_BYTES) f.writeText(
                    trimmed(f.readLines()).joinToString("\n") + "\n"
                )
            }
        } catch (_: Exception) {
            // Logging must never take a feature down with it.
        }
    }

    /** Pure, so the level gate is pinned by [AppLogTest] rather than by eye. */
    fun shouldLog(level: Level, min: Level): Boolean = level.rank >= min.rank

    /** Pure line shape: `08-19 21:04:11.402 I [poll][personal] auto → OK`. */
    fun formatLine(
        stampText: String,
        level: Level,
        category: String,
        profileKey: String?,
        event: String,
    ): String = "$stampText ${level.tag} [$category][${profileKey ?: "-"}] $event"

    /** Pure trim rule — keep the newest [keep] lines. */
    fun trimmed(lines: List<String>, keep: Int = KEEP_LINES): List<String> = lines.takeLast(keep)

    /**
     * Doze state at the moment an alarm fires — the single most useful fact for
     * judging whether `setExactAndAllowWhileIdle` really held overnight.
     */
    fun powerState(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java) ?: return "power=?"
        val idle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isDeviceIdleMode else false
        val saver = pm.isPowerSaveMode
        val ignoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
        return "doze=$idle saver=$saver battOptExempt=$ignoring"
    }

    fun clear(context: Context) {
        try {
            synchronized(lock) { file(context).delete() }
        } catch (_: Exception) {
            // Best effort.
        }
    }
}
