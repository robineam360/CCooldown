package com.robin.claudeusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins CCRM-27 (Error Taxonomy)'s decode paths: the tolerant key decode, and the
 * one-upgrade migration guess that maps every status string the app has ever
 * persisted onto the right kind — so an old stored failure never wears the
 * INTERNAL copy for a status the taxonomy actually knows.
 */
class ErrorKindTest {

    @Test
    fun `key decode is tolerant`() {
        assertEquals(ErrorKind.NETWORK, ErrorKind.fromKey("network"))
        assertEquals(ErrorKind.RATE_LIMITED, ErrorKind.fromKey("rateLimited"))
        assertEquals(ErrorKind.INTERNAL, ErrorKind.fromKey("gremlins"))
        assertEquals(ErrorKind.INTERNAL, ErrorKind.fromKey(null))
    }

    @Test
    fun `every persisted status string maps to its kind`() {
        // The exact strings doFetch/authFailure have ever written.
        assertEquals(ErrorKind.AUTH, ErrorKind.fromStatus("No token set"))
        assertEquals(ErrorKind.AUTH, ErrorKind.fromStatus("Re-auth needed"))
        assertEquals(ErrorKind.AUTH, ErrorKind.fromStatus("Re-auth needed — renewal kept failing"))
        assertEquals(ErrorKind.RATE_LIMITED, ErrorKind.fromStatus("Rate limited (429)"))
        assertEquals(ErrorKind.SERVER, ErrorKind.fromStatus("HTTP 529"))
        assertEquals(ErrorKind.NETWORK, ErrorKind.fromStatus("Network: Unable to resolve host"))
        assertEquals(
            ErrorKind.NETWORK,
            ErrorKind.fromStatus("Token refresh failed (timeout) — will retry"),
        )
        assertEquals(ErrorKind.INVALID_RESPONSE, ErrorKind.fromStatus("Unrecognized response shape"))
        assertEquals(ErrorKind.INTERNAL, ErrorKind.fromStatus("Never fetched"))
    }

    @Test
    fun `only auth and internal are severe`() {
        assertEquals(
            setOf(ErrorKind.AUTH, ErrorKind.INTERNAL),
            ErrorKind.entries.filter { it.severe }.toSet(),
        )
    }

    @Test
    fun `every kind carries remediation copy and a short label`() {
        for (kind in ErrorKind.entries) {
            assertTrue(kind.title.isNotBlank())
            assertTrue(kind.short.isNotBlank())
            // Short labels fit a widget caption — one line, no punctuation freight.
            assertTrue(kind.short.length <= 24)
        }
    }
}
