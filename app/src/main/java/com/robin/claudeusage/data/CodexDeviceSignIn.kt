package com.robin.claudeusage.data

import android.content.Context
import android.content.SharedPreferences
import com.robin.claudeusage.data.source.ChatGptSource
import org.json.JSONObject
import java.io.IOException

/**
 * OpenAI's device-code sign-in (CCRM-54 (ChatGPT Account)), the flow the Codex CLI
 * uses. Verified 2026-09-06 against `codex-rs/login/src/device_code_auth.rs` in
 * <https://github.com/openai/codex>.
 *
 * Strictly better than what this app does for Anthropic: the phone asks for a short
 * code, the user types it on `auth.openai.com/codex/device` in any browser — even on
 * a different device — and **the server hands back the PKCE verifier**. No localhost
 * socket, no app link, no pasted secret.
 *
 * The three URLs are built the way `request_device_code` builds them: the issuer, then
 * `/api/accounts` for the two device endpoints, while the verification URL is
 * `{issuer}/codex/device` — composed by the client, *not* read from the response.
 */
object CodexDeviceSignIn {

    private const val USERCODE_PATH = "/api/accounts/deviceauth/usercode"
    private const val TOKEN_POLL_PATH = "/api/accounts/deviceauth/token"

    val USERCODE_URL = ChatGptSource.ISSUER + USERCODE_PATH
    val POLL_URL = ChatGptSource.ISSUER + TOKEN_POLL_PATH
    val VERIFY_URL = "${ChatGptSource.ISSUER}/codex/device"

    /** `redirect_uri` for the exchange. An HTTPS URL the client never navigates to. */
    val REDIRECT_URI = "${ChatGptSource.ISSUER}/deviceauth/callback"

    /** `max_wait` in `poll_for_token`, and what the CLI's own prompt tells the user. */
    const val MAX_WAIT_MS = 15L * 60 * 1000

    /**
     * The response's `interval` is a **string** (`deserialize_interval` parses it) and
     * defaults to 0 when absent — which would spin — so it is floored at 1 and capped
     * at a minute so a silly value can't strand the sheet.
     */
    private const val MIN_INTERVAL_SEC = 1
    private const val MAX_INTERVAL_SEC = 60
    private const val FALLBACK_INTERVAL_SEC = 5

    data class Started(
        val deviceAuthId: String,
        val userCode: String,
        val intervalSec: Int,
        val verifyUrl: String,
        val expiresAtMs: Long,
        val profileKey: String,
    )

    sealed class Poll {
        /** Not yet — the user hasn't finished on the web page. */
        object Pending : Poll()

        /** Past the 15-minute window; the user needs a new code. */
        object Expired : Poll()

        /**
         * Terminal, and not pending. The CLI collapses every non-2xx that isn't 403/404
         * into one hard failure (`"device auth failed with status {n}"`), so an explicit
         * refusal and an invalidated code are the same fact to us; the sheet says "start
         * again" either way rather than inventing a distinction the server doesn't make.
         */
        data class Denied(val status: Int) : Poll()

        data class Granted(val authorizationCode: String, val codeVerifier: String) : Poll()
    }

    /** HTTP 404 from `/usercode` — OpenAI has device-code login switched off for this client. */
    class Unavailable : Exception("Device sign-in isn't switched on for this client")

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("device_pending", Context.MODE_PRIVATE)

    /**
     * Asks for a user code and persists the flow, so the sheet can be rebuilt after
     * process death the way [OAuthSignIn.pending] rebuilds the browser trip.
     *
     * @throws Unavailable on 404 — the one status `request_user_code` singles out.
     */
    fun start(context: Context, profile: Profile): Started {
        val resp = ChatGptSource.postJson(
            USERCODE_URL,
            JSONObject().put("client_id", ChatGptSource.CLIENT_ID).toString(),
        )
        if (resp.code == 404) throw Unavailable()
        if (resp.code !in 200..299) throw IOException("HTTP ${resp.code}")
        val started = parseStarted(resp.body, profile.key, System.currentTimeMillis())
            ?: throw IOException("device sign-in response was missing a code")
        prefs(context).edit()
            .putString("deviceAuthId", started.deviceAuthId)
            .putString("userCode", started.userCode)
            .putInt("intervalSec", started.intervalSec)
            .putLong("expiresAtMs", started.expiresAtMs)
            .putString("profile", started.profileKey)
            .apply()
        return started
    }

