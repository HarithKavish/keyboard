package com.harithkavish.keyboard;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;

/**
 * How solid the keys should be, and whether what is behind them is light or
 * dark.
 *
 * <p><b>The keyboard cannot see what is underneath it.</b> Android gives an
 * input method no way to read the pixels of the window below -- that is a
 * security boundary rather than an oversight, and the ways around it
 * (MediaProjection, an accessibility service) demand consent a keyboard has no
 * business asking for. So there is no sampling of the app's colours here, and
 * there cannot be.
 *
 * <p>What is available is the wallpaper, through
 * {@link WallpaperManager#getWallpaperColors}. That is genuinely what sits
 * behind the keys on the launcher and in any app that does not draw there, so
 * adapting to it is real rather than decorative -- it is simply not the whole
 * picture. Everything else is the opacity the person sets by hand.
 *
 * <p>The wallpaper read goes over IPC and can block, so it never happens on the
 * main thread, and the answer is cached for the life of the process.
 */
final class Appearance {

    private static final String PREFS = "glass_keyboard_learning";
    private static final String KEY_OPACITY = "key_opacity";
    private static final String KEY_ADAPT = "opacity_adapt";

    /** Middle of the slider leaves the keys exactly as they were designed. */
    static final int DEFAULT_OPACITY = 50;

    /** Below this the keys stop reading as glass; above it they stop being see-through. */
    private static final float MIN_SCALE = 0.6f;
    private static final float MAX_SCALE = 2.2f;
    /** What a light wallpaper adds on top of the slider. */
    private static final float LIGHT_BOOST = 1.35f;

    /**
     * Relative luminance above which a backdrop counts as light. Deliberately
     * below the midpoint: a pale key disappears into a pale wallpaper long
     * before that wallpaper is anywhere near white.
     */
    private static final double LIGHT_THRESHOLD = 0.45;

    /** Null until the wallpaper has been read; then TRUE for a light one. */
    private static volatile Boolean cachedLight;

    interface Listener {
        void onBackdropResolved(boolean light);
    }

    private Appearance() {
    }

    // ---------------------------------------------------------------- settings

    static int opacity(Context context) {
        return prefs(context).getInt(KEY_OPACITY, DEFAULT_OPACITY);
    }

    static void setOpacity(Context context, int value) {
        prefs(context).edit().putInt(KEY_OPACITY, clamp(value)).apply();
    }

    static boolean isAdaptive(Context context) {
        return prefs(context).getBoolean(KEY_ADAPT, true);
    }

    static void setAdaptive(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_ADAPT, value).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    // ------------------------------------------------------------------- maths

    /**
     * The multiplier applied to every key's alpha.
     *
     * <p>The middle of the slider is 1.0 exactly, so the default is the design
     * as drawn rather than an arbitrary point on a curve. A light backdrop adds
     * a further boost, because that is the case where the keys actually vanish.
     */
    static float scale(int opacity, boolean lightBackdrop) {
        int value = clamp(opacity);
        float scale;
        if (value <= DEFAULT_OPACITY) {
            scale = MIN_SCALE + (1f - MIN_SCALE) * (value / (float) DEFAULT_OPACITY);
        } else {
            scale = 1f + (MAX_SCALE - 1f)
                    * ((value - DEFAULT_OPACITY) / (float) (100 - DEFAULT_OPACITY));
        }
        return lightBackdrop ? Math.min(MAX_SCALE, scale * LIGHT_BOOST) : scale;
    }

    /** Applies a scale to the alpha of an ARGB colour, keeping its channels. */
    static int scaleAlpha(int argb, float scale) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * scale);
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Whether a colour is light enough that pale keys would vanish into it.
     *
     * <p>Relative luminance, not a plain average of the channels: the eye is far
     * more sensitive to green than to blue, and an average calls a saturated
     * blue light when it is nothing of the kind.
     */
    static boolean isLight(int color) {
        // Unpacked by hand rather than through android.graphics.Color, which is
        // an unmocked stub under unit tests. It is three shifts, and doing them
        // here is what lets the luminance rule be tested at all.
        double r = channel((color >> 16) & 0xFF);
        double g = channel((color >> 8) & 0xFF);
        double b = channel(color & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b > LIGHT_THRESHOLD;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    // --------------------------------------------------------------- wallpaper

    /** The last answer, or null if the wallpaper has not been read yet. */
    static Boolean knownBackdrop() {
        return cachedLight;
    }

    /** Forgets the cached answer, so a changed wallpaper is picked up. */
    static void forgetBackdrop() {
        cachedLight = null;
    }

    /**
     * Reads the wallpaper's colours off the main thread and reports back on it.
     * Does nothing before API 27, where the colours are not exposed at all.
     */
    static void probe(final Context context, final Handler main, final Listener listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return;
        }
        Boolean known = cachedLight;
        if (known != null) {
            listener.onBackdropResolved(known);
            return;
        }
        final Context application = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Boolean light = readWallpaper(application);
                if (light == null) {
                    return;
                }
                cachedLight = light;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onBackdropResolved(light);
                    }
                });
            }
        }, "glass-backdrop").start();
    }

    private static Boolean readWallpaper(Context context) {
        // Repeated from probe(), and not redundant: lint reads each method on its
        // own, and a guard one call up is a guard it cannot see. It is also the
        // check that matters if this is ever called from anywhere else.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return null;
        }
        try {
            WallpaperManager manager = WallpaperManager.getInstance(context);
            if (manager == null) {
                return null;
            }
            android.app.WallpaperColors colors =
                    manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors == null) {
                return null;
            }
            return isLight(colors.getPrimaryColor().toArgb());
        } catch (RuntimeException e) {
            // A live wallpaper that reports nothing, a device that restricts the
            // read, an OEM that throws: none of it is worth a dead keyboard.
            return null;
        }
    }
}
