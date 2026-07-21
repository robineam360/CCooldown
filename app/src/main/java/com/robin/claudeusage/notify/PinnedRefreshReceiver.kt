package com.robin.claudeusage.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.work.Polling

/** "Refresh" action on the pinned notification: kicks a one-shot fetch for its profile. */
class PinnedRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PinnedNotification.ACTION_REFRESH) return
        val profile = UsageCache(context).pinnedProfile()
        Polling.refreshOnce(context, manual = true, profile = profile)
    }
}
