package com.robin.claudeusage.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CCBG-17 (Strip Revocation) and CCBG-18 (Strip Lifetime Stamp) — the two rules a folded
 * panel strip is judged by, pinned down here because both defects were failures of a rule
 * that existed nowhere it could be read.
 */
class StripRulesTest {

    private val fired = 1_700_000_000_000L
    private val minute = 60_000L

    // --- CCBG-18: the lifetime is read now, not stamped then --------------------------

    @Test
    fun `an explicit lifetime shortens a strip stamped under auto`() {
        // The reported case: folded under "auto" on a 7-day window, so stamped days out.
        val stamped = fired + 4 * 24 * 60 * minute
        assertEquals(fired + 15 * minute, StripRules.expiry(fired, stamped, "15m"))
        assertEquals(fired + 30 * minute, StripRules.expiry(fired, stamped, "30m"))
        assertEquals(fired + 60 * minute, StripRules.expiry(fired, stamped, "1h"))
    }

    @Test
    fun `auto keeps the stamped window-reset ceiling untouched`() {
        val stamped = fired + 4 * 24 * 60 * minute
        assertEquals(stamped, StripRules.expiry(fired, stamped, "auto"))
    }

    @Test
    fun `an unknown lifetime value falls back to auto rather than expiring the strip`() {
        val stamped = fired + 90 * minute
        assertEquals(stamped, StripRules.expiry(fired, stamped, "whatever"))
    }

    @Test
    fun `a longer choice never extends a deliberately short strip`() {
        // A reset strip is stamped at a fixed 30 minutes (Alerts.RESET_STRIP_MS) because
        // "you're back" stops being interesting quickly. "1h" must not stretch it.
        val stamped = fired + 30 * minute
        assertEquals(stamped, StripRules.expiry(fired, stamped, "1h"))
        assertEquals(stamped, StripRules.expiry(fired, stamped, "30m"))
        // Shorter still wins, though.
        assertEquals(fired + 15 * minute, StripRules.expiry(fired, stamped, "15m"))
    }

    @Test
    fun `explicit lifetimes are the ones with a fixed length`() {
        assertEquals(15 * minute, StripRules.explicitLifetimeMs("15m"))
        assertEquals(30 * minute, StripRules.explicitLifetimeMs("30m"))
        assertEquals(60 * minute, StripRules.explicitLifetimeMs("1h"))
        // "auto" has no length of its own — it means "until the window resets".
        assertNull(StripRules.explicitLifetimeMs("auto"))
    }

    // --- CCBG-17: which toggle owns which strip ---------------------------------------

    @Test
    fun `pace strips are gated by the pace toggle`() {
        assertEquals(StripRules.Gate.PACE, StripRules.gateFor("pace.Session"))
        assertEquals(StripRules.Gate.PACE, StripRules.gateFor("pace.Weekly"))
    }

    @Test
    fun `threshold strips are gated by their own threshold set`() {
        assertEquals(StripRules.Gate.SESSION_THRESHOLD, StripRules.gateFor("sessionAlert"))
        assertEquals(StripRules.Gate.WEEKLY_THRESHOLD, StripRules.gateFor("weeklyAlert"))
        assertEquals(
            StripRules.Gate.MODEL_CAP_THRESHOLD,
            StripRules.gateFor("modelAlert.claude-opus-4"),
        )
    }

    @Test
    fun `reset strips name the window whose ping mode governs them`() {
        assertEquals(StripRules.Gate.RESET, StripRules.gateFor("reset.Session"))
        assertEquals("Session", StripRules.resetWindow("reset.Session"))
        assertEquals("Weekly", StripRules.resetWindow("reset.Weekly"))
        assertNull(StripRules.resetWindow("pace.Session"))
        // A truncated kind names no window, so it falls back to the profile toggle alone
        // rather than to some arbitrary window's mode.
        assertNull(StripRules.resetWindow("reset."))
    }

    @Test
    fun `an unrecognised kind is shown, not swallowed`() {
        // Written by a newer build, read by an older one: the owning profile's alerts
        // toggle still governs it, but nothing else silently drops it.
        assertEquals(StripRules.Gate.PROFILE, StripRules.gateFor("somethingNew"))
        assertEquals(StripRules.Gate.PROFILE, StripRules.gateFor(""))
    }

    @Test
    fun `a model name containing a dot doesn't collide with the reset prefix`() {
        assertEquals(
            StripRules.Gate.MODEL_CAP_THRESHOLD,
            StripRules.gateFor("modelAlert.claude-3.5-sonnet"),
        )
    }

    // --- CCBG-16: the account name is composed now, not frozen then --------------------

    @Test
    fun `a strip is labelled from the live name, so a rename follows`() {
        // The reported case: the event fired while the account was called "Pro", and the
        // panel header had since moved on to "Personal". The stored sentence carries no
        // name at all, so the only name that can be drawn is the current one.
        val stored = "7-day window will run out early"
        assertEquals(
            "Pro: 7-day window will run out early",
            StripRules.stripTitle(stored, "work", "Pro"),
        )
        assertEquals(
            "Personal: 7-day window will run out early",
            StripRules.stripTitle(stored, "work", "Personal"),
        )
    }

    @Test
    fun `a record written before the fix is drawn as it stands, never prefixed twice`() {
        // No profileKey means the name is already inside the title — possibly the stale
        // one. Showing it unchanged is the honest degrade; "Personal: Pro: ..." is not.
        assertEquals(
            "Pro: 7-day window will run out early",
            StripRules.stripTitle("Pro: 7-day window will run out early", "", "Personal"),
        )
    }

    @Test
    fun `an empty label leaves the sentence alone rather than prefixing a colon`() {
        assertEquals(
            "5-hour window at 80%",
            StripRules.stripTitle("5-hour window at 80%", "personal", ""),
        )
    }

    @Test
    fun `event strips take the same shape as the live condition strips`() {
        // Conditions.labelled composes "label: title" for the other profiles' faults.
        // Events must match it exactly or one panel shows two grammars.
        assertEquals(
            "Work: sign-in stopped working",
            StripRules.stripTitle("sign-in stopped working", "work", "Work"),
        )
    }
}
