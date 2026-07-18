package com.robin.claudeusage.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.robin.claudeusage.alerts.Alerts
import com.robin.claudeusage.widget.BarWidget
import com.robin.claudeusage.widget.UsageWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

sealed class FetchResult(val message: String) {
    class Success : FetchResult("OK")
    class TooSoon(secondsLeft: Long) : FetchResult("Skipped — wait ${secondsLeft}s (rate-limit floor)")
    class BackedOff(minutesLeft: Long) : FetchResult("Backing off after 429 — next try in ~${minutesLeft}m")
    class RateLimited : FetchResult("Rate limited (429) — backing off")
    class AuthNeeded : FetchResult("Re-auth needed — paste a fresh token")
    class NoCredentials : FetchResult("No token set — open settings")
    class Error(detail: String) : FetchResult("Error: $detail")
}

class UsageRepository(private val context: Context) {

    private val credStore = CredentialStore(context)
    private val cache = UsageCache(context)
    private val historyStore = HistoryStore(context)

    companion object {
        private val mutex = Mutex()
        private const val MANUAL_MIN_INTERVAL_MS = 180_000L // safe floor per community findings
        private const val EXPIRY_MARGIN_MS = 5 * 60_000L

        // Renewals failing continuously for this long means the refresh token is
        // dead (revoked or rotated away by the source machine), not a blip —
        // flag re-auth so the user gets a notification instead of silent staleness.
        private const val STUCK_REFRESH_MS = 6 * 60 * 60_000L
    }

    fun snapshot(profile: Profile): Snapshot = cache.snapshot(profile)

    fun cacheSettings(): UsageCache = cache

    fun history(): HistoryStore = historyStore

    fun hasCredentials(profile: Profile): Boolean = credStore.load(profile) != null

    fun tokenAddedAt(profile: Profile): Long = credStore.addedAt(profile)

    fun tokenTail(profile: Profile): String? = credStore.tokenTail(profile)

    fun tokenExpiresAt(profile: Profile): Long = credStore.load(profile)?.expiresAt ?: 0L

    fun refreshExpiresAt(profile: Profile): Long = cache.refreshExpiresAt(profile)

    fun plan(profile: Profile): String? = cache.plan(profile)

    fun lastRenewedAt(profile: Profile): Long = cache.lastRenewedAt(profile)

    fun configuredProfiles(): List<Profile> = Profile.entries.filter { hasCredentials(it) }

    fun clearCredentials(profile: Profile) {
        credStore.clear(profile)
        historyStore.clear(profile)
        cache.setAuthState(profile, AuthState.NO_CREDENTIALS)
        cache.setTokenMeta(profile, 0L, null)
        cache.setLastRenewedAt(profile, 0L)
        cache.setFirstRefreshFailAt(profile, 0L)
        cache.setStaleNotified(profile, false)
    }

    /** Fetch one profile. Manual calls respect a 180s floor since last success. */
    suspend fun refreshNow(profile: Profile, manual: Boolean): FetchResult = withContext(Dispatchers.IO) {
        val result = mutex.withLock {
            val r = doFetch(profile, manual)
            updateWidgets()
            r
        }
        Alerts.evaluate(context, cache)
        result
    }

    /** Background path: fetch every profile that has a token. */
    suspend fun refreshAll(manual: Boolean = false): Map<Profile, FetchResult> = withContext(Dispatchers.IO) {
        val results = mutex.withLock {
            val r = configuredProfiles().associateWith { doFetch(it, manual) }
            updateWidgets()
            r
        }
        Alerts.evaluate(context, cache)
        results
    }

    /** Validates pasted credentials with a live call; persists them on success. */
    suspend fun validateAndSave(profile: Profile, pastedText: String): FetchResult =
        withContext(Dispatchers.IO) {
            val result = mutex.withLock {
                val pasted = CredentialStore.parsePasted(pastedText)
                    ?: return@withLock FetchResult.Error(
                        "Couldn't read that as a token — copy the whole claudeAiOauth JSON " +
                            "in one go and try again"
                    )
                credStore.save(profile, pasted.creds, stampAdded = true)
                cache.setAuthState(profile, AuthState.OK)
                cache.setTokenMeta(profile, pasted.refreshExpiresAt, pasted.plan)
                cache.setLastRenewedAt(profile, 0L)
                cache.setFirstRefreshFailAt(profile, 0L)
                cache.setStaleNotified(profile, false)
                val r = doFetch(profile, manual = false, ignoreGates = true)
                updateWidgets()
                r
            }
            Alerts.evaluate(context, cache)
            result
        }

