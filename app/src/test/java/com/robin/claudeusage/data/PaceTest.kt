package com.robin.claudeusage.data

import com.robin.claudeusage.data.Projection.PaceMilestone
import com.robin.claudeusage.data.Projection.PaceSeverity
import com.robin.claudeusage.data.Projection.PaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pace-alert ladder and transition rules (CCRM-21). The five guards each
 * exist because of a way this feature gets annoying; the tests are named for them.
 */
class PaceTest {

    private val fiveHours = Projection.SESSION_MS

    private fun est(hitsLimitAtMs: Long? = null, pctAtReset: Double = 50.0) =
        Projection.Estimate(1.0, hitsLimitAtMs, pctAtReset)

    // --- the severity ladder ---

    @Test
    fun `a young window is untracked no matter how alarming it projects`() {
        // 2% used after four minutes projects to 150% and means nothing.
        assertEquals(
            PaceSeverity.UNTRACKED,
            Projection.paceSeverity(2.0, 4 * 60_000L, fiveHours, est(hitsLimitAtMs = 1L, pctAtReset = 100.0)),
        )
        // Same for real usage in the first minute of a window.
        assertEquals(
            PaceSeverity.UNTRACKED,
            Projection.paceSeverity(40.0, 30_000L, fiveHours, est(hitsLimitAtMs = 1L)),
        )
    }

    @Test
    fun `young-window suppression scales with the period but floors at a minute`() {
        assertEquals(3 * 60_000L, Projection.paceMinElapsedMs(fiveHours))
        assertEquals(60_000L, Projection.paceMinElapsedMs(90 * 60_000L))
    }

    @Test
    fun `the ladder orders itself by how bad things are`() {
        val anHour = 60 * 60_000L
        assertEquals(PaceSeverity.HEALTHY, Projection.paceSeverity(20.0, anHour, fiveHours, est()))
        assertEquals(
            PaceSeverity.CLOSE,
            Projection.paceSeverity(40.0, anHour, fiveHours, est(pctAtReset = 92.0)),
        )
        assertEquals(
            PaceSeverity.RUNNING_OUT,
            Projection.paceSeverity(40.0, anHour, fiveHours, est(hitsLimitAtMs = 123L, pctAtReset = 100.0)),
        )
        assertEquals(
            PaceSeverity.SPENT,
            Projection.paceSeverity(99.6, anHour, fiveHours, null),
        )
    }

    @Test
    fun `no projection means no verdict about the future`() {
        // A null estimate (not enough signal) can never be CLOSE or RUNNING_OUT.
        assertEquals(
            PaceSeverity.HEALTHY,
            Projection.paceSeverity(60.0, 60 * 60_000L, fiveHours, null),
        )
    }

    // --- milestone satisfaction ---

    @Test
    fun `severities imply their milestones cumulatively`() {
        assertEquals(0, Projection.paceSatisfied(PaceSeverity.HEALTHY, 50.0))
        assertEquals(
            PaceMilestone.CUTTING_IT_CLOSE.bit,
            Projection.paceSatisfied(PaceSeverity.CLOSE, 50.0),
        )
        assertEquals(
            PaceMilestone.WILL_RUN_OUT.bit or PaceMilestone.CUTTING_IT_CLOSE.bit,
            Projection.paceSatisfied(PaceSeverity.RUNNING_OUT, 50.0),
        )
    }

    @Test
    fun `almost out keys on the quota not the ladder`() {
        assertEquals(
            PaceMilestone.ALMOST_OUT.bit,
            Projection.paceSatisfied(PaceSeverity.HEALTHY, 91.0),
        )
        // Untracked stays silent even at high usage — a window can be young AND full
        // right after enabling the feature mid-window with no history yet.
        assertEquals(0, Projection.paceSatisfied(PaceSeverity.UNTRACKED, 95.0))
    }

    // --- the transition rules (paceStep) ---

    private val key = 1_785_400_000_000L
    private val all = PaceMilestone.WILL_RUN_OUT.bit or
        PaceMilestone.CUTTING_IT_CLOSE.bit or PaceMilestone.ALMOST_OUT.bit

    @Test
    fun `guard 1 - the first observation of a window never fires`() {
        // A window already at 95% when the feature is enabled: everything satisfied,
        // nothing fired, all of it marked as known so it can't fire later either.
        val step = Projection.paceStep(null, key, fiveHours, all)
        assertTrue(step.fire.isEmpty())
        assertEquals(PaceState(key, all), step.carry)
    }

