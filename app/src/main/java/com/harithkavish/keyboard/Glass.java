package com.harithkavish.keyboard;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * What a pane of this keyboard's glass is made of, and how one is drawn.
 *
 * <p>The keys, the suggestion strip and the emoji panel all draw the same glass.
 * That has to mean the same code: the colours were tuned together, and two
 * copies would drift apart the first time either was touched. So the decisions
 * live here once, and each view keeps only its own shaders -- which it must,
 * since a vertical gradient is built for one specific height and a key, a
 * suggestion and an emoji cell are all different heights.
 */
final class Glass {

    /** Stacked rather than blurred: three cheap draws read as one soft shadow. */
    static final int[] SHADOW_OFFSET_DP = {3, 2, 1};
    static final int[] SHADOW_ALPHA = {0x16, 0x12, 0x0E};

    private Glass() {
    }

    /**
     * The top of a pane's gradient.
     *
     * <p>Over a dark backdrop the pane tints dark rather than pale. At the
     * opacity needed to hide what is behind it, a pale pane leaves pale glyphs
     * sitting on near-white and unreadable, so which way it tints matters more
     * than how solid it is. The tint still carries the colour behind it: a red
     * wallpaper gives a dark red key.
     */
    static int top(boolean night, float opacityScale) {
        return Appearance.scaleAlpha(night ? 0x73000000 : 0xD9FFFFFF, opacityScale);
    }

    /**
     * The bottom of a pane's gradient. A shallow range on purpose: a steep
     * top-to-bottom gradient is read as a moulded surface catching a light, a
     * flatter one as frosted.
     *
     * <p>Alpha rises downwards on the dark panes and falls on the pale ones,
     * which is the same "lit from above" reading either way round.
     */
    static int bottom(boolean night, float opacityScale) {
        return Appearance.scaleAlpha(night ? 0x8C000000 : 0xC4FFFFFF, opacityScale);
    }

    /**
     * The rim: bright along the top edge, faint but still present by the bottom.
     * Letting it vanish entirely loses the pane's shape against a busy wallpaper.
     */
    static int rimTop(boolean night, float opacityScale) {
        return Appearance.scaleAlpha(night ? 0x99FFFFFF : 0xB3FFFFFF, opacityScale);
    }

    static int rimBottom(boolean night, float opacityScale) {
        return Appearance.scaleAlpha(night ? 0x26FFFFFF : 0x2BFFFFFF, opacityScale);
    }

    /** A pressed pane. More of whichever way it already tints, so it reads as down. */
    static int brighten(int argb) {
        int alpha = Math.min(255, (int) ((argb >>> 24) * 1.9f));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** A pane that sits back a step, so the primary ones read as primary. */
    static int dim(int argb) {
        int alpha = (int) ((argb >>> 24) * 0.6f);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    static Shader vertical(float height, int top, int bottom) {
        return new LinearGradient(0f, 0f, 0f, height, top, bottom, Shader.TileMode.CLAMP);
    }

    /** A hairline. Anything thicker outlines the pane instead of lighting it. */
    static float rimWidth(float density) {
        return Math.max(1f, density * 0.75f);
    }

    /**
     * Shadow, body and rim, at a rectangle.
     *
     * <p>The pane is drawn at the origin and moved into place, because the
     * shaders are built for a gradient running from y=0 to a given height.
     */
    static void drawPane(Canvas canvas, Paint fill, Paint rim, Paint shadow,
                         float density, float x, float y, float w, float h,
                         float radius, Shader body, Shader rimShade) {
        if (w <= 0f || h <= 0f) {
            return;
        }
        int save = canvas.save();
        canvas.translate(x, y);
        for (int i = 0; i < SHADOW_OFFSET_DP.length; i++) {
            float off = SHADOW_OFFSET_DP[i] * density;
            shadow.setColor(SHADOW_ALPHA[i] << 24);
            canvas.drawRoundRect(off * 0.4f, off, w - off * 0.4f, h + off,
                    radius, radius, shadow);
        }
        fill.setShader(body);
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, fill);
        rim.setShader(rimShade);
        float half = rim.getStrokeWidth() / 2f;
        canvas.drawRoundRect(half, half, w - half, h - half, radius, radius, rim);
        canvas.restoreToCount(save);
    }
}
