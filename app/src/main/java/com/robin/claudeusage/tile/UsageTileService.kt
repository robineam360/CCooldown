package com.robin.claudeusage.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.robin.claudeusage.MainActivity
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.work.Polling

/** Quick Settings tile: 5-hour utilization at a glance from any screen. */
abstract class BaseUsageTileService(private val profile: Profile) : TileService() {

    companion object {
        private const val FRESH_ENOUGH_MS = 3 * 60_000L
    }

    override fun onStartListening() {
        val snapshot = UsageCache(this).snapshot(profile)
        val session = snapshot.data?.session
        qsTile?.apply {
            when {
                session?.percent != null -> {
                    state = Tile.STATE_ACTIVE
                    label = "${profile.label} ${session.percent.toInt()}%"
                    subtitle = "7d ${snapshot.data?.weekly?.percent?.toInt() ?: 0}%"
                }
                else -> {
                    state = Tile.STATE_INACTIVE
                    label = "Claude ${profile.label}"
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

    @Suppress("DEPRECATION")
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
