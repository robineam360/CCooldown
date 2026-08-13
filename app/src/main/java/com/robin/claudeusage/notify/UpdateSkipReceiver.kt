package com.robin.claudeusage.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.robin.claudeusage.data.UsageCache

/**
 * "Skip this version" on the update notification (CCRM-28): silences exactly that
 * version — a newer release still notifies — and takes the notification down.
 * Deliberately no un-skip affordance; the manual check still surfaces everything.
 */
class UpdateSkipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateNotification.ACTION_SKIP) return
        val version = intent.getStringExtra(UpdateNotification.EXTRA_VERSION) ?: return
        UsageCache(context).setDismissedUpdateVersion(version)
        NotificationManagerCompat.from(context).cancel(UpdateNotification.NOTIFICATION_ID)
    }
}
