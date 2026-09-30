package com.harithkavish.keyboard;

import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

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
        implements GlassKeyboardView.Listener, EmojiPanelView.Listener {

    /**
     * Enough to find the sentence the cursor is in. Longer than it needs to be
     * for the current word alone, because automatic capitalisation and the
     * punctuation suggestion both depend on where the sentence started.
     */
    private static final int CONTEXT_CHARS = 96;

    /** Marks that end a sentence, after which the next letter is capitalised. */
    private static final String SENTENCE_ENDS = ".!?";
    /** Marks that take a space after them when they follow a word. */
    private static final String SPACED_MARKS = ".!?,;:";

    private FrameLayout root;
    private GlassKeyboardView keyboard;
    private EmojiPanelView emojiPanel;
    private Predictor predictor;

    /** The word the last autocorrection replaced, while it can still be undone. */
    private String correctionTyped;
    /** What it was replaced with. */
    private String correctionResult;
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
    }

    @Override
    public View onCreateInputView() {
        keyboard = new GlassKeyboardView(this);
        keyboard.setListener(this);
        emojiPanel = new EmojiPanelView(this);
        emojiPanel.setListener(this);
        emojiPanel.setVisibility(View.GONE);

        root = new FrameLayout(this);
        // Both wrap: each view measures itself to the keyboard's height, so the
        // picker never grows to fill the screen behind it.
        root.addView(keyboard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(emojiPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        clearInheritedBackgrounds();
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
                finishWord(input, null);
                enter(input);
                break;
            default:
                if (isWordCharacter(code)) {
                    clearPendingCorrection();
                    input.commitText(String.valueOf((char) code), 1);
                } else {
                    // A separator ends the word, which is when a correction can
                    // be made and when there is something worth learning.
                    finishWord(input, String.valueOf((char) code));
                }
        }
        refreshSuggestions();
    }

    /**
     * Closes off the word before the cursor: corrects it if it should be
     * corrected, learns it, then commits whatever ended it.
     *
     * <p>A mark that ends a sentence also takes a space after it, so the next
     * sentence starts where it should without the person reaching for the space
     * bar. {@link #refreshSuggestions} then turns shift back on.
     */
    private void finishWord(InputConnection input, String separator) {
        TextContext context = readContext(input);
        String previous = context.previous;
        String typed = context.current;

        if (!typed.isEmpty()) {
            if (awaitingReplacement != null && !awaitingReplacement.equalsIgnoreCase(typed)) {
                // The person undid a correction and has now typed what they
                // actually meant. That pair is the most reliable signal there is.
                predictor.learnCorrection(awaitingReplacement, typed);
                awaitingReplacement = null;
            }
            String corrected = predictor.correct(typed);
            if (corrected != null) {
                input.deleteSurroundingText(typed.length(), 0);
                input.commitText(corrected, 1);
                correctionTyped = typed;
                correctionResult = corrected;
            } else {
                clearPendingCorrection();
            }
            predictor.learnWord(previous, corrected != null ? corrected : typed);
            previous = corrected != null ? corrected : typed;
        }
        if (separator == null) {
            return;
        }
        boolean spaced = separator.length() == 1
                && SPACED_MARKS.indexOf(separator.charAt(0)) >= 0
                && !typed.isEmpty();
        input.commitText(spaced ? separator + " " : separator, 1);
        if (spaced) {
            predictor.learnPunctuation(previous, separator);
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
                predictor.rejectCorrection(typed, corrected);
                awaitingReplacement = typed;
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

    @Override
    public void onEmojiPicked(String emoji) {
        InputConnection input = getCurrentInputConnection();
        if (input == null) {
            return;
        }
        TextContext context = readContext(input);
        // The word it follows is what the association is worth learning against:
        // the word being typed if there is one, otherwise the one before.
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

    @Override
    public void onBackToKeyboard() {
        showKeyboard();
    }

    // ----------------------------------------------------------------- plumbing

    private void showEmojiPanel() {
        if (emojiPanel == null || keyboard == null) {
            return;
        }
        emojiPanel.refresh();
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
        if (keyboard == null) {
            return;
        }
        InputConnection input = getCurrentInputConnection();
        if (input == null) {
            keyboard.setSuggestions(Collections.<String>emptyList(),
                    Collections.<String>emptyList());
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
        keyboard.setSuggestions(words,
                predictor.predictEmoji(context.previous, context.current));
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

    private void clearPendingCorrection() {
        correctionTyped = null;
        correctionResult = null;
    }

    private void clearCorrectionState() {
        clearPendingCorrection();
        awaitingReplacement = null;
    }
}
