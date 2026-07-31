package com.robin.claudeusage.ping

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.robin.claudeusage.data.PingSchedule
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.UsageCache
import java.time.ZoneId

/**
 * Arms the alarms that drive window pings (CCRM-17).
 *
 * Uses `AlarmManager.setExactAndAllowWhileIdle`, **not** WorkManager. The app's usage
 * polling ([com.robin.claudeusage.work.Polling]) is happy to be inexact — a widget that
 * refreshes a few minutes late is fine. A ping is not: window boundaries follow the
 * message that opens them, so an alarm that fires at 04:03 means the user owns
 * 04:03–09:03 for the rest of the day. Every minute of drift is a minute of the
 * coverage the feature exists to provide.
 */
object PingScheduler {

    private const val ACTION_PING = "com.robin.claudeusage.action.WINDOW_PING"

    /** The deferred "did it actually open a window" check (CCBG-5). */
    const val ACTION_VERIFY = "com.robin.claudeusage.action.WINDOW_PING_VERIFY"

    private const val EXTRA_PROFILE = "profile"
    private const val EXTRA_INTENDED_AT = "intendedAt"

    fun zone(): ZoneId = ZoneId.systemDefault()

    /**
     * Recomputes and re-arms the alarm for one profile. Safe to call repeatedly — it
     * replaces any existing alarm rather than stacking them.
     */
    fun reschedule(context: Context, profile: Profile) {
        val cache = UsageCache(context)
        val config = cache.pingConfig(profile)
        if (!config.enabled) {
            cancel(context, profile)
            return
        }
        val decision = PingSchedule.decide(
            nowMs = System.currentTimeMillis(),
            zone = zone(),
            config = config,
            day = cache.pingDayState(profile),
            sessionResetAtMs = sessionResetAt(cache, profile),
        )
        val at = when (decision) {
            is PingSchedule.Decision.Wait -> decision.atMs
            is PingSchedule.Decision.DoneForToday -> decision.atMs
            // Due now (the alarm fired late, or the user just enabled it mid-window).
            is PingSchedule.Decision.Ping -> System.currentTimeMillis() + 1_000L
            PingSchedule.Decision.Off -> {
                cancel(context, profile)
                return
            }
        }
        armAt(context, profile, at)
    }

    fun rescheduleAll(context: Context) {
        for (profile in Profile.entries) reschedule(context, profile)
    }

    /** Arms a single exact alarm, falling back to inexact if the permission is missing. */
    fun armAt(context: Context, profile: Profile, atMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, profile, atMs)
        PingLog.log(
            context, profile,
            "ARM ping at ${java.time.Instant.ofEpochMilli(atMs)} " +
                "in ${(atMs - System.currentTimeMillis()) / 60_000}m exact=${canScheduleExact(context)}",
        )
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            // Degraded but not broken: the ping still happens, just possibly minutes
            // late, which shifts the window. The settings screen says so and offers
            // to fix it rather than failing silently.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    /** Arms the deferred verification check for a ping we just sent (CCBG-5). */
    fun armVerify(context: Context, profile: Profile, atMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, profile, atMs, ACTION_VERIFY)
        PingLog.log(context, profile, "ARM verify in ${(atMs - System.currentTimeMillis()) / 1000}s")
        // Verification is not time-critical the way a ping is — a minute either way
        // costs nothing, so this deliberately doesn't consume an exact-alarm slot.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }

    fun cancel(context: Context, profile: Profile) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context, profile, 0L))
        am.cancel(pendingIntent(context, profile, 0L, ACTION_VERIFY))
    }

    /**
     * Whether exact alarms are available. Android 12/13 require a user grant that can
     * be revoked at any time, so this is checked at arm time rather than cached.
     */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun sessionResetAt(cache: UsageCache, profile: Profile): Long? =
        cache.snapshot(profile).data?.session?.resetsAt?.toEpochMilli()

    private fun pendingIntent(
        context: Context,
        profile: Profile,
        intendedAtMs: Long,
        action: String = ACTION_PING,
    ): PendingIntent {
        val intent = Intent(context, PingAlarmReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_PROFILE, profile.key)
            putExtra(EXTRA_INTENDED_AT, intendedAtMs)
        }
        // Request code per profile *and* per action, so a profile's ping and verify
        // alarms are independent and neither replaces the other.
        val base = if (action == ACTION_VERIFY) 1750 else 1700
        return PendingIntent.getBroadcast(
            context,
            base + profile.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun profileOf(intent: Intent): Profile = Profile.fromKey(intent.getStringExtra(EXTRA_PROFILE))

    fun intendedAt(intent: Intent): Long = intent.getLongExtra(EXTRA_INTENDED_AT, 0L)
}
