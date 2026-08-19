package com.robin.claudeusage.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins CCRM-22 (Used or Left)'s arithmetic and copy — rev B of the approved
 * wireframe: every numeric readout follows the token, and neither mode may ever
 * overstate. Used truncates (never claims a limit not reached); Left floors the
 * exact remainder (never promises headroom that isn't there), so 99.7% used must
 * read 0% left, not 1%.
 */
class UsageDisplayTest {

    // --- the integer core ---

    @Test
    fun `used truncates, never rounds up`() {
        assertEquals(99, Fmt.usageInt(99.7, left = false))
        assertEquals(47, Fmt.usageInt(47.0, left = false))
        assertEquals(0, Fmt.usageInt(0.9, left = false))
    }

    @Test
    fun `left floors the exact remainder`() {
        // 99.7 used leaves 0.3 — floored to 0, never shown as the 1% you don't have.
        assertEquals(0, Fmt.usageInt(99.7, left = true))
        assertEquals(53, Fmt.usageInt(47.0, left = true))
        // 0.9 used leaves 99.1 → 99, not 100.
        assertEquals(99, Fmt.usageInt(0.9, left = true))
    }

    @Test
    fun `boundaries agree in both modes`() {
        assertEquals(100, Fmt.usageInt(0.0, left = true))
        assertEquals(0, Fmt.usageInt(100.0, left = true))
        assertEquals(100, Fmt.usageInt(100.0, left = false))
    }

    @Test
    fun `over the limit clamps left at zero rather than going negative`() {
        assertEquals(0, Fmt.usageInt(104.2, left = true))
        // Used keeps reporting the real overshoot.
        assertEquals(104, Fmt.usageInt(104.2, left = false))
    }

    // --- the strings ---

    @Test
    fun `short form is bare in used mode and worded in left mode`() {
        assertEquals("47%", Fmt.usageShort(47.0, left = false))
        assertEquals("53% left", Fmt.usageShort(47.0, left = true))
    }

    @Test
    fun `worded form carries its word in both modes`() {
        assertEquals("47% used", Fmt.usageWorded(47.0, left = false))
        assertEquals("53% left", Fmt.usageWorded(47.0, left = true))
    }

    @Test
    fun `null percent falls back to zero, matching the sites that pass it`() {
        // Call sites with their own null rendering ("—") never reach these; the
        // ones that pass null today render 0, and Left's complement is 100.
        assertEquals("0% used", Fmt.usageWorded(null, left = false))
        assertEquals("100% left", Fmt.usageWorded(null, left = true))
        assertEquals("0%", Fmt.usageShort(null, left = false))
    }

    @Test
    fun `at the limit left reads spent, used reads full`() {
        assertEquals("0% left", Fmt.usageWorded(100.0, left = true))
        assertEquals("100% used", Fmt.usageWorded(100.0, left = false))
    }

    @Test
    fun `credits rounded display percent keeps its rounded complement`() {
        // The credits card passes the pre-rounded display percent (6 for $5.99 of
        // $100) — Left must complement the rounded figure, not re-derive it.
        assertEquals("6% used", Fmt.usageWorded(6.0, left = false))
        assertEquals("94% left", Fmt.usageWorded(6.0, left = true))
    }
}
