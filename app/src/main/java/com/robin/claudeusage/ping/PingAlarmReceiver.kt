package com.robin.claudeusage.ping

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.robin.claudeusage.alerts.Alerts
import com.robin.claudeusage.data.PingResult
import com.robin.claudeusage.data.PingSchedule
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageRepository
import com.robin.claudeusage.data.VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Drives window pings (CCRM-17). Two alarm kinds:
 *
 *  - **Ping** — re-decide (the alarm may be stale, and the user may have opened a window
 *    themselves meanwhile), send, then hand off to a deferred check.
 *  - **Verify** — the deferred check, because the usage endpoint lags the inference by
 *    up to several minutes and an inline check reported working pings as failures
 *    (CCBG-5).
 */
class PingAlarmReceiver : BroadcastReceiver() {

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val profile = PingScheduler.profileOf(context, intent)
        val intendedAt = PingScheduler.intendedAt(intent)
        val verifying = intent.action == PingScheduler.ACTION_VERIFY
        val app = context.applicationContext
        val pending = goAsync()

        GlobalScope.launch(Dispatchers.IO) {
            val late = if (intendedAt > 0) PingSchedule.latenessMs(intendedAt, System.currentTimeMillis()) else 0L
            PingLog.log(
                app, profile,
                "ALARM ${if (verifying) "verify" else "ping"} late=${late / 1000}s " +
                    "exact=${PingScheduler.canScheduleExact(app)} ${PingLog.powerState(app)}",
            )
            try {
                if (verifying) runVerify(app, profile) else runPing(app, profile, intendedAt)
            } catch (e: Exception) {
                PingLog.log(app, profile, "CRASH ${e.javaClass.simpleName}: ${e.message}")
                throw e
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runPing(context: Context, profile: Profile, intendedAtMs: Long) {
        val cache = UsageCache(context)
        val repo = UsageRepository(context)
        val now = System.currentTimeMillis()

        // Fresh usage first: the decision hinges on whether a window is open, and a
        // stale snapshot would have us ping into one.
        repo.refreshNow(profile, manual = false)

        val decision = PingSchedule.decide(
            nowMs = now,
            zone = PingScheduler.zone(),
            config = cache.pingConfig(profile),
            day = cache.pingDayState(profile),
            sessionResetAtMs = repo.snapshot(profile).data?.session?.resetsAt?.toEpochMilli(),
        )

        val resetAt = repo.snapshot(profile).data?.session?.resetsAt
        PingLog.log(
            context, profile,
            "DECIDE ${decision.javaClass.simpleName} window=${resetAt ?: "none"} " +
                "startedToday=${cache.pingDayState(profile).windowsStarted}",
        )

        when (decision) {
            is PingSchedule.Decision.Ping -> send(context, repo, cache, profile, intendedAtMs)
            is PingSchedule.Decision.Wait -> {
                cache.setPingOutcome(profile, now, decision.because, failed = false)
                cache.setPingRetryIndex(profile, 0)
            }
            is PingSchedule.Decision.DoneForToday -> cache.setPingRetryIndex(profile, 0)
            PingSchedule.Decision.Off -> Unit
        }
        PingScheduler.reschedule(context, profile)
    }

    private suspend fun send(
        context: Context,
        repo: UsageRepository,
        cache: UsageCache,
        profile: Profile,
        intendedAtMs: Long,
    ) {
        val now = System.currentTimeMillis()

        // Hard floor between sends, whatever the rest of the logic thinks. This is the
        // backstop that keeps a confused verification from becoming a ping storm.
        if (PingSchedule.tooSoonToSend(cache.pingLastSentAt(profile), now)) {
            cache.setPingOutcome(profile, now, "Skipped — pinged moments ago", failed = false)
            PingLog.log(context, profile, "SEND skipped (inside ${PingSchedule.MIN_SEND_INTERVAL_MS / 60_000}m floor)")
            return
        }

        val result = repo.sendWindowPing(profile)
        PingLog.log(context, profile, "SEND sent=${result.sent} failed=${result.failed} — ${result.message}")
        val at = System.currentTimeMillis()
        val late = if (intendedAtMs > 0) PingSchedule.latenessMs(intendedAtMs, at) else 0L

        // Report lateness rather than hiding it. With the boundary truncating to the
        // hour this is usually cosmetic, but crossing an hour boundary costs a full one.
        val note = if (late >= 60_000L) " (fired ${late / 60_000}m late)" else ""
        cache.setPingOutcome(profile, at, result.message + note, result.failed)

        if (result.sent) {
            cache.setPingRetryIndex(profile, 0)
            PingScheduler.armVerify(context, profile, at + PingSchedule.VERIFY_DELAY_MS)
            return
        }
        if (!result.failed) return // AlreadyOpen — nothing to do, nothing to retry.

        // Only a genuine *send* failure retries. "Couldn't confirm" must never get here.
        val step = cache.pingRetryIndex(profile)
        if (step < PingSchedule.RETRY_BACKOFF_MS.size) {
            cache.setPingRetryIndex(profile, step + 1)
            PingScheduler.armAt(context, profile, at + PingSchedule.RETRY_BACKOFF_MS[step])
        } else {
            cache.setPingRetryIndex(profile, 0)
            Alerts.notifyPingFailed(context, profile, result.message)
        }
    }

    /**
     * The deferred check. Note what it never does: notify. An unconfirmed ping is not a
     * failed one, and waking someone at 4am to report a success as a failure is exactly
     * the bug this replaced.
     */
    private suspend fun runVerify(context: Context, profile: Profile) {
        val cache = UsageCache(context)
        val repo = UsageRepository(context)
        val attempt = cache.pingVerifyAttempt(profile) + 1
        val result = repo.verifyWindowPing(profile)
        val at = System.currentTimeMillis()
        PingLog.log(
            context, profile,
            "VERIFY attempt=$attempt/${PingSchedule.MAX_VERIFY_ATTEMPTS} " +
                "opened=${result.opened} — ${result.message}",
        )

        when {
            result is VerifyResult.Opened -> {
                cache.setPingOutcome(profile, at, result.message, failed = false)
                cache.recordPingWindowStarted(
                    profile,
                    LocalDate.ofInstant(Instant.ofEpochMilli(at), PingScheduler.zone()),
                )
                cache.clearPingVerification(profile)
            }
            attempt < PingSchedule.MAX_VERIFY_ATTEMPTS -> {
                cache.setPingVerifyAttempt(profile, attempt)
                cache.setPingOutcome(profile, at, result.message, failed = false)
                PingScheduler.armVerify(context, profile, at + PingSchedule.VERIFY_RETRY_MS)
                return // Don't reschedule the chain mid-verification.
            }
            else -> {
                // Out of checks. Count the window as started anyway: every observation
                // says pings do work, and MIN_SEND_INTERVAL_MS already prevents a burst.
                // Miscounting one window is far cheaper than pinging in a loop.
                cache.setPingOutcome(profile, at, VerifyResult.GaveUp().message, failed = false)
                cache.recordPingWindowStarted(
                    profile,
                    LocalDate.ofInstant(Instant.ofEpochMilli(at), PingScheduler.zone()),
                )
                cache.clearPingVerification(profile)
            }
        }
        PingScheduler.reschedule(context, profile)
    }
}

/** Alarms don't survive a reboot, so re-arm both profiles once the device is back. */
class PingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        PingScheduler.rescheduleAll(context.applicationContext)
    }
}
