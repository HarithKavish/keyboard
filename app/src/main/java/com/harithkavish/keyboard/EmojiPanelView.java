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

    /** The three rows typed into while searching. No shift, no punctuation. */
    private static final String[] SEARCH_ROWS = {
        "qwertyuiop", "asdfghjkl", "zxcvbnm",
    };
    /**
     * How much of a cell the glyph fills. Generous: the pane is inset from the
     * cell and the glyph inset again from that, so a modest-looking factor ends
     * up as a small emoji on the screen.
     */
    private static final float EMOJI_SHARE = 0.72f;

    /**
     * The home keyboard's geometry, repeated here so the two agree.
     *
     * <p>These mirror `GlassKeyboardView.layoutKeys()`. Duplicated rather than
     * shared because that method lays out a `Keys.Key[][]` with weights, and
     * this is three fixed rows of plain letters -- but if those numbers move,
     * these have to move with them or the search keys land somewhere else.
     */
    private static final float KEY_GAP_DP = 6f;
    private static final float KEY_RADIUS_DP = 10f;
    /** A letter's width on a full ten-key row. */
    private static final float ROW_UNITS = 10f;

    /** More matches than this and the rest are not worth scrolling to. */
    private static final int MAX_RESULTS = 60;

    /** Half a blink. Android's own text caret uses the same beat. */
    private static final long CARET_BLINK_MS = 500L;

    private boolean searching;
    /** Whether the caret is currently drawn; flipped by {@link #caretBlinker}. */
    private boolean caretOn = true;
    private final StringBuilder query = new StringBuilder();
    private final List<String> results = new ArrayList<>();
    /** How far the result strip is scrolled sideways. */
    private float resultScroll;
    private int pressedResult = -1;
    private int pressedLetter = -1;
    private boolean pressedBack;
    private boolean draggingResults;

    private boolean night;
    private float opacityScale = 1f;
    /** TRUE or FALSE once the wallpaper has been read; null means follow the system. */
    private Boolean backdropLight;
    private Shader cellShader;
    private Shader cellRimShader;
    private Shader headerShader;
    private Shader headerRimShader;
    private Shader barShader;
    private Shader barShaderPressed;
    private Shader barRimShader;

    private float cell;
    /** Side margin, the same 14dp the keys use, so the two pages line up. */
    private float padH;
    private float headerHeight;
    private float barHeight;
    private float sideWidth;
    private float contentHeight;
    private float scroll;

    private VelocityTracker tracker;
    private float downX;
    private float downY;
    private float lastY;
    /** Sideways companion to lastY, for dragging the result strip. */
    private float lastX;
    private boolean scrolling;
    private int pressedTab = -1;
    private boolean pressedAbc;
    private boolean pressedBackspace;
    private float flingVelocity;

    /**
     * Flips the caret and asks for one more frame.
     *
     * <p>Only posted while searching, and stopped the moment the panel leaves
     * the window: a Runnable that keeps invalidating a detached view is a
     * battery leak nobody goes looking for.
     */
    private final Runnable caretBlinker = new Runnable() {
        @Override
        public void run() {
            if (!searching) {
                return;
            }
            caretOn = !caretOn;
            invalidate();
            postDelayed(this, CARET_BLINK_MS);
        }
    };

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

    private float headerInset() {
        // The same inset as a cell, so the heading row's panes and the grid's
        // line up instead of being a pixel or two out from each other.
        return cellInset();
    }

    /** The back button, or the heading, at the left of the top row. */
    private float headingButtonWidth() {
        return headerHeight - headerInset() * 2f;
    }

    /** The query box, at the right of the top row where the magnifier sits. */
    private float searchBoxWidth() {
        return (getWidth() - padH * 2f) * 0.30f;
    }

    private float resultsLeft() {
        return padH + headingButtonWidth() + headerInset() * 2f;
    }

    private float resultsRight() {
        return getWidth() - padH - searchBoxWidth() - headerInset() * 2f;
    }

    private float keyGap() {
        return KEY_GAP_DP * density;
    }

    private float keysWidth() {
        return getWidth() - padH * 2f;
    }

    /**
     * The width of one letter. Taken from a full ten-key row, so the nine- and
     * seven-letter rows sit inside it centred rather than stretching to match.
     */
    private float keyUnit() {
        return (keysWidth() - keyGap() * (ROW_UNITS - 1f)) / ROW_UNITS;
    }

    private float searchRowHeight() {
        return gridHeight() / SEARCH_ROWS.length;
    }

    /** Where a row starts, centred inside the full width like the keyboard's. */
    private float searchRowLeft(String row) {
        float rowWidth = keyGap() * (row.length() - 1) + keyUnit() * row.length();
        return padH + (keysWidth() - rowWidth) / 2f;
    }

    /** A result occupies exactly one cell, like the grid below it. */
    private float resultCell() {
        return cell;
    }

    private float resultsSpan() {
        return Math.max(0f, resultsRight() - resultsLeft());
    }

    /** The top of the scrolling grid: everything above it is pinned. */
    private float gridTop() {
        return headerHeight;
    }

    /** How much of the grid is on screen, between the heading and the bar. */
    private float gridHeight() {
        return viewportHeight() - gridTop();
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

        // The heading is a button-weight pane, not a cell-weight one: it is a
        // label rather than something to press, so it sits back with the bar.
        float headerPane = headerHeight - cellInset() * 2f;
        headerShader = Glass.vertical(headerPane, Glass.dim(top), Glass.dim(bottom));
        headerRimShader = Glass.vertical(headerPane, rimA, rimB);

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
        if (searching) {
            exitSearch();
        }
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

    private void enterSearch() {
        searching = true;
        startCaret();
        query.setLength(0);
        results.clear();
        resultScroll = 0f;
        flingVelocity = 0f;
        removeCallbacks(flinger);
        invalidate();
    }

    /** Back to the category that was open before the magnifier was tapped. */
    private void exitSearch() {
        searching = false;
        removeCallbacks(caretBlinker);
        query.setLength(0);
        results.clear();
        resultScroll = 0f;
        pressedResult = -1;
        pressedLetter = -1;
        measurePage();
        setScroll(scroll);
        invalidate();
    }

    /** Restarts the blink so the caret is solid while someone is typing. */
    private void startCaret() {
        removeCallbacks(caretBlinker);
        caretOn = true;
        postDelayed(caretBlinker, CARET_BLINK_MS);
    }

    private void editQuery(char letter, boolean delete) {
        if (delete) {
            if (query.length() == 0) {
                return;
            }
            query.setLength(query.length() - 1);
        } else {
            if (query.length() >= 24) {
                return;
            }
            query.append(letter);
        }
        results.clear();
        results.addAll(Emoji.search(query.toString(), MAX_RESULTS));
        resultScroll = 0f;
        // A caret that blinks out mid-keystroke reads as a dropped key.
        startCaret();
        invalidate();
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

    /**
     * Records an emoji picked somewhere other than this panel.
     *
     * <p>The suggestion strip commits emoji too, and those were not reaching
     * Recents -- the list only ever saw what was tapped in here, which made
     * Recents a record of one route to an emoji rather than of the emoji used.
     */
    void recordRecent(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return;
        }
        noteRecent(emoji);
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
        padH = 14f * density;
        cell = (w - 2f * padH) / COLUMNS;
        barHeight = 46f * density;
        sideWidth = (w - 2f * padH) * SIDE_SHARE;
        // One cell tall, so a search result is the same size and shape as the
        // same emoji in the grid below rather than a shrunken copy of it.
        headerHeight = cell;
        emojiPaint.setTextSize(cell * EMOJI_SHARE);
        headerPaint.setTextSize(15f * density);
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
        // The heading is pinned, so it is not part of what scrolls.
        contentHeight = rows * cell;
    }

    private float viewportHeight() {
        return getHeight() - barHeight;
    }

    private void setScroll(float value) {
        float max = Math.max(0f, contentHeight - gridHeight());
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
        drawHeading(canvas);

        if (searching) {
            drawSearchKeys(canvas);
            drawBottomBar(canvas);
            return;
        }

        int save = canvas.save();
        // Clipped below the heading, so rows scroll under it rather than over it.
        canvas.clipRect(0f, gridTop(), getWidth(), viewportHeight());
        canvas.translate(0f, gridTop() - scroll);

        String[] items = sectionItems.get(page);
        float viewTop = scroll;
        float viewBottom = scroll + gridHeight();
        float inset = cellInset();
        float pane = cell - inset * 2f;
        float radius = pane * 0.28f;
        for (int i = 0; i < items.length; i++) {
            float x = padH + (i % COLUMNS) * cell;
            float y = (i / COLUMNS) * cell;
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

    /**
     * The category name, pinned above the grid. On the same glass as the
     * buttons, because bare text over a wallpaper was the one label here with
     * nothing behind it to be read against.
     */
    private void drawHeading(Canvas canvas) {
        float hi = headerInset();
        float pane = headerHeight - hi * 2f;
        float radius = pane * 0.34f;
        float cy = headerHeight / 2f;
        float baseline = cy - (headerPaint.descent() + headerPaint.ascent()) / 2f;

        if (!searching) {
            // Heading across the row, with the magnifier boxed at the far end.
            float boxW = headingButtonWidth();
            float left = padH;
            float nameW = getWidth() - padH * 2f - boxW - hi * 2f;
            Glass.drawPane(canvas, fill, rim, shadow, density, left, hi,
                    nameW, pane, radius, headerShader, headerRimShader);
            canvas.drawText(sectionNames.get(page), left + 14f * density, baseline,
                    headerPaint);
            float boxLeft = getWidth() - padH - boxW;
            Glass.drawPane(canvas, fill, rim, shadow, density, boxLeft, hi,
                    boxW, pane, radius,
                    pressedBack ? barShaderPressed : headerShader, headerRimShader);
            drawMagnifier(canvas, boxLeft + boxW / 2f, cy, pane * 0.32f);
            return;
        }

        float backW = headingButtonWidth();
        Glass.drawPane(canvas, fill, rim, shadow, density, padH, hi, backW, pane,
                radius, pressedBack ? barShaderPressed : headerShader,
                headerRimShader);
        drawBackArrow(canvas, padH + backW / 2f, cy, pane * 0.30f);

        drawResults(canvas, hi, pane, radius);

        float boxW = searchBoxWidth();
        float boxLeft = getWidth() - padH - boxW;
        Glass.drawPane(canvas, fill, rim, shadow, density, boxLeft, hi, boxW, pane,
                radius, headerShader, headerRimShader);
        float textLeft = boxLeft + 10f * density;
        boolean empty = query.length() == 0;
        String shown = empty
                ? getResources().getString(R.string.emoji_search) : query.toString();
        headerPaint.setAlpha(empty ? 0x80 : 0xFF);
        canvas.drawText(shown, textLeft, baseline, headerPaint);
        headerPaint.setAlpha(0xFF);
        if (caretOn) {
            float caretX = textLeft
                    + (empty ? 0f : headerPaint.measureText(query.toString()))
                    + density * 2f;
            float half = pane * 0.28f;
            canvas.drawRect(caretX, cy - half, caretX + density * 1.5f, cy + half,
                    headerPaint);
        }
    }

    /**
     * The matches, between the back button and the query. Clipped rather than
     * wrapped: there is one row for them, and more than fits scrolls sideways.
     */
    private void drawResults(Canvas canvas, float hi, float pane, float radius) {
        float span = resultsSpan();
        if (span <= 0f || results.isEmpty()) {
            return;
        }
        int save = canvas.save();
        canvas.clipRect(resultsLeft(), hi, resultsRight(), hi + pane);
        float slot = resultCell();
        emojiPaint.setTextSize(slot * EMOJI_SHARE);
        for (int i = 0; i < results.size(); i++) {
            float x = resultsLeft() + i * slot - resultScroll;
            if (x + slot < resultsLeft() || x > resultsRight()) {
                continue;
            }
            // Drawn exactly as a grid cell is: same inset, same pane, same
            // corner. A result is the same emoji, so it should not look
            // like a different kind of thing.
            Glass.drawPane(canvas, fill, rim, shadow, density, x + hi, hi,
                    slot - hi * 2f, pane, (slot - hi * 2f) * 0.28f,
                    pressedResult == i ? barShaderPressed : cellShader,
                    cellRimShader);
            float baseline = hi + pane / 2f
                    - (emojiPaint.descent() + emojiPaint.ascent()) / 2f;
            canvas.drawText(results.get(i), x + slot / 2f, baseline, emojiPaint);
        }
        canvas.restoreToCount(save);
    }

    private void drawMagnifier(Canvas canvas, float cx, float cy, float r) {
        icon.setStrokeWidth(Math.max(density, r * 0.22f));
        canvas.drawCircle(cx - r * 0.18f, cy - r * 0.18f, r * 0.72f, icon);
        iconPath.reset();
        iconPath.moveTo(cx + r * 0.36f, cy + r * 0.36f);
        iconPath.lineTo(cx + r * 0.95f, cy + r * 0.95f);
        canvas.drawPath(iconPath, icon);
    }

    private void drawBackArrow(Canvas canvas, float cx, float cy, float r) {
        icon.setStrokeWidth(Math.max(density, r * 0.24f));
        iconPath.reset();
        iconPath.moveTo(cx + r * 0.5f, cy - r);
        iconPath.lineTo(cx - r * 0.5f, cy);
        iconPath.lineTo(cx + r * 0.5f, cy + r);
        canvas.drawPath(iconPath, icon);
    }

    /**
     * Three rows of letters where the emoji grid was. The keyboard is not on
     * screen while the picker is, so without these there is nothing to type a
     * search with.
     */
    private void drawSearchKeys(Canvas canvas) {
        float top = gridTop();
        float rowHeight = searchRowHeight();
        float unit = keyUnit();
        float gap = keyGap();
        float paneHeight = rowHeight - gap;
        float radius = KEY_RADIUS_DP * density;
        tabPaint.setTextSize(Math.min(paneHeight * 0.40f, 14f * density));

        int top0 = Glass.top(night, opacityScale);
        int bottom0 = Glass.bottom(night, opacityScale);
        Shader body = Glass.vertical(paneHeight, top0, bottom0);
        Shader bodyDown = Glass.vertical(paneHeight, Glass.brighten(top0),
                Glass.brighten(bottom0));
        Shader edge = Glass.vertical(paneHeight, Glass.rimTop(night, opacityScale),
                Glass.rimBottom(night, opacityScale));

        int index = 0;
        for (int r = 0; r < SEARCH_ROWS.length; r++) {
            String row = SEARCH_ROWS[r];
            float x = searchRowLeft(row);
            float y = top + r * rowHeight;
            for (int c = 0; c < row.length(); c++, index++) {
                Glass.drawPane(canvas, fill, rim, shadow, density, x, y,
                        unit, paneHeight, radius,
                        pressedLetter == index ? bodyDown : body, edge);
                float baseline = y + paneHeight / 2f
                        - (tabPaint.descent() + tabPaint.ascent()) / 2f;
                canvas.drawText(String.valueOf(row.charAt(c)), x + unit / 2f,
                        baseline, tabPaint);
                x += unit + gap;
            }
        }
    }

    private void drawBottomBar(Canvas canvas) {
        float top = getHeight() - barHeight;
        float cy = top + barHeight / 2f;

        // ABC, bottom left, continuing the cycle the keyboard's own key started.
        tabPaint.setTextSize(14f * density);
        float bi = barInset();
        float barPane = barHeight - bi * 2f;
        float barRadius = barPane * 0.30f;
        Glass.drawPane(canvas, fill, rim, shadow, density, padH + bi, top + bi,
                sideWidth - bi * 2f, barPane, barRadius,
                pressedAbc ? barShaderPressed : barShader, barRimShader);
        canvas.drawText("ABC", padH + sideWidth / 2f,
                cy - (tabPaint.descent() + tabPaint.ascent()) / 2f, tabPaint);

        // No category tabs while searching: they answer a question nobody is
        // asking mid-search, and the back button is the way out.
        int tabs = searching ? 0 : sectionItems.size();
        if (tabs > 0) {
            float width = (getWidth() - 2f * padH - 2f * sideWidth) / tabs;
            tabPaint.setTextSize(Math.min(cell, width) * 0.46f);
            int current = currentSection();
            for (int i = 0; i < tabs; i++) {
                float cx = padH + sideWidth + width * (i + 0.5f);
                // Square, with an emoji cell's corner: these hold emoji, so they
                // should read as the same kind of thing.
                float side = Math.min(width - density * 2f, barPane);
                Glass.drawPane(canvas, fill, rim, shadow, density,
                        cx - side / 2f, cy - side / 2f, side, side, side * 0.28f,
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
        float bx = getWidth() - padH - sideWidth / 2f;
        Glass.drawPane(canvas, fill, rim, shadow, density,
                getWidth() - padH - sideWidth + bi, top + bi, sideWidth - bi * 2f,
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
                if (y < headerHeight) {
                    pressHeading(x);
                    return true;
                }
                if (searching && y < viewportHeight()) {
                    pressedLetter = letterAt(x, y);
                    invalidate();
                    return true;
                }
                if (y >= getHeight() - barHeight) {
                    if (x < padH + sideWidth) {
                        pressedAbc = true;
                    } else if (x > getWidth() - padH - sideWidth) {
                        pressedBackspace = true;
                    } else if (!searching) {
                        pressedTab = tabAt(x);
                    }
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (tracker != null) {
                    tracker.addMovement(event);
                }
                if (draggingResults) {
                    float span = resultsSpan();
                    float max = Math.max(0f, results.size() * resultCell() - span);
                    resultScroll = Math.max(0f, Math.min(max, resultScroll - (x - lastX)));
                    lastX = x;
                    if (Math.abs(x - downX) > touchSlop) {
                        pressedResult = -1;
                    }
                    invalidate();
                    return true;
                }
                if (pressedTab >= 0 || pressedAbc || pressedBackspace
                        || pressedBack || pressedLetter >= 0) {
                    return true;
                }
                if (searching) {
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
                if (handleHeadingRelease(x, y)) {
                    releaseTracker();
                    return true;
                }
                if (pressedLetter >= 0) {
                    int letter = pressedLetter;
                    pressedLetter = -1;
                    invalidate();
                    if (letterAt(x, y) == letter) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        editQuery(letterFor(letter), false);
                    }
                    releaseTracker();
                    return true;
                }
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
                pressedBack = false;
                pressedLetter = -1;
                pressedResult = -1;
                draggingResults = false;
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
        if (wasAbc && inBar && x < padH + sideWidth) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) {
                listener.onBackToKeyboard();
            }
        } else if (wasBackspace && inBar && x > getWidth() - padH - sideWidth) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (searching) {
                // While searching this is the query's backspace, not the field's.
                editQuery('\0', true);
            } else if (listener != null) {
                listener.onBackspace();
            }
        } else if (wasTab >= 0 && inBar && tabAt(x) == wasTab
                && wasTab < sectionItems.size()) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (searching) {
                exitSearch();
            }
            setPage(wasTab);
        }
        return true;
    }

    private void pressHeading(float x) {
        pressedBack = false;
        pressedResult = -1;
        draggingResults = false;
        if (!searching) {
            pressedBack = x >= getWidth() - padH - headingButtonWidth();
            invalidate();
            return;
        }
        if (x <= padH + headingButtonWidth()) {
            pressedBack = true;
        } else if (x >= resultsLeft() && x <= resultsRight()) {
            draggingResults = true;
            pressedResult = resultAt(x);
            lastX = x;
        }
        invalidate();
    }

    private boolean handleHeadingRelease(float x, float y) {
        boolean wasBack = pressedBack;
        int wasResult = pressedResult;
        boolean wasDragging = draggingResults;
        if (!wasBack && wasResult < 0 && !wasDragging) {
            return false;
        }
        pressedBack = false;
        pressedResult = -1;
        draggingResults = false;
        invalidate();
        boolean inRow = y < headerHeight;
        if (wasBack && inRow) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (searching) {
                exitSearch();
            } else {
                enterSearch();
            }
        } else if (wasResult >= 0 && inRow && resultAt(x) == wasResult
                && wasResult < results.size()) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            String picked = results.get(wasResult);
            noteRecent(picked);
            if (listener != null) {
                listener.onEmojiPicked(picked);
            }
            invalidate();
        }
        return true;
    }

    private int resultAt(float x) {
        if (results.isEmpty() || x < resultsLeft() || x > resultsRight()) {
            return -1;
        }
        int index = (int) ((x - resultsLeft() + resultScroll) / resultCell());
        return index >= 0 && index < results.size() ? index : -1;
    }

    private int letterAt(float x, float y) {
        if (!searching || y < gridTop() || y >= viewportHeight()) {
            return -1;
        }
        float rowHeight = searchRowHeight();
        int r = (int) ((y - gridTop()) / rowHeight);
        if (r < 0 || r >= SEARCH_ROWS.length) {
            return -1;
        }
        String row = SEARCH_ROWS[r];
        float unit = keyUnit();
        float pitch = unit + keyGap();
        // Snapped to the nearest key rather than the pane, so the gap between
        // two keys is not dead space -- the same as keyAt() on the keyboard.
        int c = (int) ((x - searchRowLeft(row)) / pitch);
        if (c < 0 || c >= row.length()) {
            return -1;
        }
        int index = 0;
        for (int i = 0; i < r; i++) {
            index += SEARCH_ROWS[i].length();
        }
        return index + c;
    }

    private char letterFor(int index) {
        int at = index;
        for (String row : SEARCH_ROWS) {
            if (at < row.length()) {
                return row.charAt(at);
            }
            at -= row.length();
        }
        return 'a';
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
        float width = (getWidth() - 2f * padH - 2f * sideWidth) / tabs;
        int index = (int) ((x - padH - sideWidth) / width);
        return Math.max(0, Math.min(tabs - 1, index));
    }

    private void handleTap(float x, float y) {
        if (y < gridTop() || y >= viewportHeight() || sectionItems.isEmpty()) {
            return;
        }
        String[] items = sectionItems.get(page);
        float contentY = y - gridTop() + scroll;
        int row = (int) (contentY / cell);
        int column = (int) ((x - padH) / cell);
        if (x < padH || column < 0 || column >= COLUMNS) {
            return;
        }
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
        removeCallbacks(caretBlinker);
        releaseTracker();
        super.onDetachedFromWindow();
    }
}
