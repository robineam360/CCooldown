package com.robin.claudeusage.data

/**
 * The three services Cooldown tracks (CCRM-53 (Provider Model)) — never a free-text
 * or user-extensible list. [vendor] is for error copy ("Couldn't reach OpenAI");
 * [themeName] names a [com.robin.claudeusage.ui.Palette] option, wired up in
 * CCRM-56 (Provider Identity) — until then [key] resolution never returns anything
 * but `CLAUDE`, so `Palette.byName` falling back to its first option is harmless.
 */
enum class Provider(val key: String, val displayName: String, val vendor: String, val themeName: String) {
    CLAUDE("claude", "Claude", "Anthropic", "Claude Orange"),
    CHATGPT("chatgpt", "ChatGPT", "OpenAI", "ChatGPT Green"),
    ANTIGRAVITY("antigravity", "Gemini", "Google", "Gemini Blue");

    companion object {
        /** Absent or unrecognised → CLAUDE, so every pre-CCRM-53 install reads unchanged. */
        fun fromKey(key: String?): Provider = entries.firstOrNull { it.key == key } ?: CLAUDE
    }
}
