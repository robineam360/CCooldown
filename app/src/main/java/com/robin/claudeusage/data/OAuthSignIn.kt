package com.robin.claudeusage.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE authorization-code sign-in against Anthropic's OAuth endpoints — the same
 * flow Claude Code itself uses. The key difference from a pasted desktop token:
 * the token family is minted here on the phone, so no other machine shares the
 * refresh token and nothing external can rotate it away. That removes the whole
 * class of "re-paste every day" failures the desktop-copy method suffered from.
 *
 * The browser trip leaves the app, so the PKCE verifier and state are persisted
 * and survive process death while the Custom Tab is in front.
 */
object OAuthSignIn {

    // 2026 migration (verified against Claude Code 2.1.214's own `claude auth
    // login` URL): the authorize endpoint moved to claude.com/cai/oauth/authorize.
    // The legacy claude.ai/oauth/authorize still renders a consent page but can no
    // longer complete the grant — it fails at submit with "Invalid request format".
    private const val AUTHORIZE_URL = "https://claude.com/cai/oauth/authorize"
    // Same migration moved the callback host to platform.claude.com.
    const val REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
    private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    // The exact scope set Claude Code 2.1.214 requests. We only need user:profile
    // (the usage endpoint) but a reduced set was rejected at grant time with
    // "Invalid request format", so we match the client's registered set verbatim.
    // org:create_api_key is silently dropped on Team accounts that disable member
    // API keys, so including it is harmless there and required for Pro/Max.
    private const val SCOPE =
        "org:create_api_key user:profile user:inference user:sessions:claude_code " +
            "user:mcp_servers user:file_upload"

    // The authorization-code family is capped ~30 days from sign-in. The token
    // response omits the exact expiry, so we estimate it for the "expires around"
    // line and the expiry warnings; it's a prompt to re-sign-in, not a hard fact.
    const val ESTIMATED_FAMILY_MS = 30L * 24 * 60 * 60 * 1000

    data class Pending(val verifier: String, val state: String, val profile: Profile)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("oauth_pending", Context.MODE_PRIVATE)

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // Form-encode a value exactly as Claude Code does: spaces → '+', ':' → '%3A'.
    // (Uri.Builder emits '%20' for spaces, which we've now matched away from.)
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Builds the authorize URL and persists the PKCE material for the return trip.
     * The URL is assembled by hand — param order and encoding are byte-identical to
     * Claude Code 2.1.214's own request, because a subtly-different request is
     * rejected at grant time with "Invalid request format".
     */
    fun begin(context: Context, profile: Profile): String {
        val verifier = randomUrlSafe(32)
        // state IS the verifier, exactly as Claude Code / opencode do it. A shorter
        // separate state (16 bytes) was the last deviation from the working request
        // and drew "Invalid request format" at grant-submit. On-device the marginal
        // CSRF benefit of a distinct state doesn't justify diverging from what works.
        val state = verifier
        prefs(context).edit()
            .putString("verifier", verifier)
            .putString("state", state)
            .putString("profile", profile.key)
            .apply()
        return AUTHORIZE_URL + "?" +
            "code=true" +
            "&client_id=" + enc(CLIENT_ID) +
            "&response_type=code" +
            "&redirect_uri=" + enc(REDIRECT_URI) +
            "&scope=" + enc(SCOPE) +
            "&code_challenge=" + enc(challengeFor(verifier)) +
            "&code_challenge_method=S256" +
            "&state=" + enc(state)
    }

    fun pending(context: Context): Pending? {
        val p = prefs(context)
        val v = p.getString("verifier", null) ?: return null
        val s = p.getString("state", null) ?: return null
        return Pending(v, s, Profile.fromKey(p.getString("profile", null)))
    }

    fun clearPending(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
