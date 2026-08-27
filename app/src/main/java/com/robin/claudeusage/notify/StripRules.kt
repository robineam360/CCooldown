package com.robin.claudeusage.notify

/**
 * The two decisions a folded strip needs, as pure functions: **how long it lives**
 * (CCBG-18 (Strip Lifetime Stamp)) and **which toggle can revoke it**
 * (CCBG-17 (Strip Revocation)).
 *
 * Both used to be implicit — the lifetime frozen into a stored timestamp at fire time, the
 * revocation nowhere at all — which is exactly why neither could be tested or reasoned
 * about. Kept here, apart from the preference reads in [Conditions] and
 * [com.robin.claudeusage.data.UsageCache], for the same reason
 * [com.robin.claudeusage.data.Projection.paceStep] is: the arithmetic is the part that can
 * be wrong, and the part worth pinning down in tests.
 */
object StripRules {

    /**
     * When a strip folded at [firedAt] and stamped to expire at [stampedExpiresAt] should
     * actually leave the panel, under the current "keep alerts in the shade for"
     * [lifetime].
     *
     * The rule is **shorten only**. `auto` means "until the window this alert is about
     * resets", which is what the stamp already holds, so it is returned untouched. An
     * explicit choice is capped by the stamp, so a longer chip can never *extend* a strip
     * that was deliberately made short — a reset strip's fixed half hour
     * (`Alerts.RESET_STRIP_MS`) stays half an hour even under "1h".
     */
    fun expiry(firedAt: Long, stampedExpiresAt: Long, lifetime: String): Long {
        val chosen = explicitLifetimeMs(lifetime) ?: return stampedExpiresAt
        return minOf(stampedExpiresAt, firedAt + chosen)
    }

    /**
     * How long an explicitly-chosen "keep alerts in the shade for" lasts, or null for
     * `auto` — which has no fixed length, being "until the window resets". Shared with
     * [com.robin.claudeusage.alerts.Alerts.eventTimeout], which stamps the same setting
     * onto a standalone notification's `setTimeoutAfter`, so the shade and the panel can
     * never disagree about what "15m" means.
     */
    fun explicitLifetimeMs(lifetime: String): Long? = when (lifetime) {
        "15m" -> 15 * 60_000L
        "30m" -> 30 * 60_000L
        "1h" -> 60 * 60_000L
        else -> null
    }

    /**
     * A folded event's strip title, labelled with the account it belongs to —
     * CCBG-16 (Stale Strip Label).
     *
     * The stored [storedTitle] is the alert sentence with no account name in it, so the
     * name is composed here, at render time, from the live registry label. That is the
     * whole fix: the frozen copy could not follow a rename, and this cannot fail to.
     * Deliberately the same `"label: sentence"` shape
     * [Conditions.labelled][com.robin.claudeusage.notify.Conditions] gives the live
     * condition strips, so events and faults read as one stack.
     *
     * @param profileKey the event's owning account key, or `""` for a record written
     *   before this fix — whose title already carries a name, possibly a stale one, and is
     *   therefore returned untouched rather than prefixed twice. Those records live at most
     *   one `alertLifetime` and need no migration.
     */
    fun stripTitle(storedTitle: String, profileKey: String, label: String): String =
        if (profileKey.isEmpty() || label.isEmpty()) storedTitle else "$label: $storedTitle"

    /** Which setting decides whether a strip of a given kind is still allowed on screen. */
    enum class Gate {
        /** Nothing beyond the owning profile's own alerts toggle. */
        PROFILE,
        PACE,
        SESSION_THRESHOLD,
        WEEKLY_THRESHOLD,
        MODEL_CAP_THRESHOLD,

        /** A reset ping — [resetWindow] names which window's mode governs it. */
        RESET,
    }

    /**
     * The gate for a stored [FoldedEvent.kind][com.robin.claudeusage.data.UsageCache.FoldedEvent.kind].
     *
     * An unrecognised kind — a record written by a newer build, read by an older one —
     * falls to [Gate.PROFILE] rather than being dropped: a strip nobody can explain is a
     * smaller failure than one silently swallowed.
     */
    fun gateFor(kind: String): Gate = when {
        kind.startsWith("pace.") -> Gate.PACE
        kind.startsWith("reset.") -> Gate.RESET
        kind == "sessionAlert" -> Gate.SESSION_THRESHOLD
        kind == "weeklyAlert" -> Gate.WEEKLY_THRESHOLD
        kind.startsWith("modelAlert.") -> Gate.MODEL_CAP_THRESHOLD
        else -> Gate.PROFILE
    }

    /** The window name inside a `reset.<window>` kind, or null for any other kind. */
    fun resetWindow(kind: String): String? =
        if (kind.startsWith("reset.")) kind.removePrefix("reset.").takeIf { it.isNotEmpty() }
        else null
}
