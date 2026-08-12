package com.robin.claudeusage.data

/**
 * What the account card's sign-in expiry line should say (CCRM-16).
 *
 * A native sign-in's token response omits the refresh-family expiry, so the app
 * shows sign-in time + a flat ~30-day guess ([OAuthSignIn.ESTIMATED_FAMILY_MS]).
 * That guess has never been verified against a real expiry, so once renewal has
 * demonstrably died the date must stop rendering as if it were knowledge — a
 * confident "expires around <date>" next to a Needs-re-auth chip is the app
 * contradicting itself. Pure logic so it's testable; the UI maps the result to
 * strings.
 *
 * Keying on [AuthState.REAUTH_NEEDED] alone covers both death signals: a direct
 * 400–403 on refresh sets it immediately, and a failing-renewal streak crossing
 * the stuck threshold escalates to it in `UsageRepository.authFailure`.
 */
object SignInExpiry {

    const val DAY_MS = 24 * 60 * 60_000L

    /**
     * A death this far before the estimated date discredits the estimate; anything
     * closer reads as the family plausibly reaching its age limit. Phrasing only —
     * the date line is dropped either way.
     */
    const val MATERIALLY_EARLY_MS = 48 * 60 * 60_000L

    sealed interface Line {
        /** Native sign-in, renewal healthy: "expires around <date>". */
        data class Estimated(val expiresAt: Long) : Line

        /** Pasted desktop token with a server-given date: "valid until <date>". */
        data class Exact(val expiresAt: Long) : Line

        /**
         * Renewal is dead on a native sign-in, so the estimate no longer applies.
         * [daysObserved] is the rounded sign-in→death interval, null when the
         * sign-in time was never stamped. [earlierThanEstimate] only picks the
         * phrasing — "earlier than the estimate" vs "likely reached its age limit".
         */
        data class RenewalDead(val daysObserved: Long?, val earlierThanEstimate: Boolean) : Line

        data object None : Line
    }

    /**
     * @param signInAt when the current family started (the credential's addedAt); 0 = unknown.
     * @param deadAt when renewal was observed dead — the fail-streak start when one is
     *   recorded, else the failure's own timestamp.
     */
    fun line(
        authState: AuthState,
        estimated: Boolean,
        refreshExpiresAt: Long,
        signInAt: Long,
        deadAt: Long,
        now: Long,
    ): Line {
        if (authState == AuthState.REAUTH_NEEDED && estimated) {
            val days =
                if (signInAt in 1..deadAt) (deadAt - signInAt + DAY_MS / 2) / DAY_MS else null
            val earlier = refreshExpiresAt > 0 && refreshExpiresAt - deadAt > MATERIALLY_EARLY_MS
            return Line.RenewalDead(days, earlier)
        }
        if (refreshExpiresAt > now) {
            return if (estimated) Line.Estimated(refreshExpiresAt) else Line.Exact(refreshExpiresAt)
        }
        return Line.None
    }
}
