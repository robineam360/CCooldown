package com.robin.claudeusage.widget

import com.robin.claudeusage.alerts.Alerts
import com.robin.claudeusage.data.AuthState
import com.robin.claudeusage.data.ModelCap
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.data.Snapshot
import com.robin.claudeusage.data.UsageData
import com.robin.claudeusage.data.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Pins the handover's state table (§4) and copy for the three ring/pace faces
 * (CCRM-39/40/41), so the states nobody looks at stay pinned by a test instead.
 */
class WidgetFaceTest {

    @Before
    fun pinLocale() {
        // The formatters follow the device locale, like Fmt; the JVM's default
        // CLDR locale renders "pm" lowercase, so the copy assertions pin en-US.
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    // 2026-08-13 14:20 EDT
    private val now: Long = Instant.parse("2026-08-13T18:20:00Z").toEpochMilli()

    private fun snapshot(
        authState: AuthState = AuthState.OK,
        fetchedAt: Long = now - 2 * 60_000L,
        lastStatus: String = "OK",
    ) = Snapshot(
        rawJson = null,
        fetchedAt = fetchedAt,
        lastStatus = lastStatus,
        lastAttemptAt = fetchedAt,
        authState = authState,
    )

    // --- faceState: the §4 table ---

    @Test
    fun `signed out wins over everything`() {
        assertEquals(
            FaceState.NOT_SIGNED_IN,
            faceState(snapshot(authState = AuthState.NO_CREDENTIALS), hasData = false, nowMs = now),
        )
    }

    @Test
    fun `no data before any state dressing`() {
        assertEquals(FaceState.NO_DATA, faceState(snapshot(), hasData = false, nowMs = now))
    }

    @Test
    fun `stale exactly at the shared six hour boundary`() {
        val atBoundary = snapshot(fetchedAt = now - Alerts.STALE_DATA_MS)
        assertEquals(FaceState.OK, faceState(atBoundary, hasData = true, nowMs = now))
        val past = snapshot(fetchedAt = now - Alerts.STALE_DATA_MS - 1)
        assertEquals(FaceState.STALE, faceState(past, hasData = true, nowMs = now))
    }

    @Test
    fun `failed poll shows the fetch error treatment, stale outranks it`() {
        val failed = snapshot(lastStatus = "Rate limited (429) — backing off")
        assertEquals(FaceState.FETCH_ERROR, faceState(failed, hasData = true, nowMs = now))
        val failedAndStale = snapshot(
            fetchedAt = now - Alerts.STALE_DATA_MS - 60_000L,
            lastStatus = "Rate limited (429) — backing off",
        )
        assertEquals(FaceState.STALE, faceState(failedAndStale, hasData = true, nowMs = now))
    }

    @Test
    fun `fresh successful fetch is ok`() {
        assertEquals(FaceState.OK, faceState(snapshot(), hasData = true, nowMs = now))
    }

    // --- widgetCountdown: two units max, "soon" inside five minutes ---

    @Test
    fun `countdown softens to soon inside five minutes`() {
        assertEquals("soon", widgetCountdown(Instant.ofEpochMilli(now + 4 * 60_000L + 59_000L), now))
        assertEquals("5m", widgetCountdown(Instant.ofEpochMilli(now + 5 * 60_000L + 1_000L), now))
        assertEquals("soon", widgetCountdown(Instant.ofEpochMilli(now - 1_000L), now))
    }

    @Test
    fun `countdown is two units max`() {
        assertEquals(
            "2h 14m",
            widgetCountdown(Instant.ofEpochMilli(now + (2 * 60 + 14) * 60_000L), now),
        )
        // 3d 4h 22m truncates the minutes — never three units.
        assertEquals(
            "3d 4h",
            widgetCountdown(Instant.ofEpochMilli(now + ((3 * 24 + 4) * 60 + 22) * 60_000L), now),
        )
        assertEquals("—", widgetCountdown(null, now))
    }

    // --- resetMoment: the exact clock, never weekday-only ---

    @Test
    fun `reset moment reads today, tomorrow, then the date`() {
        // now is 14:20 local; 19:34 is still today.
        assertEquals(
            "today at 7:34 PM",
            resetMoment(Instant.parse("2026-08-13T23:34:00Z"), false, now, zone),
        )
        assertEquals(
            "tomorrow at 9:00 AM",
            resetMoment(Instant.parse("2026-08-14T13:00:00Z"), false, now, zone),
        )
        // A weekly reset carries the date — a 7-day window starts and ends on
        // the same weekday, so a weekday label would be ambiguous.
        val weekly = resetMoment(Instant.parse("2026-08-16T13:00:00Z"), false, now, zone)
        assertEquals("Aug 16 at 9:00 AM", weekly)
        assertEquals(
            "Aug 16 at 09:00",
            resetMoment(Instant.parse("2026-08-16T13:00:00Z"), true, now, zone),
        )
        assertEquals("", resetMoment(null, false, now, zone))
    }

    // --- paceSentence: the handover copy, refusal included ---

    @Test
    fun `pace sentence names the moment when the limit lands early`() {
        val resetMs = Instant.parse("2026-08-13T23:34:00Z").toEpochMilli()
        val hits = Instant.parse("2026-08-13T22:51:00Z").toEpochMilli() // 18:51 local
        val est = Projection.Estimate(ratePctPerHour = 12.5, hitsLimitAtMs = hits, pctAtReset = 100.0)
        assertEquals(
            "At this pace: 100% at 6:51 PM — 43m before the reset",
            paceSentence(est, resetMs, use24h = false, zone = zone),
        )
    }

    @Test
    fun `pace sentence projects the landing when under the limit`() {
        val resetMs = Instant.parse("2026-08-16T13:00:00Z").toEpochMilli()
        val est = Projection.Estimate(ratePctPerHour = 0.5, hitsLimitAtMs = null, pctAtReset = 78.4)
        assertEquals(
            "At this pace: 78% by the reset",
            paceSentence(est, resetMs, use24h = false, zone = zone),
        )
    }

    @Test
    fun `refused projection prints why`() {
        assertEquals(
            "Usage hasn't moved enough yet to project a pace",
            paceSentence(null, now, use24h = false, zone = zone),
        )
    }

    // --- windowRows: payload order, capped at four ---

    private fun window(pct: Double) = UsageWindow(pct, Instant.ofEpochMilli(now + 60_000L), null)

    @Test
    fun `rows keep payload order - session, weekly, model caps`() {
        val rows = windowRows(
            UsageData(
                session = window(72.0),
                weekly = window(41.0),
                modelCaps = listOf(ModelCap("Opus 4.5", window(84.0))),
            )
        )
        assertEquals(listOf("5h", "7d", "Opus 4.5"), rows.map { it.title })
        assertEquals(Projection.SESSION_MS, rows[0].windowLengthMs)
        // Weekly AND every model cap tick against the 7-day clock.
        assertEquals(Projection.WEEKLY_MS, rows[1].windowLengthMs)
        assertEquals(Projection.WEEKLY_MS, rows[2].windowLengthMs)
    }

    @Test
    fun `rows cap at four so the fixed windows always survive`() {
        val rows = windowRows(
            UsageData(
                session = window(72.0),
                weekly = window(41.0),
                modelCaps = listOf(
                    ModelCap("Opus 4.5", window(84.0)),
                    ModelCap("Sonnet 4.5", window(37.0)),
                    ModelCap("Haiku 4.5", window(12.0)),
                ),
            )
        )
        assertEquals(4, rows.size)
        assertEquals(listOf("5h", "7d", "Opus 4.5", "Sonnet 4.5"), rows.map { it.title })
    }

    @Test
    fun `a missing window drops its row instead of faking one`() {
        val rows = windowRows(UsageData(session = null, weekly = window(41.0), modelCaps = emptyList()))
        assertEquals(listOf("7d"), rows.map { it.title })
    }
}
