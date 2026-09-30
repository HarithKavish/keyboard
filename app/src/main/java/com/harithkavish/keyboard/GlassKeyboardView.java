package com.harithkavish.keyboard;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws the whole keyboard into one canvas and reports the keys that were
 * pressed.
 *
 * <p>One view, not thirty: a View per key would cost a measure and a layout pass
 * per key on every rotation and an allocation per key at startup, for drawing
 * that is a handful of rounded rectangles either way.
 *
 * <p>The glass look is a few cheap draws rather than a real blur: a stacked
 * shadow for lift, a shallow vertical gradient for the pane, and a rim light
 * that is brightest along the top edge. There is deliberately no gloss band
 * across the upper half -- a bright highlight over the top half of a rounded
 * rect is the signature of moulded plastic, and it is what made an earlier
 * version of this read as a toy. What sells glass instead is restraint: low
 * contrast, a hairline rim, and whatever is behind showing through.
 *
 * <p>Android cannot blur what is behind another window before API 31, and where
 * it can, the blur covers the whole window rectangle rather than each key.
 */
final class GlassKeyboardView extends View {

    interface Listener {
        /** Called with a character, or with one of the {@link Keys} command codes. */
        void onKey(int code);

        /** A word from the suggestion strip was tapped. */
        void onSuggestion(String word);

        /** An emoji from the suggestion strip was tapped. */
        void onEmojiPicked(String emoji);
    }

    private static final float ROW_UNITS = 10f;

    private static final long REPEAT_FIRST_MS = 400;
    private static final long REPEAT_NEXT_MS = 50;
    private static final long DOUBLE_TAP_MS = 350;

    /** Three words on the left, two emoji on the right. */
    private static final int WORD_SLOTS = 3;
    private static final int EMOJI_SLOTS = 2;
    /** Share of the strip the words take; the emoji have the rest. */
    private static final float WORD_SHARE = 0.72f;

    /**
     * A soft shadow stacked by hand, largest offset first. Hardware-accelerated
     * canvases will not blur an arbitrary shape cheaply -- BlurMaskFilter forces
     * a software layer -- so the falloff is three offset rounded rects instead.
     * Without it the panes sit flat against the wallpaper and stop reading as
     * glass at all.
     */
    private static final int[] SHADOW_OFFSET_DP = {3, 2, 1};
    private static final int[] SHADOW_ALPHA = {0x16, 0x12, 0x0E};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Colour emoji carry their own colour, and a shadow under them looks wrong. */
    private final Paint emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();
    private final RectF slot = new RectF();

    private final float density;
    private final int touchSlop;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Listener listener;
    private Keys.Key[][] rows = Keys.page(Keys.LETTERS);
    private float[] rowWeights = Keys.rowHeights(Keys.LETTERS);
    private int page = Keys.LETTERS;

    private final List<String> words = new ArrayList<>(WORD_SLOTS);
    private final List<String> emoji = new ArrayList<>(EMOJI_SLOTS);
    private final RectF[] wordSlots = new RectF[WORD_SLOTS];
    private final RectF[] emojiSlots = new RectF[EMOJI_SLOTS];
    /** Index into the strip, words first then emoji, or -1. */
    private int pressedSlot = -1;

    private Keys.Key pressed;
    /** The key lifted off, handed to {@link #performClick()} to act on. */
    private Keys.Key pendingKey;
    private String pendingSuggestion;
    private boolean pendingSuggestionIsEmoji;
    private boolean shift;
    private boolean capsLock;
    private long lastShiftTap;

    private float keyRadius;
    private float gap;
    private float stripHeight;
    private float letterRowHeight;
    private boolean night;

