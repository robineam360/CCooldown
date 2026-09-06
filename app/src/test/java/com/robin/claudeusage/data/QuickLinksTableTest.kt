package com.robin.claudeusage.data

import com.robin.claudeusage.allowedLinkUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CCRM-26 (Quick Links) per provider (CCRM-57 (Provider Plumbing)). The scheme
 * guard itself lives in `QuickLinksTest`; this pins the **table** — that every
 * provider has links, that they are the right company's, and that each one is a URL
 * `openInBrowser` will actually launch rather than silently drop.
 */
class QuickLinksTableTest {

    @Test
    fun `every provider has a status link and an account link`() {
        for (provider in Provider.entries) {
            val links = QuickLinks.forProvider(provider)
            assertEquals("$provider link count", 2, links.size)
            // The status page is account-independent, so it skips the browser picker;
            // the account page is the one that needs the right browser's session.
            assertEquals("$provider status uses picker", false, links[0].usePicker)
            assertEquals("$provider account uses picker", true, links[1].usePicker)
        }
    }

    @Test
    fun `every url passes the launch guard`() {
        for (provider in Provider.entries) {
            for (link in QuickLinks.forProvider(provider)) {
                assertTrue("${link.label}: ${link.url}", allowedLinkUrl(link.url))
                assertTrue("${link.label} is not https", link.url.startsWith("https://"))
                assertTrue("${link.label} has no label", link.label.isNotBlank())
            }
        }
    }

    @Test
    fun `claude keeps exactly the destinations it had before the table`() {
        assertEquals(
            listOf("https://status.anthropic.com", "https://claude.ai/settings/usage"),
            QuickLinks.forProvider(Provider.CLAUDE).map { it.url },
        )
    }

    @Test
    fun `chatgpt goes to openai, never to anthropic`() {
        assertEquals(
            listOf("https://status.openai.com", "https://chatgpt.com/#settings"),
            QuickLinks.forProvider(Provider.CHATGPT).map { it.url },
        )
    }

    @Test
    fun `gemini has somewhere to point before CCRM-55 unblocks`() {
        assertEquals(
            listOf("https://status.cloud.google.com", "https://antigravity.google"),
            QuickLinks.forProvider(Provider.ANTIGRAVITY).map { it.url },
        )
    }

    /**
     * The whole point of the table: no account's escape hatch may send the user to
     * a different company's status page.
     */
    @Test
    fun `no provider's links mention another provider's host`() {
        val hostWords = mapOf(
            Provider.CLAUDE to listOf("anthropic", "claude"),
            Provider.CHATGPT to listOf("openai", "chatgpt"),
            Provider.ANTIGRAVITY to listOf("google", "antigravity"),
        )
        for (provider in Provider.entries) {
            val mine = hostWords.getValue(provider)
            val theirs = hostWords.filterKeys { it != provider }.values.flatten()
            for (link in QuickLinks.forProvider(provider)) {
                val url = link.url.lowercase()
                assertTrue("${link.url} names none of $mine", mine.any { url.contains(it) })
                for (word in theirs) {
                    assertEquals("${link.url} names $word", false, url.contains(word))
                }
            }
        }
    }

    @Test
    fun `the error notice's button names this account's vendor`() {
        assertEquals("Check Anthropic status", QuickLinks.statusLabel(Provider.CLAUDE))
        assertEquals("Check OpenAI status", QuickLinks.statusLabel(Provider.CHATGPT))
        assertEquals("Check Google status", QuickLinks.statusLabel(Provider.ANTIGRAVITY))
        assertEquals("https://status.openai.com", QuickLinks.statusUrl(Provider.CHATGPT))
    }
}
