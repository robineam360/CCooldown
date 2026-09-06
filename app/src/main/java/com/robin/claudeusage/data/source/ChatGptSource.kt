package com.robin.claudeusage.data.source

import com.robin.claudeusage.BuildConfig
import com.robin.claudeusage.data.Credentials
import com.robin.claudeusage.data.HttpResult
import com.robin.claudeusage.data.ModelCap
import com.robin.claudeusage.data.Provider
import com.robin.claudeusage.data.SpendCredits
import com.robin.claudeusage.data.UsageData
import com.robin.claudeusage.data.UsageWindow
import com.robin.claudeusage.data.WindowKind
import com.robin.claudeusage.data.classifyWindow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * ChatGPT usage, read the way the official Codex CLI reads it (CCRM-54 (ChatGPT
 * Account)). Unlike [ClaudeSource] there is no User-Agent gate to work around:
 * OpenQuota sends its own UA and is served, so we send an **honest** one that
 * names this app.
 *
 * Endpoint and header names verified 2026-09-06 against
 * `codex-rs/backend-client/src/client/rate_limit_resets.rs` and `client.rs` in
 * <https://github.com/openai/codex>.
 *
 * **Rules this file binds itself to** (CCRM-54): never send
 * `x-openai-codex-luna-reserve` — the source reserves it for clients that can
 * *apply* Reserve, "not passive account usage readers", which is exactly us; and
 * never call `rate-limit-reset-credits/consume`, which is a **write** that spends
 * a credit.
 */
object ChatGptSource : UsageSource {

    /** The Codex CLI's own client id — `CLIENT_ID` in `codex-rs/login/src/auth/manager.rs`. */
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

    const val ISSUER = "https://auth.openai.com"

    /** `REFRESH_TOKEN_URL` in `auth/manager.rs`; the same endpoint serves the code exchange. */
    const val TOKEN_URL = "$ISSUER/oauth/token"

    /**
     * `rate_limit_status_url()` builds `{base}/wham/usage`, and `client.rs` appends
     * `/backend-api` to any `https://chatgpt.com` base — so this is the effective URL.
     */
    const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"

    /** Honest, and ours. Nothing here pretends to be the Codex CLI. */
    val USER_AGENT = "Cooldown/${BuildConfig.VERSION_NAME} (Android)"

    private val jsonMedia = "application/json".toMediaType()
    private val formMedia = "application/x-www-form-urlencoded".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override val provider: Provider = Provider.CHATGPT

