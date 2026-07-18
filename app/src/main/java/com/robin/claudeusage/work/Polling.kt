package com.robin.claudeusage.work

import android.content.Context
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
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.UsageRepository
import java.time.Instant
import java.util.concurrent.TimeUnit

class UsagePollWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean("manual", false)
        val onlyProfile = inputData.getString("profile")?.let { Profile.fromKey(it) }
        val repo = UsageRepository(applicationContext)

        val results: Collection<FetchResult> =
            if (onlyProfile != null) listOf(repo.refreshNow(onlyProfile, manual))
            else repo.refreshAll(manual).values

        // Alerts are evaluated inside the repository after every fetch path.
        val cache = UsageCache(applicationContext)
        Polling.scheduleResetChecks(applicationContext, cache)

        // WorkManager periodic work can't run more often than every 15 min; for
        // shorter user-chosen intervals we self-chain one-shots (periodic stays
        // as a backstop if the chain ever breaks).
        val interval = cache.pollIntervalMinutes()
        if (interval < 15) Polling.chainNext(applicationContext, interval)

        val transientFailure = results.any { it is FetchResult.Error }
        return if (transientFailure && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

object Polling {

    private const val PERIODIC_NAME = "usage-poll"
    private const val ONESHOT_NAME = "usage-poll-once"

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
        for (profile in Profile.entries) {
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
