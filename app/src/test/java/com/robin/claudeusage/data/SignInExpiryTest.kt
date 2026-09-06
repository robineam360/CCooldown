package com.robin.claudeusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInExpiryTest {

    private val day = SignInExpiry.DAY_MS
    private val signIn = 1_000_000_000_000L
    private val estimate = signIn + OAuthSignIn.ESTIMATED_FAMILY_MS

    private fun line(
        authState: AuthState = AuthState.OK,
        estimated: Boolean = true,
        refreshExpiresAt: Long = estimate,
        signInAt: Long = signIn,
        deadAt: Long = 0L,
        now: Long = signIn + 5 * day,
    ) = SignInExpiry.line(authState, estimated, refreshExpiresAt, signInAt, deadAt, now)

    @Test
    fun `healthy native sign-in shows the estimated date`() {
        assertEquals(SignInExpiry.Line.Estimated(estimate), line())
    }

    @Test
    fun `healthy pasted token shows the exact date`() {
        assertEquals(SignInExpiry.Line.Exact(estimate), line(estimated = false))
    }

    @Test
    fun `a passed date with healthy auth shows nothing`() {
        assertEquals(SignInExpiry.Line.None, line(now = estimate + day))
    }

    @Test
    fun `renewal death well before the estimate discredits it`() {
        val dead = signIn + 9 * day
        val result = line(authState = AuthState.REAUTH_NEEDED, deadAt = dead, now = dead)
        assertTrue(result is SignInExpiry.Line.RenewalDead)
        result as SignInExpiry.Line.RenewalDead
        assertEquals(9L, result.daysObserved)
        assertTrue(result.earlierThanEstimate)
    }

    @Test
    fun `renewal death within the tolerance of the estimate is an age-limit death`() {
        val dead = estimate - SignInExpiry.MATERIALLY_EARLY_MS / 2
        val result = line(authState = AuthState.REAUTH_NEEDED, deadAt = dead, now = dead)
            as SignInExpiry.Line.RenewalDead
        assertFalse(result.earlierThanEstimate)
    }

    @Test
    fun `renewal death after the estimate passed still renders the dead line`() {
        // The old date gate (refreshExpiresAt > now) would have hidden the line
        // entirely here — dying of old age must still be said out loud.
        val dead = estimate + day
        val result = line(authState = AuthState.REAUTH_NEEDED, deadAt = dead, now = dead)
            as SignInExpiry.Line.RenewalDead
        assertFalse(result.earlierThanEstimate)
        assertEquals(31L, result.daysObserved)
    }

    @Test
    fun `an unstamped sign-in time yields no interval`() {
        val result = line(authState = AuthState.REAUTH_NEEDED, signInAt = 0L, deadAt = signIn + day)
            as SignInExpiry.Line.RenewalDead
        assertNull(result.daysObserved)
    }

    @Test
    fun `a death timestamp before the sign-in yields no interval`() {
        // Clock weirdness or a stale fail-streak from before a re-sign-in: an
        // impossible negative interval must not render as a huge positive one.
        val result = line(authState = AuthState.REAUTH_NEEDED, deadAt = signIn - day)
            as SignInExpiry.Line.RenewalDead
        assertNull(result.daysObserved)
    }

    @Test
    fun `the interval rounds to the nearest day`() {
        val dead = signIn + 29 * day + (day * 6 / 10) // 29.6 days
        val result = line(authState = AuthState.REAUTH_NEEDED, deadAt = dead, now = dead)
            as SignInExpiry.Line.RenewalDead
        assertEquals(30L, result.daysObserved)
    }

    @Test
    fun `a missing estimate date cannot claim the death was early`() {
        val result = line(
            authState = AuthState.REAUTH_NEEDED,
            refreshExpiresAt = 0L,
            deadAt = signIn + day,
        ) as SignInExpiry.Line.RenewalDead
        assertFalse(result.earlierThanEstimate)
    }

    @Test
    fun `a dead pasted token keeps the exact-date behaviour`() {
        // Scoped deliberately: CCRM-16 is about the estimate. A pasted token's
        // server-given date stays rendered as-is; the re-auth chip carries the news.
        val result = line(authState = AuthState.REAUTH_NEEDED, estimated = false, deadAt = signIn + day)
        assertEquals(SignInExpiry.Line.Exact(estimate), result)
    }

    /**
     * CCRM-57 (Provider Plumbing): the ~30-day family life is Anthropic's, inferred
     * and never verified. A ChatGPT sign-in leaves `refreshExpiresAt` at 0 and
     * `estimated` false on purpose — every expiry surface (this line, the pinned
     * panel's strip, the expiry alert) is gated on that figure being set, so all
     * three must fall silent rather than invent a date for OpenAI's token family.
     */
    @Test
    fun `an account with no reported family life draws no expiry line`() {
        assertEquals(
            SignInExpiry.Line.None,
            line(refreshExpiresAt = 0L, estimated = false),
        )
        // …and stays silent once that sign-in dies: there was never a date to discredit.
        assertEquals(
            SignInExpiry.Line.None,
            line(
                authState = AuthState.REAUTH_NEEDED,
                estimated = false,
                refreshExpiresAt = 0L,
                deadAt = signIn + day,
            ),
        )
    }
}
