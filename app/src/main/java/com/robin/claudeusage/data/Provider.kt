package com.robin.claudeusage.data

/**
 * The three services Cooldown tracks (CCRM-53 (Provider Model)) — never a free-text
 * or user-extensible list. [vendor] is for error copy ("Couldn't reach OpenAI");
 * [themeName] names a [com.robin.claudeusage.ui.Palette] option (wired up in
 * CCRM-56 (Provider Identity)); [appPackage] is the provider's own Android app,
 * for the pinned notification's optional "open the provider app" tap target.
 */
enum class Provider(
    val key: String,
    val displayName: String,
    val vendor: String,
    val themeName: String,
    val appPackage: String,
) {
    CLAUDE("claude", "Claude", "Anthropic", "Claude Orange", "com.anthropic.claude"),
    CHATGPT("chatgpt", "ChatGPT", "OpenAI", "ChatGPT Green", "com.openai.chatgpt"),
    ANTIGRAVITY("antigravity", "Gemini", "Google", "Gemini Blue", "com.google.android.apps.bard");

    companion object {
        /** Absent or unrecognised → CLAUDE, so every pre-CCRM-53 install reads unchanged. */
        fun fromKey(key: String?): Provider = entries.firstOrNull { it.key == key } ?: CLAUDE
    }
}
