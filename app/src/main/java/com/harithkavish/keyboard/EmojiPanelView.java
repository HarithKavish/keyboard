package com.harithkavish.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * The emoji picker: a scrolling grid with a row along the bottom holding the ABC
 * key, the category tabs and backspace.
 *
 * <p>Exactly as tall as the keyboard, never the screen. It opens in the same
 * window, so a picker that sized itself to the space available would shove the
 * app off the top of the display every time it was opened.
 *
 * <p>Transparent like the keyboard, which is the whole reason it is drawn here
 * rather than handed to a RecyclerView -- and drawn into one canvas for the same
 * reason the keys are: several hundred child views to show a few hundred glyphs
 * is a lot of layout for something that is one text draw per cell.
 *
 * <p>ABC sits at the bottom left, under the same thumb as the cycle key that
 * opened the picker, so letters → symbols → emoji → letters is three taps in one
 * place. No search, no GIFs and no stickers: those are three separate products.
 */
final class EmojiPanelView extends View {

    interface Listener {
        void onEmojiPicked(String emoji);

        void onBackspace();

        /** The ABC key: hand the screen back to the letters. */
        void onBackToKeyboard();
    }

    private static final String PREFS = "glass_keyboard_learning";
    private static final String KEY_RECENTS = "emoji_recents";
    private static final int MAX_RECENTS = 27;
    private static final int COLUMNS = 9;
    private static final float FLING_DECAY = 0.92f;
    private static final float FLING_STOP = 2f;
    /** Share of the width taken by ABC on the left and backspace on the right. */
    private static final float SIDE_SHARE = 0.14f;

    private final Paint emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tabPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();
    private final RectF rect = new RectF();

    private final float density;
    private final int touchSlop;
    private final SharedPreferences prefs;

    private Listener listener;
    private final List<String> recents = new ArrayList<>();
    /** Section labels and contents, recents first when there are any. */
    private final List<String> sectionNames = new ArrayList<>();
    private final List<String[]> sectionItems = new ArrayList<>();
    /** Scroll offset of each section's header, filled by {@link #measureSections}. */
    private final List<Float> sectionTops = new ArrayList<>();

    private float cell;
    private float headerHeight;
    private float barHeight;
    private float sideWidth;
    private float contentHeight;
    private float scroll;

    private VelocityTracker tracker;
    private float downX;
    private float downY;
    private float lastY;
    private boolean scrolling;
    private int pressedTab = -1;
    private boolean pressedAbc;
    private boolean pressedBackspace;
    private float flingVelocity;

    private final Runnable flinger = new Runnable() {
        @Override
        public void run() {
            if (Math.abs(flingVelocity) < FLING_STOP) {
                flingVelocity = 0f;
                return;
            }
            setScroll(scroll - flingVelocity);
            flingVelocity *= FLING_DECAY;
            postOnAnimation(this);
        }
    };

    EmojiPanelView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        setBackgroundColor(0x00000000);
        emojiPaint.setTextAlign(Paint.Align.CENTER);
        headerPaint.setTextAlign(Paint.Align.LEFT);
        tabPaint.setTextAlign(Paint.Align.CENTER);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        readTheme();
        loadRecents();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    private void readTheme() {
        int mode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean night = mode == Configuration.UI_MODE_NIGHT_YES;
        int ink = night ? 0xFFFFFFFF : 0xFF14161C;
        headerPaint.setColor(ink);
        headerPaint.setAlpha(0xB3);
        headerPaint.setShadowLayer(density, 0f, density * 0.5f,
                night ? 0x66000000 : 0x40FFFFFF);
        tabPaint.setColor(ink);
        icon.setColor(ink);
        chip.setColor(night ? 0x26FFFFFF : 0x4DFFFFFF);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        readTheme();
        invalidate();
    }

