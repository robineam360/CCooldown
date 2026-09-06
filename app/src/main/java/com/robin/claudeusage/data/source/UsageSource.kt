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

    /**
     * The plan named by the *usage* payload, when the provider reports one there —
     * ChatGPT does, and its live value beats the one the id_token carried at sign-in
     * (CCRM-54 (ChatGPT Account)). Null by default, so Claude writes nothing and its
     * plan keeps coming from the token response exactly as before.
     */
    fun planFrom(body: String): String? = null

    /** Which HTTP statuses mean "the token is dead", as opposed to "their server is down". */
    fun isAuthFailure(status: Int): Boolean
}

data class TokenGrant(val creds: Credentials, val plan: String?, val tier: String?)

object Sources {
    fun of(provider: Provider): UsageSource = when (provider) {
        Provider.CLAUDE -> ClaudeSource
        Provider.CHATGPT -> ChatGptSource
        Provider.ANTIGRAVITY -> throw NotImplementedError("Antigravity source lands in CCRM-55 (Antigravity Account)")
    }
}
