package com.robin.claudeusage.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The account list, as data (CCRM-6 (Multi-Account)).
 *
 * Lives on its own `"profiles"` SharedPreferences file — one JSON array plus a `nextSlot`
 * int and a one-time `seeded` flag — rather than inside [UsageCache], because [UsageCache]
 * keys *by* profile and would then have to bootstrap itself.
 *
 * All the interesting logic is in the [Companion] as pure functions over [State], so the
 * seed/allocate/rename/remove rules are unit-testable without an Android runtime; this class
 * is only persistence around them.
 *
 * **Slots are never reused.** [remove] drops a profile but leaves `nextSlot` alone, so the
 * next account added gets a fresh slot and the retired one's notification IDs, alarm request
 * codes and tile binding go permanently quiet instead of being inherited.
 */
class ProfileRegistry(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    // Read ~40 times per composition and once per widget draw, so the JSON is parsed only
    // when something has actually written. The revision int is the cheap validity check.
    private var memo: State? = null
    private var memoRev: Int = -1

    /** Every registered account, in stored order — added accounts land at the end. */
    fun all(): List<Profile> = state().profiles

    fun byKey(key: String?): Profile? = key?.let { k -> all().firstOrNull { it.key == k } }

    /**
     * The account to fall back to when nothing else resolves — the old
     * `Profile.fromKey` default, now registry-backed. Total by construction: [remove]
     * refuses the last profile, so the list is never empty.
     */
    fun first(): Profile = all().firstOrNull() ?: SEED_PERSONAL

    /** Straight replacement for the old `Profile.fromKey`: a stored key, or the fallback. */
    fun resolve(key: String?): Profile = byKey(key) ?: first()

    /** True when [remove] would do something — the last remaining account can't be removed. */
    fun canRemove(): Boolean = all().size > 1

    /**
     * The label a cleared rename field restores — the one the account was created with,
     * which for an added account is positional ("Account 3") and so cannot be re-derived
     * from its slot after an earlier removal. Read by the rename dialog so its copy names
     * the value it will actually restore.
     */
    fun defaultLabelFor(key: String): String = state().defaultLabel(key)

    /**
     * Mints a new account: `key = "p$nextSlot"`, `slot = nextSlot++`. A blank [label] takes
     * the positional default ("Account 3" for the third account), which is also what a
     * cleared rename field restores.
     */
    fun add(label: String? = null): Profile {
        val (next, profile) = add(state(), label)
        write(next)
        return profile
    }

    /** Blank clears back to the label the account was created with. */
    fun rename(key: String, label: String) {
        write(rename(state(), key, label))
    }

    /**
     * Drops [key] from the list. Deletes nothing else — the caller
     * ([UsageRepository.removeProfile]) owns the destructive ordering, and this has to run
     * in the middle of it, once nothing else needs to resolve the key.
     *
     * @return false if the key is unknown or is the last account left.
     */
    fun remove(key: String): Boolean {
        val current = state()
        val next = remove(current, key)
        if (next.profiles.size == current.profiles.size) return false
        write(next)
        return true
    }

    private fun state(): State {
        seedIfNeeded()
        val rev = prefs.getInt(PREF_REV, 0)
        memo?.let { if (rev == memoRev) return it }
        val parsed = decode(prefs.getString(PREF_LIST, null), prefs.getInt(PREF_NEXT_SLOT, 0))
        memo = parsed
        memoRev = rev
        return parsed
    }

    /**
     * One-time seed, on first read: `personal`/slot 0 and `work`/slot 1, carrying over
     * whatever labels the pre-CCRM-6 `customLabel` prefs held.
     *
     * Both slots are emitted **unconditionally** — existing installs have both whether or
     * not Work ever got a token, and dropping the empty one would delete a card the user can
     * see today. Idempotency is a stored flag, not "are they present": gated on presence, a
     * deliberately removed Personal would resurrect on the next launch.
     */
    private fun seedIfNeeded() {
        if (prefs.getBoolean(PREF_SEEDED, false)) return
        // Read the legacy labels straight from the cache's own file rather than through
        // UsageCache, which would recurse back into this registry to resolve a label.
        val legacy = appContext.getSharedPreferences("usage_cache", Context.MODE_PRIVATE)
        write(
            seed(
                personalLabel = legacy.getString("customLabel", null),
                workLabel = legacy.getString("${Profile.WORK_KEY}.customLabel", null),
            )
        )
    }

    private fun write(state: State) {
        prefs.edit()
            .putString(PREF_LIST, encode(state))
            .putInt(PREF_NEXT_SLOT, state.nextSlot)
            .putBoolean(PREF_SEEDED, true)
            .putInt(PREF_REV, prefs.getInt(PREF_REV, 0) + 1)
            .apply()
        memo = state
        memoRev = prefs.getInt(PREF_REV, 0)
    }

    companion object {

        private const val PREF_LIST = "list"
        private const val PREF_NEXT_SLOT = "nextSlot"
        private const val PREF_SEEDED = "seeded"
        private const val PREF_REV = "rev"

        /** Labels are the user's, and they have to fit a tab and a tile — 16 chars, as today. */
        const val MAX_LABEL = 16

        val SEED_PERSONAL = Profile(Profile.LEGACY_KEY, 0, "Personal")
        val SEED_WORK = Profile(Profile.WORK_KEY, 1, "Work")

        /**
         * The whole registry as one value: the ordered list, the never-reused slot counter,
         * and each account's creation-time label (what a cleared rename field restores).
         */
        data class State(
            val profiles: List<Profile>,
            val nextSlot: Int,
            val defaults: Map<String, String> = emptyMap(),
        ) {
            fun defaultLabel(key: String): String = defaults[key]
                ?: profiles.firstOrNull { it.key == key }?.let { fallbackLabel(it) }
                ?: ""
        }

        /**
         * What an account is called before anyone renames it, when even the stored default
         * is missing (a hand-edited or truncated prefs file). Slot-derived rather than
         * position-derived here precisely because it has to work without the list.
         */
        fun fallbackLabel(profile: Profile): String = when (profile.key) {
            Profile.LEGACY_KEY -> SEED_PERSONAL.label
            Profile.WORK_KEY -> SEED_WORK.label
            else -> "Account ${profile.slot + 1}"
        }

        fun seed(personalLabel: String?, workLabel: String?): State {
            val personal = SEED_PERSONAL.copy(label = clean(personalLabel) ?: SEED_PERSONAL.label)
            val work = SEED_WORK.copy(label = clean(workLabel) ?: SEED_WORK.label)
            return State(
                profiles = listOf(personal, work),
                nextSlot = 2,
                defaults = mapOf(
                    personal.key to SEED_PERSONAL.label,
                    work.key to SEED_WORK.label,
                ),
            )
        }

        fun add(state: State, label: String?): Pair<State, Profile> {
            val slot = state.nextSlot
            // Positional, not slot-derived: the third account you add reads "Account 3"
            // even if you removed one earlier. It is a starting label, not an identity.
            val default = "Account ${state.profiles.size + 1}"
            val profile = Profile(key = "p$slot", slot = slot, label = clean(label) ?: default)
            return State(
                profiles = state.profiles + profile,
                nextSlot = slot + 1,
                defaults = state.defaults + (profile.key to default),
            ) to profile
        }

        fun rename(state: State, key: String, label: String): State = state.copy(
            profiles = state.profiles.map {
                if (it.key != key) it
                else it.copy(label = clean(label) ?: state.defaultLabel(key))
            }
        )

        /** No-op for an unknown key, and for the last account left — see [first]. */
        fun remove(state: State, key: String): State {
            if (state.profiles.size <= 1) return state
            if (state.profiles.none { it.key == key }) return state
            return State(
                profiles = state.profiles.filterNot { it.key == key },
                // Deliberately untouched: retiring a slot forever is the whole point.
                nextSlot = state.nextSlot,
                defaults = state.defaults - key,
            )
        }

        fun encode(state: State): String {
            val arr = JSONArray()
            for (p in state.profiles) {
                arr.put(
                    JSONObject()
                        .put("k", p.key)
                        .put("s", p.slot)
                        .put("l", p.label)
                        .put("d", state.defaultLabel(p.key))
                )
            }
            return arr.toString()
        }

        fun decode(json: String?, nextSlot: Int): State {
            if (json.isNullOrBlank()) return State(emptyList(), nextSlot)
            return try {
                val arr = JSONArray(json)
                val profiles = ArrayList<Profile>(arr.length())
                val defaults = LinkedHashMap<String, String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("k")
                    if (key.isEmpty() || profiles.any { it.key == key }) continue
                    val slot = o.optInt("s", -1)
                    if (slot < 0) continue
                    val label = o.optString("l").ifEmpty { key }
                    val profile = Profile(key, slot, label)
                    profiles += profile
                    defaults[key] = o.optString("d").ifEmpty { fallbackLabel(profile) }
                }
                State(
                    profiles = profiles,
                    // A counter that has fallen behind a slot already in use would hand a
                    // new account a live account's notification IDs. Clamp on read.
                    nextSlot = maxOf(nextSlot, (profiles.maxOfOrNull { it.slot } ?: -1) + 1),
                    defaults = defaults,
                )
            } catch (_: Exception) {
                State(emptyList(), nextSlot)
            }
        }

        private fun clean(label: String?): String? =
            label?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() }
    }
}
