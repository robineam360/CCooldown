package com.robin.claudeusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PingScheduleTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val today: LocalDate = LocalDate.of(2026, 7, 30)

    /** The user's own example: first ping 04:00, three renewals, stop at midnight. */
    private val config = PingSchedule.Config(
        enabled = true,
        firstPingMinuteOfDay = 4 * 60,
        renewals = 3,
        cutoffMinuteOfDay = 0,
    )

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(today, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    private fun fresh() = PingSchedule.DayState(day = null, windowsStarted = 0)

    private fun decide(
        nowMs: Long,
        day: PingSchedule.DayState = fresh(),
        sessionResetAtMs: Long? = null,
        cfg: PingSchedule.Config = config,
    ) = PingSchedule.decide(nowMs, zone, cfg, day, sessionResetAtMs)

    @Test
    fun `disabled cancels everything`() {
        val off = config.copy(enabled = false)
        assertEquals(PingSchedule.Decision.Off, decide(at(4), cfg = off))
    }

    @Test
    fun `before the first ping it waits for it`() {
        val d = decide(at(2, 30))
        assertTrue(d is PingSchedule.Decision.Wait)
        assertEquals(at(4), (d as PingSchedule.Decision.Wait).atMs)
    }

    @Test
    fun `at the first ping time it pings`() {
        val d = decide(at(4))
        assertTrue(d is PingSchedule.Decision.Ping)
        assertTrue((d as PingSchedule.Decision.Ping).isFirstOfDay)
    }

    @Test
    fun `an open window is never pinged into, and the chain follows the real reset`() {
        // The user started work at 03:00, so they own 03:00-08:00. The 04:00 alarm
        // must not ping, and must re-arm for 08:00 -- not for the configured 09:00.
        val d = decide(at(4), sessionResetAtMs = at(8))
        assertTrue(d is PingSchedule.Decision.Wait)
        assertEquals(at(8), (d as PingSchedule.Decision.Wait).atMs)
    }

    @Test
    fun `a window that has already ended is pinged over`() {
        // resets_at in the past means the window closed; nothing is open now.
        val d = decide(at(9), sessionResetAtMs = at(8), day = PingSchedule.DayState(today, 1))
        assertTrue(d is PingSchedule.Decision.Ping)
        assertFalse((d as PingSchedule.Decision.Ping).isFirstOfDay)
    }

    @Test
    fun `renewals bound the day at four windows`() {
        // 3 renewals + the first = 4. The fifth attempt must stop.
        val d = decide(at(19, 30), day = PingSchedule.DayState(today, 4))
        assertTrue(d is PingSchedule.Decision.DoneForToday)
        assertEquals(
            at(4) + 24 * 3600_000L,
            (d as PingSchedule.Decision.DoneForToday).atMs,
        )
    }

    @Test
    fun `none renewals means exactly one window`() {
        val once = config.copy(renewals = 0)
        assertTrue(decide(at(4), cfg = once) is PingSchedule.Decision.Ping)
        val after = decide(at(9), day = PingSchedule.DayState(today, 1), cfg = once)
        assertTrue(after is PingSchedule.Decision.DoneForToday)
    }

    @Test
    fun `yesterday's count does not bound today`() {
        val stale = PingSchedule.DayState(today.minusDays(1), 4)
        assertTrue(decide(at(4), day = stale) is PingSchedule.Decision.Ping)
    }

    @Test
    fun `the cutoff stops the chain rather than opening a window into the night`() {
        // 23:30 with a 23:00 cutoff: past it, so nothing more today.
        val early = config.copy(cutoffMinuteOfDay = 23 * 60)
        val d = decide(at(23, 30), day = PingSchedule.DayState(today, 2), cfg = early)
        assertTrue(d is PingSchedule.Decision.DoneForToday)
    }

    @Test
    fun `midnight cutoff means end of today, not start of it`() {
        // cutoff 0 must not read as 00:00 today, or every ping would be "past cutoff".
        assertTrue(decide(at(4)) is PingSchedule.Decision.Ping)
        assertTrue(decide(at(19)) is PingSchedule.Decision.Ping)
    }

    @Test
    fun `an open window running past the cutoff ends the day`() {
        val early = config.copy(cutoffMinuteOfDay = 20 * 60)
        val d = decide(at(19), sessionResetAtMs = at(23), day = PingSchedule.DayState(today, 2), cfg = early)
        assertTrue(d is PingSchedule.Decision.DoneForToday)
    }

    // --- did the ping actually land? ---

    @Test
    fun `no window before and one after proves the ping opened it`() {
        assertTrue(PingSchedule.windowMoved(null, at(9)))
    }

    @Test
    fun `no window after means the ping did nothing`() {
        assertFalse(PingSchedule.windowMoved(null, null))
        assertFalse(PingSchedule.windowMoved(at(9), null))
    }

    @Test
    fun `resets_at drift is not mistaken for a new window`() {
        // The five real CCBG-4 values span ~1.3s. Every pair must read as "no move",
        // or the verification step would report success when nothing happened.
        val drift = listOf(1785403199913L, 1785403199625L, 1785403200333L, 1785403200950L, 1785403200698L)
        for (a in drift) for (b in drift) {
            assertFalse("$a -> $b should not count as a new window", PingSchedule.windowMoved(a, b))
        }
    }

    @Test
    fun `a genuine five-hour jump counts as a new window`() {
        assertTrue(PingSchedule.windowMoved(at(9), at(14)))
    }

    @Test
    fun `lateness is measured but never negative`() {
        assertEquals(3 * 60_000L, PingSchedule.latenessMs(at(4), at(4, 3)))
        assertEquals(0L, PingSchedule.latenessMs(at(4), at(3, 59)))
    }

    // --- the ping-storm guard (CCBG-5) ---

    @Test
    fun `a second send is refused inside the floor`() {
        val sent = at(4)
        assertTrue(PingSchedule.tooSoonToSend(sent, sent + 60_000L))
        assertTrue(PingSchedule.tooSoonToSend(sent, sent + 9 * 60_000L))
    }

    @Test
    fun `a send is allowed once the floor has passed`() {
        val sent = at(4)
        assertFalse(PingSchedule.tooSoonToSend(sent, sent + PingSchedule.MIN_SEND_INTERVAL_MS))
        assertFalse(PingSchedule.tooSoonToSend(sent, sent + 30 * 60_000L))
    }

    @Test
    fun `never having sent is not too soon`() {
        assertFalse(PingSchedule.tooSoonToSend(0L, at(4)))
    }

    @Test
    fun `the whole verification window covers the observed lag`() {
        // Observed: invisible seconds after the ping, visible within ~5 minutes. Both
        // checks together must reach past that or we'd give up while it's still coming.
        val total = PingSchedule.VERIFY_DELAY_MS +
            (PingSchedule.MAX_VERIFY_ATTEMPTS - 1) * PingSchedule.VERIFY_RETRY_MS
        assertTrue("verification gives up after ${total}ms", total >= 5 * 60_000L)
        // ...and must finish well inside the send floor, so a slot can never be
        // re-pinged while its own verification is still outstanding.
        assertTrue(total < PingSchedule.MIN_SEND_INTERVAL_MS)
    }
}
