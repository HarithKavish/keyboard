package com.harithkavish.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The opacity maths and the light/dark judgement.
 *
 * <p>The wallpaper read itself needs a device, but everything that decides what
 * to do with the answer is here, and getting the luminance wrong would make the
 * keyboard adapt the wrong way on exactly the wallpapers it exists to help with.
 */
public class AppearanceTest {

    private static final float EPSILON = 0.001f;

    @Test
    public void theDefaultLeavesTheDesignAlone() {
        // Middle of the slider must be exactly 1.0, or the default build is not
        // the design anyone looked at.
        assertEquals(1f, Appearance.scale(Appearance.DEFAULT_OPACITY, false), EPSILON);
    }

    @Test
    public void thinnerBelowTheMiddleAndSolidAbove() {
        assertTrue(Appearance.scale(0, false) < 1f);
        assertTrue(Appearance.scale(25, false) < 1f);
        assertTrue(Appearance.scale(75, false) > 1f);
        assertTrue(Appearance.scale(100, false) > Appearance.scale(75, false));
    }

    @Test
    public void risesWithoutAJump() {
        float previous = -1f;
        for (int i = 0; i <= 100; i += 5) {
            float scale = Appearance.scale(i, false);
            assertTrue("scale must not fall at " + i, scale >= previous);
            previous = scale;
        }
    }

    @Test
    public void aLightBackdropMakesKeysMoreSolid() {
        for (int i = 0; i <= 100; i += 25) {
            assertTrue("light backdrop at " + i,
                    Appearance.scale(i, true) >= Appearance.scale(i, false));
        }
    }

    @Test
    public void staysWithinItsBounds() {
        // Beyond the top the keys stop being see-through, which is the one thing
        // this keyboard is for.
        assertTrue(Appearance.scale(100, true) <= 2.2f + EPSILON);
        assertTrue(Appearance.scale(0, false) >= 0.6f - EPSILON);
    }

    @Test
    public void clampsInputOutOfRange() {
        assertEquals(Appearance.scale(0, false), Appearance.scale(-40, false), EPSILON);
        assertEquals(Appearance.scale(100, false), Appearance.scale(500, false), EPSILON);
    }

    @Test
    public void scalesAnAlphaWithoutTouchingTheColour() {
        assertEquals(0xFFFFFF, Appearance.scaleAlpha(0x80FFFFFF, 1f) & 0xFFFFFF);
        assertEquals(0x80, (Appearance.scaleAlpha(0x40FFFFFF, 2f) >>> 24) & 0xFF);
        assertEquals(0x20, (Appearance.scaleAlpha(0x40FFFFFF, 0.5f) >>> 24) & 0xFF);
    }

    @Test
    public void neverPushesAlphaPastOpaque() {
        assertEquals(0xFF, (Appearance.scaleAlpha(0xC0FFFFFF, 4f) >>> 24) & 0xFF);
        assertEquals(0x00, (Appearance.scaleAlpha(0x10FFFFFF, 0f) >>> 24) & 0xFF);
    }

    @Test
    public void callsPaleColoursLight() {
        assertTrue("white", Appearance.isLight(0xFFFFFFFF));
        assertTrue("cream", Appearance.isLight(0xFFF5EFE0));
        assertTrue("pale grey", Appearance.isLight(0xFFD8D8D8));
    }

    @Test
    public void callsDarkColoursDark() {
        assertFalse("black", Appearance.isLight(0xFF000000));
        assertFalse("navy", Appearance.isLight(0xFF101828));
        assertFalse("dark grey", Appearance.isLight(0xFF3A3A3A));
    }

    @Test
    public void weighsGreenAboveBlue() {
        // A plain average of the channels calls saturated blue light, which it
        // very much is not. Relative luminance is the whole reason this is not
        // a three-way average.
        assertFalse("pure blue is not a light backdrop", Appearance.isLight(0xFF0000FF));
        assertTrue("pure green is", Appearance.isLight(0xFF00FF00));
    }
}
