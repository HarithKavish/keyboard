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

import java.util.Collections;
import java.util.List;

/**
 * The input method itself. It owns the keyboard and the emoji picker, and
 * forwards what they report to whatever field currently has focus.
 *
 * <p>It also holds the only state that cannot live in either view: what word the
 * cursor is sitting in, and whether the last thing typed was an autocorrection
 * that the person might be about to reject.
 */
public final class GlassKeyboardService extends InputMethodService
        implements GlassKeyboardView.Listener, EmojiPanelView.Listener {

    /** Enough to find the word under the cursor and the one before it. */
    private static final int CONTEXT_CHARS = 64;

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
        root.addView(keyboard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(emojiPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
     */
    private void finishWord(InputConnection input, String separator) {
        String[] context = readContext(input);
        String previous = context[0];
        String typed = context[1];

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
                correctionTyped = null;
                correctionResult = null;
            }
            predictor.learnWord(previous, corrected != null ? corrected : typed);
        }
        if (separator != null) {
            input.commitText(separator, 1);
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

            CharSequence before = input.getTextBeforeCursor(corrected.length() + 1, 0);
            String tail = before == null ? "" : before.toString();
            // Only undo if the correction really is what sits behind the cursor;
            // anything else means the text moved and this is an ordinary delete.
            if (tail.length() >= corrected.length()
                    && tail.regionMatches(true, tail.length() - corrected.length() - trailing(tail),
                            corrected, 0, corrected.length())) {
                input.deleteSurroundingText(corrected.length() + trailing(tail), 0);
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
        } else {
            input.deleteSurroundingText(1, 0);
        }
    }

    /** 1 when the text ends in the separator committed after a correction. */
    private static int trailing(String tail) {
        return !tail.isEmpty() && !isWordCharacter(tail.charAt(tail.length() - 1)) ? 1 : 0;
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
        String[] context = readContext(input);
        clearCorrectionState();
        if (!context[1].isEmpty()) {
            input.deleteSurroundingText(context[1].length(), 0);
        }
        String shaped = keyboard != null && keyboard.isShifted()
                ? Character.toUpperCase(word.charAt(0)) + word.substring(1) : word;
        input.commitText(shaped + " ", 1);
        predictor.learnWord(context[0], word);
        refreshSuggestions();
    }

    @Override
    public void onEmojiPicked(String emoji) {
        InputConnection input = getCurrentInputConnection();
        if (input == null) {
            return;
        }
        String[] context = readContext(input);
        // The word it follows is what the association is worth learning against:
        // the word being typed if there is one, otherwise the one before.
        String anchor = !context[1].isEmpty() ? context[1] : context[0];
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
        String[] context = readContext(input);
        List<String> words = predictor.predictWords(context[0], context[1]);
        List<String> emoji = predictor.predictEmoji(context[0], context[1]);
        keyboard.setSuggestions(words, emoji);
    }

    /**
     * The word the cursor sits in, and the word before it.
     *
     * <p>Read back from the field every time rather than tracked as the person
     * types: the cursor can move, text can be pasted, and another app can edit
     * the field underneath us. A tracked buffer would drift, and drift here
     * means correcting the wrong word.
     *
     * @return {@code {previousWord, currentWord}}; the first may be null and the
     *     second is empty when the cursor is not inside a word.
     */
    private String[] readContext(InputConnection input) {
        CharSequence before = input.getTextBeforeCursor(CONTEXT_CHARS, 0);
        if (before == null || before.length() == 0) {
            return new String[]{null, ""};
        }
        int end = before.length();
        int wordStart = end;
        while (wordStart > 0 && isWordCharacter(before.charAt(wordStart - 1))) {
            wordStart--;
        }
        String current = before.subSequence(wordStart, end).toString();

        int gap = wordStart;
        while (gap > 0 && !isWordCharacter(before.charAt(gap - 1))) {
            gap--;
        }
        int previousStart = gap;
        while (previousStart > 0 && isWordCharacter(before.charAt(previousStart - 1))) {
            previousStart--;
        }
        String previous = previousStart < gap
                ? before.subSequence(previousStart, gap).toString() : null;
        return new String[]{previous, current};
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
