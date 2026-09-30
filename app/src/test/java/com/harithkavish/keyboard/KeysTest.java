package com.harithkavish.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The view lays a row out flush to both edges when its weights total ten, and
 * centres it at letter width when they total less. Those two cases are the whole
 * layout algorithm, so the tables have to keep meaning what it assumes: every
 * row totals exactly ten, or is a short row that is deliberately narrower.
 */
public class KeysTest {

    private static final float ROW_UNITS = 10f;

    @Test
    public void thePagesHaveTheRowsTheViewExpects() {
        // The letters page carries a number row the symbol pages do not need.
        assertEquals(5, Keys.page(Keys.LETTERS).length);
        assertEquals(4, Keys.page(Keys.SYMBOLS).length);
        assertEquals(4, Keys.page(Keys.MORE).length);
    }

    @Test
    public void everyPageHasAHeightForEveryRow() {
        // The view multiplies row i by height i. A short array would throw at
        // layout time, on a device, in whatever app happened to be open.
        for (int page : new int[]{Keys.LETTERS, Keys.SYMBOLS, Keys.MORE}) {
            assertEquals("page " + page,
                    Keys.page(page).length, Keys.rowHeights(page).length);
        }
    }

    @Test
    public void theNumberRowIsShorterThanALetterRow() {
        float[] heights = Keys.rowHeights(Keys.LETTERS);
        assertTrue(heights[0] < heights[1]);
        for (int i = 1; i < heights.length; i++) {
            assertEquals(1f, heights[i], 0.001f);
        }
    }

    @Test
    public void fullRowsTotalTenUnits() {
        for (int page : new int[]{Keys.LETTERS, Keys.SYMBOLS, Keys.MORE}) {
            Keys.Key[][] rows = Keys.page(page);
            // The nine-letter home row is the one short row, and the number row
            // the letters page gained pushes its index along by one.
            int shortRow = page == Keys.LETTERS ? 2 : 1;
            for (int i = 0; i < rows.length; i++) {
                float total = 0f;
                for (Keys.Key key : rows[i]) {
                    total += key.weight;
                }
                float expected = i == shortRow ? 9f : ROW_UNITS;
                assertEquals("page " + page + " row " + i, expected, total, 0.001f);
            }
        }
    }

    @Test
    public void everyPageCanReachTheOthers() {
        // A page with no way back strands the user on it.
        assertTrue(hasCode(Keys.page(Keys.LETTERS), Keys.PAGE_SYMBOLS));
        assertTrue(hasCode(Keys.page(Keys.SYMBOLS), Keys.PAGE_LETTERS));
        assertTrue(hasCode(Keys.page(Keys.SYMBOLS), Keys.PAGE_MORE));
        assertTrue(hasCode(Keys.page(Keys.MORE), Keys.PAGE_LETTERS));
        assertTrue(hasCode(Keys.page(Keys.MORE), Keys.PAGE_SYMBOLS));
    }

    @Test
    public void everyPageCanTypeSpaceDeleteAndEnter() {
        for (int page : new int[]{Keys.LETTERS, Keys.SYMBOLS, Keys.MORE}) {
            Keys.Key[][] rows = Keys.page(page);
            assertTrue("space on page " + page, hasCode(rows, ' '));
            assertTrue("backspace on page " + page, hasCode(rows, Keys.BACKSPACE));
            assertTrue("enter on page " + page, hasCode(rows, Keys.ENTER));
        }
    }

    @Test
    public void lettersPageIsQwertyUnderANumberRow() {
        Keys.Key[][] rows = Keys.page(Keys.LETTERS);
        assertEquals("1234567890", labels(rows[0]));
        assertEquals("qwertyuiop", labels(rows[1]));
        assertEquals("asdfghjkl", labels(rows[2]));
    }

    @Test
    public void everyPageCanReachEmojiAndAt() {
        for (int page : new int[]{Keys.LETTERS, Keys.SYMBOLS, Keys.MORE}) {
            assertTrue("emoji on page " + page, hasCode(Keys.page(page), Keys.EMOJI));
            assertTrue("at sign on page " + page, hasCode(Keys.page(page), '@'));
        }
    }

    @Test
    public void commandCodesAreDistinct() {
        // They are hand-numbered, and two sharing a value would silently make
        // one key do the other's job.
        int[] codes = {Keys.SHIFT, Keys.BACKSPACE, Keys.ENTER, Keys.PAGE_SYMBOLS,
            Keys.PAGE_LETTERS, Keys.PAGE_MORE, Keys.EMOJI};
        for (int i = 0; i < codes.length; i++) {
            for (int j = i + 1; j < codes.length; j++) {
                assertTrue("codes " + i + " and " + j + " collide", codes[i] != codes[j]);
            }
            assertTrue("command codes must be negative", codes[i] < 0);
        }
    }

    private static String labels(Keys.Key[] row) {
        StringBuilder out = new StringBuilder();
        for (Keys.Key key : row) {
            out.append(key.label);
        }
        return out.toString();
    }

    private static boolean hasCode(Keys.Key[][] rows, int code) {
        for (Keys.Key[] row : rows) {
            for (Keys.Key key : row) {
                if (key.code == code) {
                    return true;
                }
            }
        }
        return false;
    }
}
