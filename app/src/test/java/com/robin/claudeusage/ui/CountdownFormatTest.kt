package com.robin.claudeusage.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Fmt.mmss] — the device-code sheet's countdown (CCRM-54 (ChatGPT Account) part 2).
 *
 * Part 1's rough sheet printed `Fmt.dhm`, which rounds to whole minutes and so sat
 * frozen on "14m" for a minute at a time while the user watched it. A code that
 * lives fifteen minutes needs seconds.
 */
class CountdownFormatTest {

    private val now = 1_757_000_000_000L

    @Test
    fun `counts down in minutes and seconds`() {
        assertEquals("14:32", Fmt.mmss(now + (14 * 60 + 32) * 1000L, now))
        assertEquals("15:00", Fmt.mmss(now + 15 * 60 * 1000L, now))
    }

    /** Seconds always take two digits, so the line doesn't jitter in width. */
    @Test
    fun `seconds are zero-padded`() {
        assertEquals("1:05", Fmt.mmss(now + 65 * 1000L, now))
        assertEquals("0:09", Fmt.mmss(now + 9 * 1000L, now))
    }

    /** Truncates rather than rounds: it must never claim time that has gone. */
    @Test
    fun `a part-second does not round up`() {
        assertEquals("0:09", Fmt.mmss(now + 9_999L, now))
    }

    @Test
    fun `clamps at zero instead of going negative`() {
        assertEquals("0:00", Fmt.mmss(now, now))
        assertEquals("0:00", Fmt.mmss(now - 60_000L, now))
    }
}
