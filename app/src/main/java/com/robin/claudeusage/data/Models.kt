package com.robin.claudeusage.data

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

enum class Severity { NORMAL, WARNING, CRITICAL }

data class UsageWindow(
    val percent: Double?,
    val resetsAt: Instant?,
    val serverSeverity: String?,
) {
    val severity: Severity
        get() = when {
            serverSeverity == "critical" || (percent ?: 0.0) > 90.0 -> Severity.CRITICAL
            serverSeverity == "warning" || (percent ?: 0.0) >= 70.0 -> Severity.WARNING
            else -> Severity.NORMAL
        }
}

data class ModelCap(val modelName: String, val window: UsageWindow)

/**
 * Pay-as-you-go "usage credits" — the spend that covers you once a plan window is
 * exhausted. Unlike the windows above this is *money*, not a rate limit, and the
 * server reports it in minor units (599 with exponent 2 = $5.99).
 */
data class SpendCredits(
    val usedMinor: Long,
    /**
     * The monthly spend cap, or **null when the account has none** — the user can switch
     * the limit off and spend without a ceiling. Nullable rather than 0 because "no cap"
     * and "capped at zero" are different states that must not render the same way
     * (CCBG-9); treating the limit as credits' existence test is what made the whole
     * section vanish for an unlimited account.
     */
    val limitMinor: Long?,
    val exponent: Int,
    val currency: String,
    val serverSeverity: String?,
    /**
     * The cumulative credit balance — the pot that actually runs out (CCBG-6). The
     * Claude app shows it as its "Balance" row; `/api/oauth/usage` reports the field
     * but has only ever returned `null` for it, and the endpoint the Claude app reads
     * it from (`claude.ai/api/organizations/{uuid}/usage`) rejects our OAuth bearer
     * (403 `account_session_invalid`, probed 2026-08-07). Parsed anyway so the display
     * corrects itself the day the server populates it. Null means "not reported",
     * which is not the same as a zero balance.
     */
    val balanceMinor: Long? = null,
) {
    /** Whether there is a cap to measure spend against at all. */
    val hasLimit: Boolean
        get() = (limitMinor ?: 0L) > 0L

    /**
     * Worth showing: either a cap to measure against, or real spend to report. An
     * account with neither has no credit budget and is better off showing nothing than
     * "$0.00 of $0.00".
     *
     * Note the deliberate gap: an uncapped account that has spent nothing yet stays
     * hidden until its first spend. Conservative on purpose — it can't be told apart
     * from a no-credits account without trusting `spend.enabled`, which is still
     * unverified (CCBG-3).
     */
    val isReportable: Boolean
        get() = hasLimit || usedMinor > 0L

    /**
     * 0-100, computed from the money rather than the server's rounded integer, and
     * **null when there is no cap** — with no denominator there is no percentage, and
     * synthesising 0 would draw an empty bar implying headroom that has no ceiling.
     */
    val percent: Double?
        get() = limitMinor?.takeIf { it > 0L }?.let { usedMinor * 100.0 / it }

    /**
     * The percentage as displayed. Rounded, not truncated like the window bars:
     * money is precise, so $5.99 of $100 should read 6%, not 5%.
     */
    val percentDisplay: Int?
        get() = percent?.let { Math.round(it).toInt() }

    /** Headroom under the cap, or null when uncapped. */
    val remainingMinor: Long?
        get() = limitMinor?.takeIf { it > 0L }?.let { (it - usedMinor).coerceAtLeast(0L) }

    /**
     * The headroom that actually constrains spending: the smaller of the monthly
     * remainder and the balance, whichever exists. `min(97.03, 91.04)` — a full-looking
     * monthly bar is a lie once the balance underneath it is smaller (CCBG-6). With no
     * balance reported this is exactly [remainingMinor], so today's rendering is
     * unchanged; with no cap the balance is the only ceiling.
     */
    val bindingRemainingMinor: Long?
        get() {
            val balance = balanceMinor?.coerceAtLeast(0L)
            val monthly = remainingMinor
            return when {
                balance == null -> monthly
                monthly == null -> balance
                else -> minOf(monthly, balance)
            }
        }
}

data class UsageData(
    val session: UsageWindow?,
    val weekly: UsageWindow?,
    val modelCaps: List<ModelCap>,
    val credits: SpendCredits? = null,
)

/**
 * Parses the /api/oauth/usage response. The schema is undocumented and contains
 * transient experiment fields, so everything unknown is ignored and every field
 * is optional. Prefers the `limits` array (server-computed percent + severity),
 * falls back to `five_hour` / `seven_day`.
 */
object UsageParser {

