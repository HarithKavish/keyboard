package com.harithkavish.keyboard;

/**
 * The key layouts, as data.
 *
 * <p>Three pages — letters, symbols, and a second symbol page — each four rows
 * deep. A row's keys carry a width weight rather than a width: ten weight units
 * fill the keyboard, so a 1.5-weight shift key is one and a half letters wide on
 * any screen, and a row adding up to less than ten (the nine-letter home row) is
 * centred by the view.
 *
 * <p>Shift, backspace and enter carry no label. The view draws those three as
 * paths, because no font is guaranteed to have the arrow glyphs and a tofu box
 * on the backspace key is not worth the few lines it saves.
 */
final class Keys {

    /** Codes below zero are commands. Every other code is the character to type. */
    static final int SHIFT = -1;
    static final int BACKSPACE = -2;
    static final int ENTER = -3;
    static final int PAGE_SYMBOLS = -4;
    static final int PAGE_LETTERS = -5;
    static final int PAGE_MORE = -6;

    static final int LETTERS = 0;
    static final int SYMBOLS = 1;
    static final int MORE = 2;

    static final class Key {
        final int code;
        final String label;
        final float weight;

        /** Set by the view every time the keyboard is measured. */
        float x, y, w, h;

        Key(int code, String label, float weight) {
            this.code = code;
            this.label = label;
            this.weight = weight;
        }

        boolean isCommand() {
            return code < 0;
        }

        boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private Keys() {
    }

    static Key[][] page(int page) {
        switch (page) {
            case SYMBOLS:
                return new Key[][]{
                    row("1234567890"),
                    row("@#$%&-+()"),
                    commandRow(PAGE_MORE, "=\\<", "*\"':;!?"),
                    bottomRow(PAGE_LETTERS, "ABC"),
                };
            case MORE:
                return new Key[][]{
                    // ~ ` | bullet root pi divide times pilcrow delta
                    row("~`|•√π÷×¶Δ"),
                    // pound cent euro yen < > = { }
                    row("£¢€¥<>={}"),
                    // backslash copyright registered trademark [ ] caret
                    commandRow(PAGE_SYMBOLS, "?123", "\\©®™[]^"),
                    bottomRow(PAGE_LETTERS, "ABC"),
                };
            default:
                return new Key[][]{
                    row("qwertyuiop"),
                    row("asdfghjkl"),
                    commandRow(SHIFT, "", "zxcvbnm"),
                    bottomRow(PAGE_SYMBOLS, "?123"),
                };
        }
    }

    /** One weight unit per character. */
    private static Key[] row(String chars) {
        Key[] keys = new Key[chars.length()];
        for (int i = 0; i < chars.length(); i++) {
            keys[i] = new Key(chars.charAt(i), String.valueOf(chars.charAt(i)), 1f);
        }
        return keys;
    }

    /** A wide command key, then the row's characters, then backspace. */
    private static Key[] commandRow(int leftCode, String leftLabel, String chars) {
        Key[] middle = row(chars);
        Key[] keys = new Key[middle.length + 2];
        keys[0] = new Key(leftCode, leftLabel, 1.5f);
        System.arraycopy(middle, 0, keys, 1, middle.length);
        keys[keys.length - 1] = new Key(BACKSPACE, "", 1.5f);
        return keys;
    }

    private static Key[] bottomRow(int pageCode, String pageLabel) {
        return new Key[]{
            new Key(pageCode, pageLabel, 1.5f),
            new Key(',', ",", 1f),
            new Key(' ', "", 5f),
            new Key('.', ".", 1f),
            new Key(ENTER, "", 1.5f),
        };
    }
}
