package com.robin.claudeusage.ui

import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pins the CCM-50 [Panel] bar pace-mark numbers for CCRM-43 (Bar Pace Marks), plus
 * the two rules the wireframe review added on top of the handover (rev B: the
 * segment starts exactly on the pace line and is never floored to the bar height).
 *
 * Boundaries are asserted through [PACE_DEAD_ZONE] itself, the way
 * [RingGeometryTest] does, so a copied gate can't silently drift from the shared one.
 */
class BarGeometryTest {

    /** A window that resets [minutes] from now — the only way elapsed is derivable. */
    private fun window(percent: Double?, minutes: Long?) = UsageWindow(
        percent = percent,
        resetsAt = minutes?.let { Instant.now().plusSeconds(it * 60) },
        serverSeverity = null,
    )

    // --- the handover fixtures ---

    @Test
    fun `90 minutes left of the 5-hour window puts the tick at 0-70`() {
        val elapsed = elapsedPercent(window(78.0, 90), Projection.SESSION_MS)!!
        assertEquals(70.0, elapsed, 0.05)
        assertEquals(0.70f, BarGeometry.tickFraction(elapsed), 1e-3f)

        // 78% is over pace; the segment runs from the pace line to the fill's edge.
        val (start, end) = BarGeometry.redSegment(78.0, elapsed)!!
        assertEquals(0.70f, start, 1e-3f)
        assertEquals(0.78f, end, 1e-3f)
    }

    @Test
    fun `the dead zone is 3 points, strict`() {
        // Over.
        assertNotNull(BarGeometry.redSegment(78.0, 70.0))
        assertNotNull(BarGeometry.redSegment(73.5, 70.0))
        // Not over: 73.0 sits exactly on elapsed + 3.0, which is inclusive of "fine".
        assertNull(BarGeometry.redSegment(73.0, 70.0))
        assertNull(BarGeometry.redSegment(72.0, 70.0))
        assertNull(BarGeometry.redSegment(30.0, 70.0))
        // Asserted through the constant, so the gate can't drift from the shared one.
        assertNull(BarGeometry.redSegment(70.0 + PACE_DEAD_ZONE, 70.0))
        assertNotNull(BarGeometry.redSegment(70.0 + PACE_DEAD_ZONE + 0.01, 70.0))
    }

    @Test
    fun `weekly and every model cap measure against 7 days`() {
        // 3.5 days left of the 7-day window → half elapsed.
        val weekly = elapsedPercent(window(60.0, 3 * 24 * 60 + 12 * 60), Projection.WEEKLY_MS)!!
        assertEquals(50.0, weekly, 0.05)
        assertEquals(0.50f, BarGeometry.tickFraction(weekly), 1e-3f)

        // A model cap is a "· 7-day" surface: same window length, same tick.
        val cap = elapsedPercent(window(92.0, 3 * 24 * 60 + 12 * 60), Projection.WEEKLY_MS)!!
        assertEquals(weekly, cap, 1e-4)   // two now() reads apart, so not bit-identical

        // Measuring a 7-day cap against the 5-hour window pins the tick at 0% — the
        // reset is further out than the whole window, so it reads as "just started".
        // This is the mistake the per-row windowLengthMs exists to prevent, and it
        // fails silently: a plausible bar with its mark parked at the left edge.
        val wrong = elapsedPercent(window(92.0, 3 * 24 * 60 + 12 * 60), Projection.SESSION_MS)!!
        assertEquals(0.0, wrong, 0.0)
    }

    // --- honesty rules ---

    @Test
    fun `no reset clock means no mark, and no percent means a bare track`() {
        // No resetsAt → elapsed is not derivable → no tick, no segment. The fill still draws.
        assertNull(elapsedPercent(window(55.0, null), Projection.SESSION_MS))
        assertFalse(BarGeometry.showTick(55.0, null))
        assertNull(BarGeometry.redSegment(55.0, null))

        // No percent → bare track: no fill to mark, so no tick either.
        assertFalse(BarGeometry.showTick(null, 70.0))
        assertNull(BarGeometry.redSegment(null, 70.0))

        // Both present → marked.
        assertTrue(BarGeometry.showTick(55.0, 70.0))
    }

