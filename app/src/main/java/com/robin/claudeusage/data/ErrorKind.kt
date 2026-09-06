package com.robin.claudeusage.data

/**
 * CCRM-27 (Error Taxonomy): the typed failure kinds, each carrying copy that
 * **names the fix** rather than printing the symptom. Produced where the failure
 * happens (`UsageRepository.doFetch` / `authFailure`), persisted beside
 * `lastStatus` so widgets and the notification strip can read it after process
 * death. The raw status string survives as the notice's small detail line —
 * remediation up top, evidence below.
 *
 * [severe] marks the two kinds worth the error colour; the rest render amber —
 * they are transient by nature and the retained last-good snapshot is still on
 * screen next to them.
 *
 * CCRM-57 (Provider Plumbing): the copy is a **function of the provider**, not a
 * fixed string. "Couldn't reach Anthropic" on a ChatGPT account is a lie about
 * which server was unreachable, and the whole point of this taxonomy is that the
 * user can act on what it says. [key] is what gets persisted, so nothing stored
 * changes and an old cached failure re-renders in the right vendor's name.
 */
enum class ErrorKind(
    val key: String,
    val severe: Boolean,
) {
    AUTH("auth", severe = true),
    RATE_LIMITED("rateLimited", severe = false),
    NETWORK("network", severe = false),
    SERVER("server", severe = false),
    INVALID_RESPONSE("invalidResponse", severe = false),
    INTERNAL("internal", severe = true);

    /**
     * The remediation line. Only the two kinds that are *about* the other end name
     * the vendor; AUTH names the flow instead, which reads right for every provider
     * ("re-sign in from Settings" is the fix whether the token came from a browser
     * trip or a device code).
     */
    fun title(provider: Provider): String = when (this) {
        AUTH -> "Sign-in stopped working — re-sign in from Settings."
        RATE_LIMITED -> "Rate limited — backing off, retries on its own."
        NETWORK -> "Couldn't reach ${provider.vendor} — check your connection, or see if it's them."
        SERVER -> "${provider.vendor}'s server errored — usually theirs, usually brief."
        INVALID_RESPONSE ->
            "The server answered in a shape this app doesn't know — an app update may be needed."
        INTERNAL -> "Something unexpected went wrong in the app."
    }

    /** The ≤24-character label for a widget pill or a condition strip. */
    fun short(provider: Provider): String = when (this) {
        AUTH -> "re-auth needed"
        RATE_LIMITED -> "rate limited"
        NETWORK -> "can't reach ${provider.vendor}"
        SERVER -> "server error"
        INVALID_RESPONSE -> "unrecognised response"
        INTERNAL -> "app error"
    }

    companion object {
        /** Tolerant decode — an unknown key is an app problem, not a crash. */
        fun fromKey(key: String?): ErrorKind =
            entries.firstOrNull { it.key == key } ?: INTERNAL

        /**
         * Best-effort kind for a status string persisted before the kind existed —
         * one upgrade's worth of migration, so an old stored failure doesn't wear
         * the INTERNAL copy until the next poll rewrites it properly.
         */
        fun fromStatus(status: String): ErrorKind = when {
            status.startsWith("Network:") -> NETWORK
            status.contains("429") -> RATE_LIMITED
            status.startsWith("HTTP ") -> SERVER
            status.contains("Re-auth") || status == "No token set" -> AUTH
            status.contains("refresh failed") -> NETWORK
            status == "Unrecognized response shape" -> INVALID_RESPONSE
            else -> INTERNAL
        }
    }
}
