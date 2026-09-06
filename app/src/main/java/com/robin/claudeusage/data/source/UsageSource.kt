package com.robin.claudeusage.data.source

import com.robin.claudeusage.data.Credentials
import com.robin.claudeusage.data.HttpResult
import com.robin.claudeusage.data.Provider
import com.robin.claudeusage.data.UsageData

/**
 * Everything a poll needs that differs per provider (CCRM-53 (Provider Model)).
 * Sign-in does NOT live here — the two flows have different shapes (browser +
 * pasted code vs device code) and different UI.
 */
interface UsageSource {
    val provider: Provider

    /** GET the usage payload with this provider's headers. */
    fun fetchUsage(creds: Credentials): HttpResult

    /** Redeem the refresh token. */
    fun refresh(creds: Credentials): HttpResult

    /** Token endpoint body → the new credentials plus what rides along (plan, account id). */
    fun parseTokenResponse(body: String, previous: Credentials?): TokenGrant?

    /** Usage body → the shared model. Null only when nothing usable is present. */
    fun parseUsage(body: String): UsageData?

    /** Which HTTP statuses mean "the token is dead", as opposed to "their server is down". */
    fun isAuthFailure(status: Int): Boolean
}

data class TokenGrant(val creds: Credentials, val plan: String?, val tier: String?)

object Sources {
    fun of(provider: Provider): UsageSource = when (provider) {
        Provider.CLAUDE -> ClaudeSource
        Provider.CHATGPT -> throw NotImplementedError("ChatGPT source lands in CCRM-54 (ChatGPT Account)")
        Provider.ANTIGRAVITY -> throw NotImplementedError("Antigravity source lands in CCRM-55 (Antigravity Account)")
    }
}
