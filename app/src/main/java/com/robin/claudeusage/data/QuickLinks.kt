package com.robin.claudeusage.data

/**
 * CCRM-26 (Quick Links) per provider, the table CCRM-57 (Provider Plumbing) asks
 * for. Two escapes per account, always in the same order and with the same jobs:
 *
 * 1. **Status** — "is it me or is it them", the one the error notice links to.
 *    Opened in the default browser: a status page is account-independent.
 * 2. **Dashboard** — the provider's own usage or settings page. Opened through the
 *    browser picker, because *which* browser holds this profile's session is the
 *    same question the sign-in asks.
 *
 * Every URL here is a compile-time constant and https, so `allowedLinkUrl` never
 * has anything to reject; the guard stays in front of the launch regardless.
 */
data class QuickLink(
    val label: String,
    val url: String,
    /** True for the account-scoped link — see the class doc. */
    val usePicker: Boolean,
)

object QuickLinks {

    fun forProvider(provider: Provider): List<QuickLink> = when (provider) {
        Provider.CLAUDE -> listOf(
            QuickLink("Anthropic status", "https://status.anthropic.com", usePicker = false),
            QuickLink("Usage dashboard", "https://claude.ai/settings/usage", usePicker = true),
        )
        Provider.CHATGPT -> listOf(
            QuickLink("OpenAI status", "https://status.openai.com", usePicker = false),
            QuickLink("ChatGPT settings", "https://chatgpt.com/#settings", usePicker = true),
        )
        // Present before CCRM-55 (Antigravity Account) so the table is total and the
        // error notice has somewhere to point the day a Gemini account can exist.
        Provider.ANTIGRAVITY -> listOf(
            QuickLink("Google Cloud status", "https://status.cloud.google.com", usePicker = false),
            QuickLink("Antigravity", "https://antigravity.google", usePicker = true),
        )
    }

    /** The "is it them?" destination — what the main screen's error notice opens. */
    fun statusUrl(provider: Provider): String = forProvider(provider).first().url

    /** "Check Anthropic status" / "Check OpenAI status" — the notice's button. */
    fun statusLabel(provider: Provider): String = "Check ${provider.vendor} status"
}
