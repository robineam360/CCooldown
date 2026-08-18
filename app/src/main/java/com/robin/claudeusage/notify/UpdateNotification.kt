package com.robin.claudeusage.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.robin.claudeusage.R
import com.robin.claudeusage.alerts.Alerts
import com.robin.claudeusage.data.UpdateCheck
import com.robin.claudeusage.data.UpdateGate
import com.robin.claudeusage.data.UpdateInfo
import com.robin.claudeusage.data.UsageCache

/**
 * The automatic update check (CCRM-28): fetches GitHub's latest release when the
 * poll-riding gate says one is due, records the outcome for the settings line, and
 * posts the once-per-version notification. All decisions are in [UpdateGate] (pure,
 * tested); this is the I/O. Never downloads or installs anything — a tap opens the
 * release page in the browser, nothing more.
 */
object UpdateNotification {

    /** App-global, deliberately outside Alerts.notifId's per-profile +100 offset. */
    const val NOTIFICATION_ID = 40

    const val ACTION_SKIP = "com.robin.claudeusage.UPDATE_SKIP"
    const val EXTRA_VERSION = "version"

    /**
     * Tail-runs on every poll (already on a worker thread). A failed fetch records
     * the failure for the settings card and nothing else — no notification, and
     * lastUpdateCheckAt stays put so the next poll retries.
     */
    fun autoCheck(context: Context, cache: UsageCache) {
        val now = System.currentTimeMillis()
        if (!UpdateGate.shouldCheckNow(cache.autoCheckUpdates(), now, cache.lastUpdateCheckAt())) return
        val info = try {
            UpdateCheck.fetchLatest(installedVersion(context))
        } catch (_: Exception) {
            cache.recordUpdateCheckFailure(now, "couldn't reach GitHub")
            return
        }
        cache.recordUpdateCheckSuccess(
            System.currentTimeMillis(),
            UpdateGate.successOutcome(info.latestVersion, info.updateAvailable),
            info.latestVersion,
        )
        maybePost(context, cache, info)
    }

    /** Once per version, ever: newer than installed, not yet notified, not skipped. */
    private fun maybePost(context: Context, cache: UsageCache, info: UpdateInfo) {
        // CCRM-44 (One Surface): with the pinned notification on, the update rides its
        // panel as a strip (Conditions.update, from latestKnownVersion) instead of
        // posting — and the strip persists while the version lags, which the
        // once-per-version notification never could. lastNotifiedVersion stays unset,
        // so switching the pinned notification off later still gets the one post.
        if (cache.pinnedEnabled()) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        if (!UpdateGate.shouldNotify(
                info.latestVersion, info.currentVersion,
                cache.lastNotifiedVersion(), cache.dismissedUpdateVersion(),
            )
        ) return
        Alerts.ensureChannels(context)

        val openRelease = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse(UpdateGate.safeReleaseUrl(info.releaseUrl))),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val skip = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID,
            Intent(context, UpdateSkipReceiver::class.java)
                .setAction(ACTION_SKIP)
                .putExtra(EXTRA_VERSION, info.latestVersion),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = "You have v${info.currentVersion} — tap for the release page."
        val notes = UpdateGate.trimNotes(info.notes)
        val bigText = buildString {
            append(body)
            append("\nNothing installs by itself.")
            if (notes.isNotBlank()) append("\n\n").append(notes)
        }
        val notification = NotificationCompat.Builder(context, Alerts.CHANNEL_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_bars)
            .setContentTitle("Update available: v${info.latestVersion}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openRelease)
            .addAction(0, "Skip this version", skip)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // CCBG-12 (Status Icon Swap): deliberately NOT given a timeout, unlike every
            // other event alert. This one posts "once per version, ever" — see
            // [maybePost] — so an expiry would silently demote that to "once per version,
            // for an hour, then never", and an update that arrived overnight would be lost
            // to everything but the settings card. Expiring it needs the gate to re-post
            // on a later check, which is a change to CCRM-28 (Update Check), not to this.
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            // Recorded only when the post succeeded (the pace-alert rollback
            // pattern) — a missing grant retries rather than losing the version.
            cache.setLastNotifiedVersion(info.latestVersion)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — the next poll tries again.
        }
    }

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
