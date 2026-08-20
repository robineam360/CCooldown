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
 */
enum class ErrorKind(
    val key: String,
    val title: String,
    val short: String,
    val severe: Boolean,
) {
    AUTH(
        "auth",
        "Sign-in stopped working — re-sign in from Settings.",
        "re-auth needed",
        severe = true,
    ),
    RATE_LIMITED(
        "rateLimited",
        "Rate limited — backing off, retries on its own.",
        "rate limited",
        severe = false,
    ),
    NETWORK(
        "network",
        "Couldn't reach Anthropic — check your connection, or see if it's them.",
        "can't reach Anthropic",
        severe = false,
    ),
    SERVER(
        "server",
        "Anthropic's server errored — usually theirs, usually brief.",
        "server error",
        severe = false,
    ),
    INVALID_RESPONSE(
        "invalidResponse",
        "The server answered in a shape this app doesn't know — an app update may be needed.",
        "unrecognised response",
        severe = false,
    ),
    INTERNAL(
        "internal",
        "Something unexpected went wrong in the app.",
        "app error",
        severe = true,
    );

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
