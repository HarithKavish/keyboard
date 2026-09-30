package com.harithkavish.keyboard;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The row of suggestions: three words with the best in the centre, and two
 * emoji beside them.
 *
 * <p><b>One row, above all three pages.</b> It used to live inside
 * {@link GlassKeyboardView}, which meant the emoji picker — a sibling view —
 * had no suggestions at all, and a page change returned inside the keyboard
 * without the service ever hearing about it, so the symbol page showed whatever
 * the letters page had left behind. Both were the same bug: the row belonged to
 * one page instead of to the input.
 *
 * <p>So it sits above the pages now, and the service feeds it once. Whichever
 * page is showing, it is the same row with the same contents.
 */
final class SuggestionStripView extends View {

    interface Listener {
        /** A word was tapped. */
        void onSuggestion(String word);

        /**
         * An emoji was tapped. Named apart from the picker's callback because it
         * means something different: this one replaces the word being typed.
         */
        void onEmojiSuggestion(String emoji);
    }

    private static final int WORD_SLOTS = 3;
    private static final int EMOJI_SLOTS = 2;
    /** Share of the width the words take when there are emoji beside them. */
    private static final float WORD_SHARE = 0.72f;

    static final float HEIGHT_DP = 38f;
    /**
     * Space below the suggestions. The band is taller than the panes by this
     * much and the gap is all at the bottom, so the row sits tight under the top
     * edge of the window while no longer touching the keys.
     */
    private static final float GAP_BELOW_DP = 6f;
    /** Side margin, matching the keys, so the two line up. */
    private static final float PAD_H_DP = 14f;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF slot = new RectF();

    private final List<String> words = new ArrayList<>(WORD_SLOTS);
    private final List<String> emoji = new ArrayList<>(EMOJI_SLOTS);
    private final RectF[] wordSlots = new RectF[WORD_SLOTS];
    private final RectF[] emojiSlots = new RectF[EMOJI_SLOTS];

    private final float density;
    private Listener listener;

    /** What the letters already typed ask for. */
    private Casing.Mode caseHint = Casing.Mode.NONE;
    /** What the shift key asks for, pushed across from the keyboard. */
    private Casing.Mode shiftMode = Casing.Mode.NONE;

    private int pressedSlot = -1;
    private float stripLeft;
    private float stripWidth;

    private boolean night;
    private float opacityScale = 1f;
    private Boolean backdropLight;
    private Shader bodyShader;
    private Shader bodyShaderPressed;
    private Shader rimShader;

