package com.robin.claudeusage.ui

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CCRM-20 pulled the plot's coordinate system out of the `Canvas` lambda so a touch
 * could be mapped back to a sample. These tests exist because that mapping is now the
 * thing a tap depends on: if [SparkGeometry.nearestSample] picks the wrong point, the
 * callout confidently reports a percentage for the wrong moment, and nothing about the
 * chart looks wrong while it does it.
 */
class SparkGeometryTest {

    private val density = Density(2f)
    private val start = 1_000_000L
    private val end = start + 5 * 60 * 60_000L // a 5-hour window

    private fun geo(width: Float = 700f, height: Float = 400f) =
        SparkGeometry(width, height, density, start, end)

    /** Samples at 0, 1, 2, 3, 4 hours into the window. */
    private val samples = (0..4).map { start + it * 60L * 60_000L to it * 10.0 }

    @Test
    fun `x maps the window onto the plot and clamps outside it`() {
        val g = geo()
        assertEquals(0f, g.x(start), 0.01f)
        assertEquals(g.plotRight, g.x(end), 0.01f)
        assertEquals(g.plotRight / 2f, g.x(start + (end - start) / 2), 0.01f)
        // A sample from a neighbouring window must not be drawn off the canvas.
        assertEquals(0f, g.x(start - 60_000L), 0.01f)
        assertEquals(g.plotRight, g.x(end + 60_000L), 0.01f)
    }

    @Test
    fun `y puts 0 percent on the axis and 100 percent at the top`() {
        val g = geo()
        assertEquals(g.plotBottom, g.y(0.0), 0.01f)
        assertEquals(g.plotTop, g.y(100.0), 0.01f)
        assertEquals((g.plotBottom + g.plotTop) / 2f, g.y(50.0), 0.01f)
        // Over-limit windows exist; they pin to the top rather than drawing above it.
        assertEquals(g.plotTop, g.y(140.0), 0.01f)
    }

    @Test
    fun `nearest snaps to the sample under the touch`() {
        val g = geo()
        for (s in samples) {
            assertEquals(s, g.nearestSample(g.x(s.first), samples))
        }
    }

    @Test
    fun `nearest rounds to the closer neighbour, never between them`() {
        val g = geo()
        val first = samples[0]
        val second = samples[1]
        val midpoint = (g.x(first.first) + g.x(second.first)) / 2f

        // Just inside the midpoint each way picks the point on that side — and the
        // returned percentage is always one that was really observed, which is the
        // whole reason we snap instead of interpolating.
        assertEquals(first, g.nearestSample(midpoint - 1f, samples))
        assertEquals(second, g.nearestSample(midpoint + 1f, samples))
        assertTrue(g.nearestSample(midpoint, samples) in samples)
    }

    @Test
    fun `a touch beyond either end still selects the end sample`() {
        val g = geo()
        assertEquals(samples.first(), g.nearestSample(-500f, samples))
        // Past the last sample there is only the projection tail, which isn't tappable —
        // so the newest real fetch is the honest answer.
        assertEquals(samples.last(), g.nearestSample(g.plotRight + 500f, samples))
    }

    @Test
    fun `nearest on an empty series is null rather than a crash`() {
        assertNull(geo().nearestSample(100f, emptyList()))
    }

    @Test
    fun `a view too small to plot into reports itself unusable`() {
        // Narrower than the threshold-label gutter, and shorter than the axis plus
        // headroom: both were guarded inline before and are now one property.
        assertFalse(SparkGeometry(20f, 400f, density, start, end).usable)
        assertFalse(SparkGeometry(700f, 30f, density, start, end).usable)
        assertTrue(geo().usable)
    }

    @Test
    fun `pace is the diagonal from zero at the start to a hundred at the reset`() {
        val g = geo()
        assertEquals(0.0, g.paceAt(start), 0.001)
        assertEquals(100.0, g.paceAt(end), 0.001)
        assertEquals(50.0, g.paceAt(start + (end - start) / 2), 0.001)
    }

    @Test
    fun `a zero-length window does not divide by zero`() {
        val g = SparkGeometry(700f, 400f, density, start, start)
        assertEquals(0f, g.x(start), 0.01f)
        assertEquals(0.0, g.paceAt(start), 0.001)
    }
}