    @Test
    fun `guard 2 - drift is the same window, a real reset is a new one`() {
        val primed = Projection.paceStep(null, key, fiveHours, PaceMilestone.CUTTING_IT_CLOSE.bit).carry
        // Drifted a second: same window, so the fired bit holds and nothing re-fires.
        val drifted = Projection.paceStep(primed, key + 1_300, fiveHours, PaceMilestone.CUTTING_IT_CLOSE.bit)
        assertTrue(drifted.fire.isEmpty())
        // ...and the key re-anchors to the newest reading (CCBG-4's lesson).
        assertEquals(key + 1_300, drifted.carry.windowKey)
        // A full window-length jump is a genuinely new window: state resets, and the
        // new window's first observation primes rather than fires.
        val next = Projection.paceStep(drifted.carry, key + fiveHours, fiveHours, PaceMilestone.CUTTING_IT_CLOSE.bit)
        assertTrue(next.fire.isEmpty())
        assertEquals(PaceState(key + fiveHours, PaceMilestone.CUTTING_IT_CLOSE.bit), next.carry)
    }

    @Test
    fun `a milestone that becomes true after priming fires once`() {
        val primed = Projection.paceStep(null, key, fiveHours, 0).carry
        val step = Projection.paceStep(primed, key, fiveHours, PaceMilestone.CUTTING_IT_CLOSE.bit)
        assertEquals(listOf(PaceMilestone.CUTTING_IT_CLOSE), step.fire)
        // Caller records delivery; the next identical reading is quiet.
        val delivered = step.carry.copy(firedMask = step.carry.firedMask or PaceMilestone.CUTTING_IT_CLOSE.bit)
        assertTrue(Projection.paceStep(delivered, key, fiveHours, PaceMilestone.CUTTING_IT_CLOSE.bit).fire.isEmpty())
    }

    @Test
    fun `escalation fires the new milestone with the worst first`() {
        val state = PaceState(key, PaceMilestone.CUTTING_IT_CLOSE.bit)
        val step = Projection.paceStep(
            state, key, fiveHours,
            PaceMilestone.WILL_RUN_OUT.bit or PaceMilestone.CUTTING_IT_CLOSE.bit,
        )
        assertEquals(listOf(PaceMilestone.WILL_RUN_OUT), step.fire)
    }

    @Test
    fun `guard 3 - hysteresis re-arms only after severity genuinely drops`() {
        // Fired at RUNNING_OUT, still satisfied: quiet.
        val fired = PaceState(key, PaceMilestone.WILL_RUN_OUT.bit or PaceMilestone.CUTTING_IT_CLOSE.bit)
        val holding = Projection.paceStep(
            fired, key, fiveHours,
            PaceMilestone.WILL_RUN_OUT.bit or PaceMilestone.CUTTING_IT_CLOSE.bit,
        )
        assertTrue(holding.fire.isEmpty())
        // Pace drops to CLOSE: the WILL_RUN_OUT bit clears (re-armed), CUTTING holds.
        val dropped = Projection.paceStep(holding.carry, key, fiveHours, PaceMilestone.CUTTING_IT_CLOSE.bit)
        assertTrue(dropped.fire.isEmpty())
        assertEquals(PaceMilestone.CUTTING_IT_CLOSE.bit, dropped.carry.firedMask)
        // Pace rises again: WILL_RUN_OUT is genuinely new news and re-fires.
        val rose = Projection.paceStep(
            dropped.carry, key, fiveHours,
            PaceMilestone.WILL_RUN_OUT.bit or PaceMilestone.CUTTING_IT_CLOSE.bit,
        )
        assertEquals(listOf(PaceMilestone.WILL_RUN_OUT), rose.fire)
    }

    @Test
    fun `guard 5 - an undelivered milestone retries instead of being lost`() {
        val primed = Projection.paceStep(null, key, fiveHours, 0).carry
        val step = Projection.paceStep(primed, key, fiveHours, PaceMilestone.ALMOST_OUT.bit)
        assertEquals(listOf(PaceMilestone.ALMOST_OUT), step.fire)
        // Delivery failed: the caller persists carry WITHOUT the bit...
        assertEquals(0, step.carry.firedMask)
        // ...so the next poll fires it again.
        val retry = Projection.paceStep(step.carry, key, fiveHours, PaceMilestone.ALMOST_OUT.bit)
        assertEquals(listOf(PaceMilestone.ALMOST_OUT), retry.fire)
    }
}
