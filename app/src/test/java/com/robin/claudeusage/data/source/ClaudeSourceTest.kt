package com.robin.claudeusage.data.source

import com.robin.claudeusage.data.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CCRM-53 (Provider Model): [ClaudeSource] must reproduce the exact refresh
 * behaviour `UsageRepository.refreshAccessToken` had before this seam existed —
 * a Claude account has to behave byte-identically.
 */
class ClaudeSourceTest {

    private val previous = Credentials("old-access", "old-refresh", 1000L, accountId = null)

    @Test
    fun `keeps the old refresh token when none is rotated`() {
        val grant = ClaudeSource.parseTokenResponse(
            """{"access_token":"new-access","expires_in":3600}""",
            previous,
        )
        assertEquals("new-access", grant!!.creds.accessToken)
        assertEquals("old-refresh", grant.creds.refreshToken)
    }

    @Test
    fun `a rotated refresh token replaces the old one`() {
        val grant = ClaudeSource.parseTokenResponse(
            """{"access_token":"new-access","refresh_token":"rotated","expires_in":3600}""",
            previous,
        )
        assertEquals("rotated", grant!!.creds.refreshToken)
    }

    @Test
    fun `expires_in becomes an absolute expiresAt`() {
        val before = System.currentTimeMillis()
        val grant = ClaudeSource.parseTokenResponse(
            """{"access_token":"new-access","expires_in":3600}""",
            previous,
        )
        assertTrue(grant!!.creds.expiresAt >= before + 3600 * 1000)
    }

    @Test
    fun `no access token in the body yields no grant`() {
        assertNull(ClaudeSource.parseTokenResponse("""{"error":"invalid_grant"}""", previous))
    }

    @Test
    fun `isAuthFailure(403) is false — only 401 means the token is dead`() {
        assertFalse(ClaudeSource.isAuthFailure(403))
        assertTrue(ClaudeSource.isAuthFailure(401))
    }
}