    override fun fetchUsage(creds: Credentials): HttpResult {
        val builder = Request.Builder()
            .url(USAGE_URL)
            .get()
            .header("Authorization", "Bearer ${creds.accessToken}")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        // Header name is literally "ChatGPT-Account-Id" (Client::headers() in client.rs);
        // omitted rather than sent empty when the id_token didn't carry one.
        creds.accountId?.takeIf { it.isNotEmpty() }?.let { builder.header("ChatGPT-Account-Id", it) }
        client.newCall(builder.build()).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string() ?: "")
        }
    }

    /**
     * The refresh grant is **JSON**, not form-encoded — read off
     * `request_chatgpt_token_refresh` in `codex-rs/login/src/auth/manager.rs`
     * (2026-09-06), which builds a `RefreshRequest { client_id, grant_type,
     * refresh_token }` and posts it with `.header("Content-Type",
     * "application/json").json(&refresh_request)`. The research note in
     * `design/research/2026-09-06-phone-feasibility.md` recorded this as
     * form-encoded from OpenQuota's behaviour; CCRM-54 said match the CLI when the
     * two disagree, so we match the CLI. Note the code **exchange** at the same URL
     * is form-encoded — see [CodexDeviceSignIn.exchange].
     */
    override fun refresh(creds: Credentials): HttpResult {
        val payload = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("grant_type", "refresh_token")
            .put("refresh_token", creds.refreshToken)
            .toString()
        return postJson(TOKEN_URL, payload)
    }

    /**
     * `RefreshResponse` in `auth/manager.rs` is `{id_token?, access_token?,
     * refresh_token?}` — **no `expires_in`** — so the access token's own JWT `exp`
     * is the expiry in practice. The `id_token` carries the plan and the account id
     * for free, under the `https://api.openai.com/auth` claim; when the response
     * omits it (refresh often does) the previous account id is kept and the plan is
     * left alone. `tier` is always null: `Fmt.tierMultiplier` parses Anthropic's
     * `default_5x` grammar and must never run on `plan_type: "pro"`.
     */
    override fun parseTokenResponse(body: String, previous: Credentials?): TokenGrant? = try {
        val o = JSONObject(body)
        val access = o.optString("access_token")
        if (access.isEmpty()) {
            null
        } else {
            val rotated = o.optString("refresh_token")
            val refresh = rotated.ifEmpty { previous?.refreshToken.orEmpty() }
            val expiresIn = o.optLong("expires_in", 0L)
            val expiresAt = if (expiresIn > 0) {
                System.currentTimeMillis() + expiresIn * 1000
            } else {
                jwtExpiryMs(access)
            }
            val claims = o.optString("id_token").takeIf { it.isNotEmpty() }
                ?.let { authClaims(it) }
            TokenGrant(
                creds = Credentials(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAt = expiresAt,
                    accountId = claims?.optString("chatgpt_account_id")?.ifEmpty { null }
                        ?: previous?.accountId,
                ),
                plan = claims?.optString("chatgpt_plan_type")?.ifEmpty { null },
                tier = null,
            )
        }
    } catch (_: Exception) {
        null
    }

    override fun parseUsage(body: String): UsageData? = ChatGptUsageParser.parse(body)

    /** The live `plan_type` beats the one the id_token carried at sign-in. */
    override fun planFrom(body: String): String? = ChatGptUsageParser.planType(body)

    /**
     * 403 as well as 401: OpenQuota maps both to token-expired before reading the
     * body, and a ChatGPT bearer that has lost its workspace answers 403.
     */
    override fun isAuthFailure(status: Int): Boolean = status == 401 || status == 403

    // --- HTTP helpers, shared with the device-code flow ---

    internal fun postJson(url: String, payload: String): HttpResult {
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string() ?: "")
        }
    }

    internal fun postForm(url: String, form: String): HttpResult {
        val request = Request.Builder()
            .url(url)
            .post(form.toRequestBody(formMedia))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string() ?: "")
        }
    }

    internal fun formEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // --- JWT reading. Claims only, never a signature check ---

    /**
     * The payload segment of a JWT, decoded. **No signature verification, deliberately**:
     * the token came straight from OpenAI's token endpoint over TLS and is only ever
     * used to label this account in our own UI — nothing is authorised by these claims.
     *
     * `java.util.Base64` rather than `android.util.Base64` so the decode is testable on
     * a plain JVM; minSdk 31 is well past the API 26 this needs.
     */
    internal fun jwtPayload(jwt: String): JSONObject? = try {
        val parts = jwt.split('.')
        if (parts.size < 2) {
            null
        } else {
            val bytes = java.util.Base64.getUrlDecoder().decode(parts[1].trimEnd('='))
            JSONObject(String(bytes, Charsets.UTF_8))
        }
    } catch (_: Exception) {
        null
    }

    /** The `https://api.openai.com/auth` claim block — `IdClaims` in `token_data.rs`. */
    internal fun authClaims(idToken: String): JSONObject? =
        jwtPayload(idToken)?.optJSONObject("https://api.openai.com/auth")

    /** The access token's `exp`, in epoch millis; 0 when it has none we can read. */
    internal fun jwtExpiryMs(jwt: String): Long {
        val exp = jwtPayload(jwt)?.optLong("exp", 0L) ?: 0L
        return if (exp > 0L) exp * 1000L else 0L
    }
}

/**
 * `/backend-api/wham/usage` → the shared [UsageData] (CCRM-54 (ChatGPT Account)).
 * Pure and fixture-tested against a payload captured on the phone 2026-09-06
 * (`app/src/test/resources/chatgpt-usage-2026-09.json`, a Plus account, with its
 * `user_id` / `account_id` / `email` redacted — this is a public repo). The shape is
 * generated from an OpenAPI spec in the `openai/codex` repo, so changes are additive
 * and diffable.
 *
 * The live payload carried five keys the documented shape didn't mention —
 * `code_review_rate_limit`, `model_usage`, `spend_control`, `promo` and
 * `rate_limit_reset_credits` — plus `allowed` / `limit_reached` inside `rate_limit`.
 * None of them is a usage reading, so all are ignored. `rate_limit_reset_credits` is
 * **read-only intelligence**: spending one means POSTing to
 * `rate-limit-reset-credits/consume`, which this app never calls.
 *
 * Windows are classified by **duration**, never by which JSON slot they arrived in
 * (CCRM-53 (Provider Model)) — OpenAI suspended the 5-hour limit for Plus / Pro /
 * Business on 2026-07-12, so a weekly-only account puts its weekly limit in
 * `primary_window` and the slot alone would misread it as a session.
 *
 * Not read, deliberately: the `x-codex-*-used-percent` header fallbacks (OpenQuota
 * resilience for a shape change we have not seen — and they need a *billable* model
 * call to observe), and `rate_limit_reached_type`, which has no field in [UsageData].
 */
object ChatGptUsageParser {

