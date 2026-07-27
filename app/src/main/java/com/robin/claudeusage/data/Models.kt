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
    val limitMinor: Long,
    val exponent: Int,
    val currency: String,
    val serverSeverity: String?,
) {
    /** 0-100, computed from the money rather than the server's rounded integer. */
    val percent: Double
        get() = if (limitMinor <= 0L) 0.0 else usedMinor * 100.0 / limitMinor

    /**
     * The percentage as displayed. Rounded, not truncated like the window bars:
     * money is precise, so $5.99 of $100 should read 6%, not 5%.
     */
    val percentDisplay: Int
        get() = Math.round(percent).toInt()

    val remainingMinor: Long
        get() = (limitMinor - usedMinor).coerceAtLeast(0L)
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
     */
    private fun creditsFrom(root: JSONObject): SpendCredits? {
        val spend = root.optJSONObject("spend")
        val used = spend?.optJSONObject("used")
        val limit = spend?.optJSONObject("limit")
        if (used != null && limit != null) {
            return SpendCredits(
                usedMinor = used.optLong("amount_minor", 0L),
                limitMinor = limit.optLong("amount_minor", 0L),
                exponent = limit.optInt("exponent", used.optInt("exponent", 2)),
                currency = limit.optString("currency").ifEmpty { used.optString("currency") }
                    .ifEmpty { "USD" },
                serverSeverity = spend.optString("severity").ifEmpty { null },
            )
        }

        val extra = root.optJSONObject("extra_usage") ?: return null
        if (extra.isNull("monthly_limit")) return null
        return SpendCredits(
            usedMinor = extra.optDouble("used_credits", 0.0).toLong(),
            limitMinor = extra.optLong("monthly_limit", 0L),
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