    private void loadRecents() {
        recents.clear();
        String stored = prefs.getString(KEY_RECENTS, "");
        if (stored != null && !stored.isEmpty()) {
            // Tab separated: an emoji is several chars, so nothing here may split
            // on character boundaries.
            for (String entry : stored.split("\t")) {
                if (!entry.isEmpty()) {
                    recents.add(entry);
                }
            }
        }
        buildSections();
    }

    /** Called when the panel is shown, in case the strip added a recent. */
    void refresh() {
        loadRecents();
        if (cell > 0f) {
            measureSections();
        }
        invalidate();
    }

    private void buildSections() {
        sectionNames.clear();
        sectionItems.clear();
        if (!recents.isEmpty()) {
            sectionNames.add("Recents");
            sectionItems.add(recents.toArray(new String[0]));
        }
        for (int i = 0; i < Emoji.CATEGORIES.length; i++) {
            sectionNames.add(Emoji.CATEGORY_NAMES[i]);
            sectionItems.add(Emoji.CATEGORIES[i]);
        }
    }

    private void noteRecent(String emoji) {
        recents.remove(emoji);
        recents.add(0, emoji);
        while (recents.size() > MAX_RECENTS) {
            recents.remove(recents.size() - 1);
        }
        StringBuilder out = new StringBuilder();
        for (String entry : recents) {
            out.append(entry).append('\t');
        }
        prefs.edit().putString(KEY_RECENTS, out.toString()).apply();
        buildSections();
        measureSections();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // The keyboard's height, not the screen's. See the class comment.
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                GlassKeyboardView.preferredHeight(getResources()));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        cell = w / (float) COLUMNS;
        barHeight = 46f * density;
        sideWidth = w * SIDE_SHARE;
        headerHeight = 24f * density;
        emojiPaint.setTextSize(cell * 0.60f);
        headerPaint.setTextSize(12f * density);
        measureSections();
        setScroll(0f);
    }

    private void measureSections() {
        sectionTops.clear();
        float y = 0f;
        for (String[] items : sectionItems) {
            sectionTops.add(y);
            int rows = (items.length + COLUMNS - 1) / COLUMNS;
            y += headerHeight + rows * cell;
        }
        contentHeight = y;
    }

    private float viewportHeight() {
        return getHeight() - barHeight;
    }

    private void setScroll(float value) {
        float max = Math.max(0f, contentHeight - viewportHeight());
        float clamped = Math.max(0f, Math.min(max, value));
        if (clamped != scroll) {
            scroll = clamped;
            invalidate();
        } else if (value != clamped) {
            // Hitting an edge ends a fling rather than letting it decay in place.
            flingVelocity = 0f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (cell <= 0f) {
            return;
        }
        int save = canvas.save();
        canvas.clipRect(0f, 0f, getWidth(), viewportHeight());
        canvas.translate(0f, -scroll);

        float viewTop = scroll;
        float viewBottom = scroll + viewportHeight();
        for (int s = 0; s < sectionItems.size(); s++) {
            float sectionTop = sectionTops.get(s);
            String[] items = sectionItems.get(s);
            int rows = (items.length + COLUMNS - 1) / COLUMNS;
            float sectionBottom = sectionTop + headerHeight + rows * cell;
            if (sectionBottom < viewTop || sectionTop > viewBottom) {
                continue;
            }
            canvas.drawText(sectionNames.get(s), 14f * density,
                    sectionTop + headerHeight * 0.75f, headerPaint);
            float gridTop = sectionTop + headerHeight;
            for (int i = 0; i < items.length; i++) {
                float x = (i % COLUMNS) * cell;
                float y = gridTop + (i / COLUMNS) * cell;
                if (y + cell < viewTop || y > viewBottom) {
                    continue;
                }
                float baseline = y + cell / 2f
                        - (emojiPaint.descent() + emojiPaint.ascent()) / 2f;
                canvas.drawText(items[i], x + cell / 2f, baseline, emojiPaint);
            }
        }
        canvas.restoreToCount(save);

        drawBottomBar(canvas);
    }

    private void drawBottomBar(Canvas canvas) {
        float top = getHeight() - barHeight;
        float cy = top + barHeight / 2f;

        // ABC, bottom left, continuing the cycle the keyboard's own key started.
        tabPaint.setTextSize(14f * density);
        if (pressedAbc) {
            rect.set(4f * density, top + 4f * density, sideWidth - 4f * density,
                    getHeight() - 4f * density);
            canvas.drawRoundRect(rect, barHeight * 0.3f, barHeight * 0.3f, chip);
        }
        canvas.drawText("ABC", sideWidth / 2f,
                cy - (tabPaint.descent() + tabPaint.ascent()) / 2f, tabPaint);

        int tabs = sectionItems.size();
        if (tabs > 0) {
            float width = (getWidth() - 2f * sideWidth) / tabs;
            tabPaint.setTextSize(Math.min(cell, width) * 0.46f);
            int current = currentSection();
            for (int i = 0; i < tabs; i++) {
                float cx = sideWidth + width * (i + 0.5f);
                if (i == current || i == pressedTab) {
                    float r = Math.min(width, barHeight) * 0.40f;
                    rect.set(cx - r, cy - r, cx + r, cy + r);
                    canvas.drawRoundRect(rect, r, r, chip);
                }
                String label = tabLabel(i);
                if (label == null) {
                    drawClock(canvas, cx, cy, Math.min(width, barHeight) * 0.22f);
                } else {
                    canvas.drawText(label, cx,
                            cy - (tabPaint.descent() + tabPaint.ascent()) / 2f, tabPaint);
                }
            }
        }

        // Backspace, bottom right.
        float s = 9f * density;
        float bx = getWidth() - sideWidth / 2f;
        if (pressedBackspace) {
            rect.set(getWidth() - sideWidth + 4f * density, top + 4f * density,
                    getWidth() - 4f * density, getHeight() - 4f * density);
            canvas.drawRoundRect(rect, barHeight * 0.3f, barHeight * 0.3f, chip);
        }
        icon.setStrokeWidth(Math.max(density, s * 0.16f));
        iconPath.reset();
        iconPath.moveTo(bx - s * 0.95f, cy);
        iconPath.lineTo(bx - s * 0.38f, cy - s * 0.64f);
        iconPath.lineTo(bx + s * 0.95f, cy - s * 0.64f);
        iconPath.lineTo(bx + s * 0.95f, cy + s * 0.64f);
        iconPath.lineTo(bx - s * 0.38f, cy + s * 0.64f);
        iconPath.close();
        canvas.drawPath(iconPath, icon);
        iconPath.reset();
        iconPath.moveTo(bx + s * 0.02f, cy - s * 0.28f);
        iconPath.lineTo(bx + s * 0.58f, cy + s * 0.28f);
        iconPath.moveTo(bx + s * 0.58f, cy - s * 0.28f);
        iconPath.lineTo(bx + s * 0.02f, cy + s * 0.28f);
        canvas.drawPath(iconPath, icon);
    }

    /** Null for the recents tab, which is drawn as a clock rather than a glyph. */
    private String tabLabel(int index) {
        boolean hasRecents = !recents.isEmpty();
        if (hasRecents && index == 0) {
            return null;
        }
        int categoryIndex = hasRecents ? index - 1 : index;
        return categoryIndex < Emoji.TAB_LABELS.length
                ? Emoji.TAB_LABELS[categoryIndex] : "";
    }

    private void drawClock(Canvas canvas, float cx, float cy, float r) {
        icon.setStrokeWidth(Math.max(density, r * 0.18f));
        canvas.drawCircle(cx, cy, r, icon);
        iconPath.reset();
        iconPath.moveTo(cx, cy - r * 0.55f);
        iconPath.lineTo(cx, cy);
        iconPath.lineTo(cx + r * 0.45f, cy + r * 0.2f);
        canvas.drawPath(iconPath, icon);
    }

    private int currentSection() {
        int current = 0;
        for (int i = 0; i < sectionTops.size(); i++) {
            if (sectionTops.get(i) <= scroll + 1f) {
                current = i;
            }
        }
        return current;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                flingVelocity = 0f;
                removeCallbacks(flinger);
                downX = x;
                downY = y;
                lastY = y;
                scrolling = false;
                if (tracker == null) {
                    tracker = VelocityTracker.obtain();
                } else {
                    tracker.clear();
                }
                tracker.addMovement(event);
                if (y >= getHeight() - barHeight) {
                    if (x < sideWidth) {
                        pressedAbc = true;
                    } else if (x > getWidth() - sideWidth) {
                        pressedBackspace = true;
                    } else {
                        pressedTab = tabAt(x);
                    }
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (tracker != null) {
                    tracker.addMovement(event);
                }
                if (pressedTab >= 0 || pressedAbc || pressedBackspace) {
                    return true;
                }
                if (!scrolling && Math.abs(y - downY) > touchSlop) {
                    scrolling = true;
                }
                if (scrolling) {
                    setScroll(scroll - (y - lastY));
                }
                lastY = y;
                return true;

            case MotionEvent.ACTION_UP:
                if (handleBarRelease(x, y)) {
                    releaseTracker();
                    return true;
                }
                if (!scrolling) {
                    handleTap(downX, downY);
                } else if (tracker != null) {
                    tracker.computeCurrentVelocity(16);
                    flingVelocity = tracker.getYVelocity();
                    postOnAnimation(flinger);
                }
                releaseTracker();
                return true;

            case MotionEvent.ACTION_CANCEL:
                clearBarPresses();
                releaseTracker();
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean handleBarRelease(float x, float y) {
        boolean wasAbc = pressedAbc;
        boolean wasBackspace = pressedBackspace;
        int wasTab = pressedTab;
        if (!wasAbc && !wasBackspace && wasTab < 0) {
            return false;
        }
        clearBarPresses();
        invalidate();
        boolean inBar = y >= getHeight() - barHeight;
        if (wasAbc && inBar && x < sideWidth) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) {
                listener.onBackToKeyboard();
            }
        } else if (wasBackspace && inBar && x > getWidth() - sideWidth) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) {
                listener.onBackspace();
            }
        } else if (wasTab >= 0 && inBar && tabAt(x) == wasTab
                && wasTab < sectionTops.size()) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            setScroll(sectionTops.get(wasTab));
        }
        return true;
    }

    private void clearBarPresses() {
        pressedAbc = false;
        pressedBackspace = false;
        pressedTab = -1;
    }

    private void releaseTracker() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    private int tabAt(float x) {
        int tabs = sectionItems.size();
        if (tabs == 0) {
            return -1;
        }
        float width = (getWidth() - 2f * sideWidth) / tabs;
        int index = (int) ((x - sideWidth) / width);
        return Math.max(0, Math.min(tabs - 1, index));
    }

    private void handleTap(float x, float y) {
        if (y >= viewportHeight()) {
            return;
        }
        float contentY = y + scroll;
        for (int s = 0; s < sectionItems.size(); s++) {
            float sectionTop = sectionTops.get(s);
            String[] items = sectionItems.get(s);
            int rows = (items.length + COLUMNS - 1) / COLUMNS;
            float gridTop = sectionTop + headerHeight;
            if (contentY < gridTop || contentY >= gridTop + rows * cell) {
                continue;
            }
            int row = (int) ((contentY - gridTop) / cell);
            int column = (int) (x / cell);
            int index = row * COLUMNS + column;
            if (index >= 0 && index < items.length) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                String picked = items[index];
                noteRecent(picked);
                if (listener != null) {
                    listener.onEmojiPicked(picked);
                }
                invalidate();
            }
            return;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(flinger);
        releaseTracker();
        super.onDetachedFromWindow();
    }
}
