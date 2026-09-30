package com.harithkavish.keyboard;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
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
 * that is brightest along the top edge. The panes are solid enough to pass the
 * colour behind them without passing its detail, which is the closest thing to
 * frosting available without a blur -- and which side they tint towards depends
 * on the backdrop, because a pane pale enough to hide text is also pale enough
 * to lose pale glyphs. There is deliberately no gloss band
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


        /**
         * The page or the shift state changed. The keyboard handles both itself,
         * but the suggestion row lives above all three pages now and capitalises
         * against the shift key, so it has to be told. A page that switched
         * silently used to leave the row showing whatever the previous page had
         * left behind.
         */
        void onKeyboardStateChanged();

        /**
         * The space bar was swiped to the right. The best suggestion belongs to
         * the row, not to the keyboard, so the service is the one that can read
         * it and type it.
         */
        void onSpaceSwipe();
    }

    private static final float ROW_UNITS = 10f;

    private static final long REPEAT_FIRST_MS = 400;
    private static final long REPEAT_NEXT_MS = 50;

    /** Three words on the left, two emoji on the right. */
    /**
     * Share of the strip the words take when there are emoji to show. With none,
     * the words spread across the whole strip rather than leaving a gap.
     */

    /**
     * Tight on purpose. The strip is the first thing under the top edge of the
     * window, so any slack in it reads as a gap between the app and the
     * keyboard rather than as breathing room around the words.
     */


    /**
     * How far the finger travels before a press on the space bar becomes a swipe.
     * Comfortably inside the bar, so the gesture is recognised long before the
     * finger could slide onto the key beyond it.
     */
    private static final float SWIPE_MIN_DP = 40f;

    /**
     * The ceiling for a glyph on a key, and for an icon's half-size.
     *
     * <p>Both match the emoji picker's bottom bar, where the ABC label is 14dp
     * and the backspace arrow is built on a 9dp half-size. Those read correctly,
     * and a keyboard whose letters grew with the key while the picker's did not
     * looked like two different apps. The key itself still scales; only what is
     * drawn inside it stops.
     */
    private static final float MAX_GLYPH_DP = 14f;
    private static final float MAX_ICON_DP = 9f;
    private static final float LETTER_ROW_DP = 46f;
    /** Cap in landscape, where a letter-row-sized keyboard would eat the screen. */
    private static final float MAX_ROW_SHARE_OF_SCREEN = 0.075f;

    /**
     * A soft shadow stacked by hand, largest offset first. Hardware-accelerated
     * canvases will not blur an arbitrary shape cheaply -- BlurMaskFilter forces
     * a software layer -- so the falloff is three offset rounded rects instead.
     * Without it the panes sit flat against the wallpaper and stop reading as
     * glass at all.
     */

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Colour emoji carry their own colour, and a shadow under them looks wrong. */
    private final Path iconPath = new Path();

    private final float density;
    private final int touchSlop;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Listener listener;
    private Keys.Key[][] rows = Keys.page(Keys.LETTERS);
    private float[] rowWeights = Keys.rowHeights(Keys.LETTERS);
    private int page = Keys.LETTERS;

    /** Index into the strip, words first then emoji, or -1. */
    /** Where the finger went down, for the space bar swipe. */
    private float downX;
    /** True once a press that began on the space bar has travelled far enough. */
    private boolean spaceSwiped;
    /**
     * What that swipe would type, for drawing on the bar. Pushed in by the
     * service, because the suggestions belong to the row rather than here.
     */
    private String swipeWord;
    /** What the word already typed asks for, independent of the shift key. */

    private Keys.Key pressed;
    /** The key lifted off, handed to {@link #performClick()} to act on. */
    private Keys.Key pendingKey;
    private boolean shift;
    private boolean capsLock;
    /**
     * True once the person has touched the shift key themselves, which stops
     * automatic capitalisation overriding their choice until the next character.
     */
    private boolean manualShift;

    private float keyRadius;
    private float gap;
    private float letterRowHeight;
    private boolean night;
    /** Multiplier on every key's alpha, from the opacity setting and the wallpaper. */
    private float opacityScale = 1f;
    /** TRUE or FALSE once the wallpaper has been read; null means follow the system. */
    private Boolean backdropLight;

    // Rebuilt only when the key height or the theme changes, so onDraw allocates
    // nothing at all.
    private Shader bodyShader;
    private Shader bodyShaderPressed;
    private Shader bodyShaderCommand;
    private Shader rimShader;
    // The strip is shorter than a key row, and a gradient built for one height
    // drawn at another clamps and loses its shape.

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
        readTheme();
    }

    /**
     * How tall the keyboard wants to be. Static because the emoji picker has to
     * match it exactly -- it opens in the same window, and a picker that measured
     * itself would be the height of the screen instead.
     */
    static int preferredHeight(Resources resources) {
        float density = resources.getDisplayMetrics().density;
        int screenHeight = resources.getDisplayMetrics().heightPixels;
        float row = Math.min(LETTER_ROW_DP * density,
                screenHeight * MAX_ROW_SHARE_OF_SCREEN);
        // Measured against the letters page even on the symbol pages, which have
        // no number row. Sizing each page to its own rows would make the whole
        // keyboard jump every time the cycle key is pressed.
        float units = 0f;
        for (float weight : Keys.rowHeights(Keys.LETTERS)) {
            units += weight;
        }
        // The suggestion row is a sibling view with its own height; this is the
        // keys alone.
        return Math.round(row * units);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Called when a new field takes focus, so the keyboard never opens mid-symbol. */
    void reset() {
        cancelRepeat();
        pressed = null;
        shift = false;
        capsLock = false;
        manualShift = false;
        if (page != Keys.LETTERS) {
            setPage(Keys.LETTERS);
        } else {
            invalidate();
        }
    }

    /**
     * Turns shift on or off for the start of a sentence. Does nothing while caps
     * lock is on, or once the person has set shift themselves -- guessing is only
     * welcome until someone says otherwise.
     */
    void setAutoShift(boolean on) {
        if (capsLock || manualShift || shift == on) {
            return;
        }
        shift = on;
        invalidate();
    }

    boolean isShifted() {
        return shift || capsLock;
    }

    private void readTheme() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        // A known backdrop beats the system theme. Dark keys on a dark wallpaper
        // are invisible no matter how opaque they are, so which treatment to use
        // matters more than how solid it is.
        night = backdropLight != null ? !backdropLight
                : mode == Configuration.UI_MODE_NIGHT_YES;
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
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                preferredHeight(getResources()));
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

        float units = 0f;
        for (float weight : rowWeights) {
            units += weight;
        }
        letterRowHeight = height / units;
        // The reference unit is a letter's width on a full ten-key row. A short
        // row is laid out with this width and centred, so the nine-letter home
        // row sits inside the top row instead of stretching to match it.
        float keysWidth = width - 2f * padH;
        float referenceUnit = (keysWidth - gap * (ROW_UNITS - 1f)) / ROW_UNITS;

        float y = 0f;
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
                unit = (keysWidth - gap * (row.length - 1)) / totalWeight;
            } else {
                unit = referenceUnit;
            }

            float rowWidth = gap * (row.length - 1) + unit * totalWeight;
            float x = padH + (keysWidth - rowWidth) / 2f;
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

    private void buildShaders() {
        if (letterRowHeight <= 0f) {
            return;
        }
        int top = Glass.top(night, opacityScale);
        int bottom = Glass.bottom(night, opacityScale);
        int rimA = Glass.rimTop(night, opacityScale);
        int rimB = Glass.rimBottom(night, opacityScale);

        float keyHeight = letterRowHeight - gap;
        bodyShader = Glass.vertical(keyHeight, top, bottom);
        bodyShaderPressed = Glass.vertical(keyHeight, Glass.brighten(top),
                Glass.brighten(bottom));
        // Command keys sit back a step so the letters read as the primary rows.
        bodyShaderCommand = Glass.vertical(keyHeight, Glass.dim(top), Glass.dim(bottom));
        rimShader = Glass.vertical(keyHeight, rimA, rimB);

        rim.setStrokeWidth(Glass.rimWidth(density));
    }

    /**
     * Shadow, body and rim, at a rectangle. Used for the keys and for the
     * suggestions both, because "the same glass" has to mean the same code --
     * two copies would drift the moment either was tuned.
     */
    private void drawPane(Canvas canvas, float x, float y, float w, float h,
                          float radius, Shader body, Shader paneRim) {
        Glass.drawPane(canvas, fill, rim, shadow, density, x, y, w, h,
                radius, body, paneRim);
    }




    @Override
    protected void onDraw(Canvas canvas) {
        if (bodyShader == null) {
            buildShaders();
            if (bodyShader == null) {
                return;
            }
        }

        for (int r = 0; r < rows.length; r++) {
            float rowHeight = letterRowHeight * rowWeights[r] - gap;
            float letterSize = Math.min(rowHeight * 0.40f, MAX_GLYPH_DP * density);
            float labelSize = Math.min(rowHeight * 0.29f, MAX_GLYPH_DP * density);
            for (Keys.Key key : rows[r]) {
                boolean down = key == pressed;
                Shader body;
                if (down) {
                    body = bodyShaderPressed;
                } else if (key.isCommand() || key.code == ' ') {
                    body = bodyShaderCommand;
                } else {
                    body = bodyShader;
                }
                drawPane(canvas, key.x, key.y, key.w, key.h, keyRadius, body, rimShader);

                int save = canvas.save();
                canvas.translate(key.x, key.y);
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
                if (spaceSwiped && key.code == ' ' && swipeWord != null) {
                    // Without this the gesture is invisible: the bar is blank, so
                    // there is nothing to say which word is about to be committed.
                    text.setTextSize(labelSize);
                    float baseline = key.h / 2f - (text.descent() + text.ascent()) / 2f;
                    canvas.drawText(swipeWord, key.w / 2f, baseline, text);
                }

                canvas.restoreToCount(save);
            }
        }
    }

    /**
     * Sets how solid the keys are drawn, and whether to treat the backdrop as
     * light regardless of the system theme.
     *
     * <p>{@code light} is null when nothing is known about what is behind, which
     * is the case below API 27 and whenever the person has turned adaptation
     * off. The system's own dark mode is the fallback.
     */
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

    /** What the shift key alone is asking for. */
    Casing.Mode caseMode() {
        if (capsLock) {
            return Casing.Mode.UPPER;
        }
        return shift ? Casing.Mode.TITLE : Casing.Mode.NONE;
    }

    /**
     * Back to the letters, wherever we were. The emoji picker hands the screen
     * back through here: it is reached from the symbol page, and returning to
     * the symbols rather than the alphabet is not what "ABC" means.
     */
    void showLetters() {
        if (page != Keys.LETTERS) {
            setPage(Keys.LETTERS);
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
        float s = Math.min(Math.min(key.w, key.h) * 0.42f, MAX_ICON_DP * density);
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
                if (capsLock) {
                    // A bar under a filled arrow, so caps lock is distinguishable
                    // from shift at a glance rather than by shade alone.
                    canvas.drawLine(cx - s * 0.52f, cy + s * 0.78f,
                            cx + s * 0.52f, cy + s * 0.78f, icon);
                }
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
                //
                // Smaller than the shared basis on purpose. A closed circle reads
                // larger than an arrow or a bracket drawn to the same bounding
                // box, so matching the others by number left this one looking
                // oversized beside the letters.
                float f = s * 0.78f;
                icon.setStrokeWidth(Math.max(density, f * 0.14f));
                canvas.drawCircle(cx, cy, f * 0.62f, icon);
                float eyeX = f * 0.24f;
                float eyeY = f * 0.18f;
                float eyeR = Math.max(density * 0.8f, f * 0.08f);
                icon.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx - eyeX, cy - eyeY, eyeR, icon);
                canvas.drawCircle(cx + eyeX, cy - eyeY, eyeR, icon);
                icon.setStyle(Paint.Style.STROKE);
                iconPath.reset();
                iconPath.moveTo(cx - f * 0.28f, cy + f * 0.14f);
                iconPath.quadTo(cx, cy + f * 0.46f, cx + f * 0.28f, cy + f * 0.14f);
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
            return key.label.toUpperCase(Locale.getDefault());
        }
        return key.label;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                spaceSwiped = false;
                press(keyAt(x, y));
                return true;

            case MotionEvent.ACTION_MOVE:
                // Dragging the space bar to the right takes the best suggestion.
                // The threshold is well inside the bar, so the gesture is claimed
                // before the finger could reach the key beyond it.
                if (!spaceSwiped && pressed != null && pressed.code == ' '
                        && x - downX >= density * SWIPE_MIN_DP) {
                    spaceSwiped = true;
                    cancelRepeat();
                    invalidate();
                }
                if (spaceSwiped) {
                    // No retargeting once it is a swipe, or sliding past the end of
                    // the bar would quietly turn it back into a key press.
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
                if (spaceSwiped) {
                    spaceSwiped = false;
                    cancelRepeat();
                    pressed = null;
                    invalidate();
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (listener != null) {
                        listener.onSpaceSwipe();
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
                spaceSwiped = false;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
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
                // Three states in a cycle: one-shot shift, caps lock, off. No
                // double-tap timing -- a timed gesture is invisible, and a person
                // who wants caps lock should not have to discover how fast to tap.
                if (capsLock) {
                    capsLock = false;
                    shift = false;
                } else if (shift) {
                    shift = false;
                    capsLock = true;
                } else {
                    shift = true;
                }
                manualShift = true;
                invalidate();
                announceState();
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
                consumeOneShotShift();
        }
    }

    /** A one-shot shift ends with the character it capitalised. */
    private void consumeOneShotShift() {
        manualShift = false;
        if (shift && !capsLock) {
            shift = false;
            invalidate();
            // The row capitalises against the shift key, so spending the
            // one-shot has to reach it too.
            announceState();
        }
    }

    /** What a space bar swipe would commit, drawn on the bar mid-gesture. */
    void setSwipeWord(String word) {
        if (word == null ? swipeWord != null : !word.equals(swipeWord)) {
            swipeWord = word;
            if (spaceSwiped) {
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
        manualShift = false;
        if (getWidth() > 0) {
            layoutKeys(getWidth(), getHeight());
        }
        invalidate();
        announceState();
    }

    /** Tells the service the row may need re-capitalising. */
    private void announceState() {
        if (listener != null) {
            listener.onKeyboardStateChanged();
        }
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
