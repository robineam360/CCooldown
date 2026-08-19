package com.robin.claudeusage.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CCRM-29 (Display Mode): the app's resolved dark flag.
 *
 * MainActivity and WidgetConfigActivity resolve the `themeMode` pref against the
 * system flag and provide the answer here; every in-app dark read goes through
 * [appDark], so a forced theme drives the chart's per-mode opacities, the bar
 * warning hues and the tick alphas along with the Material scheme — not just the
 * colours (the exact drift the roadmap entry warns about: a light-mode 7% wash
 * over a forced-dark background is invisible).
 *
 * Unprovided (null) falls back to the system flag. Widgets and the notification
 * render outside the composition and keep following the system deliberately —
 * their backdrop is the launcher's and the shade's, not ours to force.
 */
val LocalAppDark = staticCompositionLocalOf<Boolean?> { null }

@Composable
fun appDark(): Boolean = LocalAppDark.current ?: isSystemInDarkTheme()

/** `themeMode` pref → dark, pure so [ThemeModeTest] can pin it. */
fun resolveDark(themeMode: String, system: Boolean): Boolean = when (themeMode) {
    "light" -> false
    "dark" -> true
    // "system", and tolerantly anything unrecognised.
    else -> system
}

/** `timeFormat` pref → 24-hour, same shape. */
fun resolve24h(timeFormat: String, system: Boolean): Boolean = when (timeFormat) {
    "12" -> false
    "24" -> true
    else -> system
}
