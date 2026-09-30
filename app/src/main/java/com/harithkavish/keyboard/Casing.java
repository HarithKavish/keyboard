package com.harithkavish.keyboard;

import java.util.Locale;

/**
 * How a suggestion should be capitalised.
 *
 * <p>Its own class because three separate things need the same answer and must
 * not disagree: what the strip draws, what a tapped suggestion commits, and what
 * the space bar inserts when it completes a word. If those drifted apart, the
 * strip would offer "Hi" and type "hi".
 *
 * <p>Two sources feed it. The shift key says what the next character will be,
 * and the word already typed says what the person has actually chosen -- typing
 * "H" asks for "Hi" even though shift has already been consumed by the H.
 */
final class Casing {

    enum Mode {
        /** Leave the word as the predictor gave it, learnt casing and all. */
        NONE,
        /** First letter only: the start of a sentence, or a name. */
        TITLE,
        /** The whole word, for caps lock. */
        UPPER,
    }

    private Casing() {
    }

    /** What the letters already typed imply about the rest of the word. */
    static Mode of(String typed) {
        if (typed == null || typed.isEmpty() || !Character.isUpperCase(typed.charAt(0))) {
            return Mode.NONE;
        }
        if (typed.length() == 1) {
            return Mode.TITLE;
        }
        for (int i = 1; i < typed.length(); i++) {
            if (Character.isLowerCase(typed.charAt(i))) {
                return Mode.TITLE;
            }
        }
        return Mode.UPPER;
    }

    /** The stronger of two requests, so neither source can quietly undo the other. */
    static Mode stronger(Mode a, Mode b) {
        if (a == null) {
            return b == null ? Mode.NONE : b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * Applies a mode to a word. Punctuation and anything not starting with a
     * letter is returned untouched, because the strip offers full stops too.
     */
    static String apply(String word, Mode mode) {
        // A null mode is NONE, not "fall through to TITLE" -- leaving it out
        // here quietly capitalised every suggestion.
        if (word == null || word.isEmpty() || mode == null || mode == Mode.NONE
                || !Character.isLetter(word.charAt(0))) {
            return word;
        }
        if (mode == Mode.UPPER) {
            return word.toUpperCase(Locale.getDefault());
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
