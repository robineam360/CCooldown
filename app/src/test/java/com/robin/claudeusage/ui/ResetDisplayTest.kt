package com.robin.claudeusage.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Pins CCRM-23 (Reset Display)'s one behavioural change to the countdown itself:
 * `Fmt.relIn` collapses to "soon" inside five minutes, aligned with the ring
 * faces' `widgetCountdown` — surfaces on a 15-minute refresh have no business
 * printing "in 43s". Everything else about the token is ordering (Option A:
 * the chosen form leads), which lives in the render sites.
 */
class ResetDisplayTest {

    @Test
    fun `inside five minutes collapses to soon`() {
        assertEquals("soon", Fmt.relIn(Instant.now().plusSeconds(4 * 60 + 30)))
        assertEquals("soon", Fmt.relIn(Instant.now().plusSeconds(30)))
    }

    @Test
    fun `five minutes and beyond keeps the countdown`() {
        // 5m30s floors to 5 whole minutes — at the boundary, still a number.
        assertEquals("in 5m", Fmt.relIn(Instant.now().plusSeconds(5 * 60 + 30)))
        assertEquals("in 2h 0m", Fmt.relIn(Instant.now().plusSeconds(2 * 3600 + 30)))
    }

    @Test
    fun `a past moment stays any moment, and null stays unknown`() {
        assertEquals("any moment", Fmt.relIn(Instant.now().minusSeconds(60)))
        assertEquals("unknown", Fmt.relIn(null))
    }
}