    // Rebuilt only when the key height or the theme changes, so onDraw allocates
    // nothing at all.
    private Shader bodyShader;
    private Shader bodyShaderPressed;
    private Shader bodyShaderCommand;
    private Shader rimShader;

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (pressed != null && pressed.code == Keys.BACKSPACE) {
                emit(Keys.BACKSPACE);
                handler.postDelayed(this, REPEAT_NEXT_MS);
            }
        }
    };

    GlassKeyboardView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(0x00000000);
        rim.setStyle(Paint.Style.STROKE);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        emojiPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < WORD_SLOTS; i++) {
            wordSlots[i] = new RectF();
        }
        for (int i = 0; i < EMOJI_SLOTS; i++) {
            emojiSlots[i] = new RectF();
        }
        readTheme();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Replaces what the strip offers. The centre word slot is the best guess,
     * the left one second and the right one third, which is the order a thumb
     * resting under the space bar reaches them in.
     */
    void setSuggestions(List<String> newWords, List<String> newEmoji) {
        words.clear();
        emoji.clear();
        if (newWords != null) {
            words.addAll(newWords.subList(0, Math.min(WORD_SLOTS, newWords.size())));
        }
        if (newEmoji != null) {
            emoji.addAll(newEmoji.subList(0, Math.min(EMOJI_SLOTS, newEmoji.size())));
        }
        invalidate();
    }

    /** Called when a new field takes focus, so the keyboard never opens mid-symbol. */
    void reset() {
        cancelRepeat();
        pressed = null;
        pressedSlot = -1;
        shift = false;
        capsLock = false;
        if (page != Keys.LETTERS) {
            setPage(Keys.LETTERS);
        } else {
            invalidate();
        }
    }

    boolean isShifted() {
        return shift || capsLock;
    }

    private void readTheme() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        night = mode == Configuration.UI_MODE_NIGHT_YES;
        // Over a dark app the glass is a faint white tint with white glyphs; over
        // a light one it is a brighter white pane with near-black glyphs. Either
        // way the glyph carries a shadow in the opposite tone, because what sits
        // behind a transparent keyboard is whatever the app draws and it is not
        // guaranteed to agree with the system theme.
        text.setColor(night ? 0xFFFFFFFF : 0xFF14161C);
        // Tight and faint: enough to hold a glyph against an unknown backdrop,
        // not enough to emboss it. A soft wide shadow under letters is the other
        // half of the toy look.
        text.setShadowLayer(density, 0f, density * 0.5f,
                night ? 0x4D000000 : 0x33FFFFFF);
        icon.setColor(text.getColor());
        bodyShader = null;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        readTheme();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        // 46dp letter rows on a phone held upright, capped so landscape does not
        // hand the keyboard most of the screen.
        float row = Math.min(46f * density, screenHeight * 0.075f);
        // Measured against the letters page even on the symbol pages, which have
        // no number row. Sizing each page to its own rows would make the whole
        // keyboard jump every time ?123 is pressed.
        float units = 0f;
        for (float weight : Keys.rowHeights(Keys.LETTERS)) {
            units += weight;
        }
        stripHeight = 40f * density;
        setMeasuredDimension(width, Math.round(row * units + stripHeight));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        layoutKeys(w, h);
    }

    private void layoutKeys(int width, int height) {
        // The side margin pulls the outer keys off the screen edges, which is
        // where a thumb tends to clip them. There is deliberately no vertical
        // padding: the keyboard runs to the top and bottom of its window.
        float padH = 14f * density;
        // The wider gap is what makes the keys read as smaller: the pane shrinks
        // while the row pitch stays where the thumb already expects it, and
        // keyAt() snaps to the nearest key, so the extra space is not dead.
        gap = 6f * density;
        keyRadius = 10f * density;

        float available = width - 2f * padH;
        layoutStrip(padH, available);

        float units = 0f;
        for (float weight : rowWeights) {
            units += weight;
        }
        letterRowHeight = (height - stripHeight) / units;
        // The reference unit is a letter's width on a full ten-key row. A short
        // row is laid out with this width and centred, so the nine-letter home
        // row sits inside the top row instead of stretching to match it.
        float referenceUnit = (available - gap * (ROW_UNITS - 1f)) / ROW_UNITS;

        float y = stripHeight;
        for (int r = 0; r < rows.length; r++) {
            Keys.Key[] row = rows[r];
            float rowHeight = letterRowHeight * rowWeights[r];
            float totalWeight = 0f;
            for (Keys.Key key : row) {
                totalWeight += key.weight;
            }

            float unit;
            if (totalWeight >= ROW_UNITS) {
                // A full-weight row spans the whole keyboard. Its own gap count
                // decides the unit, so the row ends flush with the edges rather
                // than short by the gaps a ten-key row would have used.
                unit = (available - gap * (row.length - 1)) / totalWeight;
            } else {
                unit = referenceUnit;
            }

            float rowWidth = gap * (row.length - 1) + unit * totalWeight;
            float x = padH + (available - rowWidth) / 2f;
            for (Keys.Key key : row) {
                key.x = x;
                key.y = y;
                key.w = unit * key.weight;
                key.h = rowHeight - gap;
                x += key.w + gap;
            }
            y += rowHeight;
        }

        buildShaders();
    }

    private void layoutStrip(float padH, float available) {
        float wordsWidth = available * WORD_SHARE;
        float wordWidth = wordsWidth / WORD_SLOTS;
        float top = gap * 0.5f;
        float bottom = stripHeight - gap * 0.5f;
        for (int i = 0; i < WORD_SLOTS; i++) {
            wordSlots[i].set(padH + wordWidth * i, top, padH + wordWidth * (i + 1), bottom);
        }
        float emojiLeft = padH + wordsWidth;
        float emojiWidth = (available - wordsWidth) / EMOJI_SLOTS;
        for (int i = 0; i < EMOJI_SLOTS; i++) {
            emojiSlots[i].set(emojiLeft + emojiWidth * i, top,
                    emojiLeft + emojiWidth * (i + 1), bottom);
        }
    }

    private void buildShaders() {
        if (letterRowHeight <= 0f) {
            return;
        }
        int top;
        int bottom;
        if (night) {
            top = 0x3DFFFFFF;
            bottom = 0x21FFFFFF;
        } else {
            top = 0x96FFFFFF;
            bottom = 0x63FFFFFF;
        }
        // A shallow range on purpose. A steep top-to-bottom gradient is read as a
        // moulded surface catching a light; a flatter one is read as frosted.
        float keyHeight = letterRowHeight - gap;
        bodyShader = vertical(keyHeight, top, bottom);
        bodyShaderPressed = vertical(keyHeight, brighten(top), brighten(bottom));
        // Command keys sit back a step so the letters read as the primary rows.
        bodyShaderCommand = vertical(keyHeight, dim(top), dim(bottom));
        // Bright along the top edge and faint but still present by the bottom.
        // Letting it vanish entirely loses the pane's shape against a busy
        // wallpaper; the shadow underneath does the rest of the separating.
        rimShader = vertical(keyHeight, night ? 0x99FFFFFF : 0xB3FFFFFF,
                night ? 0x26FFFFFF : 0x2BFFFFFF);
        // A hairline. Anything thicker outlines the key instead of lighting it.
        rim.setStrokeWidth(Math.max(1f, density * 0.75f));
        chip.setColor(night ? 0x1FFFFFFF : 0x4DFFFFFF);
    }

    private Shader vertical(float height, int top, int bottom) {
        return new LinearGradient(0f, 0f, 0f, height, top, bottom, Shader.TileMode.CLAMP);
    }

    private static int brighten(int argb) {
        int alpha = Math.min(255, (int) ((argb >>> 24) * 1.9f));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private static int dim(int argb) {
        int alpha = (int) ((argb >>> 24) * 0.6f);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bodyShader == null) {
            buildShaders();
            if (bodyShader == null) {
                return;
            }
        }

        drawStrip(canvas);

        for (int r = 0; r < rows.length; r++) {
            float rowHeight = letterRowHeight * rowWeights[r] - gap;
            float letterSize = rowHeight * 0.40f;
            float labelSize = rowHeight * 0.29f;
            for (Keys.Key key : rows[r]) {
                int save = canvas.save();
                // The shaders are built for a gradient one letter-row tall, so
                // each key is drawn at the origin and moved into place.
                canvas.translate(key.x, key.y);

                boolean down = key == pressed;
                Shader body;
                if (down) {
                    body = bodyShaderPressed;
                } else if (key.isCommand() || key.code == ' ') {
                    body = bodyShaderCommand;
                } else {
                    body = bodyShader;
                }

                for (int i = 0; i < SHADOW_OFFSET_DP.length; i++) {
                    float off = SHADOW_OFFSET_DP[i] * density;
                    shadow.setColor(SHADOW_ALPHA[i] << 24);
                    canvas.drawRoundRect(off * 0.4f, off, key.w - off * 0.4f, key.h + off,
                            keyRadius, keyRadius, shadow);
                }

                fill.setShader(body);
                canvas.drawRoundRect(0f, 0f, key.w, key.h, keyRadius, keyRadius, fill);

                rim.setShader(rimShader);
                float half = rim.getStrokeWidth() / 2f;
                canvas.drawRoundRect(half, half, key.w - half, key.h - half,
                        keyRadius, keyRadius, rim);

                if (hasIcon(key)) {
                    drawIcon(canvas, key);
                } else {
                    String label = labelFor(key);
                    if (!label.isEmpty()) {
                        text.setTextSize(key.label.length() > 1 ? labelSize : letterSize);
                        float baseline = key.h / 2f - (text.descent() + text.ascent()) / 2f;
                        canvas.drawText(label, key.w / 2f, baseline, text);
                    }
                }

                canvas.restoreToCount(save);
            }
        }
    }

    private void drawStrip(Canvas canvas) {
        float wordSize = stripHeight * 0.34f;
        float emojiSize = stripHeight * 0.46f;
        text.setTextSize(wordSize);
        emojiPaint.setTextSize(emojiSize);
        float radius = stripHeight * 0.32f;

        for (int i = 0; i < WORD_SLOTS; i++) {
            // The centre slot holds the best guess, so words are placed
            // 1 -> centre, 2 -> left, 3 -> right rather than left to right.
            int rank = i == 1 ? 0 : (i == 0 ? 1 : 2);
            if (rank >= words.size()) {
                continue;
            }
            slot.set(wordSlots[i]);
            if (pressedSlot == i) {
                canvas.drawRoundRect(slot, radius, radius, chip);
            }
            float baseline = slot.centerY() - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(words.get(rank), slot.centerX(), baseline, text);
        }

        for (int i = 0; i < EMOJI_SLOTS && i < emoji.size(); i++) {
            slot.set(emojiSlots[i]);
            if (pressedSlot == WORD_SLOTS + i) {
                canvas.drawRoundRect(slot, radius, radius, chip);
            }
            float baseline = slot.centerY()
                    - (emojiPaint.descent() + emojiPaint.ascent()) / 2f;
            canvas.drawText(emoji.get(i), slot.centerX(), baseline, emojiPaint);
        }
    }

    private static boolean hasIcon(Keys.Key key) {
        return key.code == Keys.SHIFT || key.code == Keys.BACKSPACE
                || key.code == Keys.ENTER || key.code == Keys.EMOJI;
    }

    /**
     * Shift, backspace, enter and the emoji key, drawn rather than typed. Each is
     * laid out in a square of side {@code s} centred in the key, so one set of
     * proportions works at every key size.
     */
    private void drawIcon(Canvas canvas, Keys.Key key) {
        float s = Math.min(key.w, key.h) * 0.42f;
        float cx = key.w / 2f;
        float cy = key.h / 2f;

        icon.setStrokeWidth(Math.max(density, s * 0.14f));
        iconPath.reset();

        switch (key.code) {
            case Keys.SHIFT:
                // An upward arrow on a stem. Outlined for one-shot shift, filled
                // once caps lock is on, which is the whole difference between them.
                iconPath.moveTo(cx, cy - s * 0.62f);
                iconPath.lineTo(cx - s * 0.60f, cy + s * 0.02f);
                iconPath.lineTo(cx - s * 0.26f, cy + s * 0.02f);
                iconPath.lineTo(cx - s * 0.26f, cy + s * 0.52f);
                iconPath.lineTo(cx + s * 0.26f, cy + s * 0.52f);
                iconPath.lineTo(cx + s * 0.26f, cy + s * 0.02f);
                iconPath.lineTo(cx + s * 0.60f, cy + s * 0.02f);
                iconPath.close();
                icon.setStyle(capsLock ? Paint.Style.FILL : Paint.Style.STROKE);
                canvas.drawPath(iconPath, icon);
                icon.setStyle(Paint.Style.STROKE);
                return;

            case Keys.BACKSPACE:
                // The usual tag shape with a cross in it.
                iconPath.moveTo(cx - s * 0.75f, cy);
                iconPath.lineTo(cx - s * 0.30f, cy - s * 0.50f);
                iconPath.lineTo(cx + s * 0.75f, cy - s * 0.50f);
                iconPath.lineTo(cx + s * 0.75f, cy + s * 0.50f);
                iconPath.lineTo(cx - s * 0.30f, cy + s * 0.50f);
                iconPath.close();
                canvas.drawPath(iconPath, icon);
                iconPath.reset();
                iconPath.moveTo(cx + s * 0.02f, cy - s * 0.22f);
                iconPath.lineTo(cx + s * 0.46f, cy + s * 0.22f);
                iconPath.moveTo(cx + s * 0.46f, cy - s * 0.22f);
                iconPath.lineTo(cx + s * 0.02f, cy + s * 0.22f);
                canvas.drawPath(iconPath, icon);
                return;

            case Keys.EMOJI:
                // A face: ring, two eyes, and a smile. Drawn rather than set as
                // text so it takes the key's own colour instead of arriving as a
                // full-colour glyph that would fight the glass.
                canvas.drawCircle(cx, cy, s * 0.62f, icon);
                float eyeX = s * 0.24f;
                float eyeY = s * 0.18f;
                float eyeR = Math.max(density * 0.8f, s * 0.08f);
                icon.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx - eyeX, cy - eyeY, eyeR, icon);
                canvas.drawCircle(cx + eyeX, cy - eyeY, eyeR, icon);
                icon.setStyle(Paint.Style.STROKE);
                iconPath.reset();
                iconPath.moveTo(cx - s * 0.28f, cy + s * 0.14f);
                iconPath.quadTo(cx, cy + s * 0.46f, cx + s * 0.28f, cy + s * 0.14f);
                canvas.drawPath(iconPath, icon);
                return;

            default:
                // Enter: a stem down the right, turning left into an arrowhead.
                iconPath.moveTo(cx + s * 0.62f, cy - s * 0.58f);
                iconPath.lineTo(cx + s * 0.62f, cy + s * 0.22f);
                iconPath.lineTo(cx - s * 0.52f, cy + s * 0.22f);
                iconPath.moveTo(cx - s * 0.18f, cy - s * 0.14f);
                iconPath.lineTo(cx - s * 0.62f, cy + s * 0.22f);
                iconPath.lineTo(cx - s * 0.18f, cy + s * 0.58f);
                canvas.drawPath(iconPath, icon);
        }
    }

    private String labelFor(Keys.Key key) {
        if (page == Keys.LETTERS && !key.isCommand() && Character.isLetter(key.code)
                && (shift || capsLock)) {
            return key.label.toUpperCase(Locale.US);
        }
        return key.label;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (y < stripHeight) {
                    pressedSlot = slotAt(x, y);
                    invalidate();
                    return true;
                }
                press(keyAt(x, y));
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pressedSlot >= 0) {
                    return true;
                }
                // A finger that slides onto another key retargets, which is how a
                // mistyped key gets corrected without lifting off first.
                Keys.Key moved = keyAt(x, y);
                if (moved != null && moved != pressed) {
                    press(moved);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (pressedSlot >= 0) {
                    int slotIndex = pressedSlot;
                    pressedSlot = -1;
                    invalidate();
                    if (slotAt(x, y) == slotIndex) {
                        takeSlot(slotIndex);
                    }
                    return true;
                }
                Keys.Key key = pressed;
                cancelRepeat();
                pressed = null;
                invalidate();
                if (key != null) {
                    pendingKey = key;
                    // Through performClick so accessibility services and anything
                    // listening for a click still see the tap.
                    performClick();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelRepeat();
                pressed = null;
                pressedSlot = -1;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    /** Words first, then emoji, or -1 for a miss. */
    private int slotAt(float x, float y) {
        for (int i = 0; i < WORD_SLOTS; i++) {
            int rank = i == 1 ? 0 : (i == 0 ? 1 : 2);
            if (rank < words.size() && wordSlots[i].contains(x, y)) {
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

    private void takeSlot(int slotIndex) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (slotIndex < WORD_SLOTS) {
            int rank = slotIndex == 1 ? 0 : (slotIndex == 0 ? 1 : 2);
            if (rank < words.size()) {
                pendingSuggestion = words.get(rank);
                pendingSuggestionIsEmoji = false;
                performClick();
            }
            return;
        }
        int index = slotIndex - WORD_SLOTS;
        if (index < emoji.size()) {
            pendingSuggestion = emoji.get(index);
            pendingSuggestionIsEmoji = true;
            performClick();
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        String suggestion = pendingSuggestion;
        pendingSuggestion = null;
        if (suggestion != null) {
            if (listener != null) {
                if (pendingSuggestionIsEmoji) {
                    listener.onEmojiPicked(suggestion);
                } else {
                    listener.onSuggestion(suggestion);
                }
            }
            return true;
        }
        Keys.Key key = pendingKey;
        pendingKey = null;
        if (key != null) {
            commit(key);
        }
        return true;
    }

    private void press(Keys.Key key) {
        cancelRepeat();
        pressed = key;
        invalidate();
        if (key == null) {
            return;
        }
        // No FLAG_IGNORE_GLOBAL_SETTING: a user who has turned haptics off for
        // the system has turned them off for this keyboard too.
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (key.code == Keys.BACKSPACE) {
            handler.postDelayed(repeat, REPEAT_FIRST_MS);
        }
    }

    private void commit(Keys.Key key) {
        switch (key.code) {
            case Keys.SHIFT:
                long now = SystemClock.uptimeMillis();
                if (capsLock) {
                    capsLock = false;
                    shift = false;
                } else if (now - lastShiftTap < DOUBLE_TAP_MS) {
                    capsLock = true;
                    shift = false;
                } else {
                    shift = !shift;
                }
                lastShiftTap = now;
                invalidate();
                return;

            case Keys.PAGE_LETTERS:
                setPage(Keys.LETTERS);
                return;
            case Keys.PAGE_SYMBOLS:
                setPage(Keys.SYMBOLS);
                return;
            case Keys.PAGE_MORE:
                setPage(Keys.MORE);
                return;

            case Keys.EMOJI:
            case Keys.BACKSPACE:
            case Keys.ENTER:
                emit(key.code);
                return;

            default:
                int code = key.code;
                if (page == Keys.LETTERS && (shift || capsLock)) {
                    code = Character.toUpperCase(code);
                }
                emit(code);
                // A one-shot shift ends with the character it capitalised.
                if (shift && !capsLock) {
                    shift = false;
                    invalidate();
                }
        }
    }

    private void setPage(int newPage) {
        page = newPage;
        rows = Keys.page(newPage);
        rowWeights = Keys.rowHeights(newPage);
        shift = false;
        capsLock = false;
        if (getWidth() > 0) {
            layoutKeys(getWidth(), getHeight());
        }
        invalidate();
    }

    private void emit(int code) {
        if (listener != null) {
            listener.onKey(code);
        }
    }

    private Keys.Key keyAt(float x, float y) {
        Keys.Key nearest = null;
        float best = Float.MAX_VALUE;
        for (Keys.Key[] row : rows) {
            for (Keys.Key key : row) {
                if (key.contains(x, y)) {
                    return key;
                }
                // Nothing hit exactly yet — track the nearest key so the gaps
                // between keys are not dead space.
                float dx = Math.max(0f, Math.max(key.x - x, x - (key.x + key.w)));
                float dy = Math.max(0f, Math.max(key.y - y, y - (key.y + key.h)));
                float distance = dx * dx + dy * dy;
                if (distance < best) {
                    best = distance;
                    nearest = key;
                }
            }
        }
        return best <= (float) touchSlop * touchSlop ? nearest : null;
    }

    private void cancelRepeat() {
        handler.removeCallbacks(repeat);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelRepeat();
        super.onDetachedFromWindow();
    }
}
