package com.robin.claudeusage.data

import com.robin.claudeusage.alerts.Alerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CCRM-6 (Multi-Account): the registry's rules, exercised against
 * [ProfileRegistry.Companion]'s pure functions — the class itself is only
 * SharedPreferences around these, so this covers the logic without an Android runtime.
 *
 * The invariants under test are the ones whose failure breaks an *existing* install:
 * slots 0/1 staying pinned, slots never being reissued, and the notification-ID scheme
 * reproducing today's IDs for those two slots.
 */
class ProfileRegistryTest {

    private fun seeded() = ProfileRegistry.seed(null, null)

    // --- seed ---

    @Test
    fun `seed pins slots 0 and 1 to personal and work`() {
        val state = seeded()
        assertEquals(listOf("personal", "work"), state.profiles.map { it.key })
        assertEquals(listOf(0, 1), state.profiles.map { it.slot })
        assertEquals(listOf("Personal", "Work"), state.profiles.map { it.label })
        assertEquals(2, state.nextSlot)
    }

    @Test
    fun `seed carries over the pre-CCRM-6 custom labels`() {
        val state = ProfileRegistry.seed("Pro", "Teams org")
        assertEquals(listOf("Pro", "Teams org"), state.profiles.map { it.label })
        // The creation-time default is what a cleared rename restores — not the carried label.
        assertEquals("Personal", state.defaultLabel("personal"))
        assertEquals("Work", state.defaultLabel("work"))
    }

    @Test
    fun `seed emits work even when it has no label of its own`() {
        // Existing installs have both slots whether or not Work ever got a token; dropping
        // the empty one would delete a card the user can see today.
        assertTrue(ProfileRegistry.seed("Pro", null).profiles.any { it.key == "work" })
    }

    @Test
    fun `seed is idempotent`() {
        assertEquals(ProfileRegistry.seed("a", "b"), ProfileRegistry.seed("a", "b"))
    }

    @Test
    fun `seed labels are trimmed and capped at 16 characters`() {
        val state = ProfileRegistry.seed("   ", "a".repeat(30))
        assertEquals("Personal", state.profiles[0].label) // blank falls back
        assertEquals(ProfileRegistry.MAX_LABEL, state.profiles[1].label.length)
    }

    // --- add ---

    @Test
    fun `add mints the key from the slot counter`() {
        val (state, third) = ProfileRegistry.add(seeded(), null)
        assertEquals("p2", third.key)
        assertEquals(2, third.slot)
        assertEquals("Account 3", third.label)
        assertEquals(3, state.nextSlot)
        assertEquals(listOf("personal", "work", "p2"), state.profiles.map { it.key })
    }

    @Test
    fun `add takes a supplied label and keeps the positional default for a cleared rename`() {
        val (state, third) = ProfileRegistry.add(seeded(), "Teams")
        assertEquals("Teams", third.label)
        assertEquals("Account 3", state.defaultLabel("p2"))
        assertEquals("Account 3", ProfileRegistry.rename(state, "p2", "  ").profiles.last().label)
    }

    // --- remove: the slot invariant ---

    @Test
    fun `nextSlot never reissues a slot after a remove`() {
        var state = seeded()
        state = ProfileRegistry.add(state, "Teams").first          // p2 / slot 2
        state = ProfileRegistry.add(state, "Side").first           // p3 / slot 3
        state = ProfileRegistry.remove(state, "p2")

        assertEquals(listOf("personal", "work", "p3"), state.profiles.map { it.key })
        assertEquals(4, state.nextSlot)

        val (after, fresh) = ProfileRegistry.add(state, null)
        // The retired slot 2 — and its notification IDs, alarm request codes and tile
        // binding — must never come back, or a stale widget inherits a new account's data.
        assertEquals("p4", fresh.key)
        assertEquals(4, fresh.slot)
        assertTrue(after.profiles.none { it.slot == 2 })
    }

    @Test
    fun `remove refuses the last account`() {
        var state = seeded()
        state = ProfileRegistry.remove(state, "work")
        assertEquals(1, state.profiles.size)
        state = ProfileRegistry.remove(state, "personal")
        assertEquals(1, state.profiles.size) // unchanged — the registry always keeps one
    }

    @Test
    fun `remove ignores an unknown key`() {
        val state = seeded()
        assertEquals(state, ProfileRegistry.remove(state, "p9"))
    }

    @Test
    fun `removing personal does not renumber or reuse its slot`() {
        var state = ProfileRegistry.add(seeded(), "Teams").first
        state = ProfileRegistry.remove(state, "personal")
        assertEquals(listOf("work", "p2"), state.profiles.map { it.key })
        assertEquals(listOf(1, 2), state.profiles.map { it.slot })
        assertEquals(3, state.nextSlot)
    }

    // --- rename, lookup, fallback ---

    @Test
    fun `rename replaces the label and leaves key and slot alone`() {
        val state = ProfileRegistry.rename(seeded(), "work", "Teams org")
        val work = state.profiles.first { it.key == "work" }
        assertEquals("Teams org", work.label)
        assertEquals(1, work.slot)
    }

    @Test
    fun `rename caps at 16 characters and trims`() {
        val state = ProfileRegistry.rename(seeded(), "personal", "  " + "x".repeat(40))
        assertEquals("x".repeat(ProfileRegistry.MAX_LABEL), state.profiles[0].label)
    }

