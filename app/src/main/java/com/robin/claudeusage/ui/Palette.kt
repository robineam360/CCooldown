package com.robin.claudeusage.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.robin.claudeusage.data.UsageWindow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/**
 * How far usage may sit from the even-pace line before it counts as above or below.
 * Shared by the chart's wash and the pace readout so the two can never disagree —
 * without it, the verdict and its colour flip on every poll while usage hovers on
 * the line.
 */
const val PACE_DEAD_ZONE = 3.0

/**
 * [paceLight]/[paceDark] are the theme's **pace-line partner** (CCRM-50 (Weekly
 * Flag)): the colour the status-bar icon's pace mark wears on this theme. Always a
 * cool hue, never a true complement — past 80% the fill is the fixed warm ladder
 * whatever the theme, so a warm line would vanish exactly in the states that matter.
 * Cyan-blue for the warm-or-neutral themes; the blue family gets spring green, the
 * cyan family violet, so the line never sits on its own hue.
 */
data class ThemeOption(
    val name: String,
    val light: Color,
    val dark: Color,
    val paceLight: Color = PACE_BLUE_LIGHT,
    val paceDark: Color = PACE_BLUE_DARK,
)

private val PACE_BLUE_LIGHT = Color(0xFF0288D1)
private val PACE_BLUE_DARK = Color(0xFF5BC8FF)
private val PACE_SPRING_LIGHT = Color(0xFF00A05C)
private val PACE_SPRING_DARK = Color(0xFF69F0AE)
private val PACE_VIOLET_LIGHT = Color(0xFF651FFF)
private val PACE_VIOLET_DARK = Color(0xFFB388FF)

/** Theme colors + the bar status shift, shared by the app UI and the widget. */
object Palette {

    /** Pseudo-option: follow the system's Material You dynamic color. */
    const val DYNAMIC = "Material You"
    const val DEFAULT = "Claude Orange"

