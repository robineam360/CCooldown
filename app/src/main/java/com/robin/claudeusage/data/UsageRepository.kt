package com.robin.claudeusage.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.robin.claudeusage.alerts.Alerts
import com.robin.claudeusage.data.source.Sources
import com.robin.claudeusage.diag.AppLog
import com.robin.claudeusage.ui.Fmt
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

/**
 * Outcome of *sending* a window ping (CCRM-17). Deliberately says nothing about whether
 * a window opened — that can't be known yet. The usage endpoint lags the inference, so
 * verification happens later on its own alarm (CCBG-5); see [VerifyResult].
 */
sealed class PingResult(val message: String, val sent: Boolean, val failed: Boolean) {
    /** Accepted by the server. Whether a window appears is [VerifyResult]'s business. */
    class Sent : PingResult("Ping sent, confirming…", true, false)
    class AlreadyOpen(boundary: String) :
        PingResult("Skipped — a window was already open to $boundary", false, false)

    class AuthNeeded : PingResult("Ping failed — sign-in needs renewing", false, true)
    class NoCredentials : PingResult("Ping failed — not signed in", false, true)
    class Error(detail: String) : PingResult("Ping failed — $detail", false, true)
}

/**
 * Outcome of the deferred check that a ping actually opened a window.
 *
 * [NotYet] is **not** a failure. The window may exist and simply not be visible on the
 * usage endpoint yet, so treating it as failure is what made a working ping report
 * itself as broken — and, via the retry path, fire three more pings (CCBG-5).
 */
sealed class VerifyResult(val message: String, val opened: Boolean) {
    class Opened(boundary: String) : VerifyResult("Window opened, runs to $boundary", true)
    class NotYet : VerifyResult("Sent, but no window visible yet", false)
    class GaveUp : VerifyResult("Sent — couldn't confirm a window opened", false)
}

class UsageRepository(private val context: Context) {

    private val credStore = CredentialStore(context)
    private val cache = UsageCache(context)
    private val registry = ProfileRegistry(context)
    private val historyStore = HistoryStore(context)
    private val sessionLogStore = SessionLog(context)

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

    fun sessionLog(): SessionLog = sessionLogStore

    fun hasCredentials(profile: Profile): Boolean = credStore.load(profile) != null

    fun tokenAddedAt(profile: Profile): Long = credStore.addedAt(profile)

    fun tokenTail(profile: Profile): String? = credStore.tokenTail(profile)

    fun tokenExpiresAt(profile: Profile): Long = credStore.load(profile)?.expiresAt ?: 0L

    fun refreshExpiresAt(profile: Profile): Long = cache.refreshExpiresAt(profile)

    fun refreshExpiryEstimated(profile: Profile): Boolean = cache.refreshExpiryEstimated(profile)

    fun plan(profile: Profile): String? = cache.plan(profile)

    fun tier(profile: Profile): String? = cache.tier(profile)

    fun signInTokenKeys(profile: Profile): String? = cache.signInTokenKeys(profile)

    fun lastRenewedAt(profile: Profile): Long = cache.lastRenewedAt(profile)

    fun registry(): ProfileRegistry = registry

    /**
     * Every registered account. The single entry point for UI code — composables reach it
     * through the repo rather than constructing a [ProfileRegistry] per call site, so the
     * label memo is shared.
     */
    fun profiles(): List<Profile> = registry.all()

    /**
     * Accounts with a token. CCRM-6 (Multi-Account) made this the source for the tab strip,
     * the History screen, the widget picker and the launcher shortcuts: an account you
     * haven't signed into has nothing to show, and Settings is where signing in happens.
     */
    fun configuredProfiles(): List<Profile> = registry.all().filter { hasCredentials(it) }

