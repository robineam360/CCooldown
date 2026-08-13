package com.robin.claudeusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateGateTest {

    private val sixHours = UpdateGate.CHECK_INTERVAL_MS
    private val t0 = 1_700_000_000_000L

    // --- the should-check-now gate ---------------------------------------------------

    @Test
    fun `a fresh install checks on the very first poll`() {
        assertTrue(UpdateGate.shouldCheckNow(autoEnabled = true, nowMs = t0, lastSuccessAtMs = 0L))
    }

    @Test
    fun `six hours after a success it checks again`() {
        assertTrue(UpdateGate.shouldCheckNow(true, t0 + sixHours, t0))
    }

    @Test
    fun `inside the six hours it stays quiet`() {
        assertFalse(UpdateGate.shouldCheckNow(true, t0 + sixHours - 1, t0))
    }

    @Test
    fun `the toggle off silences the gate regardless of age`() {
        assertFalse(UpdateGate.shouldCheckNow(false, t0 + 10 * sixHours, t0))
    }

    @Test
    fun `a failed check does not advance the anchor, so the next poll retries`() {
        // The gate only ever sees the last *successful* check — a failure at t0+7h
        // leaves the anchor at t0, so a poll minutes later is still due.
        val lastSuccess = t0
        val pollAfterFailure = t0 + 7 * 60 * 60_000L + 5 * 60_000L
        assertTrue(UpdateGate.shouldCheckNow(true, pollAfterFailure, lastSuccess))
    }

    // --- the post-or-not decision table -----------------------------------------------

    @Test
    fun `a newer version never seen before notifies`() {
        assertTrue(UpdateGate.shouldNotify("v0.15", "0.14", null, null))
    }

    @Test
    fun `up to date never notifies`() {
        assertFalse(UpdateGate.shouldNotify("v0.14", "0.14", null, null))
        assertFalse(UpdateGate.shouldNotify("0.13", "0.14", null, null))
    }

    @Test
    fun `a version already notified stays silent — once per version, ever`() {
        assertFalse(UpdateGate.shouldNotify("v0.15", "0.14", "0.15", null))
    }

    @Test
    fun `a skipped version stays silent`() {
        assertFalse(UpdateGate.shouldNotify("v0.15", "0.14", null, "0.15"))
    }

    @Test
    fun `a release newer than the skipped one notifies — skip is per-version`() {
        assertTrue(UpdateGate.shouldNotify("v0.16", "0.14", "0.15", "0.15"))
    }

    @Test
    fun `normalisation makes v-prefixed and bare versions the same version`() {
        assertFalse(UpdateGate.shouldNotify("0.15", "0.14", "v0.15", null))
        assertFalse(UpdateGate.shouldNotify("v0.15", "0.14", null, "0.15"))
    }

    // --- the settings outcome line ----------------------------------------------------

    @Test
    fun `outcomes read as the settings card draws them`() {
        assertEquals("up to date (v0.14)", UpdateGate.successOutcome("v0.14", false))
        assertEquals("v0.15 available", UpdateGate.successOutcome("0.15", true))
    }

    @Test
    fun `the skipped marker appears only when the available version is the skipped one`() {
        assertEquals(
            "v0.15 available (skipped)",
            UpdateGate.outcomeLine("v0.15 available", "v0.15"),
        )
        assertEquals("v0.16 available", UpdateGate.outcomeLine("v0.16 available", "0.15"))
        assertEquals("up to date (v0.14)", UpdateGate.outcomeLine("up to date (v0.14)", "0.15"))
    }

    @Test
    fun `isSkipped compares through normalisation`() {
        assertTrue(UpdateGate.isSkipped("v0.15", "0.15"))
        assertFalse(UpdateGate.isSkipped("v0.16", "0.15"))
        assertFalse(UpdateGate.isSkipped("v0.15", null))
    }

    // --- notes trimming ----------------------------------------------------------------

    @Test
    fun `headings are stripped and list markers become dots`() {
        val notes = "## What's new\n- Pace alerts get quiet hours\n* Fold widget fixes"
        assertEquals(
            "What's new\n· Pace alerts get quiet hours\n· Fold widget fixes",
            UpdateGate.trimNotes(notes),
        )
    }

    @Test
    fun `only the first four non-blank lines survive, with an ellipsis`() {
        val notes = "one\n\ntwo\nthree\n\nfour\nfive"
        assertEquals("one\ntwo\nthree\nfour…", UpdateGate.trimNotes(notes))
    }

    @Test
    fun `the character cap cuts a single enormous line`() {
        val long = "x".repeat(400)
        val trimmed = UpdateGate.trimNotes(long)
        assertEquals(UpdateGate.MAX_NOTE_CHARS + 1, trimmed.length) // cap + the ellipsis
        assertTrue(trimmed.endsWith("…"))
    }

    @Test
    fun `blank notes trim to nothing`() {
        assertEquals("", UpdateGate.trimNotes(""))
        assertEquals("", UpdateGate.trimNotes("  \n\n  "))
    }

    // --- the tap URL -------------------------------------------------------------------

    @Test
    fun `a github release page passes through`() {
        val url = "https://github.com/robineam360/CCooldown/releases/tag/v0.15"
        assertEquals(url, UpdateGate.safeReleaseUrl(url))
    }

    @Test
    fun `anything else falls back to the hardcoded releases page`() {
        assertEquals(UpdateGate.FALLBACK_RELEASE_URL, UpdateGate.safeReleaseUrl(null))
        assertEquals(UpdateGate.FALLBACK_RELEASE_URL, UpdateGate.safeReleaseUrl(""))
        assertEquals(UpdateGate.FALLBACK_RELEASE_URL, UpdateGate.safeReleaseUrl("http://github.com/x"))
        assertEquals(UpdateGate.FALLBACK_RELEASE_URL, UpdateGate.safeReleaseUrl("https://evil.com/github.com"))
        assertEquals(UpdateGate.FALLBACK_RELEASE_URL, UpdateGate.safeReleaseUrl("https://github.com.evil.com/x"))
        assertEquals(UpdateGate.FALLBACK_RELEASE_URL, UpdateGate.safeReleaseUrl("not a url ::"))
    }
}