    val options = listOf(
        ThemeOption("Claude Orange", Color(0xFFD97757), Color(0xFFE59980)),
        ThemeOption("Blue", Color(0xFF1A73E8), Color(0xFF8AB4F8), PACE_SPRING_LIGHT, PACE_SPRING_DARK),
        ThemeOption("Indigo", Color(0xFF3949AB), Color(0xFF9FA8DA), PACE_SPRING_LIGHT, PACE_SPRING_DARK),
        ThemeOption("Cyan", Color(0xFF00ACC1), Color(0xFF4DD0E1), PACE_VIOLET_LIGHT, PACE_VIOLET_DARK),
        ThemeOption("Teal", Color(0xFF00897B), Color(0xFF4DB6AC), PACE_VIOLET_LIGHT, PACE_VIOLET_DARK),
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
     * The theme's pace-line partner (CCRM-50 (Weekly Flag)). Resolves through
     * [byName] like [color] does, so "Material You" lands on Claude Orange's
     * partner — matching the fill that surface actually draws for it.
     */
    fun paceColor(name: String, dark: Boolean): Color =
        byName(name).let { if (dark) it.paceDark else it.paceLight }

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

// --- CCRM-3 step 1: surface tokens ---
//
// One set of tokens every surface reads, so widgets, the notification and the app
// screen can't drift apart. This is the *token* axis only — what things look like.
// What each surface *draws* (bars vs ring vs huge number) is the layout axis, and it
// lives with the surface, because its scope differs: global for the notification,
// per instance for widgets, fixed in-app.
//
// Deliberately additive. Every resolver below returns today's value when handed the
// defaults, so this file can land with nothing wired up and nothing moving. See
// `SurfaceTokensTest`, which pins exactly that.

/** How a usage bar's ends are drawn. */
enum class BarShape { ROUNDED, SQUARE }

/** What sits behind a widget's content. */
enum class BackgroundMode {
    /** The widget background colour — today's look, always legible. */
    SOLID,

    /** A scrim: the wallpaper shows through, but text keeps its contrast. */
    TRANSLUCENT,

    /** Nothing. Contrast becomes the wallpaper's problem — pair with [TextContrast]. */
    NONE,
}

/**
 * Which way content colour is forced. Only meaningful once the background stops being
 * ours: `GlanceTheme.colors.onSurface` follows the *system* dark-mode flag, not the
 * wallpaper behind one particular widget, so on [BackgroundMode.NONE] "auto" can put
 * light text on a light wallpaper and read as a bug rather than a choice.
 */
enum class TextContrast { AUTO, LIGHT, DARK }

data class SurfaceTokens(
    val accentName: String = Palette.DEFAULT,
    val barShape: BarShape = BarShape.ROUNDED,
    val background: BackgroundMode = BackgroundMode.SOLID,
    val textContrast: TextContrast = TextContrast.AUTO,
    val textScale: Float = 1f,
)

/**
 * Resolves [SurfaceTokens] to the concrete values a renderer needs.
 *
 * The accent is the exception: a "Material You" accent can only be read from a live
 * composition (`GlanceTheme.colors.primary` / `MaterialTheme.colorScheme.primary`), so
 * it stays resolved at the call site the way `widgetThemeColor()` already does it.
 * [isDynamicAccent] is the whole of this object's involvement — pretending otherwise
 * would mean threading a Context through a pure model to gain nothing.
 */
object Tokens {

    /** Scrim strength for [BackgroundMode.TRANSLUCENT] — wallpaper visible, text safe. */
    const val TRANSLUCENT_ALPHA = 0.55f

    /** Squared bars keep a hairline radius; a true 0 reads as a rendering fault. */
    val SQUARE_BAR_RADIUS: Dp = 2.dp

    // Forced content colours. The dark one matches the `onPrimary` already used for
    // dark schemes in WidgetConfigActivity, so a forced choice and an automatic one
    // can't land on two different near-blacks.
    val FORCED_LIGHT = Color(0xFFF5F5F5)
    val FORCED_DARK = Color(0xFF1F1F1F)

    fun isDynamicAccent(accentName: String): Boolean = accentName == Palette.DYNAMIC

    /**
     * Bar corner radius. [BarShape.ROUNDED] is `height / 2` — a full pill, which is
     * what every bar draws today (`RoundedCornerShape(height / 2)` in the app,
     * `cornerRadius(height / 2)` in Glance), so the default is a no-op.
     */
    fun barCornerRadius(shape: BarShape, height: Dp): Dp = when (shape) {
        BarShape.ROUNDED -> height / 2
        BarShape.SQUARE -> SQUARE_BAR_RADIUS
    }

    /**
     * The colour to paint behind widget content, given the surface colour the platform
     * would otherwise use. [BackgroundMode.SOLID] returns [base] unchanged.
     */
    fun background(mode: BackgroundMode, base: Color): Color = when (mode) {
        BackgroundMode.SOLID -> base
        BackgroundMode.TRANSLUCENT -> base.copy(alpha = TRANSLUCENT_ALPHA)
        BackgroundMode.NONE -> Color.Transparent
    }

    /**
     * Content colour. [TextContrast.AUTO] returns [auto] — whatever the theme already
     * chose — so the default changes nothing; the forced modes exist for the case where
     * the background is no longer ours to reason about.
     */
    fun contentColor(contrast: TextContrast, auto: Color): Color = when (contrast) {
        TextContrast.AUTO -> auto
        TextContrast.LIGHT -> FORCED_LIGHT
        TextContrast.DARK -> FORCED_DARK
    }

    /**
     * Scales a text size, in sp. Takes and returns a bare Float rather than a
     * `TextUnit`: the app uses Compose's `.sp` and the widgets use Glance's, and this
     * has to serve both without picking one.
     */
    fun scaledSp(baseSp: Float, scale: Float): Float = baseSp * scale
}

object Fmt {

    /** "Thu 11:45 PM" or "Thu 23:45" in the device's local time zone. */
    fun dayTime(instant: Instant?, use24h: Boolean): String {
        instant ?: return ""
        val pattern = if (use24h) "EEE HH:mm" else "EEE h:mm a"
        return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(instant)
    }

    /**
     * "11:45 PM" or "23:45" — no day part. Only for the 5-hour window, which is
     * always a few hours out at most, so the day is never in question.
     */
    fun timeOnly(instant: Instant?, use24h: Boolean): String {
        instant ?: return ""
        val pattern = if (use24h) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(instant)
    }

    /**
     * "23 Jul" — for chart axes spanning days, where a weekday name alone is
     * ambiguous (a 7-day window starts and ends on the same weekday).
     */
    fun dayMonth(instant: Instant?): String {
        instant ?: return ""
        return DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault()).format(instant)
    }

    /**
     * "in 4h 47m", collapsing to **"soon" inside five minutes** (CCRM-23
     * (Reset Display), aligned with the ring faces' `widgetCountdown`): most
     * surfaces showing this refresh on a 15-minute cadence, so counting down the
     * last seconds would just be a stale number wearing false precision.
     */
    fun relIn(instant: Instant?): String {
        instant ?: return "unknown"
        val d = Duration.between(Instant.now(), instant)
        if (d.isNegative) return "any moment"
        if (d.toMinutes() < 5) return "soon"
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

    // --- CCRM-22 (Used or Left) ---
    //
    // The one place a usage percentage becomes a number for display. Every numeric
    // readout follows the token — worded labels, ring bores, status-bar digits, the
    // number-tile plate alike (rev B: nothing is exempt) — while fills, pace ticks
    // and the 80/90/100 warning ladder always key on the used percent.

    /**
     * Used truncates (99.7 → 99 — the never-overstate rule); Left floors the exact
     * remainder (99.7 used → 0 left), so neither mode ever promises headroom that
     * isn't there. Over-limit clamps Left at 0 rather than going negative.
     */
    fun usageInt(percent: Double, left: Boolean): Int =
        if (left) (100.0 - percent).toInt().coerceAtLeast(0) else percent.toInt()

    /** "47%" in Used mode; "53% left" in Left mode — the word carries the flip. */
    fun usageShort(percent: Double?, left: Boolean): String =
        if (left) "${usageInt(percent ?: 0.0, true)}% left"
        else "${(percent ?: 0.0).toInt()}%"

    /** "47% used" / "53% left" — the worded card headline. */
    fun usageWorded(percent: Double?, left: Boolean): String =
        if (left) usageShort(percent, true) else "${(percent ?: 0.0).toInt()}% used"

    /**
     * "default_5x" → "5x" — the rate-limit multiplier out of a tier string
     * (CCRM-38). Split on non-alphanumerics, take the first part that ends in a
     * lowercase "x" with an all-digits stem; leading zeros drop ("05x" → "5x").
     * Anything else — "high_volume", "5X", a bare number — is null, and the
     * caller falls back to the bare plan. The raw tier is stored as-is; this
     * runs at render time only.
     */
    fun tierMultiplier(tier: String?): String? {
        tier ?: return null
        val part = tier.split(Regex("[^A-Za-z0-9]+")).firstOrNull {
            it.length >= 2 && it.endsWith('x') && it.dropLast(1).all { c -> c.isDigit() }
        } ?: return null
        val stem = part.dropLast(1).trimStart('0').ifEmpty { "0" }
        return "${stem}x"
    }
}

/**
 * How far through a window we are, 0-100 — the "even pace" reference the trend
 * chart draws as a diagonal. Usage above this is outrunning the clock and will
 * hit the limit before the reset if it keeps up.
 *
 * This used to surface as a "Days elapsed" bar next to the usage bars. The chart's
 * diagonal says the same thing without spending a row on it, so the number now
 * only feeds the pace readout.
 */
fun elapsedPercent(window: UsageWindow?, windowLengthMs: Long): Double? {
    val resets = window?.resetsAt ?: return null
    val total = windowLengthMs.toDouble()
    if (total <= 0.0) return null
    val remaining = Duration.between(Instant.now(), resets).toMillis().toDouble()
    return ((total - remaining) / total * 100.0).coerceIn(0.0, 100.0)
}
