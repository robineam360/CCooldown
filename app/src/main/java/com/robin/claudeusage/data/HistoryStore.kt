package com.robin.claudeusage.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One successful fetch, flattened for trend math. */
data class HistoryPoint(
    val at: Long,               // epoch millis of the fetch
    val sessionPct: Double?,
    val sessionResetAt: Long,   // window identity (its resets_at); 0 = not started
    val weeklyPct: Double?,
    val weeklyResetAt: Long,
)

/**
 * Usage history, one JSONL file per profile in filesDir. Kept just past 7 days
 * so the weekly window always has a full curve. At 15-minute polls that's a few
 * hundred short lines, so each record rewrites the pruned file atomically
 * (temp file + rename) rather than risking a torn append.
 */
class HistoryStore(context: Context) {

    private val dir: File = context.applicationContext.filesDir

    companion object {
        private const val MAX_AGE_MS = 8L * 24 * 60 * 60_000L
    }

    private fun file(profile: Profile) = File(dir, "usage-history-${profile.key}.jsonl")

    fun record(profile: Profile, data: UsageData, at: Long) {
        val line = JSONObject().apply {
            put("t", at)
            data.session?.percent?.let { put("sp", it) }
            data.session?.resetsAt?.let { put("sr", it.toEpochMilli()) }
            data.weekly?.percent?.let { put("wp", it) }
            data.weekly?.resetsAt?.let { put("wr", it.toEpochMilli()) }
        }.toString()
        val kept = readLines(profile).filter { timestampOf(it) > at - MAX_AGE_MS }
        writeAtomically(file(profile), kept + line)
    }

    fun points(profile: Profile): List<HistoryPoint> =
        readLines(profile).mapNotNull { parsePoint(it) }.sortedBy { it.at }

    fun clear(profile: Profile) {
        file(profile).delete()
    }

    private fun readLines(profile: Profile): List<String> = try {
        val f = file(profile)
        if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeAtomically(target: File, lines: List<String>) {
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(lines.joinToString("\n") + "\n")
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (_: Exception) {
            // History is best-effort; a failed write only costs one data point.
        }
    }

    private fun timestampOf(line: String): Long = try {
        JSONObject(line).optLong("t")
    } catch (_: Exception) {
        0L
    }

    private fun parsePoint(line: String): HistoryPoint? = try {
        val o = JSONObject(line)
        val t = o.optLong("t")
        if (t <= 0) null else HistoryPoint(
            at = t,
            sessionPct = if (o.has("sp")) o.optDouble("sp") else null,
            sessionResetAt = o.optLong("sr", 0L),
            weeklyPct = if (o.has("wp")) o.optDouble("wp") else null,
            weeklyResetAt = o.optLong("wr", 0L),
        )
    } catch (_: Exception) {
        null
    }
}
