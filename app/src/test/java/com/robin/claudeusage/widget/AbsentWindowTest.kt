package com.robin.claudeusage.widget

import com.robin.claudeusage.data.ModelCap
import com.robin.claudeusage.data.SpendCredits
import com.robin.claudeusage.data.UsageData
import com.robin.claudeusage.data.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [absentWindowMessage] — what a Ring or Bar widget says when it was configured on a
 * window the account turns out not to have (CCRM-54 (ChatGPT Account) part 2, the
 * wireframe's decision 6).
 *
 * The distinction under test is *absent* vs *not fetched yet*. Both draw a null
 * window, and before this the face showed "—" (Ring) or a fake `0% used` with
 * "Starts when a message is sent" (Bar) — copy that reads as "wait a moment" and
 * would never stop being wrong on a ChatGPT Plus account.
 */
class AbsentWindowTest {

    private fun window(pct: Double) = UsageWindow(pct, null, null)

    @Test
    fun `no payload is not an absent window`() {
        // Nothing has been fetched: "—" is right, and a sentence would be a claim
        // about the account that no payload has been seen to support.
        assertNull(absentWindowMessage(null, weekly = false))
        assertNull(absentWindowMessage(null, weekly = true))
    }

    @Test
    fun `a present window says nothing`() {
        val data = UsageData(window(9.0), window(79.0), emptyList())
        assertNull(absentWindowMessage(data, weekly = false))
        assertNull(absentWindowMessage(data, weekly = true))
    }

    /** The real case: a ChatGPT Plus/Pro account since OpenAI lifted the 5-hour limit. */
    @Test
    fun `a weekly-only account names the missing 5-hour window`() {
        val data = UsageData(null, window(63.0), emptyList())
        assertEquals(
            "No 5-hour window on this account",
            absentWindowMessage(data, weekly = false),
        )
        assertNull(absentWindowMessage(data, weekly = true))
    }

    @Test
    fun `a session-only account names the missing 7-day window`() {
        val data = UsageData(window(42.0), null, emptyList())
        assertEquals(
            "No 7-day window on this account",
            absentWindowMessage(data, weekly = true),
        )
        assertNull(absentWindowMessage(data, weekly = false))
    }

    /**
     * Model caps ride the 7-day clock but are not the 7-day window: a payload with
     * caps and no weekly still has no weekly bar to draw.
     */
    @Test
    fun `model caps do not stand in for the weekly window`() {
        val data = UsageData(null, null, listOf(ModelCap("Spark", window(12.0))))
        assertEquals(
            "No 7-day window on this account",
            absentWindowMessage(data, weekly = true),
        )
    }

    /** Credits are money, not a window — they can't fill either slot. */
    @Test
    fun `credits alone leave both windows absent`() {
        val data = UsageData(
            null, null, emptyList(),
            SpendCredits(0L, null, 2, "USD", null, balanceMinor = 1240L),
        )
        assertEquals(
            "No 5-hour window on this account",
            absentWindowMessage(data, weekly = false),
        )
        assertEquals(
            "No 7-day window on this account",
            absentWindowMessage(data, weekly = true),
        )
    }
}
