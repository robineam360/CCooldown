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

data class UsageData(
    val session: UsageWindow?,
    val weekly: UsageWindow?,
    val modelCaps: List<ModelCap>,
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

        if (session == null && weekly == null && caps.isEmpty()) null
        else UsageData(session, weekly, caps)
    } catch (_: Exception) {
        null
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