    SuggestionStripView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(0x00000000);
        text.setTextAlign(Paint.Align.CENTER);
        emojiPaint.setTextAlign(Paint.Align.CENTER);
        rim.setStyle(Paint.Style.STROKE);
        shadow.setStyle(Paint.Style.FILL);
        for (int i = 0; i < WORD_SLOTS; i++) {
            wordSlots[i] = new RectF();
        }
        for (int i = 0; i < EMOJI_SLOTS; i++) {
            emojiSlots[i] = new RectF();
        }
        readTheme();
    }

    static int preferredHeight(Resources resources) {
        return Math.round(HEIGHT_DP * resources.getDisplayMetrics().density);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------- contents

    void setSuggestions(List<String> newWords, List<String> newEmoji,
                        Casing.Mode hint) {
        caseHint = hint == null ? Casing.Mode.NONE : hint;
        words.clear();
        emoji.clear();
        if (newWords != null) {
            words.addAll(newWords.subList(0, Math.min(WORD_SLOTS, newWords.size())));
        }
        if (newEmoji != null) {
            emoji.addAll(newEmoji.subList(0, Math.min(EMOJI_SLOTS, newEmoji.size())));
        }
        // The slots depend on whether there are emoji, so they are laid out here
        // rather than only when the view is sized.
        if (stripWidth > 0f) {
            layoutSlots();
        }
        invalidate();
    }

    /** The shift key's contribution to how a suggestion is capitalised. */
    void setShiftMode(Casing.Mode mode) {
        Casing.Mode next = mode == null ? Casing.Mode.NONE : mode;
        if (next != shiftMode) {
            shiftMode = next;
            invalidate();
        }
    }

    /** The best word as it would be typed, or null when there is none. */
    String topSuggestion() {
        return words.isEmpty() ? null : shape(words.get(0));
    }

    private String shape(String word) {
        return Casing.apply(word, Casing.stronger(shiftMode, caseHint));
    }

    // ------------------------------------------------------------ appearance

    void setAppearance(float scale, Boolean light) {
        if (scale == opacityScale && equal(light, backdropLight)) {
            return;
        }
        opacityScale = scale;
        backdropLight = light;
        readTheme();
        buildShaders();
        invalidate();
    }

    private static boolean equal(Boolean a, Boolean b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        readTheme();
        buildShaders();
        invalidate();
    }

    private void readTheme() {
        int mode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        night = backdropLight != null ? !backdropLight
                : mode == Configuration.UI_MODE_NIGHT_YES;
        text.setColor(night ? 0xFFFFFFFF : 0xFF14161C);
        text.setShadowLayer(density, 0f, density * 0.5f,
                night ? 0x66000000 : 0x40FFFFFF);
    }

    private float inset() {
        return density * 2f;
    }

    /** The part of the band the suggestions actually occupy. */
    private float slotHeight() {
        return getHeight() - density * GAP_BELOW_DP;
    }

    private void buildShaders() {
        if (getHeight() <= 0) {
            return;
        }
        int top = Glass.top(night, opacityScale);
        int bottom = Glass.bottom(night, opacityScale);
        float pane = slotHeight() - inset() * 2f;
        bodyShader = Glass.vertical(pane, top, bottom);
        bodyShaderPressed = Glass.vertical(pane, Glass.brighten(top),
                Glass.brighten(bottom));
        rimShader = Glass.vertical(pane, Glass.rimTop(night, opacityScale),
                Glass.rimBottom(night, opacityScale));
        rim.setStrokeWidth(Glass.rimWidth(density));
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                preferredHeight(getResources()));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        stripLeft = PAD_H_DP * density;
        stripWidth = w - 2f * stripLeft;
        layoutSlots();
        buildShaders();
    }

    private void layoutSlots() {
        // With nothing to put in the emoji slots, the words take the whole strip.
        // Leaving a third of it empty just to keep the words where they would be
        // if there were emoji reads as a bug.
        boolean hasEmoji = !emoji.isEmpty();
        float wordsWidth = hasEmoji ? stripWidth * WORD_SHARE : stripWidth;
        float wordWidth = wordsWidth / WORD_SLOTS;
        float top = 0f;
        float bottom = slotHeight();
        for (int i = 0; i < WORD_SLOTS; i++) {
            wordSlots[i].set(stripLeft + wordWidth * i, top,
                    stripLeft + wordWidth * (i + 1), bottom);
        }
        if (!hasEmoji) {
            for (RectF rect : emojiSlots) {
                rect.setEmpty();
            }
            return;
        }
        float emojiLeft = stripLeft + wordsWidth;
        float emojiWidth = (stripWidth - wordsWidth) / EMOJI_SLOTS;
        for (int i = 0; i < EMOJI_SLOTS; i++) {
            emojiSlots[i].set(emojiLeft + emojiWidth * i, top,
                    emojiLeft + emojiWidth * (i + 1), bottom);
        }
    }

    // --------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (getHeight() <= 0 || bodyShader == null) {
            return;
        }
        // Sized against the panes rather than the band, so the gap below does
        // not quietly shrink the words.
        text.setTextSize(slotHeight() * 0.44f);
        emojiPaint.setTextSize(slotHeight() * 0.58f);
        float radius = getHeight() * 0.32f;

        for (int i = 0; i < WORD_SLOTS; i++) {
            int rank = rankOfSlot(i);
            if (rank >= words.size()) {
                continue;
            }
            slot.set(wordSlots[i]);
            drawSlotPane(canvas, slot, pressedSlot == i, radius);
            float baseline = slot.centerY() - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(shape(words.get(rank)), slot.centerX(), baseline, text);
        }

        for (int i = 0; i < EMOJI_SLOTS && i < emoji.size(); i++) {
            slot.set(emojiSlots[i]);
            drawSlotPane(canvas, slot, pressedSlot == WORD_SLOTS + i, radius);
            float baseline = slot.centerY()
                    - (emojiPaint.descent() + emojiPaint.ascent()) / 2f;
            canvas.drawText(emoji.get(i), slot.centerX(), baseline, emojiPaint);
        }
    }

    private void drawSlotPane(Canvas canvas, RectF bounds, boolean down, float radius) {
        float in = inset();
        Glass.drawPane(canvas, fill, rim, shadow, density,
                bounds.left + in, bounds.top + in,
                bounds.width() - in * 2f, bounds.height() - in * 2f, radius,
                down ? bodyShaderPressed : bodyShader, rimShader);
    }

    /** Centre slot holds the best guess, left the second, right the third. */
    private static int rankOfSlot(int slotIndex) {
        if (slotIndex == 1) {
            return 0;
        }
        return slotIndex == 0 ? 1 : 2;
    }

    // ----------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedSlot = slotAt(x, y);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                return true;

            case MotionEvent.ACTION_UP:
                int index = pressedSlot;
                pressedSlot = -1;
                invalidate();
                if (index >= 0 && slotAt(x, y) == index) {
                    take(index);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                pressedSlot = -1;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** Words first, then emoji, or -1 for a miss. */
    private int slotAt(float x, float y) {
        for (int i = 0; i < WORD_SLOTS; i++) {
            if (rankOfSlot(i) < words.size() && wordSlots[i].contains(x, y)) {
                return i;
            }
        }
        for (int i = 0; i < EMOJI_SLOTS; i++) {
            if (i < emoji.size() && emojiSlots[i].contains(x, y)) {
                return WORD_SLOTS + i;
            }
        }
        return -1;
    }

    private void take(int slotIndex) {
        if (listener == null) {
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        performClick();
        if (slotIndex < WORD_SLOTS) {
            int rank = rankOfSlot(slotIndex);
            if (rank < words.size()) {
                // Shaped, so what the person saw is what gets typed and what gets
                // learnt -- that is how "Harith" is remembered as a name.
                listener.onSuggestion(shape(words.get(rank)));
            }
            return;
        }
        int index = slotIndex - WORD_SLOTS;
        if (index < emoji.size()) {
            listener.onEmojiSuggestion(emoji.get(index));
        }
    }
}
