package com.robin.claudeusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `every kind carries remediation copy and a short label for every provider`() {
        for (provider in Provider.entries) {
            for (kind in ErrorKind.entries) {
                assertTrue(kind.title(provider).isNotBlank())
                assertTrue(kind.short(provider).isNotBlank())
                // Short labels fit a widget caption — one line, no punctuation freight.
                assertTrue(kind.short(provider).length <= 24)
            }
        }
    }

    // --- CCRM-57 (Provider Plumbing): the copy names the right company ---

    @Test
    fun `the two kinds about the other end name that provider's vendor`() {
        assertEquals(
            "Couldn't reach Anthropic — check your connection, or see if it's them.",
            ErrorKind.NETWORK.title(Provider.CLAUDE),
        )
        assertEquals(
            "Couldn't reach OpenAI — check your connection, or see if it's them.",
            ErrorKind.NETWORK.title(Provider.CHATGPT),
        )
        assertEquals("can't reach OpenAI", ErrorKind.NETWORK.short(Provider.CHATGPT))
        assertEquals(
            "OpenAI's server errored — usually theirs, usually brief.",
            ErrorKind.SERVER.title(Provider.CHATGPT),
        )
        assertEquals(
            "Google's server errored — usually theirs, usually brief.",
            ErrorKind.SERVER.title(Provider.ANTIGRAVITY),
        )
    }

    /**
     * A ChatGPT account must never be told to check Anthropic. This is the assertion
     * that would have caught the whole class of defect CCRM-57 exists to clear.
     */
    @Test
    fun `no kind ever names another provider's vendor`() {
        for (provider in Provider.entries) {
            val others = Provider.entries.filter { it != provider }.map { it.vendor }
            for (kind in ErrorKind.entries) {
                val copy = kind.title(provider) + " " + kind.short(provider)
                for (other in others) {
                    assertFalse("$kind on $provider mentions $other", copy.contains(other))
                }
            }
        }
    }

    /**
     * The fix for a dead sign-in is the same sentence for everyone — it names the
     * flow, not the company — so it must stay vendor-free.
     */
    @Test
    fun `the auth fix reads the same for every provider`() {
        assertEquals(
            setOf("Sign-in stopped working — re-sign in from Settings."),
            Provider.entries.map { ErrorKind.AUTH.title(it) }.toSet(),
        )
    }

    /** The persisted key is the storage contract and must not move with the copy. */
    @Test
    fun `keys are unchanged by the provider split`() {
        assertEquals(
            listOf("auth", "rateLimited", "network", "server", "invalidResponse", "internal"),
            ErrorKind.entries.map { it.key },
        )
    }
}
