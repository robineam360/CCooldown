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
import com.robin.claudeusage.data.ProfileRegistry
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.data.Projection
import com.robin.claudeusage.ui.Fmt
import com.robin.claudeusage.ui.UsageIcon
import com.robin.claudeusage.ui.elapsedPercent
import com.robin.claudeusage.work.Polling

/**
 * Quick Settings tile: 5-hour utilization at a glance from any screen.
 *
 * Bound to a **slot**, not to an account (CCRM-6 (Multi-Account)). A QS tile is a
 * statically declared `<service>` and Android will not let the list be driven at runtime,
 * so there is a fixed pool of four — slots 0–3. A tile whose slot has no account reports
 * `STATE_UNAVAILABLE` rather than guessing, and because slots are never reused it can never
 * come back to life holding a *different* account's numbers.
 *
 * The documented limit: a fifth account works on every surface except a Quick Settings tile.
 */
abstract class BaseUsageTileService(private val slot: Int) : TileService() {

    companion object {
        private const val FRESH_ENOUGH_MS = 3 * 60_000L
    }

    private fun profileForSlot(): Profile? =
        ProfileRegistry(this).all().firstOrNull { it.slot == slot }

    override fun onStartListening() {
        val profile = profileForSlot() ?: run {
            // No account in this slot — never placed, or removed since. Greyed out and
            // inert, which is the only honest thing a tile bound to nothing can be.
            qsTile?.apply {
                state = Tile.STATE_UNAVAILABLE
                label = "Claude account ${slot + 1}"
                subtitle = "no account"
                updateTile()
            }
            return
        }
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
                    // This surface flattens a bitmap to one tint, measured in CCRM-49
                    // (Glyph Legibility), so it gets the alpha-mask rendering.
                    //
                    // CCRM-51 (Rails Gauge) is why that path still says everything it
                    // needs to: the needle is a *shape*, so "ahead of pace" survives
                    // here even though the red slice cannot, and the 7-day rung ladder
                    // opens with an *outlined* dot rather than a filled one — a filled
                    // dot would arrive fully inked and read as the "spent" rung. The
                    // remaining collapse is ABOVE/SPENT merging at full alpha: which
                    // rung is lost, "the week needs a look" is kept.
                    icon = Icon.createWithBitmap(
                        UsageIcon.draw(
                            this@BaseUsageTileService, session.percent,
                            cache.pinnedIconStyle(), cache.usageLeft(),
                            sessionElapsed = elapsedPercent(session, Projection.SESSION_MS),
                            weeklyPct = snapshot.data?.weekly?.percent,
                            weeklyElapsed = elapsedPercent(
                                snapshot.data?.weekly, Projection.WEEKLY_MS,
                            ),
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
        val profile = profileForSlot() ?: return
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("profile", profile.key)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, profile.slot, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

// The two original class names are kept deliberately: renaming a declared service breaks
// every tile the user has already placed. They are simply rebound to slots 0 and 1, which
// are pinned to `personal`/`work` for exactly this reason.
class PersonalTileService : BaseUsageTileService(0)

class WorkTileService : BaseUsageTileService(1)

class Slot2TileService : BaseUsageTileService(2)

class Slot3TileService : BaseUsageTileService(3)
