package com.robin.claudeusage.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins CCRM-34 (Diagnostics Log)'s pure pieces: the level gate, the tolerant
 * level decode, the line shape, and the trim rule. The file plumbing itself is
 * Android I/O and gets judged at the device pass.
 */
class AppLogTest {

    @Test
    fun `level gate lets equal-or-higher through`() {
        assertTrue(AppLog.shouldLog(AppLog.Level.INFO, min = AppLog.Level.INFO))
        assertTrue(AppLog.shouldLog(AppLog.Level.ERROR, min = AppLog.Level.INFO))
        assertFalse(AppLog.shouldLog(AppLog.Level.DEBUG, min = AppLog.Level.INFO))
        // Debug mode records everything.
        assertTrue(AppLog.shouldLog(AppLog.Level.DEBUG, min = AppLog.Level.DEBUG))
    }

    @Test
    fun `level decode is tolerant and case-insensitive`() {
        assertEquals(AppLog.Level.DEBUG, AppLog.Level.fromKey("debug"))
        assertEquals(AppLog.Level.WARN, AppLog.Level.fromKey("WARN"))
        assertEquals(AppLog.Level.INFO, AppLog.Level.fromKey("verbose"))
        assertEquals(AppLog.Level.INFO, AppLog.Level.fromKey(null))
    }

    @Test
    fun `line shape carries stamp, level tag, category and profile`() {
        assertEquals(
            "08-19 21:04:11.402 I [poll][personal] auto → OK",
            AppLog.formatLine("08-19 21:04:11.402", AppLog.Level.INFO, "poll", "personal", "auto → OK"),
        )
        assertEquals(
            "08-19 21:04:11.402 W [alerts][-] post blocked",
            AppLog.formatLine("08-19 21:04:11.402", AppLog.Level.WARN, "alerts", null, "post blocked"),
        )
    }

    @Test
    fun `trim keeps the newest lines`() {
        val lines = (1..1000).map { "line $it" }
        val kept = AppLog.trimmed(lines)
        assertEquals(AppLog.KEEP_LINES, kept.size)
        assertEquals("line 1000", kept.last())
        assertEquals("line ${1000 - AppLog.KEEP_LINES + 1}", kept.first())
    }

    // --- redactPayload (CCRM-54 (ChatGPT Account)) ---

    /**
     * The capture button logs a usage body, and OpenAI's names the account holder. These
     * pin what leaves the app, so "it carries no tokens" can never again be mistaken for
     * "it carries nothing personal".
     */
    @Test
    fun `identifying fields are stripped from a captured body`() {
        val body = """
            {"user_id":"user-abc","account_id":"0000-fake","email":"someone@example.com",
            "plan_type":"plus","rate_limit":{"primary_window":{"used_percent":9}}}
        """.trimIndent().replace("\n", "")
        val out = AppLog.redactPayload(body)
        assertFalse(out.contains("someone@example.com"))
        assertFalse(out.contains("user-abc"))
        assertFalse(out.contains("0000-fake"))
        // The shape is the whole point of capturing a body — it must survive.
        assertTrue(out.contains("plan_type"))
        assertTrue(out.contains("plus"))
        assertTrue(out.contains("used_percent"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test
    fun `nested identifiers are stripped too`() {
        val body = """{"account":{"owner":{"email":"a@b.com","user_id":"u1"}},"ok":true}"""
        val out = AppLog.redactPayload(body)
        assertFalse(out.contains("a@b.com"))
        assertFalse(out.contains("u1"))
        assertTrue(out.contains("ok"))
    }

    @Test
    fun `identifiers inside arrays are stripped`() {
        val body = """{"members":[{"email":"x@y.com"},{"email":"p@q.com"}]}"""
        val out = AppLog.redactPayload(body)
        assertFalse(out.contains("x@y.com"))
        assertFalse(out.contains("p@q.com"))
    }

    @Test
    fun `an email hiding in a value under an innocent key is still caught`() {
        val body = """{"message":"signed in as someone@example.com","used_percent":9}"""
        val out = AppLog.redactPayload(body)
        assertFalse(out.contains("someone@example.com"))
        assertTrue(out.contains("used_percent"))
    }

    @Test
    fun `a body that is not JSON is scrubbed rather than dropped`() {
        // A malformed body is exactly when you most want to see it.
        val out = AppLog.redactPayload("<html>error for someone@example.com</html>")
        assertFalse(out.contains("someone@example.com"))
        assertTrue(out.contains("html"))
    }

    @Test
    fun `a body with nothing personal in it comes back intact`() {
        val body = """{"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":9}}}"""
        val out = AppLog.redactPayload(body)
        assertFalse(out.contains("[redacted]"))
        assertTrue(out.contains("used_percent"))
    }

    @Test
    fun `a null identifier stays null rather than becoming the placeholder`() {
        val out = AppLog.redactPayload("""{"email":null,"used_percent":9}""")
        assertTrue(out.contains("null"))
        assertFalse(out.contains("[redacted]"))
    }
}
