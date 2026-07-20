package com.robin.claudeusage.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HttpResult(val code: Int, val body: String)

/**
 * Raw HTTP for the endpoints we use. NOTE the two endpoints want OPPOSITE
 * User-Agents:
 *  - usage endpoint (api.anthropic.com): send the claude-code User-Agent (USER_AGENT)
 *    — without it the request routes to an aggressively rate-limited bucket.
 *  - token endpoint (platform.claude.com): must NOT send a claude-code User-Agent —
 *    its WAF 429-blocks it. See postToken().
 */
object ApiClient {

    // Bump occasionally to a recent real Claude Code release. USAGE ENDPOINT ONLY —
    // do not use on the token endpoint (see postToken).
    const val USER_AGENT = "claude-code/2.1.214"

    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    // 2026 migration: authorize + token endpoints moved to the claude.com /
    // platform.claude.com family (verified against Claude Code 2.1.214's binary).
    // Claude's own SDK posts JSON (application/json) to this endpoint.
    private const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
    private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    // Must match the redirect_uri used at authorize time (see OAuthSignIn).
    private const val REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"

    private val jsonMedia = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun fetchUsage(accessToken: String): HttpResult {
        val request = Request.Builder()
            .url(USAGE_URL)
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("anthropic-beta", "oauth-2025-04-20")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string() ?: "")
        }
    }

    /**
     * Exchanges a PKCE authorization code (from the sign-in callback page) for a
     * fresh token family. `state` is the value echoed back in the pasted code.
     */
    fun exchangeCode(code: String, state: String, verifier: String): HttpResult {
        val payload = JSONObject()
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("state", state)
            .put("client_id", CLIENT_ID)
            .put("redirect_uri", REDIRECT_URI)
            .put("code_verifier", verifier)
            .toString()
        return postToken(payload)
    }

    /** Returns new credentials on success, non-200 result on failure. */
    fun refreshToken(refreshToken: String): HttpResult {
        val payload = JSONObject()
            .put("grant_type", "refresh_token")
            .put("refresh_token", refreshToken)
            .put("client_id", CLIENT_ID)
            .toString()
        return postToken(payload)
    }

    private fun postToken(jsonPayload: String): HttpResult {
        // Do NOT send a claude-code User-Agent here. The token endpoint sits behind a
        // WAF that returns an opaque 429 (type rate_limit_error) for any request whose
        // User-Agent is claude-code, a browser (Mozilla), curl, or empty — while
        // allowing library UAs (okhttp, axios, anthropic-sdk-typescript). This was the
        // real cause of the deterministic 429 on both exchange AND refresh (verified
        // 2026-07-20 by capturing Claude Code's own 200 exchange, which uses axios,
        // plus User-Agent isolation probes). We omit the header so OkHttp's default
        // "okhttp/<version>" is sent, which the WAF accepts. anthropic-beta is not
        // required here and does not affect the gate; kept for parity with refresh.
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(jsonPayload.toRequestBody(jsonMedia))
            .header("anthropic-beta", "oauth-2025-04-20")
            .header("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string() ?: "")
        }
    }
}
