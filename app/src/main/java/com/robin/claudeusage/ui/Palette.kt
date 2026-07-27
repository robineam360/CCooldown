package com.robin.claudeusage.ui

import androidx.compose.ui.graphics.Color
import com.robin.claudeusage.data.UsageWindow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

data class ThemeOption(val name: String, val light: Color, val dark: Color)

/** Theme colors + the bar status shift, shared by the app UI and the widget. */
object Palette {

    /** Pseudo-option: follow the system's Material You dynamic color. */
    const val DYNAMIC = "Material You"
    const val DEFAULT = "Claude Orange"

    val options = listOf(
        ThemeOption("Claude Orange", Color(0xFFD97757), Color(0xFFE59980)),
        ThemeOption("Blue", Color(0xFF1A73E8), Color(0xFF8AB4F8)),
        ThemeOption("Indigo", Color(0xFF3949AB), Color(0xFF9FA8DA)),
        ThemeOption("Cyan", Color(0xFF00ACC1), Color(0xFF4DD0E1)),
        ThemeOption("Teal", Color(0xFF00897B), Color(0xFF4DB6AC)),
        ThemeOption("Green", Color(0xFF188038), Color(0xFF81C995)),
        ThemeOption("Amber", Color(0xFFF9A825), Color(0xFFFDD663)),
        ThemeOption("Deep Orange", Color(0xFFE64A19), Color(0xFFFF8A65)),
        ThemeOption("Red", Color(0xFFD93025), Color(0xFFF28B82)),
        ThemeOption("Pink", Color(0xFFD81B60), Color(0xFFF48FB1)),
        ThemeOption("Purple", Color(0xFF8E24AA), Color(0xFFCE93D8)),
        ThemeOption("Brown", Color(0xFF6D4C41), Color(0xFFBCAAA4)),
    )

    fun byName(name: String): ThemeOption =
        options.firstOrNull { it.name == name } ?: options.first()

    fun color(name: String, dark: Boolean): Color =
        byName(name).let { if (dark) it.dark else it.light }

    /**
     * Bars only: theme color normally, yellow above 80%, orange above 90%, red
     * at 100%. The warning hues are deliberately vivid (saturated orange, true
     * red) so they never blend into the muted Claude Orange terracotta theme.
     */
    fun barColor(percent: Double?, theme: Color, dark: Boolean): Color {
        val pct = percent ?: 0.0
        return when {
            pct >= 100.0 -> if (dark) Color(0xFFFF5252) else Color(0xFFC62828)
            pct > 90.0 -> if (dark) Color(0xFFFFA726) else Color(0xFFF57C00)
            pct > 80.0 -> if (dark) Color(0xFFFDD663) else Color(0xFFF9A825)
            else -> theme
        }
    }
}

object Fmt {

    /** "Thu 11:45 PM" or "Thu 23:45" in the device's local time zone. */
    fun dayTime(instant: Instant?, use24h: Boolean): String {
        instant ?: return ""
        val pattern = if (use24h) "EEE HH:mm" else "EEE h:mm a"
        return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(instant)
    }

    /** "in 4h 47m" */
    fun relIn(instant: Instant?): String {
        instant ?: return "unknown"
        val d = Duration.between(Instant.now(), instant)
        if (d.isNegative) return "any moment"
        val h = d.toHours()
        val m = d.toMinutes() % 60
        return when {
            h >= 24 -> "in ${h / 24}d ${h % 24}h"
            h > 0 -> "in ${h}h ${m}m"
            else -> "in ${m}m"
        }
    }

    /**
     * "$9.57" — an amount in minor units rendered with its currency symbol.
     * Deliberately not locale-formatted: these are Anthropic's billing figures, so
     * they should read the same way they do in Claude's own UI wherever you are.
     */
    fun money(minorUnits: Long, exponent: Int, currencyCode: String): String {
        val exp = exponent.coerceIn(0, 6)
        val amount = minorUnits / Math.pow(10.0, exp.toDouble())
        val symbol = try {
            Currency.getInstance(currencyCode).getSymbol(Locale.US)
        } catch (_: Exception) {
            "$currencyCode "
        }
        return symbol + String.format(Locale.US, "%,.${exp}f", amount)
    }

    /** "Jul 16, 2026" */
    fun date(epochMs: Long): String =
        DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))

    /** "Aug 13, 8:30 PM" or "Aug 13, 20:30" */
    fun dateTime(epochMs: Long, use24h: Boolean): String {
        val pattern = if (use24h) "MMM d, HH:mm" else "MMM d, h:mm a"
        return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))
    }

    /** Countdown to a future moment: "25d 2h 4m" / "2h 4m" / "4m" / "now" */
    fun dhm(untilEpochMs: Long): String {
        val mins = (untilEpochMs - System.currentTimeMillis()) / 60_000
        if (mins <= 0) return "now"
        val d = mins / (24 * 60)
        val h = (mins / 60) % 24
        val m = mins % 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    /** Plain duration between two moments: "1d 2h" / "1h 20m" / "12m" */
    fun span(ms: Long): String {
        val mins = ms / 60_000
        if (mins < 1) return "moments"
        val d = mins / (24 * 60)
        val h = (mins / 60) % 24
        val m = mins % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    /** "12m ago" / "2h 10m ago" / "never" */
    fun ago(epochMs: Long): String {
        if (epochMs <= 0) return "never"
        val mins = (System.currentTimeMillis() - epochMs) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 24 * 60 -> "${mins / 60}h ${mins % 60}m ago"
            else -> "${mins / (24 * 60)}d ${(mins / 60) % 24}h ago"
        }
    }

    /** "Thu 7:46 PM (12m ago)" */
    fun dayTimeWithAgo(epochMs: Long, use24h: Boolean): String {
        if (epochMs <= 0) return "never"
        return "${dayTime(Instant.ofEpochMilli(epochMs), use24h)} (${ago(epochMs)})"
    }
}

/**
 * Synthetic "time elapsed" bar for the 7-day window: 0% right after a reset,
 * 100% when the next reset arrives. Comparing it against the usage bars shows
 * whether usage is running ahead of or behind the week.
 */
fun daysElapsedWindow(weekly: UsageWindow?): UsageWindow? {
    val resets = weekly?.resetsAt ?: return null
    val totalMs = Duration.ofDays(7).toMillis().toDouble()
    val elapsedMs = totalMs - Duration.between(Instant.now(), resets).toMillis().toDouble()
    val pct = (elapsedMs / totalMs * 100.0).coerceIn(0.0, 100.0)
    return UsageWindow(pct, resets, null)
}