    /** Pure half of [start], so the shape is pinned by a test rather than by eye. */
    internal fun parseStarted(body: String, profileKey: String, nowMs: Long): Started? = try {
        val o = JSONObject(body)
        val id = o.optString("device_auth_id")
        // The CLI accepts both spellings (`#[serde(alias = "usercode")]`).
        val code = o.optString("user_code").ifEmpty { o.optString("usercode") }
        if (id.isEmpty() || code.isEmpty()) {
            null
        } else {
            Started(
                deviceAuthId = id,
                userCode = code,
                intervalSec = intervalFrom(o),
                verifyUrl = VERIFY_URL,
                expiresAtMs = nowMs + MAX_WAIT_MS,
                profileKey = profileKey,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun intervalFrom(o: JSONObject): Int {
        val raw = o.optString("interval").trim().toIntOrNull()
            ?: o.optInt("interval", 0).takeIf { it > 0 }
            ?: FALLBACK_INTERVAL_SEC
        return raw.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
    }

    fun pending(context: Context): Started? {
        val p = prefs(context)
        val id = p.getString("deviceAuthId", null) ?: return null
        val code = p.getString("userCode", null) ?: return null
        return Started(
            deviceAuthId = id,
            userCode = code,
            intervalSec = p.getInt("intervalSec", FALLBACK_INTERVAL_SEC),
            verifyUrl = VERIFY_URL,
            expiresAtMs = p.getLong("expiresAtMs", 0L),
            profileKey = p.getString("profile", null).orEmpty(),
        )
    }

    fun clearPending(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun poll(started: Started): Poll {
        val resp = ChatGptSource.postJson(
            POLL_URL,
            JSONObject()
                .put("device_auth_id", started.deviceAuthId)
                .put("user_code", started.userCode)
                .toString(),
        )
        return classifyPoll(resp.code, resp.body, System.currentTimeMillis(), started.expiresAtMs)
    }

    /**
     * The status mapping, straight from `poll_for_token`:
     *
     * - **2xx** → the grant, carrying `authorization_code`, `code_challenge` and
     *   `code_verifier`.
     * - **403 or 404** → *pending*; sleep `interval` and go round again. Note that 404
     *   means the opposite thing here than it does on `/usercode`, where it means the
     *   whole flow is switched off — hence [Unavailable] is raised only from [start].
     * - **anything else** → terminal failure. The CLI has no separate "denied" status.
     *
     * Expiry is the client's own 15-minute cap, checked before returning Pending so a
     * dead flow can't poll forever.
     */
    internal fun classifyPoll(status: Int, body: String, nowMs: Long, expiresAtMs: Long): Poll {
        if (status in 200..299) {
            val granted = try {
                val o = JSONObject(body)
                val code = o.optString("authorization_code")
                val verifier = o.optString("code_verifier")
                if (code.isEmpty() || verifier.isEmpty()) null else Poll.Granted(code, verifier)
            } catch (_: Exception) {
                null
            }
            // A 2xx that carries neither is a shape change, not a grant. Treated as
            // pending because the 15-minute cap bounds it; a Denied here would abort a
            // sign-in that may still be perfectly alive.
            return granted ?: expiredOrPending(nowMs, expiresAtMs)
        }
        if (status == 403 || status == 404) return expiredOrPending(nowMs, expiresAtMs)
        return Poll.Denied(status)
    }

    private fun expiredOrPending(nowMs: Long, expiresAtMs: Long): Poll =
        if (expiresAtMs > 0L && nowMs >= expiresAtMs) Poll.Expired else Poll.Pending

    /**
     * Redeems the grant. **Form-encoded**, unlike [ChatGptSource.refresh] which is JSON —
     * the two really do differ, and the parameter order below is `exchange_code_for_tokens`
     * in `codex-rs/login/src/server.rs` verbatim.
     */
    fun exchange(granted: Poll.Granted): HttpResult {
        val form = "grant_type=authorization_code" +
            "&code=" + ChatGptSource.formEncode(granted.authorizationCode) +
            "&redirect_uri=" + ChatGptSource.formEncode(REDIRECT_URI) +
            "&client_id=" + ChatGptSource.formEncode(ChatGptSource.CLIENT_ID) +
            "&code_verifier=" + ChatGptSource.formEncode(granted.codeVerifier)
        return ChatGptSource.postForm(ChatGptSource.TOKEN_URL, form)
    }
}
