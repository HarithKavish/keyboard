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
    public void everyPageHasFourRows() {
        for (int page : new int[]{Keys.LETTERS, Keys.SYMBOLS, Keys.MORE}) {
            assertEquals("page " + page, 4, Keys.page(page).length);
        }
    }

    @Test
    public void fullRowsTotalTenUnits() {
        for (int page : new int[]{Keys.LETTERS, Keys.SYMBOLS, Keys.MORE}) {
            Keys.Key[][] rows = Keys.page(page);
            for (int i = 0; i < rows.length; i++) {
                float total = 0f;
                for (Keys.Key key : rows[i]) {
                    total += key.weight;
                }
                // Row 1 is the short row on every page — nine letters, centred.
                float expected = i == 1 ? 9f : ROW_UNITS;
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
    public void lettersPageIsQwerty() {
        Keys.Key[][] rows = Keys.page(Keys.LETTERS);
        StringBuilder top = new StringBuilder();
        for (Keys.Key key : rows[0]) {
            top.append(key.label);
        }
        assertEquals("qwertyuiop", top.toString());
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