    /**
     * Forgets the token for this slot. Deliberately leaves the local trend data
     * (`HistoryStore` + `SessionLog`) alone: clearing credentials is nearly always
     * a re-sign-in of the *same* account, and wiping the history made that silent
     * data loss (CCBG-1). Points self-identify by their window's `resetsAt`, so
     * anything genuinely stale ages out through the 8-day prune on its own.
     */
    fun clearCredentials(profile: Profile) {
        credStore.clear(profile)
        cache.setAuthState(profile, AuthState.NO_CREDENTIALS)
        cache.setTokenMeta(profile, 0L, null, null)
        cache.setRefreshExpiryEstimated(profile, false)
        cache.setNativeSignIn(profile, false)
        cache.setLastRenewedAt(profile, 0L)
        cache.setFirstRefreshFailAt(profile, 0L)
        cache.setStaleNotified(profile, false)
        OAuthSignIn.clearPending(context)
    }

    /**
     * Adds an account (CCRM-6 (Multi-Account)). Mints a fresh key and slot, then republishes
     * the shortcuts so the new entry appears the moment it has a token.
     */
    fun addProfile(label: String? = null, provider: Provider = Provider.CLAUDE): Profile =
        registry.add(label, provider)

    fun renameProfile(profile: Profile, label: String) = registry.rename(profile.key, label)

    /** False for the last remaining account — the registry always keeps one. */
    fun canRemoveProfile(): Boolean = registry.canRemove()

    /**
     * Removes an account and everything belonging to it (CCRM-6 (Multi-Account) phase 4).
     * The **only** genuinely destructive path in the app, and the first caller
     * [HistoryStore.clear] and [SessionLog.clear] have ever had — both were noted as
     * callerless in CCRM-14 (Clear History).
     *
     * The order is load-bearing, not stylistic:
     *
     * 1. **Alarms first**, while the slot is still resolvable — an armed ping outlives the
     *    account otherwise, and fires against a profile that no longer exists.
     * 2. **Notifications next**, for the same reason: `slot * 100 + 1…31` is only computable
     *    while we hold the profile, and an orphan sits in the shade until reboot.
     * 3. Credentials, then the cache, then the two JSONL files — data, largest blast radius
     *    last.
     * 4. **Only then** drop it from the registry: everything above needs to resolve the key.
     * 5. Repoint whatever pointed at it, republish the shortcuts, and redraw every surface
     *    from the settled state.
     *
     * Because slots are never reused there is no window in which a not-yet-redrawn widget or
     * tile can read a *new* account's numbers. The worst case is a surface showing nothing.
     *
     * @return false if this is the last account, in which case nothing is touched.
     */
    suspend fun removeProfile(profile: Profile): Boolean {
        if (!registry.canRemove()) return false

        com.robin.claudeusage.ping.PingScheduler.cancel(context, profile)
        Alerts.cancelAllFor(context, profile)

        credStore.clear(profile)
        if (OAuthSignIn.pending(context)?.profile == profile) OAuthSignIn.clearPending(context)
        cache.clearProfile(profile)
        historyStore.clear(profile)
        sessionLogStore.clear(profile)

        if (!registry.remove(profile.key)) return false

        val replacement = registry.first()
        if (cache.pinnedProfile() == profile) cache.setPinnedProfile(replacement)
        WidgetPrefs(context).repointFrom(profile.key, replacement)

        AppLog.log(
            context, AppLog.Level.INFO, "account", profile,
            "removed — slot ${profile.slot} retired, data deleted",
        )
        com.robin.claudeusage.Shortcuts.publish(context)
        updateWidgets()
        com.robin.claudeusage.notify.PinnedNotification.update(context, cache)
        return true
    }

    fun hasPendingSignIn(profile: Profile): Boolean =
        OAuthSignIn.pending(context)?.profile == profile

    /** Begins native sign-in: returns the authorize URL to open in a browser. */
    fun startSignIn(profile: Profile): String = OAuthSignIn.begin(context, profile)

