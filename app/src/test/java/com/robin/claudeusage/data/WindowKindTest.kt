package com.robin.claudeusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CCRM-53 (Provider Model): a window is classified by its **duration**, never by
 * which JSON slot it arrived in — a weekly-only account can put its weekly limit
 * in a `primary_window`-shaped slot.
 */
class WindowKindTest {

    @Test
    fun `18000 seconds is a session window`() {
        assertEquals(WindowKind.SESSION, classifyWindow(18_000L))
    }

    @Test
    fun `604800 seconds is a weekly window`() {
        assertEquals(WindowKind.WEEKLY, classifyWindow(604_800L))
    }

    @Test
    fun `null is unclassifiable`() {
        assertEquals(WindowKind.OTHER, classifyWindow(null))
    }

    @Test
    fun `an unrecognised duration is OTHER, not misread as session or weekly`() {
        assertEquals(WindowKind.OTHER, classifyWindow(3_600L))
    }
}
