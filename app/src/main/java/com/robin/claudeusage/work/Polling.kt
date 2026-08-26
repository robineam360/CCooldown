package com.robin.claudeusage.work

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.robin.claudeusage.data.FetchResult
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.ProfileRegistry
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageRepository
import com.robin.claudeusage.diag.AppLog
import com.robin.claudeusage.notify.UpdateNotification
import com.robin.claudeusage.widget.MiniRingsWidget
import com.robin.claudeusage.widget.PaceWidget
import com.robin.claudeusage.widget.RingWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

class UsagePollWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean("manual", false)
        val onlyProfile = inputData.getString("profile")
            ?.let { ProfileRegistry(applicationContext).resolve(it) }
        val repo = UsageRepository(applicationContext)

        val byProfile: Map<Profile, FetchResult> =
            if (onlyProfile != null) mapOf(onlyProfile to repo.refreshNow(onlyProfile, manual))
            else repo.refreshAll(manual)
        // CCRM-34 (Diagnostics Log): successes at DEBUG (routine), anything else at
        // INFO — outcomes only, never payloads.
        for ((p, r) in byProfile) {
            AppLog.log(
                applicationContext,
                if (r is FetchResult.Success) AppLog.Level.DEBUG else AppLog.Level.INFO,
                "poll", p, "${if (manual) "manual" else "auto"} → ${r.message}",
            )
        }
        val results: Collection<FetchResult> = byProfile.values

        // Alerts are evaluated inside the repository after every fetch path.
        val cache = UsageCache(applicationContext)
        Polling.scheduleResetChecks(applicationContext, cache)

        // Window pings chain off the observed resets_at, which only changes when we
        // poll — so the alarm has to follow the fresh value rather than the one it was
        // armed against (CCRM-17).
        com.robin.claudeusage.ping.PingScheduler.rescheduleAll(applicationContext)

        // WorkManager periodic work can't run more often than every 15 min; for
        // shorter user-chosen intervals we self-chain one-shots (periodic stays
        // as a backstop if the chain ever breaks).
        val interval = cache.pollIntervalMinutes()
        if (interval < 15) Polling.chainNext(applicationContext, interval)

        // Auto update check (CCRM-28) tail-runs the poll — no scheduler of its own,
        // and nothing on the launch path ever blocks on it. The 6-hour gate lives in
        // UpdateGate.shouldCheckNow; a failed check records itself and retries here
        // next time.
        withContext(Dispatchers.IO) {
            UpdateNotification.autoCheck(applicationContext, cache)
        }

        val transientFailure = results.any { it is FetchResult.Error }
        return if (transientFailure && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

/**
 * Redraw-only tick for the ring/pace faces (CCRM-39/40/41): their countdowns
 * ("2h 14m") would otherwise only move when a poll lands. No network, no cache
 * writes — and it cancels itself once the last of the three faces is removed.
 * Per-minute alarms are deliberately rejected: the faces soften to "soon"
 * inside five minutes, which is what makes a 15-minute cadence honest.
 */
class WidgetRedrawWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = GlanceAppWidgetManager(applicationContext)
        val any = manager.getGlanceIds(RingWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(MiniRingsWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(PaceWidget::class.java).isNotEmpty()
        if (!any) {
            Polling.cancelWidgetRedrawTick(applicationContext)
            return Result.success()
        }
        try {
            RingWidget().updateAll(applicationContext)
            MiniRingsWidget().updateAll(applicationContext)
            PaceWidget().updateAll(applicationContext)
        } catch (_: Exception) {
            // A single face failing to render shouldn't fail the tick.
        }
        return Result.success()
    }
}

object Polling {

    private const val PERIODIC_NAME = "usage-poll"
    private const val ONESHOT_NAME = "usage-poll-once"
    private const val REDRAW_NAME = "widget-redraw"
    private const val REDRAW_RESET_NAME = "widget-redraw-reset"

    /** 15 min is WorkManager's periodic floor; the "soon" softening covers the gap. */
    fun scheduleWidgetRedrawTick(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetRedrawWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            REDRAW_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    fun cancelWidgetRedrawTick(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(REDRAW_NAME)
    }

    /**
     * One self-arming redraw at the reset moment when it's close, so the ring
     * empties / "soon" clears promptly at rollover instead of lingering for up
     * to a tick. One-shot, no standing alarm.
     */
    fun armResetRedraw(context: Context, resetAtMs: Long) {
        val delayMs = resetAtMs - System.currentTimeMillis() + 5_000L
        if (delayMs !in 1..20 * 60_000L) return
        val request = OneTimeWorkRequestBuilder<WidgetRedrawWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            REDRAW_RESET_NAME, ExistingWorkPolicy.REPLACE, request
        )
    }

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(context: Context, intervalMinutes: Long) {
        val request = PeriodicWorkRequestBuilder<UsagePollWorker>(
            intervalMinutes.coerceAtLeast(15L), TimeUnit.MINUTES
        )
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    /** Next link in the short-interval chain (intervals below WorkManager's 15-min periodic floor). */
    fun chainNext(context: Context, intervalMinutes: Long) {
        val request = OneTimeWorkRequestBuilder<UsagePollWorker>()
            .setConstraints(networkConstraint)
            .setInitialDelay(intervalMinutes.coerceAtLeast(5L), TimeUnit.MINUTES)
            .setInputData(workDataOf("manual" to false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "usage-poll-chain", ExistingWorkPolicy.REPLACE, request
        )
    }

    /** Immediate one-shot fetch (widget tap / app button). Null profile = all configured. */
    fun refreshOnce(context: Context, manual: Boolean = true, profile: Profile? = null) {
        val request = OneTimeWorkRequestBuilder<UsagePollWorker>()
            .setConstraints(networkConstraint)
            .setInputData(workDataOf("manual" to manual, "profile" to profile?.key))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (profile == null) ONESHOT_NAME else "$ONESHOT_NAME-${profile.key}",
            ExistingWorkPolicy.KEEP, request
        )
    }

    /**
     * Schedules a poll shortly after each window's known reset moment so the
     * "window reset" notification arrives within a couple of minutes of the
     * actual reset instead of waiting for the next periodic cycle.
     */
    fun scheduleResetChecks(context: Context, cache: UsageCache) {
        val now = Instant.now()
        for (profile in cache.registry().all()) {
            val data = cache.snapshot(profile).data ?: continue
            val targets = listOf(
                "session" to data.session?.resetsAt,
                "weekly" to data.weekly?.resetsAt,
            )
            for ((window, resetsAt) in targets) {
                if (resetsAt == null || !resetsAt.isAfter(now)) continue
                val delayMs = resetsAt.toEpochMilli() - now.toEpochMilli() + 2 * 60_000L
                val request = OneTimeWorkRequestBuilder<UsagePollWorker>()
                    .setConstraints(networkConstraint)
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("manual" to false, "profile" to profile.key))
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "reset-check-${profile.key}-$window", ExistingWorkPolicy.REPLACE, request
                )
            }
        }
    }
}
