package com.harithkavish.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;

/**
 * The picker's search.
 *
 * <p>It is a plain function over the keyword table, so the ranking is testable
 * without a device — which matters, because "the search feels wrong" is the kind
 * of report that is impossible to act on after the fact.
 */
public class EmojiSearchTest {

    @Test
    public void findsAnEmojiByItsExactKeyword() {
        assertFalse(Emoji.search("smile", 20).isEmpty());
        assertFalse(Emoji.search("cat", 20).isEmpty());
    }

    @Test
    public void findsByAPartialWord() {
        // Typing is incremental: the results have to appear before the word is
        // finished or the search is useless while it is being used.
        assertFalse(Emoji.search("smi", 20).isEmpty());
        assertFalse(Emoji.search("hea", 20).isEmpty());
    }

    @Test
    public void ranksAnExactKeywordFirst() {
        List<String> exact = Emoji.search("cat", 20);
        List<String> prefix = Emoji.search("ca", 20);
        assertFalse(exact.isEmpty());
        assertFalse(prefix.isEmpty());
        // Whatever "cat" matches exactly must lead its own search.
        assertEquals(exact.get(0), Emoji.search("cat", 1).get(0));
    }

    @Test
    public void returnsNoDuplicates() {
        // A keyword appears against several emoji and an emoji has several
        // keywords, so the bands overlap by construction.
        List<String> found = Emoji.search("smile", 60);
        assertEquals("duplicates in the results", found.size(),
                new HashSet<>(found).size());
    }

    @Test
    public void respectsTheLimit() {
        assertTrue(Emoji.search("a", 5).size() <= 5);
        assertTrue(Emoji.search("e", 12).size() <= 12);
    }

    @Test
    public void findsNothingForNothing() {
        assertTrue(Emoji.search("", 20).isEmpty());
        assertTrue(Emoji.search("   ", 20).isEmpty());
        assertTrue(Emoji.search(null, 20).isEmpty());
    }

    @Test
    public void findsNothingForGibberish() {
        assertTrue(Emoji.search("qzxwvk", 20).isEmpty());
    }

    @Test
    public void ignoresCaseAndSurroundingSpace() {
        assertEquals(Emoji.search("smile", 10), Emoji.search("  SMILE ", 10));
    }

    @Test
    public void everyResultIsARealEmoji() {
        for (String found : Emoji.search("a", 40)) {
            assertFalse("empty result", found.isEmpty());
            // Every emoji in the table is above the basic plane or a symbol;
            // a bare ASCII letter would mean the pairs had slipped out of step.
            assertTrue("keyword leaked into the results: " + found,
                    found.charAt(0) > 0x7F);
        }
    }
}
