package com.robin.claudeusage.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Outcome of an update check against the public GitHub Releases API. */
data class UpdateInfo(
    val latestVersion: String,    // normalised, e.g. "0.14"
    val currentVersion: String,   // installed versionName, normalised
    val updateAvailable: Boolean,
    val releaseUrl: String,       // release page to open in the browser
    val notes: String,            // release body / changelog
)

/**
 * Checks GitHub for the latest published release. The repo is public, so this needs
 * no authentication. Unauthenticated GitHub API allows 60 requests/hour per IP —
 * ample for a manual "Check for updates" button.
 */
object UpdateCheck {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/robineam360/CCooldown/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Blocking network call — invoke off the main thread. Throws on network/HTTP error. */
    fun fetchLatest(currentVersion: String): UpdateInfo {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CCooldown-android")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("GitHub returned HTTP ${resp.code}")
            }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifBlank { json.optString("name") }
            val latest = normalize(tag)
            val current = normalize(currentVersion)
            return UpdateInfo(
                latestVersion = latest,
                currentVersion = current,
                updateAvailable = compare(latest, current) > 0,
                releaseUrl = json.optString("html_url"),
                notes = json.optString("body").trim(),
            )
        }
    }

    /** Strips a leading "v" so "v0.14" and "0.14" compare equal. */
    fun normalize(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    /** Numeric compare of dot-separated versions. >0 when a is newer than b. */
    fun compare(a: String, b: String): Int {
        val pa = a.split(".")
        val pb = b.split(".")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }
}
