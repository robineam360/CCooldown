package com.robin.claudeusage.data.source

import com.robin.claudeusage.data.WindowKind
import com.robin.claudeusage.data.classifyWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pins [ChatGptUsageParser] to `/backend-api/wham/usage` (CCRM-54 (ChatGPT Account)).
 *
 * `chatgpt-usage-2026-09.json` is a **real payload**, captured on the phone on
 * 2026-09-06 from a Plus account, with only its three identifying fields — `user_id`,
 * `account_id`, `email` — replaced; this is a public repo. Everything shape-bearing is
 * verbatim, so when OpenAI changes the schema this file is where it shows up.
 *
 * States the captured account doesn't happen to be in (a weekly-only plan, Spark caps,
 * a live credit balance) are exercised with hand-built bodies, each labelled as such.
 */
class ChatGptUsageParserTest {

    private val real: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("chatgpt-usage-2026-09.json")!!
            .bufferedReader().readText()
    }

    // --- the captured payload ---

    @Test
    fun `the captured payload reads both windows, classified by duration`() {
        val data = ChatGptUsageParser.parse(real)!!
        val session = data.session!!
        val weekly = data.weekly!!
        assertEquals(9.0, session.percent!!, 0.001)
        assertEquals(79.0, weekly.percent!!, 0.001)
        assertEquals(Instant.ofEpochSecond(1788712966L), session.resetsAt)
        assertEquals(Instant.ofEpochSecond(1788882142L), weekly.resetsAt)
    }

    @Test
    fun `the 5-hour window is present on a Plus account`() {
        // OpenAI suspended the 5-hour limit for Plus / Pro / Business on 2026-07-12;
        // the capture shows an 18000-second primary_window anyway. So the suspension
        // isn't universal, and neither branch may be assumed — hence the classifier.
        assertEquals(WindowKind.SESSION, classifyWindow(18_000L))
        assertNotNull(ChatGptUsageParser.parse(real)!!.session)
    }

    @Test
    fun `reset_at wins when the payload carries both reset forms`() {
        // The real windows carry reset_at *and* reset_after_seconds. The absolute one
        // is authoritative; deriving from the relative one would re-anchor the window
        // to whenever we happened to fetch.
        val session = ChatGptUsageParser.parse(real, nowMs = 0L)!!.session!!
        assertEquals(Instant.ofEpochSecond(1788712966L), session.resetsAt)
    }

    @Test
    fun `a null additional_rate_limits is no caps, not a failure`() {
        val data = ChatGptUsageParser.parse(real)!!
        assertTrue(data.modelCaps.isEmpty())
    }

    @Test
    fun `has_credits false on the captured payload reports no credits`() {
        assertNull(ChatGptUsageParser.parse(real)!!.credits)
    }

    @Test
    fun `the live plan_type is readable off the captured payload`() {
        assertEquals("plus", ChatGptUsageParser.planType(real))
        assertNull(ChatGptUsageParser.planType("""{"rate_limit":{}}"""))
    }

    @Test
    fun `the keys we deliberately ignore don't derail the parse`() {
        // code_review_rate_limit, model_usage, spend_control, promo and
        // rate_limit_reset_credits all appear in the capture and none is a reading.
        val data = ChatGptUsageParser.parse(real)
        assertNotNull(data)
        assertEquals(9.0, data!!.session!!.percent!!, 0.001)
    }

    // --- states the captured account isn't in (hand-built bodies) ---

    @Test
    fun `weekly alone in primary_window lands in weekly, not session`() {
        val body = """
            {"rate_limit":{"primary_window":
            {"used_percent":12,"reset_at":1788758400,"limit_window_seconds":604800}}}
        """.trimIndent().replace("\n", "")
        val data = ChatGptUsageParser.parse(body)!!
        assertNull("a 7-day window must never be read as a session", data.session)
        assertEquals(12.0, data.weekly!!.percent!!, 0.001)
    }

    @Test
    fun `absent primary_window yields a null session and a non-null payload`() {
        val body = """
            {"plan_type":"plus","rate_limit":{"secondary_window":
            {"used_percent":30,"reset_at":1788758400,"limit_window_seconds":604800}}}
        """.trimIndent().replace("\n", "")
        val data = ChatGptUsageParser.parse(body)
        assertNotNull("a weekly-only account still has usage to show", data)
        assertNull(data!!.session)
        assertEquals(30.0, data.weekly!!.percent!!, 0.001)
    }

    @Test
    fun `reset_after_seconds is used when reset_at is absent`() {
        val now = 1_788_000_000_000L
        val body = """
            {"rate_limit":{"primary_window":
            {"used_percent":5,"reset_after_seconds":3600,"limit_window_seconds":18000}}}
        """.trimIndent().replace("\n", "")
        val data = ChatGptUsageParser.parse(body, nowMs = now)!!
        assertEquals(Instant.ofEpochMilli(now + 3_600_000L), data.session!!.resetsAt)
    }

    @Test
    fun `a window with no reset at all parses with a null resetsAt`() {
        val body = """{"rate_limit":{"primary_window":{"used_percent":5,"limit_window_seconds":18000}}}"""
        val session = ChatGptUsageParser.parse(body)!!.session!!
        assertEquals(5.0, session.percent!!, 0.001)
        assertNull(session.resetsAt)
    }

    @Test
    fun `positional fallback applies only when neither window declares a duration`() {
        val body = """
            {"rate_limit":{"primary_window":{"used_percent":9,"reset_at":1788240000},
            "secondary_window":{"used_percent":44,"reset_at":1788758400}}}
        """.trimIndent().replace("\n", "")
        val data = ChatGptUsageParser.parse(body)!!
        assertEquals(9.0, data.session!!.percent!!, 0.001)
        assertEquals(44.0, data.weekly!!.percent!!, 0.001)
    }

    @Test
    fun `a window on some other clock is dropped rather than forced into a slot`() {
        assertEquals(WindowKind.OTHER, classifyWindow(3600L))
        val body = """
            {"rate_limit":{"primary_window":
            {"used_percent":9,"reset_at":1788240000,"limit_window_seconds":3600},
            "secondary_window":
            {"used_percent":44,"reset_at":1788758400,"limit_window_seconds":604800}}}
        """.trimIndent().replace("\n", "")
        val data = ChatGptUsageParser.parse(body)!!
        assertNull("an hourly window has no home in this shape", data.session)
        assertEquals(44.0, data.weekly!!.percent!!, 0.001)
    }

    @Test
    fun `Spark's weekly window becomes a model cap and its 5-hour one is dropped`() {
        val body = """
            {"additional_rate_limits":[{"limit_name":"spark","rate_limit":{
            "primary_window":{"used_percent":8,"reset_at":1788240000,"limit_window_seconds":18000},
            "secondary_window":{"used_percent":61,"reset_at":1788758400,"limit_window_seconds":604800}}}]}
        """.trimIndent().replace("\n", "")
        val data = ChatGptUsageParser.parse(body)!!
        assertEquals(1, data.modelCaps.size)
        assertEquals("Spark", data.modelCaps[0].modelName)
        assertEquals(61.0, data.modelCaps[0].window.percent!!, 0.001)
    }

    @Test
    fun `an additional limit with only a 5-hour window contributes no cap`() {
        val body = """
            {"additional_rate_limits":[{"limit_name":"spark","rate_limit":{"primary_window":
            {"used_percent":8,"reset_at":1788240000,"limit_window_seconds":18000}}}]}
        """.trimIndent().replace("\n", "")
        assertNull("nothing else in the payload, so nothing usable", ChatGptUsageParser.parse(body))
    }

    @Test
    fun `a balance of 12 point 4 is 1240 minor units, as a number or a string`() {
        // The capture sends `"balance": "0"` — a *string*. Both spellings must read.
        for (balance in listOf("12.4", "\"12.4\"")) {
            val body = """{"credits":{"has_credits":true,"unlimited":false,"balance":$balance}}"""
            val credits = ChatGptUsageParser.parse(body)!!.credits!!
            assertEquals("balance spelled $balance", 1240L, credits.balanceMinor)
            assertEquals(0L, credits.usedMinor)
            assertNull("ChatGPT credits have no monthly cap", credits.limitMinor)
            assertTrue(credits.isReportable)
        }
    }

    @Test
    fun `unlimited credits are not reportable`() {
        val body = """{"credits":{"has_credits":true,"unlimited":true,"balance":"0"}}"""
        val credits = ChatGptUsageParser.parse(body)!!.credits!!
        assertTrue(credits.unlimited)
        assertFalse("nothing to measure, so nothing is drawn", credits.isReportable)
    }

    @Test
    fun `a zero balance with credits enabled is reported but not drawn`() {
        val body = """{"rate_limit":{"primary_window":{"used_percent":1,"limit_window_seconds":18000}},
            "credits":{"has_credits":true,"unlimited":false,"balance":"0"}}""".trimIndent().replace("\n", "")
        val credits = ChatGptUsageParser.parse(body)!!.credits!!
        assertEquals(0L, credits.balanceMinor)
        assertFalse("no cap, no spend, no balance — nothing to say", credits.isReportable)
    }

    @Test
    fun `junk parses to null rather than an empty reading`() {
        assertNull(ChatGptUsageParser.parse("not json"))
        assertNull(ChatGptUsageParser.parse("{}"))
        assertNull(ChatGptUsageParser.parse("""{"rate_limit":{}}"""))
    }

    @Test
    fun `limit names are title-cased`() {
        assertEquals("Spark", ChatGptUsageParser.titleCase("spark"))
        assertEquals("Gpt 5 Codex", ChatGptUsageParser.titleCase("gpt_5_codex"))
        assertEquals("Deep Research", ChatGptUsageParser.titleCase("deep-research"))
    }

    @Test
    fun `the fixture carries no identifying data`() {
        // A guard, not a formality: this file is committed to a public repo, and the
        // live endpoint really does return the account's email in the usage body.
        assertFalse(real.contains("@gmail"))
        assertTrue(real.contains("redacted@example.com"))
        assertTrue(real.contains("user-REDACTED"))
    }
}
