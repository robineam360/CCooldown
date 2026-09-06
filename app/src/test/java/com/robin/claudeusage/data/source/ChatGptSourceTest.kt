package com.robin.claudeusage.data.source

import com.robin.claudeusage.data.Credentials
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [ChatGptSource]'s pure halves (CCRM-54 (ChatGPT Account)). The endpoints themselves
 * are not exercised here — what is pinned is the token-response reading, which is where
 * a shape change would quietly sign the user out.
 */
class ChatGptSourceTest {

    /** An **unsigned** JWT — only the payload segment is ever read, never a signature. */
    private fun jwt(claims: JSONObject): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = enc.encodeToString(claims.toString().toByteArray())
        return "$header.$payload.sig"
    }

    private fun idToken(plan: String?, accountId: String?): String {
        val auth = JSONObject()
        plan?.let { auth.put("chatgpt_plan_type", it) }
        accountId?.let { auth.put("chatgpt_account_id", it) }
        return jwt(JSONObject().put("https://api.openai.com/auth", auth))
    }

    @Test
    fun `403 is an auth failure as well as 401`() {
        // OpenQuota maps both to token-expired before reading the body; unlike Claude,
        // where only 401 means the token is dead.
        assertTrue(ChatGptSource.isAuthFailure(401))
        assertTrue(ChatGptSource.isAuthFailure(403))
        assertFalse(ChatGptSource.isAuthFailure(429))
        assertFalse(ChatGptSource.isAuthFailure(500))
    }

    @Test
    fun `refresh keeps the previous refresh token when none is rotated back`() {
        val previous = Credentials("old-access", "keep-me", 0L, "acct-1")
        val body = JSONObject().put("access_token", jwt(JSONObject())).toString()
        val grant = ChatGptSource.parseTokenResponse(body, previous)!!
        assertEquals("keep-me", grant.creds.refreshToken)
        assertEquals("the account id survives a refresh that omits an id_token", "acct-1", grant.creds.accountId)
    }

    @Test
    fun `a rotated refresh token replaces the previous one`() {
        val previous = Credentials("old-access", "old-refresh", 0L, null)
        val body = JSONObject()
            .put("access_token", jwt(JSONObject()))
            .put("refresh_token", "new-refresh")
            .toString()
        val grant = ChatGptSource.parseTokenResponse(body, previous)!!
        assertEquals("new-refresh", grant.creds.refreshToken)
    }

    @Test
    fun `expires_in absent falls back to the access token's own exp`() {
        // RefreshResponse in auth/manager.rs is {id_token?, access_token?,
        // refresh_token?} — there is no expires_in, so this is the normal path.
        val exp = 1_788_000_000L
        val body = JSONObject()
            .put("access_token", jwt(JSONObject().put("exp", exp)))
            .put("refresh_token", "r")
            .toString()
        val grant = ChatGptSource.parseTokenResponse(body, null)!!
        assertEquals(exp * 1000L, grant.creds.expiresAt)
    }

    @Test
    fun `expires_in wins when the server does send one`() {
        val before = System.currentTimeMillis()
        val body = JSONObject()
            .put("access_token", jwt(JSONObject().put("exp", 1_788_000_000L)))
            .put("refresh_token", "r")
            .put("expires_in", 3600)
            .toString()
        val grant = ChatGptSource.parseTokenResponse(body, null)!!
        assertTrue(grant.creds.expiresAt >= before + 3_600_000L)
        assertTrue(grant.creds.expiresAt <= System.currentTimeMillis() + 3_600_000L)
    }

    @Test
    fun `an unreadable access token leaves the expiry unknown rather than guessed`() {
        val body = JSONObject().put("access_token", "not-a-jwt").put("refresh_token", "r").toString()
        val grant = ChatGptSource.parseTokenResponse(body, null)!!
        assertEquals(0L, grant.creds.expiresAt)
    }

    @Test
    fun `the id_token carries the plan and the account id`() {
        val body = JSONObject()
            .put("access_token", jwt(JSONObject()))
            .put("refresh_token", "r")
            .put("id_token", idToken(plan = "pro", accountId = "acct-99"))
            .toString()
        val grant = ChatGptSource.parseTokenResponse(body, null)!!
        assertEquals("pro", grant.plan)
        assertEquals("acct-99", grant.creds.accountId)
    }

    @Test
    fun `tier is always null so Anthropic's multiplier grammar never runs on a plan_type`() {
        val body = JSONObject()
            .put("access_token", jwt(JSONObject()))
            .put("refresh_token", "r")
            .put("id_token", idToken(plan = "pro", accountId = "acct-99"))
            .toString()
        assertNull(ChatGptSource.parseTokenResponse(body, null)!!.tier)
    }

    @Test
    fun `a response with no access token is not a grant`() {
        assertNull(ChatGptSource.parseTokenResponse("""{"error":"invalid_grant"}""", null))
        assertNull(ChatGptSource.parseTokenResponse("not json", null))
    }

    @Test
    fun `jwt payload decoding survives a missing signature and odd padding`() {
        val claims = ChatGptSource.jwtPayload(jwt(JSONObject().put("exp", 42L)))!!
        assertEquals(42L, claims.optLong("exp"))
        assertNull(ChatGptSource.jwtPayload("only-one-segment"))
        assertEquals(0L, ChatGptSource.jwtExpiryMs("only-one-segment"))
    }

    @Test
    fun `the User-Agent is honest and names this app`() {
        // Nothing here pretends to be the Codex CLI — no UA gate is reported on
        // OpenAI's usage host, unlike Anthropic's.
        assertTrue(ChatGptSource.USER_AGENT.startsWith("Cooldown/"))
        assertTrue(ChatGptSource.USER_AGENT.endsWith("(Android)"))
        assertFalse(ChatGptSource.USER_AGENT.contains("codex"))
    }
}
