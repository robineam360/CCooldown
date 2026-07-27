package com.robin.claudeusage.data

import com.robin.claudeusage.ui.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [UsageParser] to payloads actually returned by /api/oauth/usage. The schema
 * is undocumented and carries transient experiment fields, so the point of these
 * tests is the *captured sample* — when the shape shifts, this is where it shows up.
 */
class UsageParserTest {

    /** Captured 2026-07-27. Trimmed only of experiment keys that are all null. */
    private val realPayload = """
        {"five_hour":{"utilization":0.0,"resets_at":"2026-07-27T11:49:59.956769+00:00",
        "limit_dollars":null,"used_dollars":null,"remaining_dollars":null},
        "seven_day":{"utilization":30.0,"resets_at":"2026-07-30T18:59:59.956800+00:00",
        "limit_dollars":null,"used_dollars":null,"remaining_dollars":null},
        "seven_day_opus":null,"tangelo":null,
        "extra_usage":{"is_enabled":true,"monthly_limit":10000,"used_credits":599.0,
        "utilization":5.99,"currency":"USD","decimal_places":2,"disabled_reason":null,
        "user_disabled":false,"spend_limit_reached":false,"credits_ever_enabled":true,
        "daily":null,"weekly":null},
        "limits":[{"kind":"session","group":"session","percent":0,"severity":"normal",
        "resets_at":"2026-07-27T11:49:59.956769+00:00","scope":null,"is_active":false},
        {"kind":"weekly_all","group":"weekly","percent":30,"severity":"normal",
        "resets_at":"2026-07-30T18:59:59.956800+00:00","scope":null,"is_active":true}],
        "spend":{"used":{"amount_minor":599,"currency":"USD","exponent":2},
        "limit":{"amount_minor":10000,"currency":"USD","exponent":2},"percent":6,
        "severity":"normal","enabled":true,"disabled_reason":null,
        "cap":{"money":null,"credits":{"amount_minor":10000,"exponent":2}},
        "balance":null,"auto_reload":null,"disclaimer":"Usage credits cover you...",
        "can_purchase_credits":false,"can_toggle":false},
        "member_dashboard_available":false}
    """.trimIndent().replace("\n", "")

    @Test
    fun `parses windows from the captured payload`() {
        val data = UsageParser.parse(realPayload)
        assertNotNull(data)
        assertEquals(0.0, data!!.session?.percent)
        assertEquals(30.0, data.weekly?.percent)
        assertTrue(data.modelCaps.isEmpty())
    }

    @Test
    fun `reads credits from the spend block`() {
        val credits = UsageParser.parse(realPayload)?.credits
        assertNotNull(credits)
        assertEquals(599L, credits!!.usedMinor)
        assertEquals(10000L, credits.limitMinor)
        assertEquals(2, credits.exponent)
        assertEquals("USD", credits.currency)
        // Computed from the money, not the server's rounded `"percent":6`.
        assertEquals(5.99, credits.percent, 0.0001)
        assertEquals(9401L, credits.remainingMinor)
    }

    @Test
    fun `displayed percent rounds rather than truncates`() {
        // 5.99% must read as 6%, not the 5% a window bar's toInt() would give.
        assertEquals(6, UsageParser.parse(realPayload)!!.credits!!.percentDisplay)
        assertEquals(0, credits(0, 10000).percentDisplay)
        assertEquals(1, credits(51, 10000).percentDisplay)      // 0.51% -> 1%
        assertEquals(100, credits(9999, 10000).percentDisplay)  // 99.99% -> 100%
        assertEquals(100, credits(10000, 10000).percentDisplay)
    }

    @Test
    fun `spending past the limit clamps remaining but not the percentage`() {
        val over = credits(12000, 10000)
        assertEquals(0L, over.remainingMinor)
        assertEquals(120.0, over.percent, 0.0001)
    }

    private fun credits(usedMinor: Long, limitMinor: Long) = SpendCredits(
        usedMinor = usedMinor,
        limitMinor = limitMinor,
        exponent = 2,
        currency = "USD",
        serverSeverity = null,
    )

    @Test
    fun `renders credits the way Claude does`() {
        val c = UsageParser.parse(realPayload)!!.credits!!
        assertEquals("$5.99", Fmt.money(c.usedMinor, c.exponent, c.currency))
        assertEquals("$100.00", Fmt.money(c.limitMinor, c.exponent, c.currency))
        assertEquals("$94.01", Fmt.money(c.remainingMinor, c.exponent, c.currency))
    }

    @Test
    fun `falls back to extra_usage when spend is absent`() {
        val credits = UsageParser.parse(
            """{"extra_usage":{"monthly_limit":5000,"used_credits":957.0,
               "currency":"USD","decimal_places":2}}""".replace("\n", "")
        )?.credits
        assertNotNull(credits)
        assertEquals(957L, credits!!.usedMinor)
        assertEquals(5000L, credits.limitMinor)
        assertEquals("$9.57", Fmt.money(credits.usedMinor, credits.exponent, credits.currency))
    }

    @Test
    fun `no credits when the account has none`() {
        val data = UsageParser.parse(
            """{"limits":[{"kind":"session","percent":12,"severity":"normal",
               "resets_at":"2026-07-27T11:49:59.956769+00:00"}],
               "extra_usage":{"monthly_limit":null},"spend":null}""".replace("\n", "")
        )
        assertNotNull(data)
        assertNull(data!!.credits)
    }

    @Test
    fun `a zero limit parses but stays hidden by the render rule`() {
        val credits = UsageParser.parse(
            """{"spend":{"used":{"amount_minor":0,"currency":"USD","exponent":2},
               "limit":{"amount_minor":0,"currency":"USD","exponent":2}}}""".replace("\n", "")
        )?.credits
        assertNotNull(credits)
        assertEquals(0L, credits!!.limitMinor)
        assertEquals(0.0, credits.percent, 0.0001)
    }

    @Test
    fun `junk is rejected rather than half-parsed`() {
        assertNull(UsageParser.parse("not json"))
        assertNull(UsageParser.parse("{}"))
    }
}
