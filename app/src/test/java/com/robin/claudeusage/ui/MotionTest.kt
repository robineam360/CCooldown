package com.robin.claudeusage.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CCRM-32's contract is small and worth pinning: **only a scale of zero (or a
 * broken read) turns motion off, and off means duration zero — never a different
 * animation.** The Android half (reading `ANIMATOR_DURATION_SCALE`) is a
 * one-liner over the settings provider and gets checked on a device; everything
 * that decides is here.
 */
class MotionTest {

    @Test
    fun `zero scale is reduced — that's the whole feature`() {
        assertTrue(Motion.reduced(0f))
    }

    @Test
    fun `any real scale keeps motion, including the developer slow and fast ones`() {
        // 0.5× and 5× come from developer options; the framework already applies
        // them to its own animators, and we deliberately don't multiply through.
        for (scale in listOf(0.5f, 1f, 1.5f, 2f, 5f, 10f)) {
            assertFalse("scale $scale should keep motion", Motion.reduced(scale))
        }
    }

    @Test
    fun `the unset default reads as motion on`() {
        // Motion.scale() falls back to 1f when the provider has no row; that
        // default must land on the animated side or a fresh device loses motion.
        assertFalse(Motion.reduced(1f))
    }

    @Test
    fun `garbage fails towards stillness, not motion`() {
        // A negative or NaN scale is a broken read; freezing is the safe verdict
        // because the user this feature exists for asked for stillness.
        assertTrue(Motion.reduced(-1f))
        assertTrue(Motion.reduced(Float.NaN))
    }

    @Test
    fun `collapse is the identity while motion is on`() {
        assertEquals(300, Motion.collapse(300, 1f))
        assertEquals(300, Motion.collapse(300, 0.5f))
    }

    @Test
    fun `collapse goes to exactly zero when motion is off`() {
        assertEquals(0, Motion.collapse(300, 0f))
        assertEquals(0, Motion.collapse(300, Float.NaN))
    }
}
