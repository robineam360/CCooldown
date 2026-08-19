package com.robin.claudeusage.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins CCRM-29 (Display Mode)'s two pure resolutions. The migration half (an old
 * `use24hTime` boolean wins over the "system" default) lives in
 * `UsageCache.timeFormat` and needs a real SharedPreferences, so it is pinned by
 * its own doc contract and the device pass rather than a JVM test.
 */
class ThemeModeTest {

    @Test
    fun `theme mode forces, system follows, garbage follows too`() {
        assertEquals(false, resolveDark("light", system = true))
        assertEquals(true, resolveDark("dark", system = false))
        assertEquals(true, resolveDark("system", system = true))
        assertEquals(false, resolveDark("system", system = false))
        // Tolerant decode: an unrecognised value must not force anything.
        assertEquals(true, resolveDark("neon", system = true))
    }

    @Test
    fun `time format forces, system follows, garbage follows too`() {
        assertEquals(false, resolve24h("12", system = true))
        assertEquals(true, resolve24h("24", system = false))
        assertEquals(true, resolve24h("system", system = true))
        assertEquals(false, resolve24h("system", system = false))
        assertEquals(false, resolve24h("13", system = false))
    }
}