    fun parse(raw: String, nowMs: Long = System.currentTimeMillis()): UsageData? = try {
        val root = JSONObject(raw)
        val rate = root.optJSONObject("rate_limit")
        val primary = rate?.optJSONObject("primary_window")
        val secondary = rate?.optJSONObject("secondary_window")

        var session: UsageWindow? = null
        var weekly: UsageWindow? = null
        // Positional reading is the last resort, not the first: only when neither
        // window declared a duration is there nothing better to go on.
        val anyDuration = listOfNotNull(primary, secondary).any { lengthSeconds(it) != null }
        if (anyDuration) {
            for (o in listOfNotNull(primary, secondary)) {
                when (classifyWindow(lengthSeconds(o))) {
                    WindowKind.SESSION -> session = windowFrom(o, nowMs)
                    WindowKind.WEEKLY -> weekly = windowFrom(o, nowMs)
                    // A window on some other clock has nowhere to go in this shape.
                    // Dropped rather than forced into a slot whose alerts, pace marks
                    // and widget rows would then all drift on the wrong clock.
                    WindowKind.OTHER -> Unit
                }
            }
        } else {
            session = windowFrom(primary, nowMs)
            weekly = windowFrom(secondary, nowMs)
        }

        val caps = mutableListOf<ModelCap>()
        val extras = root.optJSONArray("additional_rate_limits")
        if (extras != null) {
            for (i in 0 until extras.length()) {
                val entry = extras.optJSONObject(i) ?: continue
                val name = entry.optString("limit_name").ifEmpty { "Model" }
                // ModelCap is weekly by contract — alerts drift it on the 7-day clock and
                // `windowRows` gives it WEEKLY_MS — so a 5-hour additional window is
                // dropped here. Recorded in CCRM-54 (ChatGPT Account), not solved.
                weeklyWindowIn(entry.optJSONObject("rate_limit"), nowMs)
                    ?.let { caps += ModelCap(titleCase(name), it) }
            }
        }

        val credits = creditsFrom(root.optJSONObject("credits"))

        if (session == null && weekly == null && caps.isEmpty() && credits == null) null
        else UsageData(session, weekly, caps, credits)
    } catch (_: Exception) {
        null
    }

    /** The live plan, e.g. `"pro"`. Title-cased at render time, not here. */
    fun planType(raw: String): String? = try {
        JSONObject(raw).optString("plan_type").ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    private fun lengthSeconds(o: JSONObject?): Long? {
        if (o == null || o.isNull("limit_window_seconds")) return null
        val v = o.optLong("limit_window_seconds", -1L)
        return if (v <= 0L) null else v
    }

    /**
     * A window may sit directly under `rate_limit`, or under its `primary_window` /
     * `secondary_window` pair the way the top-level block does. Both are accepted;
     * only a 7-day one is returned.
     */
    private fun weeklyWindowIn(rate: JSONObject?, nowMs: Long): UsageWindow? {
        if (rate == null) return null
        val candidates = listOfNotNull(
            rate.optJSONObject("primary_window"),
            rate.optJSONObject("secondary_window"),
            rate.takeIf { it.has("used_percent") || it.has("limit_window_seconds") },
        )
        for (o in candidates) {
            if (classifyWindow(lengthSeconds(o)) == WindowKind.WEEKLY) return windowFrom(o, nowMs)
        }
        return null
    }

    private fun windowFrom(o: JSONObject?, nowMs: Long): UsageWindow? {
        if (o == null) return null
        return UsageWindow(
            percent = if (o.isNull("used_percent")) null else o.optDouble("used_percent"),
            resetsAt = resetFrom(o, nowMs),
            // OpenAI reports no severity of its own; ours comes from the percent.
            serverSeverity = null,
        )
    }

    /** `reset_at` is epoch **seconds**; some builds send `reset_after_seconds` instead. */
    private fun resetFrom(o: JSONObject, nowMs: Long): Instant? {
        if (!o.isNull("reset_at")) {
            val at = o.optLong("reset_at", 0L)
            if (at > 0L) return Instant.ofEpochSecond(at)
        }
        if (!o.isNull("reset_after_seconds")) {
            val after = o.optLong("reset_after_seconds", -1L)
            if (after >= 0L) return Instant.ofEpochMilli(nowMs + after * 1000L)
        }
        return null
    }

    /**
     * `credits` is `{has_credits, unlimited, balance}` — a balance with no cap. There
     * is no monthly limit and no cumulative "used" figure to report, so both stay at
     * their empty values and the balance carries the whole reading. An `unlimited`
     * account has nothing to measure and [SpendCredits.isReportable] hides it.
     *
     * **`balance` arrives as a JSON *string*** (`"0"`) on the captured payload, not the
     * number the documented shape showed. `optDouble` coerces either, and the test pins
     * both — money as a decimal string is how a server avoids float rounding, so the
     * string is likelier to be the stable spelling than the number was.
     */
    private fun creditsFrom(o: JSONObject?): SpendCredits? {
        if (o == null || !o.optBoolean("has_credits", false)) return null
        val balance = if (o.isNull("balance")) null else o.optDouble("balance", 0.0)
        return SpendCredits(
            usedMinor = 0L,
            limitMinor = null,
            exponent = 2,
            currency = "USD",
            serverSeverity = null,
            balanceMinor = balance?.let { Math.round(it * 100.0) },
            unlimited = o.optBoolean("unlimited", false),
        )
    }

    /** `"spark"` → `"Spark"`, `"gpt_5_codex"` → `"Gpt 5 Codex"`. */
    internal fun titleCase(raw: String): String = raw
        .split('_', '-', ' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        .ifEmpty { raw }
}
