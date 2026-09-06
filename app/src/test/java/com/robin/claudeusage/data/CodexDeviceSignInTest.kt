package com.robin.claudeusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-code flow's pure halves (CCRM-54 (ChatGPT Account)).
 *
 * The status mapping is the part worth pinning: it is read straight off
 * `poll_for_token` in `codex-rs/login/src/device_code_auth.rs`, where **403 and 404
 * both mean "not yet"** — the opposite of what 404 means on `/usercode`, which is
 * "device login is switched off entirely". Getting those two the wrong way round
 * would either abort every healthy sign-in or poll a dead flow for fifteen minutes.
 */
class CodexDeviceSignInTest {

    private val expires = 1_788_000_900_000L // now + 15 min
    private val now = 1_788_000_000_000L

    private fun poll(status: Int, body: String = "", at: Long = now) =
        CodexDeviceSignIn.classifyPoll(status, body, at, expires)

    @Test
    fun `403 and 404 mean pending`() {
        assertEquals(CodexDeviceSignIn.Poll.Pending, poll(403))
        assertEquals(CodexDeviceSignIn.Poll.Pending, poll(404))
    }

    @Test
    fun `pending past the fifteen-minute cap becomes expired`() {
        assertEquals(CodexDeviceSignIn.Poll.Expired, poll(403, at = expires))
        assertEquals(CodexDeviceSignIn.Poll.Expired, poll(404, at = expires + 1))
    }

    @Test
    fun `every other status is terminal`() {
        // The CLI collapses these into one hard failure, so we do too — the sheet
        // says "start again" rather than inventing a distinction the server doesn't make.
        assertEquals(CodexDeviceSignIn.Poll.Denied(400), poll(400))
        assertEquals(CodexDeviceSignIn.Poll.Denied(401), poll(401))
        assertEquals(CodexDeviceSignIn.Poll.Denied(410), poll(410))
        assertEquals(CodexDeviceSignIn.Poll.Denied(500), poll(500))
    }

    @Test
    fun `a 2xx carrying the grant hands back the server's own PKCE verifier`() {
        val body = """
            {"authorization_code":"ac_123","code_challenge":"cc","code_verifier":"cv_456"}
        """.trimIndent()
        val result = poll(200, body)
        assertEquals(CodexDeviceSignIn.Poll.Granted("ac_123", "cv_456"), result)
    }

    @Test
    fun `a 2xx missing the grant fields is treated as pending, not as a denial`() {
        // A shape change must not abort a sign-in that may still be alive; the
        // fifteen-minute cap bounds the retrying either way.
        assertEquals(CodexDeviceSignIn.Poll.Pending, poll(200, """{"status":"pending"}"""))
        assertEquals(CodexDeviceSignIn.Poll.Pending, poll(200, "not json"))
        assertEquals(CodexDeviceSignIn.Poll.Expired, poll(200, "{}", at = expires))
    }

    @Test
    fun `the user code response is read with both spellings and a string interval`() {
        val body = """{"device_auth_id":"dev_1","user_code":"ABCD-EFGH","interval":"5"}"""
        val started = CodexDeviceSignIn.parseStarted(body, "p3", now)!!
        assertEquals("dev_1", started.deviceAuthId)
        assertEquals("ABCD-EFGH", started.userCode)
        assertEquals(5, started.intervalSec)
        assertEquals("p3", started.profileKey)
        assertEquals(now + CodexDeviceSignIn.MAX_WAIT_MS, started.expiresAtMs)
        assertEquals("https://auth.openai.com/codex/device", started.verifyUrl)

        val alias = """{"device_auth_id":"dev_2","usercode":"WXYZ","interval":"3"}"""
        assertEquals("WXYZ", CodexDeviceSignIn.parseStarted(alias, "p3", now)!!.userCode)
    }

    @Test
    fun `an absent or zero interval never becomes a spin`() {
        // `interval` is #[serde(default)] and would otherwise arrive as 0.
        val body = """{"device_auth_id":"dev_1","user_code":"ABCD"}"""
        assertTrue(CodexDeviceSignIn.parseStarted(body, "p1", now)!!.intervalSec >= 1)
        val zero = """{"device_auth_id":"dev_1","user_code":"ABCD","interval":"0"}"""
        assertEquals(1, CodexDeviceSignIn.parseStarted(zero, "p1", now)!!.intervalSec)
    }

    @Test
    fun `a response with no code is not a started flow`() {
        assertNull(CodexDeviceSignIn.parseStarted("""{"device_auth_id":"dev_1"}""", "p1", now))
        assertNull(CodexDeviceSignIn.parseStarted("not json", "p1", now))
    }

    @Test
    fun `Unavailable exists for the one status that means the flow is switched off`() {
        // `request_user_code` singles out 404 on /usercode: "device code login is not
        // enabled for this Codex server." Only [start] raises it — never the poll.
        val e = CodexDeviceSignIn.Unavailable()
        assertTrue(e.message!!.contains("switched on"))
    }

    @Test
    fun `the three endpoints are the ones the CLI builds`() {
        assertEquals(
            "https://auth.openai.com/api/accounts/deviceauth/usercode",
            CodexDeviceSignIn.USERCODE_URL,
        )
        assertEquals(
            "https://auth.openai.com/api/accounts/deviceauth/token",
            CodexDeviceSignIn.POLL_URL,
        )
        assertEquals(
            "https://auth.openai.com/deviceauth/callback",
            CodexDeviceSignIn.REDIRECT_URI,
        )
    }
}