    @Test
    fun `a blank rename restores the creation-time default`() {
        var state = ProfileRegistry.rename(seeded(), "work", "Teams")
        state = ProfileRegistry.rename(state, "work", "")
        assertEquals("Work", state.profiles.first { it.key == "work" }.label)
    }

    @Test
    fun `rename ignores an unknown key`() {
        val state = seeded()
        assertEquals(state, ProfileRegistry.rename(state, "p9", "Nope"))
    }

    @Test
    fun `fallbackLabel names the seeded keys and numbers the rest by slot`() {
        assertEquals("Personal", ProfileRegistry.fallbackLabel(Profile("personal", 0, "x")))
        assertEquals("Work", ProfileRegistry.fallbackLabel(Profile("work", 1, "x")))
        assertEquals("Account 3", ProfileRegistry.fallbackLabel(Profile("p2", 2, "x")))
        assertEquals("Account 6", ProfileRegistry.fallbackLabel(Profile("p5", 5, "x")))
    }

    @Test
    fun `identity is the key alone, so a rename never changes which profile this is`() {
        val before = Profile("work", 1, "Work")
        val after = Profile("work", 1, "Teams org")
        assertEquals(before, after)
        assertEquals(before.hashCode(), after.hashCode())
        // Which is what keeps a Map<Profile, String> of labels usable across a rename.
        assertEquals("looked up", mapOf(before to "looked up")[after])
        assertNotEquals(before, Profile("personal", 0, "Work"))
    }

    // --- persistence round-trip ---

    @Test
    fun `encode then decode round-trips profiles, slots, labels and defaults`() {
        var state = ProfileRegistry.add(seeded(), "Teams").first
        state = ProfileRegistry.rename(state, "personal", "Pro")
        val back = ProfileRegistry.decode(ProfileRegistry.encode(state), state.nextSlot)
        assertEquals(state.profiles, back.profiles)
        assertEquals(state.profiles.map { it.label }, back.profiles.map { it.label })
        assertEquals(state.nextSlot, back.nextSlot)
        assertEquals("Account 3", back.defaultLabel("p2"))
    }

    @Test
    fun `decode survives junk and a missing file`() {
        assertTrue(ProfileRegistry.decode(null, 2).profiles.isEmpty())
        assertTrue(ProfileRegistry.decode("", 2).profiles.isEmpty())
        assertTrue(ProfileRegistry.decode("not json", 2).profiles.isEmpty())
        // Entries missing a key or a slot are dropped rather than taking the list with them.
        val partial = ProfileRegistry.decode(
            """[{"k":"personal","s":0,"l":"Pro"},{"s":4},{"k":"p9"}]""", 5,
        )
        assertEquals(listOf("personal"), partial.profiles.map { it.key })
    }

    @Test
    fun `decode clamps a stale nextSlot up past every slot in use`() {
        // A counter that had fallen behind would hand a new account a live account's
        // notification IDs — the exact collision this scheme exists to prevent.
        val state = ProfileRegistry.decode(
            """[{"k":"personal","s":0,"l":"P"},{"k":"p7","s":7,"l":"X"}]""", 1,
        )
        assertEquals(8, state.nextSlot)
    }

    @Test
    fun `decode drops duplicate keys`() {
        val state = ProfileRegistry.decode(
            """[{"k":"work","s":1,"l":"A"},{"k":"work","s":9,"l":"B"}]""", 2,
        )
        assertEquals(1, state.profiles.size)
        assertEquals("A", state.profiles[0].label)
    }

    @Test
    fun `decoded state with no stored default falls back by key and slot`() {
        val state = ProfileRegistry.decode("""[{"k":"p3","s":3,"l":"Renamed"}]""", 4)
        assertEquals("Account 4", state.defaultLabel("p3"))
        assertNull(state.profiles.firstOrNull { it.key == "personal" })
    }

    // --- notification IDs ---

    /** Every kind [Alerts] actually posts: 1–8 fixed, 10+n per-model caps, 30/31 pace. */
    private val kindsInUse: List<Int> = (1..8) + (10..29) + listOf(30, 31)

    @Test
    fun `notifId reproduces today's ids for slots 0 and 1`() {
        val personal = Profile("personal", 0, "Personal")
        val work = Profile("work", 1, "Work")
        for (kind in kindsInUse) {
            // Pre-CCRM-6: `kind + if (profile == WORK) 100 else 0`.
            assertEquals(kind, Alerts.notifId(personal, kind))
            assertEquals(kind + 100, Alerts.notifId(work, kind))
        }
    }

    @Test
    fun `notifId never collides across four slots for any kind in use`() {
        val profiles = listOf(
            Profile("personal", 0, "Personal"),
            Profile("work", 1, "Work"),
            Profile("p2", 2, "Teams"),
            Profile("p3", 3, "Side"),
        )
        val ids = profiles.flatMap { p -> kindsInUse.map { Alerts.notifId(p, it) } }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `the id stride outruns the widest kind, so it holds for any slot`() {
        // The ×100 stride is only safe while kinds stay below it.
        assertTrue(Alerts.MAX_KIND < 100)
        for (slot in 0..40) {
            val p = Profile("p$slot", slot, "x")
            assertEquals(slot * 100 + 1, Alerts.notifId(p, 1))
            assertTrue(Alerts.notifId(p, Alerts.MAX_KIND) < (slot + 1) * 100)
        }
    }
}
