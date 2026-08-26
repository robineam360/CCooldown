package com.robin.claudeusage.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.work.Polling

/**
 * The pinned notification's two broadcasts.
 *
 * [PinnedNotification.ACTION_REFRESH] is the user's "Refresh" action — it fetches.
 * [PinnedNotification.ACTION_EXPIRE] is CCBG-18 (Strip Lifetime Stamp)'s own alarm and
 * deliberately does **not**: a strip whose lifetime is up should leave on time, and
 * spending a network round-trip to retire a line of text would be the wrong trade.
 */
class PinnedRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PinnedNotification.ACTION_REFRESH -> {
                val profile = UsageCache(context).pinnedProfile()
                Polling.refreshOnce(context, manual = true, profile = profile)
            }
            // A redraw only. `foldedEvents` prunes on read, so re-rendering is all it
            // takes; the render also re-arms the alarm for whatever strip is next.
            PinnedNotification.ACTION_EXPIRE ->
                PinnedNotification.update(context, UsageCache(context))
        }
    }
}
