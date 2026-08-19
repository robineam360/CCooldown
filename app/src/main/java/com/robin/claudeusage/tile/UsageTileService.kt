package com.robin.claudeusage.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.robin.claudeusage.MainActivity
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.UsageIcon
import com.robin.claudeusage.work.Polling

/** Quick Settings tile: 5-hour utilization at a glance from any screen. */
abstract class BaseUsageTileService(private val profile: Profile) : TileService() {

    companion object {
        private const val FRESH_ENOUGH_MS = 3 * 60_000L
    }

    override fun onStartListening() {
        val cache = UsageCache(this)
        val snapshot = cache.snapshot(profile)
        val profileLabel = cache.profileLabel(profile)
        val session = snapshot.data?.session
        qsTile?.apply {
            when {
                session?.percent != null -> {
                    state = Tile.STATE_ACTIVE
                    // CCRM-22 (Used or Left): room for the word, so it flips worded.
                    label = "$profileLabel ${Fmt.usageShort(session.percent, cache.usageLeft())}"
                    // The 5-hour reset earns the subtitle over the 7-day number:
                    // it's the one that changes what you do next. The countdown/clock
                    // choice is the global CCRM-23 (Reset Display) token now — the
                    // tile reads it, it no longer owns it.
                    subtitle = when {
                        session.resetsAt == null -> "not started"
                        cache.resetClock() ->
                            "resets ${Fmt.timeOnly(session.resetsAt, cache.use24hTime())}"
                        else -> "resets ${Fmt.relIn(session.resetsAt)}"
                    }
                    // Fills as the window burns, in whichever icon style is set.
                    // The system tints tile icons like status-bar icons, so this is
                    // an alpha mask — level shows through fill, never through colour.
                    icon = Icon.createWithBitmap(
                        UsageIcon.draw(
                            this@BaseUsageTileService, session.percent,
                            cache.pinnedIconStyle(), cache.usageLeft(),
                        )
                    )
                }
                else -> {
                    state = Tile.STATE_INACTIVE
                    label = "Claude $profileLabel"
                    subtitle = "no data"
                }
            }
            updateTile()
        }
        // Opening the shade is a natural "check now" moment — but gated, so
        // flicking the shade open and shut doesn't hammer the API.
        if (System.currentTimeMillis() - snapshot.fetchedAt > FRESH_ENOUGH_MS) {
            Polling.refreshOnce(this, manual = false, profile = profile)
        }
    }

    // The Intent overload is deprecated but still the only option below API 34,
    // and minSdk is 31.
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("profile", profile.key)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, profile.ordinal, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

class PersonalTileService : BaseUsageTileService(Profile.PERSONAL)

class WorkTileService : BaseUsageTileService(Profile.WORK)
