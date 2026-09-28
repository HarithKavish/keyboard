package com.harithkavish.keyboard;

import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * The input method itself. It owns one view and forwards what that view reports
 * to whatever field currently has focus.
 */
public final class GlassKeyboardService extends InputMethodService
        implements GlassKeyboardView.Listener {

    private GlassKeyboardView keyboard;

    @Override
    public View onCreateInputView() {
        keyboard = new GlassKeyboardView(this);
        keyboard.setListener(this);
        return keyboard;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (keyboard != null) {
            keyboard.reset();
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

    @Override
    public void onKey(int code) {
        InputConnection input = getCurrentInputConnection();
        if (input == null) {
            return;
        }
        switch (code) {
            case Keys.BACKSPACE:
                delete(input);
                break;
            case Keys.ENTER:
                enter(input);
                break;
            default:
                input.commitText(String.valueOf((char) code), 1);
        }
    }

    private void delete(InputConnection input) {
        CharSequence selected = input.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            // With a selection, backspace clears the selection rather than
            // eating a character beyond it.
            input.commitText("", 1);
        } else {
            input.deleteSurroundingText(1, 0);
        }
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
}
