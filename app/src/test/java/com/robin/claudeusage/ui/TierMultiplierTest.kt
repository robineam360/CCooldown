package com.robin.claudeusage.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [Fmt.tierMultiplier], the CCRM-38 render-time parse of the raw rate-limit
 * tier string. The contract: split on non-alphanumerics, take the first part that
 * ends in a lowercase "x" with an all-digits stem; everything else is null so the
 * chip falls back to the bare plan rather than guessing.
 */
class TierMultiplierTest {

    @Test
    fun `default_5x parses to 5x`() {
        assertEquals("5x", Fmt.tierMultiplier("default_5x"))
    }

    @Test
    fun `default_20x parses to 20x`() {
        assertEquals("20x", Fmt.tierMultiplier("default_20x"))
    }

    @Test
    fun `leading zeros drop`() {
        assertEquals("5x", Fmt.tierMultiplier("05x"))
    }

    /** A 1x tier renders — no special case that hides it. */
    @Test
    fun `1x is a real tier`() {
        assertEquals("1x", Fmt.tierMultiplier("1x"))
    }

    /** Uppercase X is not the shape we matched on; don't guess. */
    @Test
    fun `uppercase X does not parse`() {
        assertNull(Fmt.tierMultiplier("5X"))
    }

    @Test
    fun `a tier with no multiplier part does not parse`() {
        assertNull(Fmt.tierMultiplier("high_volume"))
    }

    @Test
    fun `empty and null are null`() {
        assertNull(Fmt.tierMultiplier(""))
        assertNull(Fmt.tierMultiplier(null))
    }

    /** A bare number has no "x" — it is not a multiplier. */
    @Test
    fun `a bare number does not parse`() {
        assertNull(Fmt.tierMultiplier("20"))
    }

    @Test
    fun `the first matching part wins`() {
        assertEquals("5x", Fmt.tierMultiplier("default_5x_20x"))
    }
}
