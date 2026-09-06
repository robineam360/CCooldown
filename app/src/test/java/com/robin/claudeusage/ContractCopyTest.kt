package com.robin.claudeusage

import com.robin.claudeusage.data.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The copy-drift half of CCRM-37 (Contract Tests), built to the slice CCRM-57
 * (Provider Plumbing) asks for: **three provider names, three vendors and four
 * trademark lines.** CCRM-37 itself stays Planned — its registry-contract grep and
 * visual-parity assertions are not here.
 *
 * Two of the three subjects are ordinary values, so they are asserted directly. The
 * fourth — the About card's disclaimer — is a Compose string literal with no
 * Robolectric to render it, so this reads the **raw source**, which is the method
 * CCRM-37 describes (OpenQuota's `uiLanguage.test.ts` does the same). Coarse, but it
 * fails the moment someone drops a trademark while editing the sentence around it,
 * which is the whole job.
 *
 * The README's notice still names Anthropic alone and is deliberately not asserted
 * here: it is rewritten in the v1.5 release step, and a test that fails until then
 * would be noise rather than a guard.
 */
class ContractCopyTest {

    /** Gradle runs unit tests from the module dir; be indifferent to which. */
    private fun source(relative: String): String {
        val candidates = listOf(File("app/$relative"), File(relative))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("couldn't find $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    private val about by lazy { source("src/main/java/com/robin/claudeusage/SettingsScreen.kt") }

    // --- the three names ---

    @Test
    fun `the app tracks exactly three services, named as their users name them`() {
        assertEquals(
            listOf("Claude", "ChatGPT", "Gemini"),
            Provider.entries.map { it.displayName },
        )
        // The key is the persisted value; it never follows a rename of the name.
        assertEquals(
            listOf("claude", "chatgpt", "antigravity"),
            Provider.entries.map { it.key },
        )
    }

    // --- the three vendors ---

    @Test
    fun `each service names the company whose server the app talks to`() {
        assertEquals(
            listOf("Anthropic", "OpenAI", "Google"),
            Provider.entries.map { it.vendor },
        )
    }

    @Test
    fun `a display name is never used where the vendor is meant`() {
        // "Couldn't reach Claude" would name the model, not the company that's down.
        for (provider in Provider.entries) {
            assertTrue(
                "${provider.vendor} must not be the display name",
                provider.vendor != provider.displayName,
            )
        }
    }

    // --- the four trademark lines ---

    @Test
    fun `the About disclaimer names all four marks and all three owners`() {
        for (claim in listOf(
            "Not affiliated with, endorsed by, or supported by Anthropic, OpenAI or ",
            "\\\"Claude\\\" is a trademark of Anthropic, PBC.",
            "\\\"ChatGPT\\\" is a trademark of ",
            "\\\"Gemini\\\" and \\\"Antigravity\\\" are trademarks of Google LLC.",
        )) {
            assertTrue("About disclaimer lost: $claim", about.contains(claim))
        }
    }

    /**
     * Four marks, not three: Antigravity is Google's product name and Gemini is the
     * model, and the disclaimer has to claim neither as ours. It says so before
     * CCRM-55 (Antigravity Account) is built, because the greyed row in the
     * Add-account sheet already puts both words on screen.
     */
    @Test
    fun `the disclaimer covers Antigravity even though no Gemini account exists yet`() {
        assertTrue(about.contains("Antigravity"))
    }

    /**
     * The mark drawables are hand-traced from each company's public artwork, so each
     * file has to say whose it is — the header is the only place the provenance
     * lives once the paths are in the repo.
     */
    @Test
    fun `every provider mark drawable credits its owner`() {
        val owners = mapOf(
            "ic_provider_claude.xml" to "Anthropic",
            "ic_provider_chatgpt.xml" to "OpenAI",
            "ic_provider_gemini.xml" to "Google",
        )
        for ((file, owner) in owners) {
            val xml = source("src/main/res/drawable/$file")
            assertTrue("$file doesn't credit $owner", xml.contains(owner))
            assertTrue("$file doesn't say trademark", xml.contains("trademark"))
        }
    }
}
