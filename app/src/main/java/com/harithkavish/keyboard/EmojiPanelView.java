package com.harithkavish.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
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
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    /**
     * Which category is on screen. One at a time: the tabs are the only way
     * between them, so a flick can never drift out of the category being
     * browsed and scrolling stops at the end of it rather than running on into
     * the next.
     */
    private int page;
    /**
     * The name of that category, kept so the page survives a rebuild. Picking a
     * first emoji inserts a Recents section at the front and shifts every index
     * along by one; the name does not move.
     */
    private String pageName;

    private boolean night;
    private float opacityScale = 1f;
    /** TRUE or FALSE once the wallpaper has been read; null means follow the system. */
    private Boolean backdropLight;
    private Shader cellShader;
    private Shader cellRimShader;
    private Shader barShader;
    private Shader barShaderPressed;
    private Shader barRimShader;

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
        rim.setStyle(Paint.Style.STROKE);
        shadow.setStyle(Paint.Style.FILL);
        readTheme();
        loadRecents();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Matches the keys: how solid the panes are, and whether the backdrop counts
     * as light. Same arguments and same meaning as on the keyboard itself.
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

    private void readTheme() {
        int mode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        night = backdropLight != null ? !backdropLight
                : mode == Configuration.UI_MODE_NIGHT_YES;
        int ink = night ? 0xFFFFFFFF : 0xFF14161C;
        headerPaint.setColor(ink);
        headerPaint.setAlpha(0xB3);
        headerPaint.setShadowLayer(density, 0f, density * 0.5f,
                night ? 0x66000000 : 0x40FFFFFF);
        tabPaint.setColor(ink);
        icon.setColor(ink);
    }

    /** How far a pane sits inside its cell, so neighbours do not touch. */
    private float cellInset() {
        return density * 2.5f;
    }

    private float barInset() {
        return density * 4f;
    }

    private void buildShaders() {
        if (cell <= 0f) {
            return;
        }
        int top = Glass.top(night, opacityScale);
        int bottom = Glass.bottom(night, opacityScale);
        int rimA = Glass.rimTop(night, opacityScale);
        int rimB = Glass.rimBottom(night, opacityScale);

        float pane = cell - cellInset() * 2f;
        cellShader = Glass.vertical(pane, top, bottom);
        cellRimShader = Glass.vertical(pane, rimA, rimB);

        // The bar sits back a step, the same way the keyboard's command keys do,
        // so the emoji themselves stay the thing being looked at.
        float barPane = barHeight - barInset() * 2f;
        barShader = Glass.vertical(barPane, Glass.dim(top), Glass.dim(bottom));
        barShaderPressed = Glass.vertical(barPane, Glass.brighten(top),
                Glass.brighten(bottom));
        barRimShader = Glass.vertical(barPane, rimA, rimB);

        rim.setStrokeWidth(Glass.rimWidth(density));
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
            measurePage();
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
        restorePage();
    }

    /** Finds the open category again after the section list was rebuilt. */
    private void restorePage() {
        if (pageName != null) {
            int at = sectionNames.indexOf(pageName);
            if (at >= 0) {
                page = at;
                return;
            }
        }
        page = Math.min(Math.max(0, page), Math.max(0, sectionItems.size() - 1));
        pageName = sectionItems.isEmpty() ? null : sectionNames.get(page);
    }

    private void setPage(int index) {
        if (index < 0 || index >= sectionItems.size()) {
            return;
        }
        page = index;
        pageName = sectionNames.get(index);
        measurePage();
        flingVelocity = 0f;
        removeCallbacks(flinger);
        scroll = 0f;
        invalidate();
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
        measurePage();
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
        buildShaders();
        measurePage();
        setScroll(0f);
    }


    /** Only the open category is laid out; nothing else is on screen. */
    private void measurePage() {
        if (sectionItems.isEmpty() || cell <= 0f) {
            contentHeight = 0f;
            return;
        }
        restorePage();
        String[] items = sectionItems.get(page);
        int rows = (items.length + COLUMNS - 1) / COLUMNS;
        contentHeight = headerHeight + rows * cell;
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
        if (cell <= 0f || sectionItems.isEmpty()) {
            return;
        }
        int save = canvas.save();
        canvas.clipRect(0f, 0f, getWidth(), viewportHeight());
        canvas.translate(0f, -scroll);

        String[] items = sectionItems.get(page);
        canvas.drawText(sectionNames.get(page), 14f * density,
                headerHeight * 0.75f, headerPaint);

        float viewTop = scroll;
        float viewBottom = scroll + viewportHeight();
        float inset = cellInset();
        float pane = cell - inset * 2f;
        float radius = pane * 0.28f;
        for (int i = 0; i < items.length; i++) {
            float x = (i % COLUMNS) * cell;
            float y = headerHeight + (i / COLUMNS) * cell;
            if (y + cell < viewTop || y > viewBottom) {
                continue;
            }
            Glass.drawPane(canvas, fill, rim, shadow, density, x + inset, y + inset,
                    pane, pane, radius, cellShader, cellRimShader);
            float baseline = y + cell / 2f
                    - (emojiPaint.descent() + emojiPaint.ascent()) / 2f;
            canvas.drawText(items[i], x + cell / 2f, baseline, emojiPaint);
        }
        canvas.restoreToCount(save);

        drawBottomBar(canvas);
    }

    private void drawBottomBar(Canvas canvas) {
        float top = getHeight() - barHeight;
        float cy = top + barHeight / 2f;

        // ABC, bottom left, continuing the cycle the keyboard's own key started.
        tabPaint.setTextSize(14f * density);
        float bi = barInset();
        float barPane = barHeight - bi * 2f;
        float barRadius = barPane * 0.34f;
        Glass.drawPane(canvas, fill, rim, shadow, density, bi, top + bi,
                sideWidth - bi * 2f, barPane, barRadius,
                pressedAbc ? barShaderPressed : barShader, barRimShader);
        canvas.drawText("ABC", sideWidth / 2f,
                cy - (tabPaint.descent() + tabPaint.ascent()) / 2f, tabPaint);

        int tabs = sectionItems.size();
        if (tabs > 0) {
            float width = (getWidth() - 2f * sideWidth) / tabs;
            tabPaint.setTextSize(Math.min(cell, width) * 0.46f);
            int current = currentSection();
            for (int i = 0; i < tabs; i++) {
                float cx = sideWidth + width * (i + 0.5f);
                float tabW = Math.min(width - density * 2f, barHeight);
                Glass.drawPane(canvas, fill, rim, shadow, density,
                        cx - tabW / 2f, top + bi, tabW, barPane, barRadius,
                        (i == current || i == pressedTab)
                                ? barShaderPressed : barShader, barRimShader);
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
        Glass.drawPane(canvas, fill, rim, shadow, density,
                getWidth() - sideWidth + bi, top + bi, sideWidth - bi * 2f,
                barPane, barRadius,
                pressedBackspace ? barShaderPressed : barShader, barRimShader);
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
        return page;
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
                && wasTab < sectionItems.size()) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            setPage(wasTab);
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
        if (y >= viewportHeight() || sectionItems.isEmpty()) {
            return;
        }
        String[] items = sectionItems.get(page);
        float contentY = y + scroll;
        if (contentY < headerHeight) {
            return;
        }
        int row = (int) ((contentY - headerHeight) / cell);
        int column = (int) (x / cell);
        int index = row * COLUMNS + column;
        if (index < 0 || index >= items.length) {
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        String picked = items[index];
        noteRecent(picked);
        if (listener != null) {
            listener.onEmojiPicked(picked);
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(flinger);
        releaseTracker();
        super.onDetachedFromWindow();
    }
}
