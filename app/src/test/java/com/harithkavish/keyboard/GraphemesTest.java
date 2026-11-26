package com.harithkavish.keyboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * One press of backspace, one character gone.
 *
 * <p>Every case here was a real symptom: an emoji that turned into a replacement
 * box on the first press and only vanished on the second, because deleting one
 * char split a surrogate pair in half.
 */
public class GraphemesTest {

    private static void assertDeletes(String text, int expected, String why) {
        assertEquals(why, expected, Graphemes.lastClusterLength(text));
    }

    @Test
    public void deletesAPlainCharacter() {
        assertDeletes("hello", 1, "an ordinary letter is one char");
        assertDeletes("a", 1, "the only letter is still one char");
    }

    @Test
    public void deletesAWholeSurrogatePair() {
        // The bug that started this: a rocket is two chars, and deleting one of
        // them leaves the other behind as a box.
        assertDeletes("go 🚀", 2, "a non-BMP emoji is a surrogate pair");
    }

    @Test
    public void deletesAnEmojiWithAVariationSelector() {
        // Heart plus U+FE0F.
        assertDeletes("love ❤️", 2, "the selector goes with its base");
    }

    @Test
    public void deletesAnEmojiWithASkinTone() {
        // Thumbs up plus a skin tone modifier: four chars, one character.
        assertDeletes("👍🏽", 4, "a skin tone is part of the emoji");
    }

    @Test
    public void deletesAFlagWhole() {
        // Two regional indicators are one flag.
        assertDeletes("🇮🇳", 4, "a flag is two regional indicators");
    }

    @Test
    public void deletesAZwjSequenceWhole() {
        // Rainbow flag: waving flag, selector, ZWJ, rainbow.
        String rainbow = "🏳️‍🌈";
        assertDeletes(rainbow, rainbow.length(), "a joined sequence is one character");
    }

    @Test
    public void deletesAKeycapWhole() {
        // '1' + U+FE0F + U+20E3.
        assertDeletes("1️⃣", 3, "a keycap is its digit plus its marks");
    }

    @Test
    public void takesOnlyTheLastOfSeveralEmoji() {
        assertDeletes("🚀🚀", 2, "one press removes one emoji");
    }

    @Test
    public void leavesAPlainLetterAfterAnEmojiAlone() {
        assertDeletes("🚀a", 1, "a letter after an emoji is still one char");
    }

    @Test
    public void handlesEmptyInput() {
        assertDeletes("", 0, "nothing to delete");
    }

    @Test
    public void neverRunsOffTheStartOfTheText() {
        // A lone joiner or modifier with no base is malformed, but a keyboard
        // still has to delete something finite rather than loop or throw.
        int length = Graphemes.lastClusterLength("‍");
        assertEquals(1, length);
        assertEquals(2, Graphemes.lastClusterLength("🏽"));
    }
}
