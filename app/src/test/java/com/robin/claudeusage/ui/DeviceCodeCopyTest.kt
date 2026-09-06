package com.robin.claudeusage.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the device-code sheet's five states (CCRM-54 (ChatGPT Account) part 2). This
 * module has no Robolectric, so a Compose sheet with five branches can only be
 * checked by eye — which is exactly how the state CCRM-15 (Above-Pace Verification)
 * exists to remember got shipped unobserved. The copy and the state table are pure
 * for that reason; the device pass in Step 5 confirms the layout.
 */
class DeviceCodeCopyTest {

    @Test
    fun `every stage says what happened`() {
        for (stage in DeviceCodeStage.entries) {
            val body = DeviceCodeCopy.body(stage)
            assertTrue("$stage has no body", body.isNotBlank())
            // Sentences, not fragments: each state has to stand on its own. The
            // in-flight state ends in an ellipsis, which is the same claim about
            // being finished.
            assertTrue(
                "$stage isn't a sentence",
                body.trimEnd().last() in listOf('.', '\u2026'),
            )
        }
    }

    @Test
    fun `only the waiting state shows a code`() {
        assertEquals(
            setOf(DeviceCodeStage.WAITING),
            DeviceCodeStage.entries.filter { DeviceCodeCopy.showsCode(it) }.toSet(),
        )
    }

    /** Nothing to retry while a code is live or on its way. */
    @Test
    fun `the retry button appears only where retrying is the answer`() {
        assertNull(DeviceCodeCopy.primaryLabel(DeviceCodeStage.STARTING))
        assertNull(DeviceCodeCopy.primaryLabel(DeviceCodeStage.WAITING))
        assertEquals("Get a new code", DeviceCodeCopy.primaryLabel(DeviceCodeStage.EXPIRED))
        assertEquals("Get a new code", DeviceCodeCopy.primaryLabel(DeviceCodeStage.DENIED))
        assertEquals("Get a new code", DeviceCodeCopy.primaryLabel(DeviceCodeStage.FAILED))
    }

    /**
     * Unavailable is the one dead end: OpenAI has the flow switched off, so another
     * code is not the answer and offering one would be a lie about what would happen.
     */
    @Test
    fun `unavailable offers nothing to retry and names the fallback`() {
        assertNull(DeviceCodeCopy.primaryLabel(DeviceCodeStage.UNAVAILABLE))
        val body = DeviceCodeCopy.body(DeviceCodeStage.UNAVAILABLE)
        assertTrue(body.contains("browser fallback"))
        assertFalse(DeviceCodeCopy.showsCode(DeviceCodeStage.UNAVAILABLE))
    }

    @Test
    fun `denied names the status when there is one and reads without it`() {
        assertEquals(
            "That sign-in didn't go through (HTTP 400). Start again with a new code.",
            DeviceCodeCopy.body(DeviceCodeStage.DENIED, "HTTP 400"),
        )
        assertEquals(
            "That sign-in didn't go through. Start again with a new code.",
            DeviceCodeCopy.body(DeviceCodeStage.DENIED, null),
        )
    }

    @Test
    fun `a start failure carries the network's own sentence`() {
        assertTrue(
            DeviceCodeCopy.body(DeviceCodeStage.FAILED, "timeout").contains("timeout"),
        )
        // …and still reads when there is nothing to add.
        assertNotNull(DeviceCodeCopy.body(DeviceCodeStage.FAILED, null))
    }

    /** The expiry sentence must name the real 15-minute life, not a rounded guess. */
    @Test
    fun `expired says how long a code lasts`() {
        assertTrue(DeviceCodeCopy.body(DeviceCodeStage.EXPIRED).contains("15 minutes"))
    }
}
