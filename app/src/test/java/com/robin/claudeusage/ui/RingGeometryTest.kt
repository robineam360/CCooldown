package com.robin.claudeusage.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the CCM-49 [Desktop] pace-mark numbers for CCRM-39 (Ring Widget) /
 * CCRM-40 (Mini-Rings Widget), so the rings can't drift from the handover spec.
 */
class RingGeometryTest {

    @Test
    fun `tick position maps elapsed around the ring from 12 o'clock`() {
        assertEquals(0f, RingGeometry.tickSweep(0.0), 1e-4f)     // 12 o'clock
        assertEquals(90f, RingGeometry.tickSweep(25.0), 1e-4f)   // 3 o'clock
        assertEquals(180f, RingGeometry.tickSweep(50.0), 1e-4f)  // 6 o'clock
        assertEquals(360f, RingGeometry.tickSweep(100.0), 1e-4f)
        assertEquals(-90f, RingGeometry.START_ANGLE, 0f)
    }

    @Test
    fun `tick width is 31 percent of stroke with a 2px floor`() {
        assertEquals(2.48f, RingGeometry.tickWidth(8f), 1e-4f)
        // Mini-ring strokes hit the floor so the tick stays visible.
        assertEquals(2f, RingGeometry.tickWidth(4f), 0f)
        assertEquals(2f, RingGeometry.tickWidth(1f), 0f)
    }

    @Test
    fun `tick overhangs both stroke edges by 30 percent of stroke`() {
        assertEquals(2.4f, RingGeometry.tickOverhang(8f), 1e-4f)
    }

    @Test
    fun `red segment gate is the shared dead zone, strict`() {
        // Exactly at elapsed + 3.0 → no segment (a hairline overshoot draws the tick alone).
        assertNull(RingGeometry.redSegment(58.3, 55.3))
        // Strictly past the dead zone → segment from tick to fill tip.
        val seg = RingGeometry.redSegment(58.31, 55.3)
        assertNotNull(seg)
        // The handover fixture: session 72% at 55.3% elapsed.
        val (start, sweep) = RingGeometry.redSegment(72.0, 55.3)!!
        assertEquals(RingGeometry.tickSweep(55.3), start, 1e-4f)
        assertEquals(RingGeometry.fillSweep(72.0) - start, sweep, 1e-4f)
        // Under pace → tick only.
        assertNull(RingGeometry.redSegment(41.0, 54.8))
        // The gate must be PACE_DEAD_ZONE itself, not a copy that can drift.
        assertNull(RingGeometry.redSegment(50.0 + PACE_DEAD_ZONE, 50.0))
        assertNotNull(RingGeometry.redSegment(50.0 + PACE_DEAD_ZONE + 0.01, 50.0))
    }

    @Test
    fun `honesty rule - no percent or no elapsed means no tick and no segment`() {
        assertFalse(RingGeometry.showTick(null, 55.0))
        assertFalse(RingGeometry.showTick(72.0, null))
        assertTrue(RingGeometry.showTick(72.0, 55.3))
        assertNull(RingGeometry.redSegment(null, 55.0))
        assertNull(RingGeometry.redSegment(72.0, null))
    }

    @Test
    fun `fill sweep clamps to the ring`() {
        assertEquals(0f, RingGeometry.fillSweep(0.0), 0f)
        assertEquals(259.2f, RingGeometry.fillSweep(72.0), 1e-3f)
        assertEquals(360f, RingGeometry.fillSweep(100.0), 0f)
        assertEquals(360f, RingGeometry.fillSweep(120.0), 0f) // over-100 payloads clamp
    }

    // --- CCRM-50 (Weekly Flag): the dot's rungs are the pace verdicts ---

    @Test
    fun `weekly flag mirrors the pace sentence verdicts`() {
        // Below even pace → silence: good news is no dot.
        assertNull(RingGeometry.weeklyFlag(30.0, 60.0))
        // The on-pace band is the sentence's own ±PACE_DEAD_ZONE, both sides.
        assertEquals(RingGeometry.WeeklyFlag.ON_PACE, RingGeometry.weeklyFlag(55.0, 57.0))
        assertEquals(RingGeometry.WeeklyFlag.ON_PACE, RingGeometry.weeklyFlag(58.0, 56.0))
        assertEquals(
            RingGeometry.WeeklyFlag.ON_PACE,
            RingGeometry.weeklyFlag(50.0 - PACE_DEAD_ZONE, 50.0),
        )
        // The gate must be PACE_DEAD_ZONE itself, not a copy that can drift — the
        // dot goes yellow at the exact poll the sentence flips to "above".
        assertEquals(
            RingGeometry.WeeklyFlag.ON_PACE,
            RingGeometry.weeklyFlag(50.0 + PACE_DEAD_ZONE, 50.0),
        )
        assertEquals(
            RingGeometry.WeeklyFlag.ABOVE,
            RingGeometry.weeklyFlag(50.0 + PACE_DEAD_ZONE + 0.01, 50.0),
        )
        assertNull(RingGeometry.weeklyFlag(50.0 - PACE_DEAD_ZONE - 0.01, 50.0))
    }

    @Test
    fun `weekly flag SPENT keys on the truncated level and needs no clock`() {
        // 99.7 truncates to 99 — not spent, and against a pace it is merely above.
        assertEquals(RingGeometry.WeeklyFlag.ABOVE, RingGeometry.weeklyFlag(99.7, 80.0))
        assertEquals(RingGeometry.WeeklyFlag.SPENT, RingGeometry.weeklyFlag(100.0, 80.0))
        // Level is absolute: spent even when elapsed says the window is nearly over…
        assertEquals(RingGeometry.WeeklyFlag.SPENT, RingGeometry.weeklyFlag(100.0, 99.0))
        // …and even with no reset clock at all.
        assertEquals(RingGeometry.WeeklyFlag.SPENT, RingGeometry.weeklyFlag(100.0, null))
    }

    @Test
    fun `weekly flag honesty - no reading is silence, no clock allows only SPENT`() {
        assertNull(RingGeometry.weeklyFlag(null, 50.0))
        assertNull(RingGeometry.weeklyFlag(null, null))
        // No clock → no pace verdict, never a guessed one.
        assertNull(RingGeometry.weeklyFlag(62.0, null))
    }
}
