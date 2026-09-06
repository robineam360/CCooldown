package com.robin.claudeusage.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CCRM-53 (Provider Model)'s `unlimited` widening — ChatGPT's credits shape has no
 * cap and an explicit "unlimited" flag. The existing cap/used rules (CCBG-9) must
 * hold unchanged for a Claude-shaped account, which never sets this.
 */
class SpendCreditsTest {

    private fun credits(
        usedMinor: Long = 0L,
        limitMinor: Long? = null,
        balanceMinor: Long? = null,
        unlimited: Boolean = false,
    ) = SpendCredits(
        usedMinor = usedMinor,
        limitMinor = limitMinor,
        exponent = 2,
        currency = "USD",
        serverSeverity = null,
        balanceMinor = balanceMinor,
        unlimited = unlimited,
    )

    @Test
    fun `unlimited hides regardless of a reported balance`() {
        assertFalse(credits(balanceMinor = 500L, unlimited = true).isReportable)
    }

    @Test
    fun `a positive balance alone reports`() {
        assertTrue(credits(balanceMinor = 500L).isReportable)
    }

    @Test
    fun `no cap, no spend, no balance stays hidden`() {
        assertFalse(credits().isReportable)
    }

    @Test
    fun `the existing cap and used rules are unchanged`() {
        assertTrue(credits(usedMinor = 1L).isReportable)
        assertTrue(credits(limitMinor = 1000L).isReportable)
        assertFalse(credits(limitMinor = 0L).isReportable)
    }
}
