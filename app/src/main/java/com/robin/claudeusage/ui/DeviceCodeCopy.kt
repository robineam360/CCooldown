package com.robin.claudeusage.ui

/**
 * The device-code sheet's five states and their words (CCRM-54 (ChatGPT Account)
 * part 2, built to `design/provider-identity-wireframe.html` section 5).
 *
 * Pure, so the copy is pinned by a test rather than only by eye: this module has
 * no Robolectric, and a sheet with five branches is exactly the kind of thing
 * where a state ships unobserved — the failure CCRM-15 (Above-Pace Verification)
 * exists to remember.
 *
 * Every state answers the same two questions in the same order: what happened,
 * and what to tap. [primaryLabel] is null where there is nothing to retry.
 */
enum class DeviceCodeStage {
    /** Asking OpenAI for a code; no code to show yet. */
    STARTING,

    /** A live code is on screen and the poll is running. */
    WAITING,

    /** Past the 15-minute window without the user finishing. */
    EXPIRED,

    /**
     * The poll returned a terminal non-2xx. The Codex CLI collapses every one of
     * those into a single failure with no distinct "denied", so neither do we.
     */
    DENIED,

    /** HTTP 404 from `/usercode` — OpenAI has the flow switched off for this client. */
    UNAVAILABLE,

    /**
     * Couldn't reach OpenAI to start at all. Not a flow state and not in the
     * wireframe — it reuses [EXPIRED]'s shape (no live code, one button to try
     * again) with the network's own sentence, rather than inventing a sixth layout.
     */
    FAILED,
}

object DeviceCodeCopy {

    const val TITLE = "Sign in to ChatGPT"

    /** The instruction above the code, shown only while a code exists. */
    const val INSTRUCTION_PREFIX = "Go to"
    const val INSTRUCTION_SUFFIX = "and enter this code"

    /** Under the code, next to the countdown. */
    const val WAITING_NOTE = "Waiting for you to finish…"

    /**
     * @param detail the failure's own sentence, used by [DeviceCodeStage.FAILED] and
     *   to name the status on [DeviceCodeStage.DENIED]. Ignored elsewhere.
     */
    fun body(stage: DeviceCodeStage, detail: String? = null): String = when (stage) {
        DeviceCodeStage.STARTING -> "Asking OpenAI for a code…"
        DeviceCodeStage.WAITING ->
            "Open the page in any browser — it can be a different device — sign in to " +
                "ChatGPT, and enter this code."
        DeviceCodeStage.EXPIRED ->
            "That code expired before it was used. Codes last 15 minutes."
        DeviceCodeStage.DENIED ->
            "That sign-in didn't go through${detail?.let { " ($it)" } ?: ""}. " +
                "Start again with a new code."
        // The fallback is named, not offered: the browser PKCE route is only built if
        // this ever happens (CCRM-54 (ChatGPT Account)), and it hasn't.
        DeviceCodeStage.UNAVAILABLE ->
            "OpenAI has code sign-in switched off for this client, so there's no code " +
                "to enter. Signing in would need the browser fallback, which this " +
                "version doesn't have. Try again later."
        DeviceCodeStage.FAILED ->
            "Couldn't reach OpenAI to get a code${detail?.let { " — $it" } ?: ""}."
    }

    /** The one action worth offering, or null when there is nothing to retry. */
    fun primaryLabel(stage: DeviceCodeStage): String? = when (stage) {
        DeviceCodeStage.STARTING, DeviceCodeStage.WAITING -> null
        DeviceCodeStage.EXPIRED, DeviceCodeStage.DENIED, DeviceCodeStage.FAILED ->
            "Get a new code"
        DeviceCodeStage.UNAVAILABLE -> null
    }

    /** Whether the code, the countdown and the copy/open buttons are on screen. */
    fun showsCode(stage: DeviceCodeStage): Boolean = stage == DeviceCodeStage.WAITING
}
