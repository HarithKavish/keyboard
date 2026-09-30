package com.harithkavish.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules about what is learnt, what is corrected and what is refused.
 *
 * <p>These run without an Android runtime, against an in-memory store. That is
 * the point of {@link Predictor.Store}: the part of this keyboard most likely to
 * be quietly wrong is the part that changes its own behaviour over time, and a
 * bug there looks like "it just feels worse now" rather than a crash.
 */
public class PredictorTest {

    /** A store that keeps everything in a map, so a test can restart a Predictor. */
    private static final class MemoryStore implements Predictor.Store {
        private final Map<String, String> strings = new HashMap<>();
        private final Map<String, Boolean> flags = new HashMap<>();

        @Override
        public String get(String key, String fallback) {
            String value = strings.get(key);
            return value == null ? fallback : value;
        }

        @Override
        public boolean getFlag(String key, boolean fallback) {
            Boolean value = flags.get(key);
            return value == null ? fallback : value;
        }

        @Override
        public void put(String key, String value) {
            strings.put(key, value);
        }

        @Override
        public void putFlag(String key, boolean value) {
            flags.put(key, value);
        }

        @Override
        public void remove(String... keys) {
            for (String key : keys) {
                strings.remove(key);
            }
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final Predictor predictor = new Predictor(store);

    // ------------------------------------------------------------ autocorrect

    @Test
    public void leavesKnownWordsAlone() {
        assertNull(predictor.correct("the"));
        assertNull(predictor.correct("keyboard") == null ? null : "corrected a word it knows");
    }

    @Test
    public void leavesVeryShortInputAlone() {
        // Single letters are far too easy to "fix" into something unwanted.
        assertNull(predictor.correct("a"));
        assertNull(predictor.correct("x"));
    }

    @Test
    public void leavesNonWordsAlone() {
        assertNull(predictor.correct("123"));
        assertNull(predictor.correct("a1b2"));
    }

    @Test
    public void fixesATransposition() {
        // The commonest typo of all, and the one plain Levenshtein misses on a
        // three letter word.
        assertEquals("the", predictor.correct("teh"));
    }

    @Test
    public void keepsTheCapitalOfTheWordItReplaces() {
        assertEquals("The", predictor.correct("Teh"));
    }

    // ------------------------------------------------- learning from a refusal

    @Test
    public void doesNotRepeatACorrectionThatWasUndone() {
        String corrected = predictor.correct("teh");
        assertEquals("the", corrected);

        predictor.rejectCorrection("teh", corrected);

        assertNull("a correction the person undid must not come back",
                predictor.correct("teh"));
    }

    @Test
    public void learnsWhatTheWordShouldHaveBeen() {
        predictor.rejectCorrection("hte", "the");
        // The person backspaced and typed what they actually meant.
        predictor.learnCorrection("hte", "hate");

        assertEquals("hate", predictor.correct("hte"));
    }

    @Test
    public void aRefusalSurvivesARestart() {
        predictor.rejectCorrection("teh", "the");
        predictor.learnCorrection("teh", "tech");
        predictor.save();

        Predictor reloaded = new Predictor(store);
        assertEquals("tech", reloaded.correct("teh"));
    }

    // ---------------------------------------------------------- word learning

    @Test
    public void aTypedWordOutranksTheSeedList() {
        String unseen = "harith";
        List<String> before = predictor.predictWords(null, "har");
        assertFalse(before.contains(unseen));

        for (int i = 0; i < 3; i++) {
            predictor.learnWord("hello", unseen);
        }

        assertEquals(unseen, predictor.predictWords(null, "har").get(0));
    }

    @Test
    public void learnsWhichWordFollowsWhich() {
        for (int i = 0; i < 4; i++) {
            predictor.learnWord("glass", "keyboard");
        }
        assertEquals("keyboard", predictor.predictWords("glass", "").get(0));
    }

    @Test
    public void offersThreeWordsAtMost() {
        assertTrue(predictor.predictWords("i", "").size() <= 3);
        assertTrue(predictor.predictWords(null, "th").size() <= 3);
    }

    @Test
    public void alwaysLeavesTheTypedWordReachable() {
        // Otherwise there is no way to keep a word the keyboard does not know.
        List<String> words = predictor.predictWords(null, "zzq");
        assertTrue(words.contains("zzq"));
    }

    @Test
    public void learnsNothingWhenWordLearningIsOff() {
        predictor.setLearnWords(false);
        for (int i = 0; i < 5; i++) {
            predictor.learnWord("hello", "harith");
        }
        assertFalse(predictor.predictWords(null, "har").contains("harith"));
        assertFalse(predictor.hasLearned());
    }

    // --------------------------------------------------------- emoji learning

    @Test
    public void hasEmojiForSeededWords() {
        assertFalse(predictor.predictEmoji(null, "birthday").isEmpty());
    }

    @Test
    public void offersTwoEmojiAtMost() {
        assertTrue(predictor.predictEmoji("happy", "love").size() <= 2);
    }

    @Test
    public void learnsAnEmojiHabit() {
        String picked = "🚀";
        for (int i = 0; i < 3; i++) {
            predictor.learnEmojiFor("deploy", picked);
        }
        assertEquals(picked, predictor.predictEmoji(null, "deploy").get(0));
    }

    @Test
    public void learnsNoEmojiWhenEmojiLearningIsOff() {
        predictor.setLearnEmoji(false);
        predictor.learnEmojiFor("deploy", "🐛");
        assertFalse(predictor.hasLearned());
    }

    @Test
    public void wordAndEmojiLearningSwitchIndependently() {
        predictor.setLearnWords(false);
        predictor.setLearnEmoji(true);
        predictor.learnWord("hello", "harith");
        predictor.learnEmojiFor("deploy", "🚀");
        assertTrue("emoji learning must not be governed by the word switch",
                predictor.hasLearned());
        assertFalse(predictor.predictWords(null, "har").contains("harith"));
    }




    // ------------------------------------------------------ the bulk word list

    /** Stands in for res/raw/words.txt, in the same frequency order. */
    private static List<String> bulk() {
        return Arrays.asList("art", "article", "articles", "artist", "artists",
                "artificial", "artwork", "arthritis", "keyboard", "keyboards",
                "programme", "programming", "wonderful", "wondering");
    }

    @Test
    public void keepsPredictingPastTheSeedList() {
        // The reported gap: typing "art" offered nothing but "art", because the
        // 345 hand-written seeds had no longer word beginning that way.
        predictor.addVocabulary(bulk());
        List<String> words = predictor.predictWords(null, "art");
        assertTrue("expected completions, got " + words, words.size() >= 2);
        assertTrue(words.contains("article") || words.contains("artist"));
    }

    @Test
    public void narrowsAsMoreIsTyped() {
        predictor.addVocabulary(bulk());
        assertTrue(predictor.predictWords(null, "artif").contains("artificial"));
        assertTrue(predictor.predictWords(null, "keyb").contains("keyboard"));
    }

    @Test
    public void offersTheCommonestCompletionFirst() {
        // The list is in frequency order, and that order has to survive.
        predictor.addVocabulary(bulk());
        assertEquals("article", predictor.predictWords(null, "articl").get(0));
    }

    @Test
    public void stillOffersOnlyThree() {
        predictor.addVocabulary(bulk());
        assertTrue(predictor.predictWords(null, "art").size() <= 3);
    }

    @Test
    public void aTypedWordStillBeatsTheBulkList() {
        predictor.addVocabulary(bulk());
        for (int i = 0; i < 4; i++) {
            predictor.learnWord("the", "artwork");
        }
        assertEquals("artwork", predictor.predictWords(null, "art").get(0));
    }

    @Test
    public void doesNotCorrectAWordFromTheBulkList() {
        // Before the big list arrived, "arthritis" was unknown and autocorrect
        // would happily turn it into something else.
        predictor.addVocabulary(bulk());
        assertNull(predictor.correct("arthritis"));
        assertNull(predictor.correct("artwork"));
    }

    @Test
    public void correctsATypoIntoTheBulkList() {
        predictor.addVocabulary(bulk());
        assertEquals("keyboard", predictor.correct("keybaord"));
    }

    @Test
    public void survivesAnEmptyOrMissingWordList() {
        // The dictionary is read from a resource at runtime; a keyboard that
        // could not read it must still be a keyboard.
        predictor.addVocabulary(null);
        predictor.addVocabulary(new ArrayList<String>());
        assertFalse(predictor.hasFullVocabulary());
        assertFalse(predictor.predictWords(null, "th").isEmpty());
    }

    @Test
    public void reportsWhetherTheBulkListArrived() {
        assertFalse(predictor.hasFullVocabulary());
        predictor.addVocabulary(bulk());
        assertTrue(predictor.hasFullVocabulary());
    }

    @Test
    public void doesNotDuplicateWordsTheSeedListAlreadyHas() {
        predictor.addVocabulary(Arrays.asList("the", "and", "artificial"));
        List<String> words = predictor.predictWords(null, "th");
        assertEquals("no duplicate suggestions", words.size(),
                new java.util.HashSet<>(words).size());
    }


    // --------------------------------------------------------- contractions

    @Test
    public void knowsContractionsWithTheirApostrophes() {
        // The source word list has no apostrophes at all: it lists "dont" and
        // "cant" as if they were words, which is exactly what the keyboard used
        // to offer.
        assertTrue(predictor.predictWords(null, "don").contains("don't"));
        assertTrue(predictor.predictWords(null, "can").contains("can't"));
    }

    @Test
    public void findsAContractionTypedWithoutItsApostrophe() {
        // Nobody reaches for the apostrophe key mid-word, so the match has to
        // step over it or the suggestion vanishes exactly when it is needed.
        assertTrue(predictor.predictWords(null, "dont").contains("don't"));
        assertTrue(predictor.predictWords(null, "didnt").contains("didn't"));
    }

    @Test
    public void correctsABareContraction() {
        assertEquals("don't", predictor.correct("dont"));
        assertEquals("can't", predictor.correct("cant"));
        assertEquals("didn't", predictor.correct("didnt"));
    }

    @Test
    public void capitalisesAndPunctuatesTheBareFormsOfI() {
        assertEquals("I'm", predictor.correct("im"));
        assertEquals("I've", predictor.correct("ive"));
    }

    @Test
    public void leavesAProperlyTypedContractionAlone() {
        assertNull(predictor.correct("don't"));
        assertNull(predictor.correct("can't"));
    }

    @Test
    public void prefixMatchingStepsOverApostrophes() {
        assertTrue(Predictor.matchesPrefix("don't", "dont"));
        assertTrue(Predictor.matchesPrefix("don't", "don"));
        assertTrue(Predictor.matchesPrefix("don't", "don'"));
        assertTrue(Predictor.matchesPrefix("don't", "don't"));
        assertTrue(Predictor.matchesPrefix("i'm", "im"));
    }

    @Test
    public void prefixMatchingStillRejectsWhatItShould() {
        assertFalse(Predictor.matchesPrefix("don't", "dog"));
        assertFalse(Predictor.matchesPrefix("don't", "donts"));
        assertFalse(Predictor.matchesPrefix("keyboard", "kbd"));
        // A word with no apostrophe must not suddenly match loosely.
        assertFalse(Predictor.matchesPrefix("dont", "dnt"));
    }

    @Test
    public void keepsBothWordsWhereTheBareFormIsAlsoReal() {
        // "its" and "it's" are both words, as are "were" and "we're". Losing
        // either would be worse than the misspelling this fixes.
        assertTrue(predictor.predictWords(null, "it").contains("it's")
                || predictor.predictWords(null, "its").contains("it's"));
        assertNull("the possessive must survive", predictor.correct("its"));
        assertNull("the past tense must survive", predictor.correct("were"));
    }


    // ------------------------------------------------- refusing a completion

    @Test
    public void remembersARefusedCompletion() {
        // The space bar expanded "k" into "keyboard" and the person deleted it.
        // Offering the same expansion again is the complaint this fixes.
        assertFalse(predictor.isRefused("k", "keyboard"));
        predictor.rejectCompletion("k", "keyboard");
        assertTrue(predictor.isRefused("k", "keyboard"));
    }

    @Test
    public void aRefusalIsSpecificToThatPair() {
        predictor.rejectCompletion("k", "keyboard");
        assertFalse("another word from the same letter", predictor.isRefused("k", "keep"));
        assertFalse("the same word from another letter", predictor.isRefused("ke", "keyboard"));
    }

    @Test
    public void aRefusalIgnoresCase() {
        predictor.rejectCompletion("K", "Keyboard");
        assertTrue(predictor.isRefused("k", "keyboard"));
    }

    @Test
    public void aRefusedCompletionAlsoStopsAutocorrect() {
        // One blocked set behind both, so a refusal is not undone by the other
        // path quietly making the same replacement.
        predictor.addVocabulary(Arrays.asList("keyboard"));
        predictor.rejectCompletion("keybaord", "keyboard");
        assertNull(predictor.correct("keybaord"));
    }

    @Test
    public void refusingACompletionDoesNotMakeTheKeyAWord() {
        // rejectCorrection() treats the typed form as evidence of a real word,
        // and should. A completion's key may be a single letter, or the word
        // BEFORE rather than the word replaced, and neither is evidence of
        // anything -- so this is the one thing the two must not share.
        predictor.rejectCompletion("zqx", "zqxword");
        predictor.rejectCorrection("zqy", "zqyword");
        predictor.save();
        String learnt = store.get("unigram", "");
        assertFalse("a completion's key is not a word", learnt.contains("zqx"));
        assertTrue("a rejected correction's typing is", learnt.contains("zqy"));
    }

    @Test
    public void ignoresARefusalWithNothingToKeyOn() {
        // The second completion replaces nothing, so if there is no previous
        // word there is nothing to record against.
        predictor.rejectCompletion("", "keyboard");
        predictor.rejectCompletion(null, "keyboard");
        assertFalse(predictor.isRefused("", "keyboard"));
    }

    @Test
    public void aRefusedCompletionSurvivesSaving() {
        predictor.rejectCompletion("k", "keyboard");
        predictor.save();
        Predictor reloaded = new Predictor(store);
        assertTrue("a refusal that is forgotten on restart is not a refusal",
                reloaded.isRefused("k", "keyboard"));
    }

    @Test
    public void forgettingEverythingForgetsRefusals() {
        predictor.rejectCompletion("k", "keyboard");
        predictor.resetLearning();
        assertFalse(predictor.isRefused("k", "keyboard"));
    }

    // -------------------------------------------------------- always capital

    @Test
    public void capitalisesALoneI() {
        assertEquals("I", predictor.correct("i"));
    }

    @Test
    public void capitalisesTheContractionsOfI() {
        assertEquals("I'm", predictor.correct("i'm"));
        assertEquals("I've", predictor.correct("i've"));
        assertEquals("I'll", predictor.correct("i'll"));
    }

    @Test
    public void leavesAnAlreadyCapitalIAlone() {
        assertNull(predictor.correct("I"));
        assertNull(predictor.correct("I'm"));
    }

    @Test
    public void stillLeavesOtherSingleLettersAlone() {
        // One letter is far too little to correct on, "i" aside.
        assertNull(predictor.correct("a"));
        assertNull(predictor.correct("x"));
        assertNull(predictor.correct("z"));
    }

    @Test
    public void aRejectedCapitalIStaysRejected() {
        predictor.rejectCorrection("i", "I");
        assertNull("undoing it must hold, like any other correction",
                predictor.correct("i"));
    }

    // ------------------------------------------------- finding emoji by keyword

    @Test
    public void findsAnEmojiByAWholeWord() {
        assertFalse(predictor.predictEmoji(null, "fire").isEmpty());
        assertFalse(predictor.predictEmoji(null, "birthday").isEmpty());
    }

    @Test
    public void findsAnEmojiBySynonym() {
        // The reported gap: one word per emoji meant "smiley" found nothing even
        // though "smile" did.
        assertFalse("smile", predictor.predictEmoji(null, "smile").isEmpty());
        assertFalse("smiley", predictor.predictEmoji(null, "smiley").isEmpty());
        assertFalse("grin", predictor.predictEmoji(null, "grin").isEmpty());
    }

    @Test
    public void findsTheTickByEveryNameForIt() {
        String tick = "\u2705";
        assertTrue("tick", predictor.predictEmoji(null, "tick").contains(tick));
        assertTrue("tickmark", predictor.predictEmoji(null, "tickmark").contains(tick));
        assertTrue("check", predictor.predictEmoji(null, "check").contains(tick));
        assertTrue("checkmark", predictor.predictEmoji(null, "checkmark").contains(tick));
        assertTrue("done", predictor.predictEmoji(null, "done").contains(tick));
    }

    @Test
    public void findsAnEmojiFromAPartialWord() {
        // Half a word is all the person has typed when the strip is drawn.
        assertFalse("smi", predictor.predictEmoji(null, "smi").isEmpty());
        assertFalse("birth", predictor.predictEmoji(null, "birth").isEmpty());
        assertFalse("roc", predictor.predictEmoji(null, "roc").isEmpty());
    }

    @Test
    public void ignoresAPrefixTooShortToMeanAnything() {
        // One letter matches half the table, which is worse than matching none.
        assertTrue(predictor.predictEmoji(null, "s").isEmpty());
    }

    @Test
    public void offersNoEmojiForAWordWithNone() {
        // The strip spreads its words out when this happens, so empty is a real
        // answer rather than a failure.
        assertTrue(predictor.predictEmoji(null, "qwxvkj").isEmpty());
    }

    @Test
    public void aKeywordInsideALongerWordStillCounts() {
        // The looseness cuts both ways: "zzzqqq" reaches the sleep emoji because
        // it begins with the keyword "zzz". That is the same rule that makes
        // "tickmark" work, and it is worth keeping despite the odd false hit.
        assertFalse(predictor.predictEmoji(null, "zzzqqq").isEmpty());
    }

    @Test
    public void whatThePersonPicksBeatsTheTable() {
        String bug = "\ud83d\udc1b";
        for (int i = 0; i < 3; i++) {
            predictor.learnEmojiFor("fire", bug);
        }
        assertEquals(bug, predictor.predictEmoji(null, "fire").get(0));
    }

    // ------------------------------------------------------------ word casing

    @Test
    public void remembersHowANameIsWritten() {
        // Mid-sentence, so the capital is a choice rather than a sentence start.
        for (int i = 0; i < 2; i++) {
            predictor.learnWord("call", "Harith");
        }
        assertEquals("Harith", predictor.display("harith"));
        assertTrue(predictor.predictWords(null, "har").contains("Harith"));
    }

    @Test
    public void doesNotTreatASentenceStartAsAName() {
        // No previous word means this capital is just where the sentence began.
        for (int i = 0; i < 4; i++) {
            predictor.learnWord(null, "Hello");
        }
        assertEquals("hello", predictor.display("hello"));
    }

    @Test
    public void casingSurvivesARestart() {
        predictor.learnWord("call", "Kevin");
        predictor.save();
        assertEquals("Kevin", new Predictor(store).display("kevin"));
    }

    @Test
    public void resetForgetsCasing() {
        predictor.learnWord("call", "Harith");
        predictor.resetLearning();
        assertEquals("harith", predictor.display("harith"));
    }

    // ------------------------------------------------------ punctuation

    @Test
    public void offersNoPunctuationUntilThereIsASentence() {
        assertNull(predictor.predictPunctuation("i", "am", 2));
        assertNull(predictor.predictPunctuation(null, null, 9));
    }

    @Test
    public void endsAnOrdinarySentenceWithAFullStop() {
        assertEquals(".", predictor.predictPunctuation("i", "home", 5));
    }

    @Test
    public void endsAQuestionWithAQuestionMark() {
        assertEquals("?", predictor.predictPunctuation("how", "you", 4));
        assertEquals("?", predictor.predictPunctuation("what", "that", 4));
    }

    @Test
    public void endsAnExclamationWithAnExclamationMark() {
        assertEquals("!", predictor.predictPunctuation("congratulations", "you", 4));
    }

    @Test
    public void learnsThePunctuationThePersonActuallyUses() {
        for (int i = 0; i < 3; i++) {
            predictor.learnPunctuation("home", "!");
        }
        assertEquals("!", predictor.predictPunctuation("i", "home", 5));
    }

    // ------------------------------------------------------------------ reset

    @Test
    public void resetForgetsEverythingLearnt() {
        predictor.learnWord("hello", "harith");
        predictor.learnEmojiFor("deploy", "🚀");
        predictor.rejectCorrection("teh", "the");
        assertTrue(predictor.hasLearned());

        predictor.resetLearning();

        assertFalse(predictor.hasLearned());
        assertFalse(predictor.predictWords(null, "har").contains("harith"));
        // The refusal is gone too, so the correction is live again.
        assertEquals("the", predictor.correct("teh"));
    }

    @Test
    public void resetKeepsTheSwitchesAndTheSeedList() {
        predictor.setLearnWords(false);
        predictor.resetLearning();

        assertFalse(predictor.isLearnWords());
        assertTrue(predictor.predictWords(null, "th").contains("that")
                || predictor.predictWords(null, "th").contains("the"));
    }

    @Test
    public void resetSurvivesARestart() {
        predictor.learnWord("hello", "harith");
        predictor.save();
        predictor.resetLearning();

        Predictor reloaded = new Predictor(store);
        assertFalse(reloaded.hasLearned());
    }

    // ---------------------------------------------------------------- helpers

    @Test
    public void distanceCountsASwapAsOneEdit() {
        assertEquals(1, Predictor.distanceWithin("teh", "the", 2));
        assertEquals(1, Predictor.distanceWithin("hte", "the", 2));
    }

    @Test
    public void distanceCountsTheOrdinaryEdits() {
        assertEquals(0, Predictor.distanceWithin("word", "word", 2));
        assertEquals(1, Predictor.distanceWithin("wor", "word", 2));
        assertEquals(1, Predictor.distanceWithin("wordd", "word", 2));
        assertEquals(1, Predictor.distanceWithin("ward", "word", 2));
    }

    @Test
    public void distanceGivesUpRatherThanCountingPast() {
        // Over budget is all the caller needs; the real figure costs more to find.
        assertTrue(Predictor.distanceWithin("completely", "different", 2) > 2);
    }

    @Test
    public void wordLikeRejectsDigitsAndPunctuation() {
        assertTrue(Predictor.isWordLike("hello"));
        assertTrue(Predictor.isWordLike("don't"));
        assertFalse(Predictor.isWordLike("hi5"));
        assertFalse(Predictor.isWordLike("a,b"));
        assertFalse(Predictor.isWordLike(""));
    }

    @Test
    public void survivesACorruptStore() {
        store.put("unigram", "this\tis\tnot\ta\tnumber\nbroken");
        store.put("bigram", "nonsense");
        // A bad line should cost one entry, not the whole keyboard.
        Predictor reloaded = new Predictor(store);
        assertNotNull(reloaded.predictWords(null, "th"));
    }
}
