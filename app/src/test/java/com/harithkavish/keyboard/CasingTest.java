package com.harithkavish.keyboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * What the strip draws, what a tapped suggestion commits and what the space bar
 * inserts all go through here, so a disagreement between them is a bug in one
 * place rather than three.
 */
public class CasingTest {

    @Test
    public void plainTypingAsksForNothing() {
        assertEquals(Casing.Mode.NONE, Casing.of("hi"));
        assertEquals(Casing.Mode.NONE, Casing.of(""));
        assertEquals(Casing.Mode.NONE, Casing.of(null));
    }

    @Test
    public void oneCapitalAsksForATitle() {
        // The case that started this: typing "H" should offer "Hi", not "hi".
        assertEquals(Casing.Mode.TITLE, Casing.of("H"));
        assertEquals(Casing.Mode.TITLE, Casing.of("Ha"));
        assertEquals(Casing.Mode.TITLE, Casing.of("Harith"));
    }

    @Test
    public void allCapitalsAskForAllCapitals() {
        assertEquals(Casing.Mode.UPPER, Casing.of("HA"));
        assertEquals(Casing.Mode.UPPER, Casing.of("HARI"));
    }

    @Test
    public void appliesTheModeToAWord() {
        assertEquals("hi", Casing.apply("hi", Casing.Mode.NONE));
        assertEquals("Hi", Casing.apply("hi", Casing.Mode.TITLE));
        assertEquals("HI", Casing.apply("hi", Casing.Mode.UPPER));
    }

    @Test
    public void leavesPunctuationAlone() {
        // The strip offers full stops too, and upper-casing one is meaningless.
        assertEquals(".", Casing.apply(".", Casing.Mode.UPPER));
        assertEquals("?", Casing.apply("?", Casing.Mode.TITLE));
    }

    @Test
    public void keepsLearntCasingUnderATitleRequest() {
        // A name already comes back capitalised; asking for a title changes
        // nothing, and must not lower the rest of it.
        assertEquals("Harith", Casing.apply("Harith", Casing.Mode.TITLE));
        assertEquals("HARITH", Casing.apply("Harith", Casing.Mode.UPPER));
    }

    @Test
    public void takesTheStrongerOfTwoRequests() {
        // Caps lock plus a plain prefix is still caps lock.
        assertEquals(Casing.Mode.UPPER,
                Casing.stronger(Casing.Mode.UPPER, Casing.Mode.NONE));
        assertEquals(Casing.Mode.UPPER,
                Casing.stronger(Casing.Mode.TITLE, Casing.Mode.UPPER));
        assertEquals(Casing.Mode.TITLE,
                Casing.stronger(Casing.Mode.NONE, Casing.Mode.TITLE));
        assertEquals(Casing.Mode.NONE,
                Casing.stronger(Casing.Mode.NONE, Casing.Mode.NONE));
    }

    @Test
    public void survivesMissingModes() {
        assertEquals(Casing.Mode.TITLE, Casing.stronger(null, Casing.Mode.TITLE));
        assertEquals(Casing.Mode.NONE, Casing.stronger(null, null));
        assertEquals("hi", Casing.apply("hi", null));
    }
}
