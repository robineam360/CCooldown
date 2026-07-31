package com.robin.claudeusage.ping

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.robin.claudeusage.data.Profile
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A plain-text trace of what the window-ping machinery actually did (CCRM-17).
 *
 * Exists because the interesting run is an unattended one — a 4am alarm, in Doze, with
 * nobody watching — and the alternative was leaving a phone unlocked on a cable all
 * night. Written to the app's **external** files dir specifically so it can be read
 * back without a debuggable build:
 *
 * ```
 * adb pull /sdcard/Android/data/com.robin.claudeusage/files/ping-log.txt
 * ```
 *
 * `run-as` is unavailable on a release-signed APK, which is what's installed, so the
 * internal files dir would be unreadable. Falls back to internal storage anyway if the
 * external dir isn't available, so logging never becomes a crash source.
 */
object PingLog {

    private const val FILE_NAME = "ping-log.txt"

    /** Trim to roughly this when it grows past it. Small enough to pull over Wi-Fi. */
    private const val MAX_BYTES = 256 * 1024L
    private const val KEEP_LINES = 400

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    /**
     * Appends one line. [profile] and [event] are free text; keep them short and
     * greppable — this gets read by eye at 8am.
     */
    fun log(context: Context, profile: Profile?, event: String) {
        val line = buildString {
            append(stamp.format(Instant.now()))
            append(if (profile == null) "  [-]       " else "  [${profile.key}] ")
            append(event)
        }
        try {
            val f = file(context)
            f.parentFile?.mkdirs()
            f.appendText(line + "\n")
            if (f.length() > MAX_BYTES) trim(f)
        } catch (_: Exception) {
            // Logging must never take the feature down with it.
        }
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
            file(context).delete()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private fun trim(f: File) {
        try {
            val kept = f.readLines().takeLast(KEEP_LINES)
            f.writeText(kept.joinToString("\n") + "\n")
        } catch (_: Exception) {
            // If trimming fails the file just stays large; not worth failing over.
        }
    }
}
