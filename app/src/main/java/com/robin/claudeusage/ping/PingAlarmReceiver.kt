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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fires on a scheduled window ping (CCRM-17). Re-checks the decision before sending —
 * the alarm may be minutes stale by the time it runs, and the user may have opened a
 * window themselves in the meantime, in which case the right move is to skip and
 * re-arm for that window's real end.
 */
class PingAlarmReceiver : BroadcastReceiver() {

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val profile = PingScheduler.profileOf(intent)
        val intendedAt = PingScheduler.intendedAt(intent)
        val app = context.applicationContext
        val pending = goAsync()

        GlobalScope.launch(Dispatchers.IO) {
            try {
                run(app, profile, intendedAt)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun run(context: Context, profile: Profile, intendedAtMs: Long) {
        val cache = UsageCache(context)
        val repo = UsageRepository(context)
        val zone = PingScheduler.zone()
        val now = System.currentTimeMillis()

        // Fresh usage first: the decision hinges on whether a window is open, and a
        // stale snapshot would have us ping into one.
        repo.refreshNow(profile, manual = false)

        val decision = PingSchedule.decide(
            nowMs = now,
            zone = zone,
            config = cache.pingConfig(profile),
            day = cache.pingDayState(profile),
            sessionResetAtMs = repo.snapshot(profile).data?.session?.resetsAt?.toEpochMilli(),
        )

        when (decision) {
            is PingSchedule.Decision.Ping -> sendAndRecord(context, repo, cache, profile, intendedAtMs, zone)
            is PingSchedule.Decision.Wait -> {
                cache.setPingOutcome(profile, now, decision.because, failed = false)
                cache.setPingRetryIndex(profile, 0)
            }
            is PingSchedule.Decision.DoneForToday -> cache.setPingRetryIndex(profile, 0)
            PingSchedule.Decision.Off -> Unit
        }
        PingScheduler.reschedule(context, profile)
    }

    private suspend fun sendAndRecord(
        context: Context,
        repo: UsageRepository,
        cache: UsageCache,
        profile: Profile,
        intendedAtMs: Long,
        zone: ZoneId,
    ) {
        val result = repo.sendWindowPing(profile)
        val at = System.currentTimeMillis()
        val late = if (intendedAtMs > 0) PingSchedule.latenessMs(intendedAtMs, at) else 0L

        // Report lateness rather than hiding it: the window follows the ping, so a late
        // alarm has genuinely shifted every boundary for the rest of the day.
        val note = if (late >= 60_000L) " (fired ${late / 60_000}m late)" else ""
        cache.setPingOutcome(profile, at, result.message + note, result.failed)

        if (result.startedWindow) {
            cache.recordPingWindowStarted(profile, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(at), zone))
            cache.setPingRetryIndex(profile, 0)
            return
        }
        if (!result.failed) {
            cache.setPingRetryIndex(profile, 0)
            return
        }

        // Failed: retry a couple of times before giving up on this slot, then tell the
        // user. A silent 4am failure is the outcome this whole feature must not have.
        val step = cache.pingRetryIndex(profile)
        if (step < PingSchedule.RETRY_BACKOFF_MS.size) {
            cache.setPingRetryIndex(profile, step + 1)
            PingScheduler.armAt(context, profile, at + PingSchedule.RETRY_BACKOFF_MS[step])
        } else {
            cache.setPingRetryIndex(profile, 0)
            Alerts.notifyPingFailed(context, profile, result.message)
        }
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