    private fun doFetch(profile: Profile, manual: Boolean, ignoreGates: Boolean = false): FetchResult {
        val now = System.currentTimeMillis()
        val creds = credStore.load(profile) ?: run {
            cache.saveFailure(profile, "No token set", now, AuthState.NO_CREDENTIALS)
            return FetchResult.NoCredentials()
        }

        if (!ignoreGates) {
            val backoffUntil = cache.backoffUntil(profile)
            if (!manual && now < backoffUntil) {
                return FetchResult.BackedOff((backoffUntil - now) / 60_000L + 1)
            }
            val fetchedAt = cache.snapshot(profile).fetchedAt
            if (manual && fetchedAt > 0 && now - fetchedAt < MANUAL_MIN_INTERVAL_MS) {
                return FetchResult.TooSoon((MANUAL_MIN_INTERVAL_MS - (now - fetchedAt)) / 1000L)
            }
        }

        var token = creds.accessToken

        if (creds.expiresAt in 1 until now + EXPIRY_MARGIN_MS) {
            token = refreshAccessToken(profile, creds) ?: return authFailure(profile, now)
        }

        return try {
            var resp = ApiClient.fetchUsage(token)
            if (resp.code == 401) {
                token = refreshAccessToken(profile, credStore.load(profile) ?: creds)
                    ?: return authFailure(profile, now)
                resp = ApiClient.fetchUsage(token)
            }
            val parsed = if (resp.code == 200) UsageParser.parse(resp.body) else null
            when {
                parsed != null -> {
                    val at = System.currentTimeMillis()
                    cache.saveSuccess(profile, resp.body, at)
                    historyStore.record(profile, parsed, at)
                    FetchResult.Success()
                }
                resp.code == 200 -> {
                    cache.saveFailure(profile, "Unrecognized response shape", now)
                    FetchResult.Error("Unrecognized response shape")
                }
                resp.code == 429 -> {
                    cache.bumpBackoff(profile, now)
                    cache.saveFailure(profile, "Rate limited (429)", now)
                    FetchResult.RateLimited()
                }
                resp.code == 401 -> authFailure(profile, now)
                else -> {
                    cache.saveFailure(profile, "HTTP ${resp.code}", now)
                    FetchResult.Error("HTTP ${resp.code}")
                }
            }
        } catch (e: IOException) {
            cache.saveFailure(profile, "Network: ${e.message ?: "offline"}", now)
            FetchResult.Error(e.message ?: "network error")
        }
    }

    /** Why the last refreshAccessToken returned null — surfaced in the status line. */
    private var lastRefreshFailDetail: String? = null

    /** Returns a fresh access token, persisting rotated tokens immediately; null on failure. */
    private fun refreshAccessToken(profile: Profile, creds: Credentials): String? {
        lastRefreshFailDetail = null
        return try {
            val resp = ApiClient.refreshToken(creds.refreshToken)
            if (resp.code != 200) {
                lastRefreshFailDetail = "HTTP ${resp.code}"
                if (resp.code in 400..403) cache.setAuthState(profile, AuthState.REAUTH_NEEDED)
                return null
            }
            val o = JSONObject(resp.body)
            val newAccess = o.optString("access_token")
            if (newAccess.isEmpty()) {
                lastRefreshFailDetail = "no token in response"
                return null
            }
            val rotatedRefresh = o.optString("refresh_token")
            val newRefresh = rotatedRefresh.ifEmpty { creds.refreshToken }
            val expiresIn = o.optLong("expires_in", 0L)
            val expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L
            credStore.save(profile, Credentials(newAccess, newRefresh, expiresAt))
            cache.setAuthState(profile, AuthState.OK)
            cache.setLastRenewedAt(profile, System.currentTimeMillis())
            cache.setFirstRefreshFailAt(profile, 0L)
            // A rotated refresh token gets a new, unknown expiry — the stored
            // date from the paste no longer applies.
            if (rotatedRefresh.isNotEmpty() && rotatedRefresh != creds.refreshToken) {
                cache.clearRefreshExpiry(profile)
            }
            newAccess
        } catch (e: Exception) {
            lastRefreshFailDetail = e.message ?: e.javaClass.simpleName
            null
        }
    }

    private fun authFailure(profile: Profile, now: Long): FetchResult {
        val state = cache.snapshot(profile).authState
        if (state == AuthState.REAUTH_NEEDED) {
            cache.saveFailure(profile, "Re-auth needed", now, AuthState.REAUTH_NEEDED)
            return FetchResult.AuthNeeded()
        }
        // The refresh endpoint 429s on dead tokens (anti-enumeration), which
        // looks transient — track the streak and escalate once it's clearly not.
        val firstFail = cache.firstRefreshFailAt(profile)
        if (firstFail == 0L) {
            cache.setFirstRefreshFailAt(profile, now)
        } else if (now - firstFail > STUCK_REFRESH_MS) {
            cache.saveFailure(profile, "Re-auth needed — renewal kept failing", now, AuthState.REAUTH_NEEDED)
            return FetchResult.AuthNeeded()
        }
        val detail = lastRefreshFailDetail?.let { " ($it)" } ?: ""
        cache.saveFailure(profile, "Token refresh failed$detail — will retry", now)
        return FetchResult.Error("token refresh failed$detail")
    }

    private suspend fun updateWidgets() {
        try {
            UsageWidget().updateAll(context)
            BarWidget().updateAll(context)
        } catch (_: Exception) {
            // No widgets placed yet — fine.
        }
    }
}