    fun parse(raw: String): UsageData? = try {
        val root = JSONObject(raw)
        var session: UsageWindow? = null
        var weekly: UsageWindow? = null
        val caps = mutableListOf<ModelCap>()

        val limits = root.optJSONArray("limits")
        if (limits != null) {
            for (i in 0 until limits.length()) {
                val o = limits.optJSONObject(i) ?: continue
                val window = UsageWindow(
                    percent = if (o.isNull("percent")) null else o.optDouble("percent"),
                    resetsAt = parseInstant(o.optString("resets_at")),
                    serverSeverity = o.optString("severity").ifEmpty { null },
                )
                when (o.optString("kind")) {
                    "session" -> session = window
                    "weekly_all" -> weekly = window
                    "weekly_scoped" -> {
                        val name = o.optJSONObject("scope")
                            ?.optJSONObject("model")
                            ?.optString("display_name")
                            .orEmpty()
                        caps += ModelCap(name.ifEmpty { "Model" }, window)
                    }
                }
            }
        }

        if (session == null) session = windowFrom(root.optJSONObject("five_hour"))
        if (weekly == null) weekly = windowFrom(root.optJSONObject("seven_day"))
        // The model caps need the same fallback or they silently vanish when `limits`
        // is absent — a missing row reads as "no caps on this account", which is a
        // legitimate state, so nothing would ever flag it (CCBG-8). Only the two
        // attested flat siblings: `seven_day_sonnet` (OpenQuota reads it) and
        // `seven_day_opus` (null-present in our own captured payloads). Anything else
        // would be a guess.
        if (caps.isEmpty()) {
            windowFrom(root.optJSONObject("seven_day_sonnet"))?.let { caps += ModelCap("Sonnet", it) }
            windowFrom(root.optJSONObject("seven_day_opus"))?.let { caps += ModelCap("Opus", it) }
        }

        val credits = creditsFrom(root)

        if (session == null && weekly == null && caps.isEmpty() && credits == null) null
        else UsageData(session, weekly, caps, credits)
    } catch (_: Exception) {
        null
    }

    /**
     * Usage credits. Prefers the `spend` block — it carries currency and exponent on
     * each amount — and falls back to the looser `extra_usage` shape, which reports
     * the same figures in minor units with a single `decimal_places`.
     *
     * **`spend.used` is what proves credits exist, never the limit** (CCBG-9). An
     * account with its monthly cap switched off reports `limit: null` while still
     * reporting real spend, and the old "no limit means no credits" reading made the
     * entire section disappear for exactly that state.
     */
    private fun creditsFrom(root: JSONObject): SpendCredits? {
        val spend = root.optJSONObject("spend")
        val used = spend?.optJSONObject("used")
        if (used != null) {
            val limit = spend.optJSONObject("limit")
            return SpendCredits(
                usedMinor = used.optLong("amount_minor", 0L),
                limitMinor = limit?.let {
                    if (it.isNull("amount_minor")) null else it.optLong("amount_minor")
                },
                exponent = limit?.optInt("exponent", used.optInt("exponent", 2))
                    ?: used.optInt("exponent", 2),
                currency = (
                    limit?.optString("currency").orEmpty().ifEmpty { used.optString("currency") }
                    ).ifEmpty { "USD" },
                serverSeverity = spend.optString("severity").ifEmpty { null },
                // Null on every payload seen so far (see the field's doc) — parsed so a
                // server-side change lights the balance up without an app update.
                balanceMinor = spend.optJSONObject("balance")?.let {
                    if (it.isNull("amount_minor")) null else it.optLong("amount_minor")
                },
            )
        }

        // Fallback shape. Either figure alone is enough to say credits exist; only an
        // account reporting neither has no credit budget.
        val extra = root.optJSONObject("extra_usage") ?: return null
        val hasLimit = !extra.isNull("monthly_limit")
        val hasUsed = !extra.isNull("used_credits")
        if (!hasLimit && !hasUsed) return null
        return SpendCredits(
            usedMinor = extra.optDouble("used_credits", 0.0).toLong(),
            limitMinor = if (hasLimit) extra.optLong("monthly_limit", 0L) else null,
            exponent = extra.optInt("decimal_places", 2),
            currency = extra.optString("currency").ifEmpty { "USD" },
            serverSeverity = null,
        )
    }

    private fun windowFrom(o: JSONObject?): UsageWindow? {
        if (o == null) return null
        return UsageWindow(
            percent = if (o.isNull("utilization")) null else o.optDouble("utilization"),
            resetsAt = parseInstant(o.optString("resets_at")),
            serverSeverity = null,
        )
    }

    private fun parseInstant(s: String?): Instant? {
        if (s.isNullOrEmpty()) return null
        return try {
            OffsetDateTime.parse(s).toInstant()
        } catch (_: Exception) {
            null
        }
    }
}
