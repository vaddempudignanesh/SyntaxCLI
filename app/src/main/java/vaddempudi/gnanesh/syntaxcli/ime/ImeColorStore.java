package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * RAM cache of the active keyboard theme colors.
 *
 * READ operations are pure in-memory. WRITE operations happen ONLY when
 * the user explicitly saves from the gesture editor (single I/O per save).
 */
public final class ImeColorStore {

    private static final String PREFS = "ime_color_store";
    private static final String K_TEXT = "key_text_color";
    private static final String K_BG   = "key_bg_color";
    private static final String K_WP   = "key_wallpaper_enabled";
    private static final String K_WP_DIM = "key_wallpaper_dim"; // 0..100

    private static volatile ImeColorStore INSTANCE;
    private static final Object LOCK = new Object();

    private volatile int textColor;
    private volatile int bgColor;
    private volatile boolean wallpaperEnabled;
    private volatile int wallpaperDim;

    private final SharedPreferences prefs;

    private ImeColorStore(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        textColor = prefs.getInt(K_TEXT, KeyboardConfig.COLOR_TEXT);
        bgColor   = prefs.getInt(K_BG,   KeyboardConfig.COLOR_BACKGROUND);
        wallpaperEnabled = prefs.getBoolean(K_WP, false);
        wallpaperDim     = prefs.getInt(K_WP_DIM, 70);
    }

    public static ImeColorStore get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) INSTANCE = new ImeColorStore(ctx);
            }
        }
        return INSTANCE;
    }

    /** Pure RAM read — no I/O. */
    public int getTextColor() { return textColor; }

    /** Pure RAM read — no I/O. */
    public int getBgColor()   { return bgColor; }

    /** Pure RAM read — no I/O. */
    public boolean isWallpaperEnabled() { return wallpaperEnabled; }

    /** Pure RAM read — no I/O. 0..100. */
    public int getWallpaperDim() { return wallpaperDim; }

    /**
     * Commit the current pending colors to RAM and to disk.
     * Call this only from the gesture editor when the user taps RESTART.
     */
    public void save(int newTextColor, int newBgColor) {
        textColor = newTextColor;
        bgColor   = newBgColor;
        prefs.edit()
                .putInt(K_TEXT, newTextColor)
                .putInt(K_BG,   newBgColor)
                .apply();
    }

    /** Commit wallpaper settings. */
    public void saveWallpaper(boolean enabled, int dim) {
        wallpaperEnabled = enabled;
        wallpaperDim     = Math.max(0, Math.min(100, dim));
        prefs.edit()
                .putBoolean(K_WP, wallpaperEnabled)
                .putInt(K_WP_DIM, wallpaperDim)
                .apply();
    }
}