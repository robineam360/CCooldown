package com.robin.claudeusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Snapshot.data] re-parses the cached body on **read**, so it needs to know whose
 * body it is (CCRM-54 (ChatGPT Account)).
 *
 * This is the regression test for a defect the device pass found on 2026-09-06:
 * CCRM-53 (Provider Model) routed the fetch-time parse through `UsageSource` but left
 * this read-time one hardcoded to Anthropic's `UsageParser`. The effect was quietly
 * awful — a ChatGPT account signed in, fetched HTTP 200, wrote "Last success" with a
 * timestamp, and then showed "No data yet" on the tab, every widget, the tile and the
 * pinned notification, because each of them reads `snapshot.data`.
 */
class SnapshotTest {

    private val chatGptBody: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("chatgpt-usage-2026-09.json")!!
            .bufferedReader().readText()
    }

    private val claudeBody = """
        {"five_hour":{"utilization":12.0,"resets_at":"2026-09-06T20:19:59.000000+00:00"},
        "seven_day":{"utilization":31.0,"resets_at":"2026-09-11T04:29:59.000000+00:00"}}
    """.trimIndent().replace("\n", "")

    private fun snapshot(body: String, provider: Provider) = Snapshot(
        rawJson = body,
        fetchedAt = 1L,
        lastStatus = "OK",
        lastAttemptAt = 1L,
        authState = AuthState.OK,
        provider = provider,
    )

    @Test
    fun `a ChatGPT body reads back through the ChatGPT parser`() {
        val data = snapshot(chatGptBody, Provider.CHATGPT).data
        assertNotNull("a successful fetch must not render as 'No data yet'", data)
        assertEquals(9.0, data!!.session!!.percent!!, 0.001)
        assertEquals(79.0, data.weekly!!.percent!!, 0.001)
    }

    @Test
    fun `the same body under the Claude parser is exactly the defect`() {
        // Kept as a test rather than a comment: it is the one line that shows *why*
        // the provider has to travel with the snapshot.
        assertNull(snapshot(chatGptBody, Provider.CLAUDE).data)
    }

    @Test
    fun `a Claude body still reads back unchanged`() {
        val data = snapshot(claudeBody, Provider.CLAUDE).data
        assertNotNull(data)
        assertEquals(12.0, data!!.session!!.percent!!, 0.001)
        assertEquals(31.0, data.weekly!!.percent!!, 0.001)
    }

    @Test
    fun `the default provider is Claude, so every existing call site is unchanged`() {
        val legacy = Snapshot(
            rawJson = claudeBody,
            fetchedAt = 1L,
            lastStatus = "OK",
            lastAttemptAt = 1L,
            authState = AuthState.OK,
        )
        assertEquals(Provider.CLAUDE, legacy.provider)
        assertNotNull(legacy.data)
    }

    @Test
    fun `a provider with no source yet reads as no data rather than throwing`() {
        // Sources.of(ANTIGRAVITY) throws NotImplementedError until CCRM-55
        // (Antigravity Account) lands, and `data` is a lazy the whole UI touches.
        assertNull(snapshot(chatGptBody, Provider.ANTIGRAVITY).data)
    }

    @Test
    fun `no body is no data, whoever the provider is`() {
        for (p in Provider.entries) {
            assertNull(
                Snapshot(null, 0L, "Never fetched", 0L, AuthState.NO_CREDENTIALS, provider = p).data,
            )
        }
    }
}
