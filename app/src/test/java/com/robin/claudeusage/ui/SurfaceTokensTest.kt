package com.robin.claudeusage.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CCRM-3 step 1 landed the token model with nothing wired up. The point of these
 * tests is the claim that made that safe: **handed the defaults, every resolver
 * returns the value the app already renders.** If one of them stops being the
 * identity, some surface has silently moved.
 */
class SurfaceTokensTest {

    private val surface = Color(0xFF1B1B1F)

    @Test
    fun `defaults match what every surface renders today`() {
        val t = SurfaceTokens()
        assertEquals(Palette.DEFAULT, t.accentName)
        assertEquals(BarShape.ROUNDED, t.barShape)
        assertEquals(BackgroundMode.SOLID, t.background)
        assertEquals(TextContrast.AUTO, t.textContrast)
        assertEquals(1f, t.textScale, 0f)

        // The three resolvers a renderer would call, all no-ops on the defaults.
        assertEquals(6.dp, Tokens.barCornerRadius(t.barShape, 12.dp))
        assertEquals(surface, Tokens.background(t.background, surface))
        assertEquals(surface, Tokens.contentColor(t.textContrast, surface))
        assertEquals(15f, Tokens.scaledSp(15f, t.textScale), 0f)
    }

    @Test
    fun `the default accent is the one the cache falls back to`() {
        // UsageCache.themeColorName() hard-codes this string as its default; if the two
        // ever disagree, a fresh install themes the widgets differently from the app.
        assertEquals("Claude Orange", Palette.DEFAULT)
        assertEquals(Palette.DEFAULT, Palette.options.first().name)
    }

    @Test
    fun `rounded bars are pills at every height the app uses`() {
        // 11dp/12dp in-app, 12dp/14dp on widgets, 7dp/8dp in the notification layouts.
        for (h in listOf(7, 8, 11, 12, 14)) {
            assertEquals((h / 2f).dp, Tokens.barCornerRadius(BarShape.ROUNDED, h.dp))
        }
    }

    @Test
    fun `squared bars keep a hairline rather than a true zero`() {
        val r = Tokens.barCornerRadius(BarShape.SQUARE, 12.dp)
        assertEquals(Tokens.SQUARE_BAR_RADIUS, r)
        assertTrue("a 0dp radius reads as a rendering fault", r.value > 0f)
    }

    @Test
    fun `translucent keeps the hue and only drops alpha`() {
        val scrim = Tokens.background(BackgroundMode.TRANSLUCENT, surface)
        assertEquals(Tokens.TRANSLUCENT_ALPHA, scrim.alpha, 0.0001f)
        assertEquals(surface.red, scrim.red, 0.0001f)
        assertEquals(surface.green, scrim.green, 0.0001f)
        assertEquals(surface.blue, scrim.blue, 0.0001f)
    }

    @Test
    fun `no background is fully transparent`() {
        assertEquals(0f, Tokens.background(BackgroundMode.NONE, surface).alpha, 0f)
    }

    @Test
    fun `forced contrast ignores whatever the theme chose`() {
        // The argument is the theme's own pick; LIGHT and DARK must not honour it.
        assertEquals(Tokens.FORCED_LIGHT, Tokens.contentColor(TextContrast.LIGHT, surface))
        assertEquals(Tokens.FORCED_DARK, Tokens.contentColor(TextContrast.DARK, surface))
        assertTrue(Tokens.FORCED_LIGHT.red > Tokens.FORCED_DARK.red)
    }

    @Test
    fun `only the Material You sentinel counts as dynamic`() {
        assertTrue(Tokens.isDynamicAccent(Palette.DYNAMIC))
        assertFalse(Tokens.isDynamicAccent(Palette.DEFAULT))
        for (option in Palette.options) assertFalse(Tokens.isDynamicAccent(option.name))
    }

    @Test
    fun `text scale is proportional in both directions`() {
        assertEquals(30f, Tokens.scaledSp(15f, 2f), 0f)
        assertEquals(7.5f, Tokens.scaledSp(15f, 0.5f), 0f)
    }
}
