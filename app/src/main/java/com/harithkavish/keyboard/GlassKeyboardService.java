package com.harithkavish.keyboard;

import android.content.res.Resources;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The input method itself. It owns the keyboard and the emoji picker, and
 * forwards what they report to whatever field currently has focus.
 *
 * <p>It also holds the state that cannot live in either view: what word the
 * cursor is sitting in, how far into a sentence it is, and whether the last
 * thing typed was an autocorrection the person might be about to reject.
 */
public final class GlassKeyboardService extends InputMethodService
        implements GlassKeyboardView.Listener, EmojiPanelView.Listener,
        SuggestionStripView.Listener {

    /**
     * Enough to find the sentence the cursor is in. Longer than it needs to be
     * for the current word alone, because automatic capitalisation and the
     * punctuation suggestion both depend on where the sentence started.
     */
    private static final int CONTEXT_CHARS = 96;

    /**
     * Marks that end a sentence, after which the next letter is capitalised. A
     * comma is deliberately not one of them.
     */
    private static final String SENTENCE_ENDS = ".!?";
    /** Marks that hug the word before them and take a space after. */
    private static final String PUNCTUATION = ".!?,;:";

    private FrameLayout root;
    private GlassKeyboardView keyboard;
    private EmojiPanelView emojiPanel;
    private SuggestionStripView strip;
    private Predictor predictor;

    /** The word the last autocorrection replaced, while it can still be undone. */
    private String correctionTyped;
    /** What it was replaced with. */
    private String correctionResult;
    /**
     * What a refusal is recorded against, which is not always what gets put
     * back. Completing "k" into "keyboard" keys on "k"; inserting an expected
     * word after "the" keys on "the", because there was nothing to replace.
     */
    private String correctionKey;
    /** True when the pending thing was a space completion rather than a fix. */
    private boolean correctionWasCompletion;
    /**
     * Set once a correction has been undone: the original typing, held until the
     * person finishes the word by hand so what they actually meant can be learnt.
     */
    private String awaitingReplacement;

    /**
     * The theme has to be set here, in the constructor, and nowhere else.
     *
     * <p>{@code android:theme} on a {@code <service>} does nothing for an input
     * method: {@link InputMethodService#onCreate()} calls
     * {@code super.setTheme(mTheme)} itself, overwriting whatever the manifest
     * asked for, and {@link #setTheme(int)} throws once the window exists. So
     * this is the only point at which a transparent theme can win — miss it and
     * the window falls back to the opaque platform IME theme, which paints a
     * black slab behind the keys.
     */
    public GlassKeyboardService() {
        setTheme(R.style.Theme_GlassKeyboard);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        predictor = Predictor.get(this);
        loadVocabulary();
    }

    /**
     * Reads the bulk word list in the background.
     *
     * <p>Twenty thousand words is too much to parse while the keyboard is being
     * put on screen, and it is not needed for the first keystroke: the curated
     * seed list is already in memory, so prediction works from the moment the
     * keyboard opens and simply gets better a few hundred milliseconds later.
     */
    private void loadVocabulary() {
        if (predictor.hasFullVocabulary()) {
            return;
        }
        final Resources resources = getResources();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> words = readWordList(resources);
                if (!words.isEmpty()) {
                    predictor.addVocabulary(words);
                }
            }
        }, "glass-vocabulary").start();
    }

    private static List<String> readWordList(Resources resources) {
        List<String> words = new ArrayList<>(20000);
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(resources.openRawResource(R.raw.words), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    words.add(line);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A keyboard that cannot read its dictionary is still a keyboard.
            // The seed list keeps prediction working.
            words.clear();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Nothing useful to do about a failed close here.
                }
            }
        }
        return words;
    }

    @Override
    public View onCreateInputView() {
        keyboard = new GlassKeyboardView(this);
        keyboard.setListener(this);
        emojiPanel = new EmojiPanelView(this);
        emojiPanel.setListener(this);
        emojiPanel.setVisibility(View.GONE);

        // One row, above both pages. It belongs to the input rather than to a
        // page, so it is a sibling of them and not a band inside either.
        strip = new SuggestionStripView(this);
        strip.setListener(this);

        FrameLayout pages = new FrameLayout(this);
        pages.addView(keyboard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        pages.addView(emojiPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(strip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(pages, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        clearInheritedBackgrounds();
        applyAppearance();
        showKeyboard();
        clearCorrectionState();
        if (keyboard != null) {
            keyboard.reset();
        }
        refreshSuggestions();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        clearCorrectionState();
        if (predictor != null) {
            // A session's learning is worth more than the few milliseconds this
            // costs, and there is no later chance to write it.
            predictor.save();
        }
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart,
                                  int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        // Deliberately does NOT invalidate the pending correction on a cursor
        // jump. This fires for our own edits too, and a correction moves the
        // cursor by more than one character, so anything keyed off the delta
        // would throw the undo away the instant it became possible. The guard
        // that matters is in backspace(): it undoes only when the corrected word
        // is genuinely the text behind the cursor.
        refreshSuggestions();
    }

    /**
     * The platform wraps the input view in its own decor — an input-area frame
     * inside the window's decor view — and those carry backgrounds from the
     * platform IME layout rather than from this app's theme. A transparent
     * window does not help if something between the keys and the app is still
     * painting, so the chain is cleared explicitly.
     */
    private void clearInheritedBackgrounds() {
        View view = root;
        while (view != null) {
            view.setBackgroundColor(Color.TRANSPARENT);
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
    }

    /**
     * Never take over the screen in landscape. Fullscreen mode replaces the app
     * with an opaque extracted-text editor, which would hide the very thing a
     * transparent keyboard exists to show.
     */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    // ------------------------------------------------------------- the keyed in

    @Override
    public void onKey(int code) {
        InputConnection input = getCurrentInputConnection();
        if (input == null) {
            return;
        }
        switch (code) {
            case Keys.EMOJI:
                showEmojiPanel();
                return;
            case Keys.BACKSPACE:
                backspace(input);
                break;
            case Keys.ENTER:
                closeWord(input, readContext(input));
                enter(input);
                break;
            default:
                if (isWordCharacter(code)) {
                    clearPendingCorrection();
                    input.commitText(String.valueOf((char) code), 1);
                } else if (code == ' ') {
                    space(input);
                } else if (PUNCTUATION.indexOf(code) >= 0) {
                    punctuate(input, (char) code);
                } else {
                    closeWord(input, readContext(input));
                    input.commitText(String.valueOf((char) code), 1);
                }
        }
        refreshSuggestions();
    }

    /**
     * The space bar, which does three different things.
     *
     * <p>After a single letter it completes the word: typing "h" then space
     * gives the best word starting with h. Pressed a second time in a row it
     * inserts the word it expects next. Otherwise it ends the word and types a
     * space, which is the ordinary case and by far the commonest.
     */
    private void space(InputConnection input) {
        TextContext context = readContext(input);

        if (context.current.length() == 1 && !standsAlone(context.current)) {
            String top = topSuggestion(context);
            // Refused once, never again: the person deleted this expansion the
            // last time it happened.
            if (top != null && !predictor.isRefused(context.current, top)) {
                clearCorrectionState();
                input.deleteSurroundingText(context.current.length(), 0);
                input.commitText(top + " ", 1);
                predictor.learnWord(context.previous, top);
                rememberCompletion(context.current, top, context.current);
                return;
            }
        }

        if (context.current.isEmpty() && context.previous != null
                && endsWithWordThenSpace(input)) {
            String top = topSuggestion(context);
            if (top != null && !predictor.isRefused(context.previous, top)) {
                clearCorrectionState();
                input.commitText(top + " ", 1);
                predictor.learnWord(context.previous, top);
                // Nothing was replaced, so undoing restores nothing; the refusal
                // is keyed on the word this one was offered after.
                rememberCompletion("", top, context.previous);
                return;
            }
        }

        closeWord(input, context);
        input.commitText(" ", 1);
    }

    /**
     * A mark hugs the word before it and takes a space after it, so "hello ."
     * becomes "hello. " however the person got there.
     *
     * <p>Only a full stop, question mark or exclamation mark begins a new
     * sentence; a comma does not. Nothing here remembers that -- capitals come
     * back because readContext() reads the text and sees the sentence restart.
     */
    private void punctuate(InputConnection input, char mark) {
        TextContext context = readContext(input);
        String previous = closeWord(input, context);
        if (context.current.isEmpty()) {
            // No word was just finished, so there may be a space sitting exactly
            // where the mark belongs.
            removeTrailingSpace(input);
        }
        input.commitText(mark + " ", 1);
        predictor.learnPunctuation(previous, String.valueOf(mark));
    }

    /**
     * Corrects and learns the word before the cursor, if there is one.
     *
     * @return the word as it ended up, or the previous word when there was none
     */
    private String closeWord(InputConnection input, TextContext context) {
        String typed = context.current;
        if (typed.isEmpty()) {
            return context.previous;
        }
        if (awaitingReplacement != null && !awaitingReplacement.equalsIgnoreCase(typed)) {
            // The person undid a correction and has now typed what they actually
            // meant. That pair is the most reliable signal there is.
            predictor.learnCorrection(awaitingReplacement, typed);
            awaitingReplacement = null;
        }
        String corrected = predictor.correct(typed);
        if (corrected != null) {
            input.deleteSurroundingText(typed.length(), 0);
            input.commitText(corrected, 1);
            correctionTyped = typed;
            correctionResult = corrected;
            correctionKey = typed;
            correctionWasCompletion = false;
        } else {
            clearPendingCorrection();
        }
        String settled = corrected != null ? corrected : typed;
        predictor.learnWord(context.previous, settled);
        return settled;
    }

    /** Whatever the strip is showing in its centre slot, capitalised to match. */
    private String topSuggestion(TextContext context) {
        List<String> words = predictor.predictWords(context.previous, context.current);
        if (words.isEmpty()) {
            return null;
        }
        String top = words.get(0);
        if (!Predictor.isWordLike(top) || top.equalsIgnoreCase(context.current)) {
            return null;
        }
        Casing.Mode mode = Casing.stronger(
                keyboard == null ? Casing.Mode.NONE : keyboard.caseMode(),
                Casing.of(context.current));
        return Casing.apply(top, mode);
    }

    /**
     * Single letters that are already words, which the space bar must not
     * "complete" into something else. Typing "a" and a space has to give "a",
     * not "and".
     */
    private static boolean standsAlone(String letter) {
        return letter.equalsIgnoreCase("a") || letter.equalsIgnoreCase("i");
    }

    /**
     * True when the cursor sits just past a letter and a space, which is what a
     * second press of the space bar looks like.
     *
     * <p>Checking the character before the space is what stops the space this
     * keyboard adds after a full stop from being mistaken for one the person
     * typed -- otherwise every sentence would end by inserting a random word.
     */
    private static boolean endsWithWordThenSpace(InputConnection input) {
        CharSequence before = input.getTextBeforeCursor(2, 0);
        return before != null && before.length() == 2
                && before.charAt(1) == ' ' && isWordCharacter(before.charAt(0));
    }

    private static void removeTrailingSpace(InputConnection input) {
        CharSequence before = input.getTextBeforeCursor(1, 0);
        if (before != null && before.length() == 1 && before.charAt(0) == ' ') {
            input.deleteSurroundingText(1, 0);
        }
    }

    /**
     * Backspace, or undo of the correction that just happened.
     *
     * <p>One press straight after an autocorrection puts back what was actually
     * typed, the way the system keyboard does. That is the only moment a
     * keyboard can be sure a correction was wrong, so it is also where the
     * refusal gets recorded.
     */
    private void backspace(InputConnection input) {
        if (correctionTyped != null && correctionResult != null) {
            String typed = correctionTyped;
            String corrected = correctionResult;
            String key = correctionKey;
            boolean wasCompletion = correctionWasCompletion;
            clearPendingCorrection();

            CharSequence before = input.getTextBeforeCursor(corrected.length() + 2, 0);
            String tail = before == null ? "" : before.toString();
            int trailing = trailingSeparators(tail);
            int start = tail.length() - corrected.length() - trailing;
            // Only undo if the correction really is what sits behind the cursor;
            // anything else means the text moved and this is an ordinary delete.
            if (start >= 0
                    && tail.regionMatches(true, start, corrected, 0, corrected.length())) {
                input.deleteSurroundingText(corrected.length() + trailing, 0);
                input.commitText(typed, 1);
                if (wasCompletion) {
                    predictor.rejectCompletion(key, corrected);
                } else {
                    predictor.rejectCorrection(typed, corrected);
                }
                // Only worth watching for a replacement if something was put
                // back; the second completion restores nothing.
                awaitingReplacement = typed.isEmpty() ? null : typed;
                return;
            }
        }
        clearPendingCorrection();
        CharSequence selected = input.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            // With a selection, backspace clears the selection rather than
            // eating a character beyond it.
            input.commitText("", 1);
            return;
        }
        CharSequence before = input.getTextBeforeCursor(32, 0);
        int span = before == null ? 1 : Math.max(1, Graphemes.lastClusterLength(before));
        input.deleteSurroundingText(span, 0);
    }

    /** How many separator characters the correction's own commit left behind. */
    private static int trailingSeparators(String tail) {
        int count = 0;
        int i = tail.length();
        while (i > 0 && !isWordCharacter(tail.charAt(i - 1)) && count < 2) {
            count++;
            i--;
        }
        return count;
    }

    private void enter(InputConnection input) {
        EditorInfo info = getCurrentInputEditorInfo();
        if (info == null) {
            input.commitText("\n", 1);
            return;
        }
        boolean multiLine = (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean actionSuppressed = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

        // A search box wants its search run, a message box wants a newline. The
        // field says which through imeOptions; guessing gets one of them wrong.
        if (!multiLine && !actionSuppressed && action != EditorInfo.IME_ACTION_NONE) {
            input.performEditorAction(action);
        } else {
            input.commitText("\n", 1);
        }
    }

    // ------------------------------------------------------------ the suggested

    @Override
    public void onSuggestion(String word) {
        InputConnection input = getCurrentInputConnection();
        if (input == null) {
            return;
        }
        TextContext context = readContext(input);
        clearCorrectionState();
        if (!Predictor.isWordLike(word)) {
            commitPunctuation(input, context, word);
            refreshSuggestions();
            return;
        }
        if (!context.current.isEmpty()) {
            input.deleteSurroundingText(context.current.length(), 0);
        }
        input.commitText(word + " ", 1);
        predictor.learnWord(context.previous, word);
        refreshSuggestions();
    }

    /**
     * Puts a mark where the word before it ends, not after the space that
     * follows it — "hello ." is not what anyone meant by tapping a full stop.
     */
    private void commitPunctuation(InputConnection input, TextContext context, String mark) {
        CharSequence before = input.getTextBeforeCursor(1, 0);
        if (before != null && before.length() == 1 && before.charAt(0) == ' ') {
            input.deleteSurroundingText(1, 0);
        }
        input.commitText(mark + " ", 1);
        predictor.learnPunctuation(context.previous, mark);
    }

    /**
     * An emoji tapped in the suggestion strip stands in for the word being
     * typed, so it replaces it. The strip offered that emoji *because* of those
     * letters -- leaving them behind gives "fire \ud83d\udd25", which is not what
     * tapping it asks for.
     */
    @Override
    public void onEmojiSuggestion(String emoji) {
        InputConnection input = getCurrentInputConnection();
        if (input == null) {
            return;
        }
        TextContext context = readContext(input);
        clearCorrectionState();
        if (!context.current.isEmpty()) {
            input.deleteSurroundingText(context.current.length(), 0);
            // A space after, like a word taken from the strip, so the next word
            // does not run into the emoji.
            input.commitText(emoji + " ", 1);
        } else {
            input.commitText(emoji, 1);
        }
        // The word it stood in for is exactly the association worth keeping.
        String anchor = !context.current.isEmpty() ? context.current : context.previous;
        predictor.learnEmojiFor(anchor, emoji);
        refreshSuggestions();
    }

    /**
     * An emoji chosen in the picker is inserted where the cursor is. Nothing is
     * replaced: reaching the picker takes two deliberate taps away from the
     * letters, and eating a half-typed word on the way back would be a surprise.
     */
    @Override
    public void onEmojiPicked(String emoji) {
        InputConnection input = getCurrentInputConnection();
        if (input == null) {
            return;
        }
        TextContext context = readContext(input);
        String anchor = !context.current.isEmpty() ? context.current : context.previous;
        clearCorrectionState();
        input.commitText(emoji, 1);
        predictor.learnEmojiFor(anchor, emoji);
        refreshSuggestions();
    }

    @Override
    public void onBackspace() {
        InputConnection input = getCurrentInputConnection();
        if (input != null) {
            backspace(input);
            refreshSuggestions();
        }
    }

    /**
     * The keyboard changed page, or the shift key moved. Nothing about which
     * words are suggested depends on either, but how they are capitalised does,
     * and the row is a separate view now -- so it has to be told.
     */
    @Override
    public void onKeyboardStateChanged() {
        refreshSuggestions();
    }

    /**
     * The space bar was swiped. The best suggestion lives in the row, so taking
     * it here is the same act as tapping the middle slot.
     */
    @Override
    public void onSpaceSwipe() {
        if (strip == null) {
            return;
        }
        String word = strip.topSuggestion();
        if (word != null) {
            onSuggestion(word);
        }
    }

    @Override
    public void onBackToKeyboard() {
        showKeyboard();
        if (keyboard != null) {
            // The picker is reached from the symbol page, so without this the
            // key marked ABC would hand back the symbols.
            keyboard.showLetters();
        }
        // Changing page clears the shift state, and nothing else would put it
        // back: coming out of the picker into an empty field left the keyboard
        // in lower case when it should be starting a sentence.
        refreshSuggestions();
    }

    // ----------------------------------------------------------------- plumbing

    /**
     * Pushes the opacity setting to the keyboard, and asks the wallpaper how
     * light it is so pale keys can be made more solid over a pale backdrop.
     *
     * <p>Nothing here samples the app behind the keyboard, because nothing can:
     * an input method has no access to the pixels of the window below it. The
     * wallpaper is the only backdrop Android will describe, and it is the one
     * actually visible wherever the app does not draw under the keyboard.
     */
    private void applyAppearance() {
        if (keyboard == null) {
            return;
        }
        final boolean adaptive = Appearance.isAdaptive(this);
        Boolean known = adaptive ? Appearance.knownBackdrop() : null;
        pushAppearance(Appearance.scale(Appearance.opacity(this),
                known != null && known), known);
        if (!adaptive) {
            return;
        }
        Appearance.probe(this, new Handler(Looper.getMainLooper()),
                new Appearance.Listener() {
                    @Override
                    public void onBackdropResolved(boolean light) {
                        if (Appearance.isAdaptive(GlassKeyboardService.this)) {
                            pushAppearance(Appearance.scale(Appearance.opacity(
                                    GlassKeyboardService.this), light), light);
                        }
                    }
                });
    }

    /**
     * The keys and the emoji panel together. They are the same glass, so a
     * setting that reached only one of them would be a bug waiting to be
     * reported -- as it was, before the panel drew panes at all.
     */
    private void pushAppearance(float scale, Boolean light) {
        if (keyboard != null) {
            keyboard.setAppearance(scale, light);
        }
        if (emojiPanel != null) {
            emojiPanel.setAppearance(scale, light);
        }
        if (strip != null) {
            strip.setAppearance(scale, light);
        }
    }

    private void showEmojiPanel() {
        if (emojiPanel == null || keyboard == null) {
            return;
        }
        emojiPanel.refresh();
        applyAppearance();
        emojiPanel.setVisibility(View.VISIBLE);
        // INVISIBLE rather than GONE: the keyboard is what gives the container
        // its height, and a GONE child would collapse the window.
        keyboard.setVisibility(View.INVISIBLE);
    }

    private void showKeyboard() {
        if (emojiPanel == null || keyboard == null) {
            return;
        }
        emojiPanel.setVisibility(View.GONE);
        keyboard.setVisibility(View.VISIBLE);
    }

    /**
     * Rebuilds the strip and the shift state from whatever is in the field.
     *
     * <p>Both are read back from the field rather than tracked, for the same
     * reason: the cursor can move, text can be pasted, and the app can edit the
     * field underneath us.
     */
    private void refreshSuggestions() {
        if (keyboard == null || strip == null) {
            return;
        }
        InputConnection input = getCurrentInputConnection();
        if (input == null) {
            strip.setSuggestions(Collections.<String>emptyList(),
                    Collections.<String>emptyList(), Casing.Mode.NONE);
            keyboard.setSwipeWord(null);
            return;
        }
        TextContext context = readContext(input);

        // Capitals at the start of a sentence, and again if it is deleted back
        // to nothing.
        keyboard.setAutoShift(context.atSentenceStart());

        List<String> words = new ArrayList<>(
                predictor.predictWords(context.previous, context.current));
        if (context.current.isEmpty()) {
            String mark = predictor.predictPunctuation(
                    context.sentenceStart, context.previous, context.wordsSoFar);
            if (mark != null) {
                // The third slot, so a full stop never costs the best word its
                // place in the centre.
                if (words.size() >= 3) {
                    words.set(2, mark);
                } else {
                    words.add(mark);
                }
            }
        }
        // The letters already typed decide the capitals as much as the shift key
        // does: after "H", the suggestion is "Hi" and not "hi".
        strip.setShiftMode(keyboard.caseMode());
        strip.setSuggestions(words,
                predictor.predictEmoji(context.previous, context.current),
                Casing.of(context.current));
        // The space bar draws this mid-swipe, so it has to follow the row.
        keyboard.setSwipeWord(strip.topSuggestion());
    }

    /** What the text before the cursor says about where we are. */
    private static final class TextContext {
        String current = "";
        String previous;
        String sentenceStart;
        int wordsSoFar;

        boolean atSentenceStart() {
            return current.isEmpty() && wordsSoFar == 0;
        }
    }

    /**
     * Parses the text before the cursor into the word being typed, the word
     * before it, and how far into the sentence they are.
     *
     * <p>Read back from the field every time rather than tracked as the person
     * types: a tracked buffer drifts as soon as the cursor moves or the app
     * edits the field, and drift here means correcting the wrong word.
     */
    private TextContext readContext(InputConnection input) {
        TextContext context = new TextContext();
        CharSequence before = input.getTextBeforeCursor(CONTEXT_CHARS, 0);
        if (before == null || before.length() == 0) {
            return context;
        }
        List<String> sentence = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < before.length(); i++) {
            char c = before.charAt(i);
            if (isWordCharacter(c)) {
                token.append(c);
                continue;
            }
            if (token.length() > 0) {
                sentence.add(token.toString());
                token.setLength(0);
            }
            if (SENTENCE_ENDS.indexOf(c) >= 0 || c == '\n') {
                // A new sentence starts here, so nothing before it is context.
                sentence.clear();
            }
        }
        context.current = token.toString();
        context.wordsSoFar = sentence.size();
        if (!sentence.isEmpty()) {
            context.previous = sentence.get(sentence.size() - 1);
            context.sentenceStart = sentence.get(0);
        }
        return context;
    }

    static boolean isWordCharacter(int code) {
        return Character.isLetter(code) || code == '\'';
    }

    /**
     * Arms the undo for a word the space bar inserted, so backspace puts back
     * what was there and records that this completion was not wanted.
     */
    private void rememberCompletion(String restore, String inserted, String key) {
        correctionTyped = restore;
        correctionResult = inserted;
        correctionKey = key;
        correctionWasCompletion = true;
    }

    private void clearPendingCorrection() {
        correctionTyped = null;
        correctionResult = null;
        correctionKey = null;
        correctionWasCompletion = false;
    }

    private void clearCorrectionState() {
        clearPendingCorrection();
        awaitingReplacement = null;
    }
}
