package com.harithkavish.keyboard;

/**
 * How much text one press of backspace should remove.
 *
 * <p>Its own class, with no Android in it, so the rules can be tested directly.
 * Getting this wrong is not subtle: an emoji deleted one char at a time turns
 * into a replacement box on the first press and only disappears on the second,
 * which is exactly what this keyboard did before.
 */
final class Graphemes {

    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int COMBINING_KEYCAP = 0x20E3;
    private static final int REGIONAL_FIRST = 0x1F1E6;
    private static final int REGIONAL_LAST = 0x1F1FF;
    private static final int SKIN_TONE_FIRST = 0x1F3FB;
    private static final int SKIN_TONE_LAST = 0x1F3FF;
    private static final int VARIATION_FIRST = 0xFE00;
    private static final int VARIATION_LAST = 0xFE0F;

    private Graphemes() {
    }

    /**
     * The number of chars in the last user-perceived character of {@code text}.
     *
     * <p>Deleting a single char is wrong for anything outside the basic plane,
     * which is most emoji: it splits a surrogate pair and leaves half of one
     * behind. It is equally wrong for a flag (two regional indicators), a skin
     * tone (a base plus a modifier), a keycap, and a family (several emoji joined
     * by zero-width joiners). All of those look like one character, so all of
     * them have to delete like one.
     *
     * <p>Returns 0 only for empty input; callers delete at least one char.
     */
    static int lastClusterLength(CharSequence text) {
        int end = text.length();
        if (end == 0) {
            return 0;
        }
        int cp = Character.codePointBefore(text, end);
        int i = end - Character.charCount(cp);

        // A flag is a pair of regional indicators and nothing else.
        if (isRegionalIndicator(cp) && i > 0) {
            int previous = Character.codePointBefore(text, i);
            if (isRegionalIndicator(previous)) {
                return end - (i - Character.charCount(previous));
            }
        }

        while (i > 0) {
            int previous = Character.codePointBefore(text, i);
            int previousStart = i - Character.charCount(previous);
            if (isAttaching(cp)) {
                // What we are holding hangs off whatever precedes it.
                cp = previous;
                i = previousStart;
                continue;
            }
            if (previous == ZERO_WIDTH_JOINER && previousStart > 0) {
                int joined = Character.codePointBefore(text, previousStart);
                cp = joined;
                i = previousStart - Character.charCount(joined);
                continue;
            }
            break;
        }
        return end - i;
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= REGIONAL_FIRST && cp <= REGIONAL_LAST;
    }

    /** Skin tones, variation selectors, keycaps and combining marks. */
    private static boolean isAttaching(int cp) {
        if (cp >= SKIN_TONE_FIRST && cp <= SKIN_TONE_LAST) {
            return true;
        }
        if (cp >= VARIATION_FIRST && cp <= VARIATION_LAST) {
            return true;
        }
        if (cp == COMBINING_KEYCAP) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }
}