    fun cancelSignIn() = OAuthSignIn.clearPending(context)

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
                cache.setTokenMeta(profile, pasted.refreshExpiresAt, pasted.plan, pasted.tier)
                // Desktop copy: exact expiry from the JSON, and rotation means the
                // family may have moved — keep the legacy (non-native) semantics.
                cache.setRefreshExpiryEstimated(profile, false)
                cache.setNativeSignIn(profile, false)
                cache.setLastRenewedAt(profile, 0L)
                cache.setFirstRefreshFailAt(profile, 0L)
                cache.setStaleNotified(profile, false)
                OAuthSignIn.clearPending(context)
                val r = doFetch(profile, manual = false, ignoreGates = true)
                updateWidgets()
                r
            }
            Alerts.evaluate(context, cache)
            result
        }

    /**
     * Completes native sign-in: parses the pasted `code#state` from the callback
     * page, verifies state, exchanges the code for a phone-owned token family, and
     * persists it. The estimated ~30-day expiry drives the re-sign-in prompt.
     */
    suspend fun completeSignIn(profile: Profile, pastedCode: String): FetchResult =
        withContext(Dispatchers.IO) {
            val result = mutex.withLock {
                val pending = OAuthSignIn.pending(context)
                    ?: return@withLock FetchResult.Error("Sign-in expired — tap Sign in again")
                val raw = pastedCode.trim()
                if (raw.isEmpty()) {
                    return@withLock FetchResult.Error("Paste the code from the sign-in page first")
                }
                val hashIdx = raw.indexOf('#')
                val code = if (hashIdx >= 0) raw.substring(0, hashIdx) else raw
                val returnedState = if (hashIdx >= 0) raw.substring(hashIdx + 1) else ""
                if (returnedState.isNotEmpty() && returnedState != pending.state) {
                    return@withLock FetchResult.Error("That code is from a different sign-in — start again")
                }
                val resp = try {
                    ApiClient.exchangeCode(code, pending.state, pending.verifier)
                } catch (e: IOException) {
                    return@withLock FetchResult.Error(e.message ?: "network error")
                }
                if (resp.code != 200) {
                    // Surface the server's own message + the code length so failures
                    // are diagnosable from a screenshot (the token endpoint answers
                    // 429 "rate_limit_error" for any code it won't accept, so the
                    // HTTP status alone doesn't say why). Kept short for the UI.
                    val serverMsg = try {
                        JSONObject(resp.body).optJSONObject("error")?.optString("message")
                            ?: JSONObject(resp.body).optString("error")
                    } catch (_: Exception) {
                        null
                    }?.takeIf { it.isNotBlank() } ?: resp.body.take(120)
                    return@withLock FetchResult.Error(
                        "Sign-in failed (HTTP ${resp.code}) [code ${code.length}ch] — $serverMsg. " +
                            "Tap Reopen page for a fresh code."
                    )
                }
                val o = JSONObject(resp.body)
                val access = o.optString("access_token")
                val refresh = o.optString("refresh_token")
                if (access.isEmpty() || refresh.isEmpty()) {
                    return@withLock FetchResult.Error("Sign-in response was missing a token")
                }
                val expiresIn = o.optLong("expires_in", 0L)
                val expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L
                credStore.save(profile, Credentials(access, refresh, expiresAt), stampAdded = true)
                cache.setAuthState(profile, AuthState.OK)
                val estExpiry = System.currentTimeMillis() + OAuthSignIn.ESTIMATED_FAMILY_MS
                // Tolerant tier read (CCRM-38) — the token endpoint's spelling is
                // unconfirmed, so try both; the key-name record below settles it.
                val tier = o.optString("rate_limit_tier").ifEmpty { o.optString("rateLimitTier") }
                    .ifEmpty { null }
                cache.setTokenMeta(profile, estExpiry, o.optString("subscriptionType").ifEmpty { null }, tier)
                cache.setSignInTokenKeys(profile, o.keys().asSequence().sorted().joinToString(", "))
                cache.setRefreshExpiryEstimated(profile, true)
                cache.setNativeSignIn(profile, true)
                cache.setLastRenewedAt(profile, 0L)
                cache.setFirstRefreshFailAt(profile, 0L)
                cache.setStaleNotified(profile, false)
                OAuthSignIn.clearPending(context)
                val r = doFetch(profile, manual = false, ignoreGates = true)
                updateWidgets()
                r
            }
            Alerts.evaluate(context, cache)
            result
        }

    /**
     * Sends a window ping (CCRM-17). **Does not verify** — see [verifyWindowPing].
     *
     * Splitting these is the CCBG-5 fix. A 200 from `/v1/messages` only says the request
     * was accepted; whether a 5-hour window opened is a separate fact that the usage
     * endpoint does not reflect for up to several minutes. Checking inline meant a
     * working ping reported itself as a failure.
     *
     * Records the pre-ping `resets_at` so the later check knows what "moved" means, and
     * stamps the send time so [PingSchedule.tooSoonToSend] can prevent a burst.
     */
    suspend fun sendWindowPing(profile: Profile): PingResult = withContext(Dispatchers.IO) {
        val result = mutex.withLock {
            val before = cache.snapshot(profile).data?.session?.resetsAt
            val now = System.currentTimeMillis()

            // A window already open needs no ping; the caller's decide() normally
            // catches this, but Test ping now can reach here directly.
            if (before != null && before.toEpochMilli() > now) {
                return@withLock PingResult.AlreadyOpen(Fmt.dayTime(before, cache.use24hTime()))
            }

            val creds = credStore.load(profile) ?: return@withLock PingResult.NoCredentials()
            var token = creds.accessToken
            if (creds.expiresAt in 1 until now + EXPIRY_MARGIN_MS) {
                token = refreshAccessToken(profile, creds) ?: return@withLock PingResult.AuthNeeded()
            }

            try {
                var resp = ApiClient.sendPing(token)
                if (resp.code == 401) {
                    token = refreshAccessToken(profile, credStore.load(profile) ?: creds)
                        ?: return@withLock PingResult.AuthNeeded()
                    resp = ApiClient.sendPing(token)
                }
                if (resp.code != 200) {
                    PingResult.Error(pingErrorDetail(resp))
                } else {
                    cache.startPingVerification(profile, now, before?.toEpochMilli())
                    PingResult.Sent()
                }
            } catch (e: IOException) {
                PingResult.Error(e.message ?: "no network")
            } catch (e: Exception) {
                PingResult.Error(e.message ?: "unexpected error")
            }
        }
        result
    }

    /**
     * The deferred half: refresh usage and see whether the pending ping opened a window.
     *
     * "Moved" goes through [PingSchedule.windowMoved], never an exact comparison —
     * `resets_at` is recomputed per request and drifts about a second (CCBG-4), so
     * `before != after` would count drift as success.
     *
     * A refresh that fails yields [VerifyResult.NotYet] rather than anything stronger:
     * not being able to look is not evidence about what happened.
     */
    suspend fun verifyWindowPing(profile: Profile): VerifyResult = withContext(Dispatchers.IO) {
        val result = mutex.withLock {
            val beforeRaw = cache.pingPendingBefore(profile)
            val before = if (beforeRaw <= 0L) null else beforeRaw
            val fetch = doFetch(profile, manual = false, ignoreGates = true)
            if (fetch !is FetchResult.Success) return@withLock VerifyResult.NotYet()

            val after = cache.snapshot(profile).data?.session?.resetsAt
            val boundary = after?.let { Fmt.dayTime(it, cache.use24hTime()) }
            if (boundary != null && PingSchedule.windowMoved(before, after.toEpochMilli())) {
                VerifyResult.Opened(boundary)
            } else {
                VerifyResult.NotYet()
            }
        }
        updateWidgets()
        result
    }

    /**
     * Debug-only endpoint probe (CCBG-6). GETs [path] on [host] with this profile's
     * token, refreshing first if it's near expiry and retrying once on a 401, so a probe
     * can't be reported as a 401 that was really just a stale token.
     *
     * **Nothing is written.** The response never reaches [UsageParser] or
     * `cache.saveSuccess`, so an unexpected body can't poison the snapshot, the widgets
     * or history — and the probe deliberately does not touch the manual-refresh rate
     * gates, since it isn't a usage read.
     *
     * Returns the raw [HttpResult], or null when there's no usable credential.
     */
    suspend fun probeEndpoint(
        profile: Profile,
        host: ApiClient.ProbeHost,
        path: String,
    ): HttpResult? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val creds = credStore.load(profile) ?: return@withLock null
            val now = System.currentTimeMillis()
            var token = creds.accessToken
            if (creds.expiresAt in 1 until now + EXPIRY_MARGIN_MS) {
                token = refreshAccessToken(profile, creds) ?: return@withLock null
            }
            try {
                var resp = ApiClient.probe(token, host, path)
                if (resp.code == 401) {
                    val fresh = refreshAccessToken(profile, credStore.load(profile) ?: creds)
                    if (fresh != null) resp = ApiClient.probe(fresh, host, path)
                }
                resp
            } catch (e: IllegalArgumentException) {
                // A rejected path (scheme, //, ..) — report it rather than probing.
                HttpResult(0, "Bad path: ${e.message}")
            } catch (e: IOException) {
                HttpResult(0, e.message ?: "no network")
            } catch (e: Exception) {
                HttpResult(0, e.message ?: "unexpected error")
            }
        }
    }

    /** Pulls the server's own error text out of a non-200 ping response when present. */
    private fun pingErrorDetail(resp: HttpResult): String {
        val fromBody = try {
            JSONObject(resp.body).optJSONObject("error")?.optString("message")?.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
        return fromBody ?: "HTTP ${resp.code}"
    }

    private fun doFetch(profile: Profile, manual: Boolean, ignoreGates: Boolean = false): FetchResult {
        val now = System.currentTimeMillis()
        val creds = credStore.load(profile) ?: run {
            cache.saveFailure(profile, "No token set", now, AuthState.NO_CREDENTIALS, ErrorKind.AUTH)
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

        val source = Sources.of(profile.provider)
        var token = creds.accessToken

        if (creds.expiresAt in 1 until now + EXPIRY_MARGIN_MS) {
            token = refreshAccessToken(profile, creds) ?: return authFailure(profile, now)
        }

        return try {
            var resp = source.fetchUsage(creds.copy(accessToken = token))
            if (source.isAuthFailure(resp.code)) {
                token = refreshAccessToken(profile, credStore.load(profile) ?: creds)
                    ?: return authFailure(profile, now)
                resp = source.fetchUsage(creds.copy(accessToken = token))
            }
            val parsed = if (resp.code == 200) source.parseUsage(resp.body) else null
            when {
                parsed != null -> {
                    val at = System.currentTimeMillis()
                    cache.saveSuccess(profile, resp.body, at)
                    historyStore.record(profile, parsed, at)
                    FetchResult.Success()
                }
                resp.code == 200 -> {
                    cache.saveFailure(
                        profile, "Unrecognized response shape", now,
                        kind = ErrorKind.INVALID_RESPONSE,
                    )
                    FetchResult.Error("Unrecognized response shape")
                }
                resp.code == 429 -> {
                    cache.bumpBackoff(profile, now)
                    cache.saveFailure(profile, "Rate limited (429)", now, kind = ErrorKind.RATE_LIMITED)
                    FetchResult.RateLimited()
                }
                source.isAuthFailure(resp.code) -> authFailure(profile, now)
                else -> {
                    cache.saveFailure(profile, "HTTP ${resp.code}", now, kind = ErrorKind.SERVER)
                    FetchResult.Error("HTTP ${resp.code}")
                }
            }
        } catch (e: IOException) {
            cache.saveFailure(profile, "Network: ${e.message ?: "offline"}", now, kind = ErrorKind.NETWORK)
            FetchResult.Error(e.message ?: "network error")
        }
    }

    /** Why the last refreshAccessToken returned null — surfaced in the status line. */
    private var lastRefreshFailDetail: String? = null

    /** Returns a fresh access token, persisting rotated tokens immediately; null on failure. */
    private fun refreshAccessToken(profile: Profile, creds: Credentials): String? {
        lastRefreshFailDetail = null
        val source = Sources.of(profile.provider)
        return try {
            val resp = source.refresh(creds)
            if (resp.code != 200) {
                lastRefreshFailDetail = "HTTP ${resp.code}"
                if (resp.code in 400..403) cache.setAuthState(profile, AuthState.REAUTH_NEEDED)
                // CCRM-34 (Diagnostics Log): status codes only — never tokens,
                // headers, or bodies (hard rule).
                AppLog.log(
                    context, AppLog.Level.WARN, "auth", profile,
                    "token renewal failed: HTTP ${resp.code}",
                )
                return null
            }
            val grant = source.parseTokenResponse(resp.body, creds) ?: run {
                lastRefreshFailDetail = "no token in response"
                return null
            }
            credStore.save(profile, grant.creds)
            cache.setAuthState(profile, AuthState.OK)
            cache.setLastRenewedAt(profile, System.currentTimeMillis())
            cache.setFirstRefreshFailAt(profile, 0L)
            val rotated = grant.creds.refreshToken != creds.refreshToken
            // A rotated refresh token gets a new, unknown expiry — the exact date
            // from a pasted desktop token no longer applies. For a native phone
            // sign-in, rotation is healthy self-renewal and the ~30-day family
            // clock still holds from sign-in, so the estimate stays put.
            if (rotated && !cache.nativeSignIn(profile)) {
                cache.clearRefreshExpiry(profile)
            }
            AppLog.log(
                context, AppLog.Level.DEBUG, "auth", profile,
                "token renewed (rotated=$rotated)",
            )
            grant.creds.accessToken
        } catch (e: Exception) {
            lastRefreshFailDetail = e.message ?: e.javaClass.simpleName
            AppLog.log(
                context, AppLog.Level.WARN, "auth", profile,
                "token renewal failed: ${e.javaClass.simpleName}",
            )
            null
        }
    }

    private fun authFailure(profile: Profile, now: Long): FetchResult {
        val state = cache.snapshot(profile).authState
        if (state == AuthState.REAUTH_NEEDED) {
            cache.saveFailure(profile, "Re-auth needed", now, AuthState.REAUTH_NEEDED, ErrorKind.AUTH)
            return FetchResult.AuthNeeded()
        }
        // The refresh endpoint 429s on dead tokens (anti-enumeration), which
        // looks transient — track the streak and escalate once it's clearly not.
        val firstFail = cache.firstRefreshFailAt(profile)
        if (firstFail == 0L) {
            cache.setFirstRefreshFailAt(profile, now)
        } else if (now - firstFail > STUCK_REFRESH_MS) {
            cache.saveFailure(
                profile, "Re-auth needed — renewal kept failing", now,
                AuthState.REAUTH_NEEDED, ErrorKind.AUTH,
            )
            return FetchResult.AuthNeeded()
        }
        val detail = lastRefreshFailDetail?.let { " ($it)" } ?: ""
        // A transient refresh failure is either the network or their server —
        // the detail string knows which.
        val kind = if (lastRefreshFailDetail?.startsWith("HTTP") == true) ErrorKind.SERVER
        else ErrorKind.NETWORK
        cache.saveFailure(profile, "Token refresh failed$detail — will retry", now, kind = kind)
        return FetchResult.Error("token refresh failed$detail")
    }

    private suspend fun updateWidgets() {
        try {
            UsageWidget().updateAll(context)
            BarWidget().updateAll(context)
            com.robin.claudeusage.widget.RingWidget().updateAll(context)
            com.robin.claudeusage.widget.MiniRingsWidget().updateAll(context)
            com.robin.claudeusage.widget.PaceWidget().updateAll(context)
        } catch (_: Exception) {
            // No widgets placed yet — fine.
        }
    }
}
