package com.robin.claudeusage.diag

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.Provider
import com.robin.claudeusage.data.UsageCache
import org.json.JSONArray
import org.json.JSONObject
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
 *
 * The one sanctioned exception — the CCRM-54 (ChatGPT Account) capture button, which
 * logs a usage body at DEBUG — goes through [redactPayload] first. "Carries no token
 * material" was the test that let it through, and it was the wrong test: OpenAI's usage
 * body carries the account holder's **email address**. Anything writing a payload here
 * redacts it first, no exceptions.
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
     * Keys whose values are the account holder, not the account's usage. Matched on the
     * key name at any depth, so a nested `{"user": {"email": …}}` is caught too.
     *
     * Discovered the hard way (CCRM-54 (ChatGPT Account), 2026-09-06): OpenAI's
     * `/backend-api/wham/usage` returns `user_id`, `account_id` **and the account's email
     * address** alongside the percentages. The documented shape never mentioned them, so
     * the first real capture wrote Robin's email into a log whose whole purpose is being
     * shared. Nothing in this app needs any of these fields.
     */
    private val IDENTIFYING_KEYS = setOf(
        "email", "email_address", "user_id", "userid", "account_id", "accountid",
        "chatgpt_user_id", "chatgpt_account_id", "org_id", "organization_id",
        "organization_uuid", "phone", "phone_number", "sub",
    )

    private const val PLACEHOLDER = "[redacted]"

    private val EMAIL_RE = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")

    /**
     * Strips personal identifiers out of a payload before it is shown or logged.
     *
     * The **body** of a usage response is the one thing this app may log at DEBUG (the
     * capture button), because it carries no token material — but "no tokens" turned out
     * not to mean "no personal data". This closes that gap without losing the shape,
     * which is the only reason to capture a body at all: keys and structure survive,
     * values that identify a person don't.
     *
     * A body that isn't JSON still gets an email scrub rather than being dropped — a
     * malformed body is exactly when you most want to see it.
     */
    fun redactPayload(body: String): String {
        val trimmedBody = body.trim()
        return try {
            when {
                trimmedBody.startsWith("{") -> redact(JSONObject(trimmedBody)).toString()
                trimmedBody.startsWith("[") -> redact(JSONArray(trimmedBody)).toString()
                else -> EMAIL_RE.replace(body, PLACEHOLDER)
            }
        } catch (_: Exception) {
            EMAIL_RE.replace(body, PLACEHOLDER)
        }
    }

    private fun redact(o: JSONObject): JSONObject {
        // Snapshot the keys first: putting into a JSONObject while iterating its own
        // keys() is undefined.
        for (key in o.keys().asSequence().toList()) {
            when {
                key.lowercase() in IDENTIFYING_KEYS -> if (!o.isNull(key)) o.put(key, PLACEHOLDER)
                else -> when (val v = o.opt(key)) {
                    is JSONObject -> o.put(key, redact(v))
                    is JSONArray -> o.put(key, redact(v))
                    is String -> if (EMAIL_RE.containsMatchIn(v)) o.put(key, EMAIL_RE.replace(v, PLACEHOLDER))
                    else -> Unit
                }
            }
        }
        return o
    }

    private fun redact(a: JSONArray): JSONArray {
        for (i in 0 until a.length()) {
            when (val v = a.opt(i)) {
                is JSONObject -> a.put(i, redact(v))
                is JSONArray -> a.put(i, redact(v))
                is String -> if (EMAIL_RE.containsMatchIn(v)) a.put(i, EMAIL_RE.replace(v, PLACEHOLDER))
                else -> Unit
            }
        }
        return a
    }

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
