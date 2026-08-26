package com.robin.claudeusage

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.robin.claudeusage.data.CredentialStore
import com.robin.claudeusage.data.UsageCache

/**
 * CCRM-33 (App Shortcuts): launcher long-press entries — one per signed-in account,
 * plus "Refresh now", capped at the launcher's own limit (CCRM-6 (Multi-Account)). Dynamic rather than static XML, because the labels follow the
 * user's renamed profile labels (`UsageCache.profileLabel`); republished on
 * every app launch and after a rename. Complements the Quick Settings tile
 * (CCRM-11 (Tile Reset Time)) rather than duplicating it: the tile is for the
 * shade, shortcuts are for the home screen.
 *
 * The profile entries reuse the same `"profile"` extra every other entry point
 * (tile, notification, alerts) already sends; "Refresh now" opens the app with
 * a `refresh` extra that MainActivity turns into a manual poll of every account.
 */
object Shortcuts {

    fun publish(context: Context) {
        try {
            val cache = UsageCache(context)
            // CCRM-6 (Multi-Account): configured accounts only, and capped. The launcher
            // rations these (typically 5 per activity) and today's unbounded list silently
            // overflowed past four accounts. "Refresh now" always keeps the last slot —
            // with five accounts you get four of them plus Refresh, not five and no
            // Refresh, because Refresh is the only entry the accounts can't substitute for.
            val cap = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
                .coerceAtLeast(2)
            val creds = CredentialStore(context)
            val profiles = cache.registry().all()
                .filter { creds.load(it) != null }
                .take(cap - 1)
            val shortcuts = profiles.map { profile ->
                ShortcutInfoCompat.Builder(context, "profile-${profile.key}")
                    .setShortLabel(cache.profileLabel(profile))
                    .setLongLabel("${cache.profileLabel(profile)} usage")
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .putExtra("profile", profile.key)
                    )
                    .build()
            } + ShortcutInfoCompat.Builder(context, "refresh")
                .setShortLabel("Refresh now")
                .setLongLabel("Refresh usage now")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_refresh))
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra("refresh", true)
                )
                .build()
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (_: Exception) {
            // Shortcuts are a convenience, never a crash source — some launchers
            // ration the slots and throw.
        }
    }
}
