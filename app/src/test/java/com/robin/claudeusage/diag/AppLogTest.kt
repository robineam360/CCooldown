package com.robin.claudeusage.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins CCRM-34 (Diagnostics Log)'s pure pieces: the level gate, the tolerant
 * level decode, the line shape, and the trim rule. The file plumbing itself is
 * Android I/O and gets judged at the device pass.
 */
class AppLogTest {

    @Test
    fun `level gate lets equal-or-higher through`() {
        assertTrue(AppLog.shouldLog(AppLog.Level.INFO, min = AppLog.Level.INFO))
        assertTrue(AppLog.shouldLog(AppLog.Level.ERROR, min = AppLog.Level.INFO))
        assertFalse(AppLog.shouldLog(AppLog.Level.DEBUG, min = AppLog.Level.INFO))
        // Debug mode records everything.
        assertTrue(AppLog.shouldLog(AppLog.Level.DEBUG, min = AppLog.Level.DEBUG))
    }

    @Test
    fun `level decode is tolerant and case-insensitive`() {
        assertEquals(AppLog.Level.DEBUG, AppLog.Level.fromKey("debug"))
        assertEquals(AppLog.Level.WARN, AppLog.Level.fromKey("WARN"))
        assertEquals(AppLog.Level.INFO, AppLog.Level.fromKey("verbose"))
        assertEquals(AppLog.Level.INFO, AppLog.Level.fromKey(null))
    }

    @Test
    fun `line shape carries stamp, level tag, category and profile`() {
        assertEquals(
            "08-19 21:04:11.402 I [poll][personal] auto → OK",
            AppLog.formatLine("08-19 21:04:11.402", AppLog.Level.INFO, "poll", "personal", "auto → OK"),
        )
        assertEquals(
            "08-19 21:04:11.402 W [alerts][-] post blocked",
            AppLog.formatLine("08-19 21:04:11.402", AppLog.Level.WARN, "alerts", null, "post blocked"),
        )
    }

    @Test
    fun `trim keeps the newest lines`() {
        val lines = (1..1000).map { "line $it" }
        val kept = AppLog.trimmed(lines)
        assertEquals(AppLog.KEEP_LINES, kept.size)
        assertEquals("line 1000", kept.last())
        assertEquals("line ${1000 - AppLog.KEEP_LINES + 1}", kept.first())
    }
}
