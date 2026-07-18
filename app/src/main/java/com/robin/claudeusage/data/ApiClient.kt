package com.robin.claudeusage.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HttpResult(val code: Int, val body: String)

/**
 * Raw HTTP for the two endpoints we use. The claude-code User-Agent is required:
 * without it the usage endpoint routes to an aggressively rate-limited bucket.
 */
object ApiClient {

    // Bump occasionally to a recent real Claude Code release.
    const val USER_AGENT = "claude-code/2.1.211"

    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    private const val TOKEN_URL = "https://console.anthropic.com/v1/oauth/token"
    private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

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

    /** Returns new credentials on success, null body result on failure. */
    fun refreshToken(refreshToken: String): HttpResult {
        val payload = JSONObject()
            .put("grant_type", "refresh_token")
            .put("refresh_token", refreshToken)
            .put("client_id", CLIENT_ID)
            .toString()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(payload.toRequestBody(jsonMedia))
            .header("anthropic-beta", "oauth-2025-04-20")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string() ?: "")
        }
    }
}
