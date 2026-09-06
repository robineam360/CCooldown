package com.robin.claudeusage.data.source

import com.robin.claudeusage.data.ApiClient
import com.robin.claudeusage.data.Credentials
import com.robin.claudeusage.data.HttpResult
import com.robin.claudeusage.data.Provider
import com.robin.claudeusage.data.UsageData
import com.robin.claudeusage.data.UsageParser
import org.json.JSONObject

/**
 * Wraps the existing Claude code **untouched** (CCRM-53 (Provider Model)) — the
 * authorize-URL encoding and the two-opposite-User-Agents rule in [ApiClient] are
 * the most empirically fragile lines this app owns and are not generalised here.
 */
object ClaudeSource : UsageSource {
    override val provider: Provider = Provider.CLAUDE

    override fun fetchUsage(creds: Credentials): HttpResult = ApiClient.fetchUsage(creds.accessToken)

    override fun refresh(creds: Credentials): HttpResult = ApiClient.refreshToken(creds.refreshToken)

    /** The exact block `UsageRepository.refreshAccessToken` ran before this seam existed. */
    override fun parseTokenResponse(body: String, previous: Credentials?): TokenGrant? = try {
        val o = JSONObject(body)
        val newAccess = o.optString("access_token")
        if (newAccess.isEmpty()) null
        else {
            val rotatedRefresh = o.optString("refresh_token")
            val newRefresh = rotatedRefresh.ifEmpty { previous?.refreshToken.orEmpty() }
            val expiresIn = o.optLong("expires_in", 0L)
            val expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L
            TokenGrant(
                creds = Credentials(newAccess, newRefresh, expiresAt, previous?.accountId),
                plan = null,
                tier = null,
            )
        }
    } catch (_: Exception) {
        null
    }

    override fun parseUsage(body: String): UsageData? = UsageParser.parse(body)

    override fun isAuthFailure(status: Int): Boolean = status == 401
}
