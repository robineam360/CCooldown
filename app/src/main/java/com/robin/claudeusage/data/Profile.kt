package com.robin.claudeusage.data

/**
 * One tracked Claude account.
 *
 * CCRM-6 (Multi-Account) turned this from a two-constant enum into a value type owned by
 * [ProfileRegistry], so the account count is data rather than code. The three fields are
 * deliberately different kinds of thing:
 *
 * - [key] is the **identity**: a stable string that namespaces every store — the credential
 *   and cache prefixes, the history and session-log filenames, the `"profile"` intent extra.
 *   Never reused, never renumbered.
 * - [slot] is the **integer identity** that Android surfaces need: the notification-ID
 *   offset, PendingIntent request codes, alarm request codes and the Quick Settings tile
 *   binding. Allocated once from a persisted monotonic counter and never reused, so a stale
 *   placed widget or tile can never inherit a *new* account's data. Slots 0 and 1 are pinned
 *   to `personal`/`work`: existing installs have widgets, alarms and posted notifications
 *   keyed off those exact ints.
 * - [label] is the **user's name for it**, 16 characters, editable and therefore not identity.
 *
 * Equality is the key alone. A [Profile] gets captured in a composition, an intent extra or a
 * widget's stored prefs and outlives the label it was built with; if equality included the
 * label then a rename would silently turn `pinnedProfile == p` false and the selected chip
 * would jump. Read a live label through [UsageCache.profileLabel], which resolves by key.
 */
data class Profile(
    val key: String,
    val slot: Int,
    val label: String,
    /**
     * Which service this account tracks (CCRM-53 (Provider Model)). Not part of identity —
     * see [equals] — since a key is minted once and the provider travels with it for the
     * account's whole life; nothing renders a provider switch mid-life.
     */
    val provider: Provider = Provider.CLAUDE,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Profile && other.key == key)

    override fun hashCode(): Int = key.hashCode()

    companion object {
        /**
         * v0.5 and earlier stored the single account's token and cache entries with no
         * prefix at all. `CredentialStore.k()` and `UsageCache.k()` still honour that, which
         * is why this key can never be renamed or reissued — it is a storage-format
         * constant, not just the first account's name.
         */
        const val LEGACY_KEY = "personal"

        const val WORK_KEY = "work"
    }
}
