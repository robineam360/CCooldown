package com.robin.claudeusage.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CCRM-56 (Provider Identity), decision 1: the three-level accent rule. Exercises
 * [Palette.resolveAccent] directly — the pure core [Palette.accentName] wraps —
 * since this module has no Robolectric to construct a real `UsageCache`/`Context`.
 */
class AccentResolutionTest {

    @Test
    fun `absent global choice resolves to per-provider`() {
        // UsageCache#themeColorName defaults to Palette.PER_PROVIDER itself when the
        // "themeColor" key is absent, so this is the shape every fresh install's
        // resolution takes: no override, no explicit global pick.
        assertEquals(
            "ChatGPT Green",
            Palette.resolveAccent(
                accountOverride = null,
                globalChoice = Palette.PER_PROVIDER,
                providerThemeName = "ChatGPT Green",
            ),
        )
    }

    @Test
    fun `an explicit global choice wins over the provider colour`() {
        assertEquals(
            "Purple",
            Palette.resolveAccent(
                accountOverride = null,
                globalChoice = "Purple",
                providerThemeName = "Gemini Blue",
            ),
        )
    }

    @Test
    fun `a per-account override wins over both the global choice and the provider colour`() {
        assertEquals(
            "Teal",
            Palette.resolveAccent(
                accountOverride = "Teal",
                globalChoice = "Purple",
                providerThemeName = "Gemini Blue",
            ),
        )
    }

    @Test
    fun `a per-account override wins even when the global choice is per-provider`() {
        assertEquals(
            "Teal",
            Palette.resolveAccent(
                accountOverride = "Teal",
                globalChoice = Palette.PER_PROVIDER,
                providerThemeName = "Claude Orange",
            ),
        )
    }

    @Test
    fun `the new provider theme names are wired into the palette`() {
        assertEquals(androidx.compose.ui.graphics.Color(0xFF10A37F), Palette.byName("ChatGPT Green").light)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF4285F4), Palette.byName("Gemini Blue").light)
    }
}
