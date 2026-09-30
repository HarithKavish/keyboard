package com.harithkavish.keyboard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the keyboard suggests, what it corrects, and everything it learns from
 * the person typing.
 *
 * <p>One instance per process, shared by the input method and the settings
 * screen -- an IME service and its app's activities run in the same process, so
 * a singleton is enough and the settings screen's writes are seen by a keyboard
 * that is already open.
 *
 * <p>Several things are learnt, and the switches cover them in two groups
 * because they fail differently: words, word pairs, how a word is capitalised
 * and what punctuation follows it, against emoji habits. A correction the person
 * undoes is remembered as a refusal, not just forgotten -- see
 * {@link #rejectCorrection} -- because a keyboard that re-makes a correction you
 * have already rejected is worse than one that never corrected at all.
 *
 * <p>Everything is bounded. An input method that grows without limit is an
 * input method that eventually stalls on the main thread while saving, so each
 * map has a cap and prunes its weakest entries when it reaches it.
 */
final class Predictor {

    private static final String PREFS = "glass_keyboard_learning";

    private static final String KEY_LEARN_WORDS = "learn_words";
    private static final String KEY_LEARN_EMOJI = "learn_emoji";
    private static final String KEY_AUTOCORRECT = "autocorrect";
    private static final String KEY_UNIGRAM = "unigram";
    private static final String KEY_BIGRAM = "bigram";
    private static final String KEY_EMOJI = "emoji";
    private static final String KEY_BLOCKED = "blocked";
    private static final String KEY_FORCED = "forced";
    private static final String KEY_CASING = "casing";
    private static final String KEY_PUNCT = "punct";

    /**
     * How far a word typed by hand outweighs a seed word. A seed word scores at
     * most {@link #SEED_TOP}; one typed twice passes almost all of them, which
     * is the intent -- the seed list is a cold start, not an opinion.
     */
    private static final int LEARNED_WEIGHT = 400;
    private static final int SEED_TOP = 1000;

    private static final int MAX_UNIGRAM = 3000;
    private static final int MAX_BIGRAM_KEYS = 1500;
    private static final int MAX_EMOJI_KEYS = 800;
    private static final int SAVE_EVERY = 12;

    /** Shorter than this and a keyword prefix match is too loose to be useful. */
    private static final int MIN_PREFIX = 2;
    /** Shorter than this and a keyword inside a longer word means nothing. */
    private static final int MIN_COMPOUND = 3;

    /** A sentence this short is not yet asking to be ended. */
    private static final int MIN_WORDS_FOR_PUNCTUATION = 3;

    private static final String[] QUESTION_STARTS = {
        "what", "where", "when", "why", "who", "whose", "which", "how",
        "is", "are", "was", "were", "do", "does", "did", "can", "could",
        "will", "would", "should", "shall", "may", "have", "has", "am",
    };
    private static final String[] EXCLAMATION_WORDS = {
        "thanks", "thank", "congratulations", "congrats", "wow", "great",
        "awesome", "amazing", "brilliant", "perfect", "hooray",
        "welcome", "excellent", "lovely",
    };

    /**
     * Words that are capitalised however they are typed. A lone "i" is the one
     * that matters; its contractions come along because they fail the same way.
     * Pairs: what was typed, what it becomes.
     */
    private static final String[] ALWAYS_CAPITAL = {
        "i", "I", "i'm", "I'm", "i've", "I've", "i'll", "I'll",
        "i'd", "I'd", "i'am", "I am",
    };

    private static Predictor instance;

    /**
     * Where the learnt state lives. An interface, not SharedPreferences, so the
     * learning can be tested without an Android runtime -- the rules about what
     * is learnt and what is refused are the part worth testing, and they do not
     * need a device to be wrong.
     */
    interface Store {
        String get(String key, String fallback);

        boolean getFlag(String key, boolean fallback);

        void put(String key, String value);

        void putFlag(String key, boolean value);

        void remove(String... keys);
    }

    private final Store store;

    /** Seed word -> score. Never mutated after construction. */
    private final Map<String, Integer> seed = new HashMap<>();
    private final List<String> seedOrder = new ArrayList<>();
    /** Keyword -> emoji, in the order the table lists them. */
    private final Map<String, List<String>> keywords = new LinkedHashMap<>();

    private final Map<String, Integer> unigram = new HashMap<>();
    private final Map<String, Map<String, Integer>> bigram = new HashMap<>();
    private final Map<String, Map<String, Integer>> emoji = new HashMap<>();
    /** Lower-cased word -> how the person writes it, with a count behind it. */
    private final Map<String, Map<String, Integer>> casing = new HashMap<>();
    /** Word -> the punctuation the person puts after it. */
    private final Map<String, Map<String, Integer>> punctuation = new HashMap<>();
    /** "typed>correction" pairs the person has undone. */
    private final Set<String> blocked = new HashSet<>();
    /** What the person actually meant, the last time they fixed a word by hand. */
    private final Map<String, String> forced = new HashMap<>();

    private final Set<String> questionStarts = new HashSet<>();
    private final Set<String> exclamationWords = new HashSet<>();
    private final Map<String, String> alwaysCapital = new HashMap<>();

    private boolean learnWords;
    private boolean learnEmoji;
    private boolean autoCorrect;
    private int unsaved;

    static synchronized Predictor get(Context context) {
        if (instance == null) {
            final SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            instance = new Predictor(new Store() {
                @Override
                public String get(String key, String fallback) {
                    return prefs.getString(key, fallback);
                }

                @Override
                public boolean getFlag(String key, boolean fallback) {
                    return prefs.getBoolean(key, fallback);
                }

                @Override
                public void put(String key, String value) {
                    prefs.edit().putString(key, value).apply();
                }

                @Override
                public void putFlag(String key, boolean value) {
                    prefs.edit().putBoolean(key, value).apply();
                }

                @Override
                public void remove(String... keys) {
                    SharedPreferences.Editor editor = prefs.edit();
                    for (String key : keys) {
                        editor.remove(key);
                    }
                    editor.apply();
                }
            });
        }
        return instance;
    }

    Predictor(Store store) {
        this.store = store;

        String[] words = Vocabulary.WORDS.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            // Linear falloff, so the head of the list is clearly preferred and
            // the tail still beats a word that has never been seen at all.
            int score = Math.max(1, SEED_TOP - (SEED_TOP * i) / Math.max(1, words.length));
            seed.put(words[i], score);
            seedOrder.add(words[i]);
        }
        seedBigrams();
        for (int i = 0; i + 1 < Emoji.KEYWORDS.length; i += 2) {
            String keyword = Emoji.KEYWORDS[i];
            List<String> glyphs = keywords.get(keyword);
            if (glyphs == null) {
                glyphs = new ArrayList<>(2);
                keywords.put(keyword, glyphs);
            }
            glyphs.add(Emoji.KEYWORDS[i + 1]);
        }
        Collections.addAll(questionStarts, QUESTION_STARTS);
        Collections.addAll(exclamationWords, EXCLAMATION_WORDS);
        for (int i = 0; i + 1 < ALWAYS_CAPITAL.length; i += 2) {
            alwaysCapital.put(ALWAYS_CAPITAL[i], ALWAYS_CAPITAL[i + 1]);
        }

        learnWords = store.getFlag(KEY_LEARN_WORDS, true);
        learnEmoji = store.getFlag(KEY_LEARN_EMOJI, true);
        autoCorrect = store.getFlag(KEY_AUTOCORRECT, true);

        readCounts(store.get(KEY_UNIGRAM, ""), unigram);
        readNested(store.get(KEY_BIGRAM, ""), bigram);
        readNested(store.get(KEY_EMOJI, ""), emoji);
        readNested(store.get(KEY_CASING, ""), casing);
        readNested(store.get(KEY_PUNCT, ""), punctuation);
        for (String entry : split(store.get(KEY_BLOCKED, ""))) {
            blocked.add(entry);
        }
        for (String entry : split(store.get(KEY_FORCED, ""))) {
            int tab = entry.indexOf('\t');
            if (tab > 0) {
                forced.put(entry.substring(0, tab), entry.substring(tab + 1));
            }
        }
    }

    private void seedBigrams() {
        for (String pair : Vocabulary.BIGRAMS) {
            int space = pair.indexOf(' ');
            if (space > 0) {
                bump(bigram, pair.substring(0, space), pair.substring(space + 1), 2);
            }
        }
    }

    // ---------------------------------------------------------------- settings

    boolean isLearnWords() {
        return learnWords;
    }

    boolean isLearnEmoji() {
        return learnEmoji;
    }

    boolean isAutoCorrect() {
        return autoCorrect;
    }

    void setLearnWords(boolean value) {
        learnWords = value;
        store.putFlag(KEY_LEARN_WORDS, value);
    }

    void setLearnEmoji(boolean value) {
        learnEmoji = value;
        store.putFlag(KEY_LEARN_EMOJI, value);
    }

    void setAutoCorrect(boolean value) {
        autoCorrect = value;
        store.putFlag(KEY_AUTOCORRECT, value);
    }

    /**
     * Forgets everything learnt, leaving the seed data and the switches alone.
     * The corrections the person has rejected go too: they are learnt state, and
     * a reset that kept them would leave the keyboard refusing corrections for
     * reasons the person can no longer see.
     */
    void resetLearning() {
        unigram.clear();
        emoji.clear();
        casing.clear();
        punctuation.clear();
        blocked.clear();
        forced.clear();
        // Seeded pairs are rebuilt; only the learnt ones are dropped.
        bigram.clear();
        seedBigrams();
        unsaved = 0;
        store.remove(KEY_UNIGRAM, KEY_BIGRAM, KEY_EMOJI, KEY_BLOCKED, KEY_FORCED,
                KEY_CASING, KEY_PUNCT);
    }

    /** True when there is anything to forget, so the button can say so. */
    boolean hasLearned() {
        return !unigram.isEmpty() || !emoji.isEmpty() || !forced.isEmpty()
                || !blocked.isEmpty() || !casing.isEmpty() || !punctuation.isEmpty();
    }

    // -------------------------------------------------------------- prediction

    /**
     * Up to three words, best first. With a prefix these are completions of the
     * word being typed; without one they are guesses at the next word.
     *
     * <p>Each comes back in the casing the person uses for it, so a name typed as
     * "Harith" is offered as "Harith" and not as "harith".
     */
    List<String> predictWords(String previous, String prefix) {
        List<String> out = new ArrayList<>(3);
        if (prefix != null && !prefix.isEmpty()) {
            String lower = prefix.toLowerCase();
            List<String> pool = new ArrayList<>();
            for (String word : seedOrder) {
                if (word.startsWith(lower)) {
                    pool.add(word);
                }
            }
            for (String word : unigram.keySet()) {
                if (word.startsWith(lower) && !seed.containsKey(word)) {
                    pool.add(word);
                }
            }
            sortByScore(pool);
            for (String word : pool) {
                if (!word.equals(lower) && out.size() < 3) {
                    out.add(display(word));
                }
            }
            // The literal typing always stays reachable, so there is a way to
            // keep a word the keyboard does not know.
            if (out.size() < 3 && !out.contains(prefix)) {
                out.add(prefix);
            }
            return out;
        }

        Map<String, Integer> next = previous == null ? null : bigram.get(previous.toLowerCase());
        if (next != null) {
            List<String> pool = new ArrayList<>(next.keySet());
            final Map<String, Integer> counts = next;
            Collections.sort(pool, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    int byCount = counts.get(b) - counts.get(a);
                    return byCount != 0 ? byCount : a.compareTo(b);
                }
            });
            for (String word : pool) {
                if (out.size() < 3) {
                    out.add(display(word));
                }
            }
        }
        for (int i = 0; i < seedOrder.size() && out.size() < 3; i++) {
            String word = display(seedOrder.get(i));
            if (!out.contains(word)) {
                out.add(word);
            }
        }
        return out;
    }

    /**
     * Up to two emoji for the context, best first. May be empty, and the strip
     * spreads its words out when it is.
     *
     * <p>Matching is deliberately loose in three directions, because a strict one
     * is useless: the whole word, a keyword the word begins with (so "tickmark"
     * finds the tick), and a keyword beginning with what has been typed so far
     * (so "smi" already finds the smile). An earlier version matched only whole
     * words against a single keyword each, which found almost nothing.
     */
    List<String> predictEmoji(String previous, String current) {
        List<String> out = new ArrayList<>(2);
        addEmojiFor(current, out);
        if (out.isEmpty()) {
            addEmojiFor(previous, out);
        }
        return out;
    }

    private void addEmojiFor(String word, List<String> out) {
        if (word == null || word.isEmpty() || out.size() >= 2 || !isWordLike(word)) {
            return;
        }
        String key = word.toLowerCase();

        // 1. What this person actually picks after this word beats any table.
        Map<String, Integer> learnt = emoji.get(key);
        if (learnt != null) {
            List<String> pool = new ArrayList<>(learnt.keySet());
            final Map<String, Integer> counts = learnt;
            Collections.sort(pool, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return counts.get(b) - counts.get(a);
                }
            });
            addAll(out, pool);
        }
        // 2. An exact keyword.
        addAll(out, keywords.get(key));
        if (out.size() >= 2) {
            return;
        }
        // 3. A keyword the typed word starts with: "tickmark" -> "tick".
        String bestCompound = null;
        for (Map.Entry<String, List<String>> entry : keywords.entrySet()) {
            String keyword = entry.getKey();
            if (keyword.length() >= MIN_COMPOUND && key.length() > keyword.length()
                    && key.startsWith(keyword)
                    && (bestCompound == null || keyword.length() > bestCompound.length())) {
                bestCompound = keyword;
            }
        }
        if (bestCompound != null) {
            addAll(out, keywords.get(bestCompound));
            if (out.size() >= 2) {
                return;
            }
        }
        // 4. A keyword that starts with what has been typed: "smi" -> "smile".
        if (key.length() >= MIN_PREFIX) {
            for (Map.Entry<String, List<String>> entry : keywords.entrySet()) {
                if (entry.getKey().startsWith(key)) {
                    addAll(out, entry.getValue());
                    if (out.size() >= 2) {
                        return;
                    }
                }
            }
        }
    }

    private static void addAll(List<String> out, List<String> glyphs) {
        if (glyphs == null) {
            return;
        }
        for (String glyph : glyphs) {
            if (out.size() < 2 && !out.contains(glyph)) {
                out.add(glyph);
            }
        }
    }

    /**
     * The punctuation that would end this sentence, or null to offer none.
     *
     * <p>Deliberately not always on. A slot spent on a full stop is a slot not
     * spent on a word, so it is only worth taking once there is a sentence long
     * enough to be worth ending.
     *
     * @param sentenceStart the first word since the last full stop, which is what
     *     says whether this is a question
     * @param previous the word the punctuation would follow
     * @param wordsSoFar words since the last sentence ended
     */
    String predictPunctuation(String sentenceStart, String previous, int wordsSoFar) {
        if (wordsSoFar < MIN_WORDS_FOR_PUNCTUATION || previous == null
                || !isWordLike(previous)) {
            return null;
        }
        Map<String, Integer> learnt = punctuation.get(previous.toLowerCase());
        if (learnt != null && !learnt.isEmpty()) {
            String best = null;
            int bestCount = 0;
            for (Map.Entry<String, Integer> e : learnt.entrySet()) {
                if (e.getValue() > bestCount) {
                    bestCount = e.getValue();
                    best = e.getKey();
                }
            }
            if (best != null) {
                return best;
            }
        }
        if (sentenceStart != null && questionStarts.contains(sentenceStart.toLowerCase())) {
            return "?";
        }
        if (exclamationWords.contains(previous.toLowerCase())
                || (sentenceStart != null
                    && exclamationWords.contains(sentenceStart.toLowerCase()))) {
            return "!";
        }
        return ".";
    }

    // ------------------------------------------------------------- autocorrect

    /**
     * The word this one should become, or null to leave it alone.
     *
     * <p>Returns null for anything it already knows, anything the person has
     * told it to leave alone, and anything it cannot find a clearly better
     * candidate for. "Clearly better" matters: correcting on a weak match is how
     * a keyboard earns a reputation for mangling names.
     */
    String correct(String typed) {
        if (!autoCorrect || typed == null || typed.isEmpty()) {
            return null;
        }
        String lower = typed.toLowerCase();
        if (!isWordLike(lower)) {
            return null;
        }
        // Checked before the length guard below, because the word this exists
        // for is one letter long.
        String capital = alwaysCapital.get(lower);
        if (capital != null) {
            return capital.equals(typed)
                    || blocked.contains(lower + ">" + capital.toLowerCase())
                    ? null : capital;
        }
        if (typed.length() < 2) {
            // Anything else this short is far too easy to "fix" into a word the
            // person never meant.
            return null;
        }
        String settled = forced.get(lower);
        if (settled != null) {
            return settled.equals(lower) ? null : reshape(typed, settled);
        }
        if (seed.containsKey(lower) || unigram.containsKey(lower)) {
            return null;
        }

        int allowed = lower.length() <= 4 ? 1 : 2;
        String best = null;
        int bestScore = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : allWords()) {
            int gap = Math.abs(candidate.length() - lower.length());
            if (gap > allowed) {
                continue;
            }
            if (blocked.contains(lower + ">" + candidate)) {
                continue;
            }
            int distance = distanceWithin(lower, candidate, allowed);
            if (distance > allowed) {
                continue;
            }
            int score = score(candidate);
            if (distance < bestDistance || (distance == bestDistance && score > bestScore)) {
                bestDistance = distance;
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? null : reshape(typed, best);
    }

    /**
     * Remembers that a correction was wrong. Called when the person undoes one,
     * which is the only unambiguous signal a keyboard ever gets about its own
     * mistakes.
     */
    void rejectCorrection(String typed, String corrected) {
        if (typed == null || corrected == null) {
            return;
        }
        blocked.add(typed.toLowerCase() + ">" + corrected.toLowerCase());
        // Rejecting is also evidence the typed form is a real word.
        if (learnWords) {
            bumpCount(unigram, typed.toLowerCase(), 1);
        }
        touch();
    }

    /**
     * Remembers what the person replaced a word with after undoing a correction,
     * so the same typing lands on the right word next time rather than merely
     * being left alone.
     */
    void learnCorrection(String typed, String replacement) {
        if (typed == null || replacement == null || typed.equalsIgnoreCase(replacement)) {
            return;
        }
        forced.put(typed.toLowerCase(), replacement.toLowerCase());
        if (learnWords) {
            bumpCount(unigram, replacement.toLowerCase(), 2);
        }
        touch();
    }

    // ---------------------------------------------------------------- learning

    /**
     * Learns a word, the pair it forms with the one before it, and how it is
     * capitalised.
     *
     * <p>Casing is only recorded mid-sentence. A word at the start of a sentence
     * is capitalised because it is at the start of a sentence, and taking that as
     * evidence would eventually capitalise half the dictionary.
     */
    void learnWord(String previous, String word) {
        if (!learnWords || word == null || !isWordLike(word)) {
            return;
        }
        String lower = word.toLowerCase();
        bumpCount(unigram, lower, 1);
        if (previous != null && isWordLike(previous)) {
            bump(bigram, previous.toLowerCase(), lower, 1);
            if (!word.equals(lower)) {
                bump(casing, lower, word, 1);
            }
        }
        touch();
    }

    void learnEmojiFor(String word, String picked) {
        if (!learnEmoji || picked == null || word == null || !isWordLike(word)) {
            return;
        }
        bump(emoji, word.toLowerCase(), picked, 1);
        touch();
    }

    void learnPunctuation(String previous, String mark) {
        if (!learnWords || previous == null || mark == null || !isWordLike(previous)) {
            return;
        }
        bump(punctuation, previous.toLowerCase(), mark, 1);
        touch();
    }

    /** How this person writes the word, if they write it any particular way. */
    String display(String lower) {
        Map<String, Integer> forms = casing.get(lower);
        if (forms == null || forms.isEmpty()) {
            return lower;
        }
        String best = lower;
        // Zero, not one: a single mid-sentence capital is already a deliberate
        // act, and requiring a second one means tapping a name into the strip
        // once teaches the keyboard nothing.
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : forms.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private void touch() {
        if (++unsaved >= SAVE_EVERY) {
            save();
        }
    }

    /** Called when the keyboard closes, so a session is never lost to a cap. */
    void save() {
        unsaved = 0;
        prune();
        StringBuilder blockedOut = new StringBuilder();
        for (String entry : blocked) {
            blockedOut.append(entry).append('\n');
        }
        StringBuilder forcedOut = new StringBuilder();
        for (Map.Entry<String, String> e : forced.entrySet()) {
            forcedOut.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        store.put(KEY_UNIGRAM, writeCounts(unigram));
        store.put(KEY_BIGRAM, writeNested(bigram));
        store.put(KEY_EMOJI, writeNested(emoji));
        store.put(KEY_CASING, writeNested(casing));
        store.put(KEY_PUNCT, writeNested(punctuation));
        store.put(KEY_BLOCKED, blockedOut.toString());
        store.put(KEY_FORCED, forcedOut.toString());
    }

    // ----------------------------------------------------------------- helpers

    /** Letters and apostrophes only: digits and punctuation are not words. */
    static boolean isWordLike(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetter(c) && c != '\'') {
                return false;
            }
        }
        return true;
    }

    /** Gives a correction the capitalisation of the word it replaces. */
    private static String reshape(String typed, String replacement) {
        if (typed.isEmpty() || !Character.isUpperCase(typed.charAt(0))) {
            return replacement;
        }
        return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
    }

    private int score(String word) {
        Integer learnt = unigram.get(word);
        Integer seeded = seed.get(word);
        return (learnt == null ? 0 : learnt * LEARNED_WEIGHT) + (seeded == null ? 0 : seeded);
    }

    private void sortByScore(List<String> pool) {
        Collections.sort(pool, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int byScore = score(b) - score(a);
                if (byScore != 0) {
                    return byScore;
                }
                int byLength = a.length() - b.length();
                return byLength != 0 ? byLength : a.compareTo(b);
            }
        });
    }

    private Set<String> allWords() {
        Set<String> all = new HashSet<>(seed.keySet());
        all.addAll(unigram.keySet());
        return all;
    }

    /**
     * Edit distance, giving up as soon as it cannot come in at or under
     * {@code max}. The early exit is what keeps this usable: it runs against
     * every known word on every word boundary.
     *
     * <p>Counts a swapped pair of neighbours as ONE edit, not two. That is the
     * difference between "teh" correcting to "the" and not correcting at all:
     * transposition is the commonest typo there is, and plain Levenshtein scores
     * it 2, which is over budget for any word of four letters or fewer.
     */
    static int distanceWithin(String a, String b, int max) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > max) {
            return max + 1;
        }
        int[] beforePrevious = new int[m + 1];
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            current[0] = i;
            int rowBest = current[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                int best = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
                if (i > 1 && j > 1
                        && ca == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    best = Math.min(best, beforePrevious[j - 2] + 1);
                }
                current[j] = best;
                rowBest = Math.min(rowBest, best);
            }
            if (rowBest > max) {
                return max + 1;
            }
            int[] recycled = beforePrevious;
            beforePrevious = previous;
            previous = current;
            current = recycled;
        }
        return previous[m];
    }

    private static void bump(Map<String, Map<String, Integer>> map, String key,
                             String value, int by) {
        Map<String, Integer> inner = map.get(key);
        if (inner == null) {
            inner = new HashMap<>();
            map.put(key, inner);
        }
        Integer had = inner.get(value);
        inner.put(value, (had == null ? 0 : had) + by);
    }

    private static void bumpCount(Map<String, Integer> map, String key, int by) {
        Integer had = map.get(key);
        map.put(key, (had == null ? 0 : had) + by);
    }

    /**
     * Drops the weakest entries once a map is over its cap. Without this, saving
     * eventually becomes a multi-megabyte string write on the main thread every
     * few words.
     */
    private void prune() {
        if (unigram.size() > MAX_UNIGRAM) {
            List<String> words = new ArrayList<>(unigram.keySet());
            sortByScore(words);
            for (String word : words.subList(MAX_UNIGRAM, words.size())) {
                unigram.remove(word);
            }
        }
        pruneKeys(bigram, MAX_BIGRAM_KEYS);
        pruneKeys(emoji, MAX_EMOJI_KEYS);
        pruneKeys(casing, MAX_EMOJI_KEYS);
        pruneKeys(punctuation, MAX_EMOJI_KEYS);
    }

    private static void pruneKeys(Map<String, Map<String, Integer>> map, int cap) {
        if (map.size() <= cap) {
            return;
        }
        List<String> keys = new ArrayList<>(map.keySet());
        final Map<String, Map<String, Integer>> source = map;
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return total(source.get(b)) - total(source.get(a));
            }
        });
        for (String key : keys.subList(cap, keys.size())) {
            map.remove(key);
        }
    }

    private static int total(Map<String, Integer> counts) {
        int sum = 0;
        for (int value : counts.values()) {
            sum += value;
        }
        return sum;
    }

    private static String[] split(String stored) {
        if (stored == null || stored.isEmpty()) {
            return new String[0];
        }
        return stored.split("\n");
    }

    private static void readCounts(String stored, Map<String, Integer> into) {
        for (String line : split(stored)) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                try {
                    into.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1)));
                } catch (NumberFormatException ignored) {
                    // A corrupt line costs one word, not the whole store.
                }
            }
        }
    }

    private static void readNested(String stored, Map<String, Map<String, Integer>> into) {
        for (String line : split(stored)) {
            String[] parts = line.split("\t");
            if (parts.length == 3) {
                try {
                    bump(into, parts[0], parts[1], Integer.parseInt(parts[2]));
                } catch (NumberFormatException ignored) {
                    // As above.
                }
            }
        }
    }

    private static String writeCounts(Map<String, Integer> map) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            out.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        return out.toString();
    }

    private static String writeNested(Map<String, Map<String, Integer>> map) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Map<String, Integer>> outer : map.entrySet()) {
            for (Map.Entry<String, Integer> inner : outer.getValue().entrySet()) {
                out.append(outer.getKey()).append('\t')
                        .append(inner.getKey()).append('\t')
                        .append(inner.getValue()).append('\n');
            }
        }
        return out.toString();
    }
}