    @Test
    fun `credits pass a null elapsed, which is what leaves them unmarked`() {
        // Money has no clock: the credits row has a percent but never an elapsed.
        assertFalse(BarGeometry.showTick(61.0, null))
        assertNull(BarGeometry.redSegment(61.0, null))
    }

    // --- the settings toggles ---

    @Test
    fun `toggle off drops the segment on bars and rings but never the tick`() {
        assertNull(BarGeometry.redSegment(90.0, 40.0, enabled = false))
        assertNotNull(BarGeometry.redSegment(90.0, 40.0, enabled = true))
        assertTrue(BarGeometry.showTick(90.0, 40.0))

        // The widgets toggle gates the shipped rings by the same rule.
        assertNull(RingGeometry.redSegment(90.0, 40.0, enabled = false))
        assertNotNull(RingGeometry.redSegment(90.0, 40.0, enabled = true))
        assertTrue(RingGeometry.showTick(90.0, 40.0))
    }

    // --- rev B: exact start, no floor ---

    @Test
    fun `the segment starts on the pace line and is never widened to the bar height`() {
        // A 3.5-point overshoot stays a 3.5-point segment. Inflating it to a minimum
        // width would drag its left edge behind the tick, which is the thing the
        // wireframe review ruled out (rev B, D3) — the boundary must land on the mark.
        val (start, end) = BarGeometry.redSegment(73.5, 70.0)!!
        assertEquals(0.70f, start, 1e-4f)
        assertEquals(0.735f, end, 1e-4f)
        assertEquals(BarGeometry.tickFraction(70.0), start, 0f)
        assertEquals(BarGeometry.fillFraction(73.5), end, 0f)
    }

    @Test
    fun `at 100 percent the segment still reports the fill's full extent`() {
        // The fill is already red, so the segment is invisible against it — but the
        // numbers stay honest, and the tick still records where even pace was.
        val (start, end) = BarGeometry.redSegment(100.0, 62.0)!!
        assertEquals(0.62f, start, 1e-4f)
        assertEquals(1f, end, 0f)
        assertTrue(BarGeometry.showTick(100.0, 62.0))
    }

    @Test
    fun `a fill at or behind the pace line yields no segment`() {
        // Guard against an inverted segment when the payload's percent trails elapsed.
        assertNull(BarGeometry.redSegment(70.0, 70.0))
        assertNull(BarGeometry.redSegment(42.0, 70.0))
        // Over-100 payloads clamp, so a clamped fill can't run past the bar's end.
        val (_, end) = BarGeometry.redSegment(120.0, 70.0)!!
        assertEquals(1f, end, 0f)
    }

    // --- clamps and floors ---

    @Test
    fun `fractions clamp to the bar`() {
        assertEquals(0f, BarGeometry.fillFraction(0.0), 0f)
        assertEquals(0.44f, BarGeometry.fillFraction(44.0), 1e-4f)
        assertEquals(1f, BarGeometry.fillFraction(100.0), 0f)
        assertEquals(1f, BarGeometry.fillFraction(120.0), 0f)
        assertEquals(0f, BarGeometry.tickFraction(-5.0), 0f)
        assertEquals(1f, BarGeometry.tickFraction(120.0), 0f)
    }

    @Test
    fun `tick width is 31 percent of bar height with a 2px floor`() {
        assertEquals(3.72f, BarGeometry.tickWidth(12f), 1e-4f)
        assertEquals(4.34f, BarGeometry.tickWidth(14f), 1e-4f)
        // Thin bars hit the floor rather than vanishing.
        assertEquals(2f, BarGeometry.tickWidth(6f), 0f)
        assertEquals(2f, BarGeometry.tickWidth(2f), 0f)
    }

    @Test
    fun `tick overhangs both long edges by 30 percent of height`() {
        assertEquals(3.6f, BarGeometry.tickOverhang(12f), 1e-4f)
        // The bitmap surfaces grow by twice this so the overhang is never clipped.
        assertEquals(12f + 2 * 3.6f, 12f + 2 * BarGeometry.tickOverhang(12f), 1e-4f)
    }
}
