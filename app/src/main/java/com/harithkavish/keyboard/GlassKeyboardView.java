package com.harithkavish.keyboard;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Locale;

/**
 * Draws the whole keyboard into one canvas and reports the keys that were
 * pressed.
 *
 * <p>One view, not thirty: a View per key would cost a measure and a layout pass
 * per key on every rotation and an allocation per key at startup, for drawing
 * that is a handful of rounded rectangles either way.
 *
 * <p>The glass look is three cheap draws rather than a real blur: a vertical
 * gradient body, a rim light that fades from the top edge down, and a highlight
 * band across the upper half. Android cannot blur what is behind another window
 * before API 31, and where it can, the blur covers the whole window rectangle,
 * which would defeat the transparent background this keyboard is built around.
 */
final class GlassKeyboardView extends View {

    interface Listener {
        /** Called with a character, or with {@link Keys#BACKSPACE} / {@link Keys#ENTER}. */
        void onKey(int code);
    }

    private static final float ROW_UNITS = 10f;
    private static final int ROWS = 4;

    private static final long REPEAT_FIRST_MS = 400;
    private static final long REPEAT_NEXT_MS = 50;
    private static final long DOUBLE_TAP_MS = 350;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();

    private final float density;
    private final int touchSlop;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Listener listener;
    private Keys.Key[][] rows = Keys.page(Keys.LETTERS);
    private int page = Keys.LETTERS;

    private Keys.Key pressed;
    /** The key lifted off, handed to {@link #performClick()} to act on. */
    private Keys.Key pendingKey;
    private boolean shift;
    private boolean capsLock;
    private long lastShiftTap;

    private float keyRadius;
    private float gap;
    private float keyHeight;
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
        readTheme();
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
        if (page != Keys.LETTERS) {
            setPage(Keys.LETTERS);
        } else {
            invalidate();
        }
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
        text.setShadowLayer(density * 1.5f, 0f, density * 0.5f,
                night ? 0x66000000 : 0x40FFFFFF);
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
        // 52dp rows on a phone held upright, capped so landscape does not hand
        // the keyboard most of the screen.
        float row = Math.min(52f * density, screenHeight * 0.11f);
        setMeasuredDimension(width, Math.round(row * ROWS + 12f * density));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        layoutKeys(w, h);
    }

    private void layoutKeys(int width, int height) {
        float padH = 4f * density;
        float padV = 6f * density;
        gap = 4f * density;
        keyRadius = 11f * density;

        float available = width - 2f * padH;
        float rowHeight = (height - 2f * padV) / rows.length;
        keyHeight = rowHeight - gap;
        // The reference unit is a letter's width on a full ten-key row. A short
        // row is laid out with this width and centred, so the nine-letter home
        // row sits inside the top row instead of stretching to match it.
        float referenceUnit = (available - gap * (ROW_UNITS - 1f)) / ROW_UNITS;

        float y = padV;
        for (Keys.Key[] row : rows) {
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
                key.h = keyHeight;
                x += key.w + gap;
            }
            y += rowHeight;
        }

        buildShaders();
    }

    private void buildShaders() {
        if (keyHeight <= 0f) {
            return;
        }
        int top;
        int bottom;
        if (night) {
            top = 0x40FFFFFF;
            bottom = 0x14FFFFFF;
        } else {
            top = 0x99FFFFFF;
            bottom = 0x4DFFFFFF;
        }
        bodyShader = vertical(top, bottom);
        bodyShaderPressed = vertical(brighten(top), brighten(bottom));
        // Command keys sit back a step so the letters read as the primary rows.
        bodyShaderCommand = vertical(dim(top), dim(bottom));
        rimShader = vertical(night ? 0x8CFFFFFF : 0x66FFFFFF, night ? 0x1AFFFFFF : 0x14000000);
        rim.setStrokeWidth(Math.max(1f, density));
        sheen.setColor(night ? 0x14FFFFFF : 0x30FFFFFF);
    }

    private Shader vertical(int top, int bottom) {
        return new LinearGradient(0f, 0f, 0f, keyHeight, top, bottom, Shader.TileMode.CLAMP);
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

        float letterSize = keyHeight * 0.42f;
        float labelSize = keyHeight * 0.30f;

        for (Keys.Key[] row : rows) {
            for (Keys.Key key : row) {
                int save = canvas.save();
                // The shaders are built once, for a gradient one key-height tall,
                // so each key is drawn at the origin and moved into place.
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
                fill.setShader(body);
                canvas.drawRoundRect(0f, 0f, key.w, key.h, keyRadius, keyRadius, fill);

                // The highlight band across the top is the part that reads as glass.
                canvas.drawRoundRect(density, density, key.w - density, key.h * 0.5f,
                        keyRadius * 0.8f, keyRadius * 0.8f, sheen);

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

    private static boolean hasIcon(Keys.Key key) {
        return key.code == Keys.SHIFT || key.code == Keys.BACKSPACE || key.code == Keys.ENTER;
    }

    /**
     * Shift, backspace and enter, drawn rather than typed. Each is laid out in a
     * square of side {@code s} centred in the key, so one set of proportions
     * works at every key size.
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
        if (page == Keys.LETTERS && !key.isCommand() && (shift || capsLock)) {
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
                press(keyAt(x, y));
                return true;

            case MotionEvent.ACTION_MOVE:
                // A finger that slides onto another key retargets, which is how a
                // mistyped key gets corrected without lifting off first.
                Keys.Key moved = keyAt(x, y);
                if (moved != null && moved != pressed) {
                    press(moved);
                }
                return true;

            case MotionEvent.ACTION_UP:
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
