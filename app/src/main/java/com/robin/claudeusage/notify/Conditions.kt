package com.robin.claudeusage.notify

import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.data.UsageCache
import com.robin.claudeusage.ui.Fmt

/**
 * CCBG-12 (Status Icon Swap): the two *conditions* — a sign-in nearing expiry, and usage
 * data that has gone stale — as data rather than as notifications.
 *
 * The distinction that matters is lifetime. A reset or a pace warning is an **event**: it
 * happens, it is read, it stops being interesting. These two are **states**: they are true
 * continuously until something fixes them, which meant a notification sitting in the shade
 * for hours or, in the sign-in case, up to seven days. That is precisely what makes Android
 * swap our live status-bar meter for the launcher icon, so the price of saying "your token
 * expires next week" was a status bar that read 72% all week.
 *
 * Folded, they render inside the pinned notification instead — it is already posted, so it
 * costs no second notification — and they disappear on their own when the condition
 * resolves, with nothing to dismiss.
 *
 * Both [Alerts] and [PinnedNotification] read this same derivation, so the decision to
 * suppress a notification and the decision to draw a strip can never disagree.
 */
object Conditions {

    /** Warn this far ahead of a known sign-in expiry — the pinned panel's own horizon. */
    private const val EXPIRY_HORIZON_MS = 7 * 86_400_000L

    /**
     * @param short one line for the collapsed row, which has room for nothing else.
     * @param detail the full sentence, shown in the expanded panel.
     * @param error true for a fault (stale data), false for a warning (expiry ahead).
     *   Drives the strip's tint and, for stale, the dimming of the numbers themselves.
     */
    data class Condition(
        val short: String,
        val title: String,
        val detail: String,
        val error: Boolean,
    )

    /**
     * Whether the pinned notification is in a position to carry [profile]'s conditions.
     *
     * False when it is switched off, or when it is showing the *other* profile — in either
     * case folding would not move the condition somewhere quieter, it would delete it. Those
     * cases keep posting a real notification exactly as before.
     */
    fun foldedInto(cache: UsageCache, profile: Profile): Boolean =
        cache.pinnedEnabled() && cache.pinnedProfile() == profile

    /** Every condition currently true for [profile], faults first. */
    fun forProfile(cache: UsageCache, profile: Profile): List<Condition> =
        listOfNotNull(stale(cache, profile), expiry(cache, profile))

    /**
     * Data is stale when polls are running but none has succeeded for hours. Reuses
     * [Alerts.STALE_DATA_MS] so the panel strip, the widget faces' stale treatment and the
     * old alert can never disagree about what "stale" means.
     */
    private fun stale(cache: UsageCache, profile: Profile): Condition? {
        val snapshot = cache.snapshot(profile)
        val fresh = snapshot.fetchedAt <= 0 ||
            System.currentTimeMillis() - snapshot.fetchedAt <= com.robin.claudeusage.alerts.Alerts.STALE_DATA_MS ||
            snapshot.lastStatus == "OK"
        if (fresh) return null
        val use24h = cache.use24hTime()
        return Condition(
            short = "Stale — nothing since ${Fmt.timeOnly(
                java.time.Instant.ofEpochMilli(snapshot.fetchedAt), use24h,
            )}",
            title = "Usage data is stale",
            detail = "Nothing fetched since ${Fmt.dayTimeWithAgo(snapshot.fetchedAt, use24h)}. " +
                "Last error: ${snapshot.lastStatus}",
            error = true,
        )
    }

    /**
     * The expiry date is only known from the pasted JSON and a rotation clears it, so this
     * is best-effort — no expiry known means no condition, not a false all-clear.
     *
     * Unlike the alert it replaces, there are no 7/3/1-day steps. Steps existed to avoid
     * re-notifying; a strip that is simply present while the condition holds needs no such
     * ceremony, and it means the warning cannot be dismissed into invisibility while it is
     * still true.
     */
    private fun expiry(cache: UsageCache, profile: Profile): Condition? {
        val expiry = cache.refreshExpiresAt(profile)
        if (expiry <= 0) return null
        val msLeft = expiry - System.currentTimeMillis()
        // Already dead is not a warning — the re-auth path owns that, and it still posts.
        if (msLeft <= 0 || msLeft > EXPIRY_HORIZON_MS) return null
        val use24h = cache.use24hTime()
        return Condition(
            short = "Sign-in expires in ${Fmt.dhm(expiry)}",
            title = "Sign-in expires in ${Fmt.dhm(expiry)}",
            detail = "Valid until ${Fmt.dateTime(expiry, use24h)}. " +
                "Paste a fresh token when convenient.",
            error = false,
        )
    }
}
