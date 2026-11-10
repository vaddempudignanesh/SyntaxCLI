package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.content.SharedPreferences;

public final class ImeColorStore {

    private static final String PREFS   = "ime_color_store";
    private static final String K_TEXT  = "key_text_color";
    private static final String K_BG    = "key_bg_color";
    private static final String K_KEY_A = "key_alpha";
    private static final String K_WP    = "key_wallpaper_enabled";
    private static final String K_WP_DIM= "key_wallpaper_dim";
    private static final String K_WP_IMG= "key_wallpaper_image";
    private static final String K_WP_VID= "key_wallpaper_video";

    private static final String K_ROW_H       = "layout_row_height_dp";
    private static final String K_ROW_GAP     = "layout_row_gap_dp";
    private static final String K_KEY_GAP     = "layout_key_gap_dp";
    private static final String K_KEY_RADIUS  = "layout_key_radius_dp";
    private static final String K_TAB_H       = "layout_tab_row_height_dp";

    private static volatile ImeColorStore INSTANCE;
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;

    private volatile int textColor;
    private volatile int bgColor;
    private volatile int keyAlpha;
    private volatile boolean wallpaperEnabled;
    private volatile int wallpaperDim;
    private volatile String wallpaperImagePath;
    private volatile String wallpaperVideoPath;

    private volatile boolean keyShrinkEnabled;
    private volatile float keyShrinkIntensity;
    private volatile long  keyShrinkDownMs;

    private volatile float rowHeightDp;
    private volatile float rowGapDp;
    private volatile float keyGapDp;
    private volatile float keyCornerRadiusDp;
    private volatile float tabRowHeightDp;

    private ImeColorStore(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        textColor          = prefs.getInt(K_TEXT, KeyboardConfig.COLOR_TEXT);
        bgColor            = prefs.getInt(K_BG,   KeyboardConfig.COLOR_BACKGROUND);
        keyAlpha           = prefs.getInt(K_KEY_A, 255);
        wallpaperEnabled   = prefs.getBoolean(K_WP, false);
        wallpaperDim       = prefs.getInt(K_WP_DIM, KeyboardConfig.DEFAULT_WALLPAPER_DIM);
        wallpaperImagePath = prefs.getString(K_WP_IMG, null);
        wallpaperVideoPath = prefs.getString(K_WP_VID, null);

        keyShrinkEnabled   = prefs.getBoolean("key_shrink_enabled",
                KeyboardConfig.DEFAULT_KEY_SHRINK_ENABLED);
        keyShrinkIntensity = prefs.getFloat("key_shrink_intensity",
                KeyboardConfig.DEFAULT_KEY_SHRINK_INTENSITY);
        keyShrinkDownMs    = prefs.getLong("key_shrink_down_ms",
                KeyboardConfig.DEFAULT_KEY_SHRINK_MS);

        rowHeightDp        = prefs.getFloat(K_ROW_H,      KeyboardConfig.DEFAULT_ROW_HEIGHT_DP);
        rowGapDp           = prefs.getFloat(K_ROW_GAP,    KeyboardConfig.DEFAULT_ROW_GAP_DP);
        keyGapDp           = prefs.getFloat(K_KEY_GAP,    KeyboardConfig.DEFAULT_KEY_HORIZONTAL_GAP_DP);
        keyCornerRadiusDp  = prefs.getFloat(K_KEY_RADIUS, KeyboardConfig.DEFAULT_KEY_CORNER_RADIUS_DP);
        tabRowHeightDp     = prefs.getFloat(K_TAB_H,      KeyboardConfig.DEFAULT_TAB_ROW_HEIGHT_DP);

        // Overlay any newer live snapshot left by the editor process.
        LiveLayoutBridge.LayoutSnapshot snap =
                LiveLayoutBridge.read(ctx.getApplicationContext());
        if (snap != null) applySnapshotInternal(snap);
    }

    public static ImeColorStore get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) INSTANCE = new ImeColorStore(ctx);
            }
        }
        return INSTANCE;
    }

    public int getTextColor() { return textColor; }
    public int getBgColor()   { return bgColor; }
    public int getKeyAlpha()  { return keyAlpha; }
    public boolean isWallpaperEnabled() { return wallpaperEnabled; }
    public int getWallpaperDim() { return wallpaperDim; }
    public String getWallpaperImagePath() { return wallpaperImagePath; }
    public String getWallpaperVideoPath() { return wallpaperVideoPath; }

    public boolean hasVideoWallpaper() {
        return wallpaperVideoPath != null && !wallpaperVideoPath.isEmpty();
    }

    public boolean hasImageWallpaper() {
        return wallpaperImagePath != null && !wallpaperImagePath.isEmpty();
    }

    public void save(int newTextColor, int newBgColor) {
        textColor = newTextColor;
        bgColor   = newBgColor;
        prefs.edit()
                .putInt(K_TEXT, newTextColor)
                .putInt(K_BG,   newBgColor)
                .apply();
    }

    public boolean isKeyShrinkEnabled() { return keyShrinkEnabled; }

    public void saveKeyShrinkEnabled(boolean enabled) {
        keyShrinkEnabled = enabled;
        prefs.edit().putBoolean("key_shrink_enabled", enabled).apply();
    }

    public float getKeyShrinkIntensity() { return keyShrinkIntensity; }
    public void saveKeyShrinkIntensity(float v) {
        keyShrinkIntensity = Math.max(0.5f, Math.min(1.0f, v));
        prefs.edit().putFloat("key_shrink_intensity", keyShrinkIntensity).apply();
    }

    public long getKeyShrinkDownMs() { return keyShrinkDownMs; }
    public void saveKeyShrinkDownMs(long ms) {
        keyShrinkDownMs = Math.max(20L, Math.min(200L, ms));
        prefs.edit().putLong("key_shrink_down_ms", keyShrinkDownMs).apply();
    }

    public void saveKeyAlpha(int alpha) {
        keyAlpha = Math.max(0, Math.min(255, alpha));
        prefs.edit().putInt(K_KEY_A, keyAlpha).apply();
    }

    public void saveWallpaper(boolean enabled, int dim, String imagePath, String videoPath) {
        wallpaperEnabled   = enabled;
        wallpaperDim       = Math.max(0, Math.min(100, dim));
        wallpaperImagePath = imagePath;
        wallpaperVideoPath = videoPath;
        prefs.edit()
                .putBoolean(K_WP, wallpaperEnabled)
                .putInt(K_WP_DIM, wallpaperDim)
                .putString(K_WP_IMG, imagePath)
                .putString(K_WP_VID, videoPath)
                .apply();
    }

    public float getRowHeightDp()       { return rowHeightDp; }
    public float getRowGapDp()          { return rowGapDp; }
    public float getKeyGapDp()          { return keyGapDp; }
    public float getKeyCornerRadiusDp() { return keyCornerRadiusDp; }
    public float getTabRowHeightDp()    { return tabRowHeightDp; }

    public void saveRowHeightDp(float dp) {
        rowHeightDp = clamp(dp, 30f, 100f);
        prefs.edit().putFloat(K_ROW_H, rowHeightDp).apply();
    }

    public void saveRowGapDp(float dp) {
        rowGapDp = clamp(dp, 0f, 30f);
        prefs.edit().putFloat(K_ROW_GAP, rowGapDp).apply();
    }

    public void saveKeyGapDp(float dp) {
        keyGapDp = clamp(dp, 0f, 20f);
        prefs.edit().putFloat(K_KEY_GAP, keyGapDp).apply();
    }

    public void saveKeyCornerRadiusDp(float dp) {
        keyCornerRadiusDp = clamp(dp, 0f, 20f);
        prefs.edit().putFloat(K_KEY_RADIUS, keyCornerRadiusDp).apply();
    }

    public void saveTabRowHeightDp(float dp) {
        tabRowHeightDp = clamp(dp, 20f, 80f);
        prefs.edit().putFloat(K_TAB_H, tabRowHeightDp).apply();
    }
// In ImeColorStore.java — replace the existing applySnapshot + applySnapshotInternal

    public void applySnapshot(LiveLayoutBridge.LayoutSnapshot s) {
        applySnapshotInternal(s);
    }

    private void applySnapshotInternal(LiveLayoutBridge.LayoutSnapshot s) {
        if (s == null) return;
        rowHeightDp       = clamp(s.rowHeightDp,    30f, 100f);
        rowGapDp          = clamp(s.rowGapDp,        0f,  30f);
        keyGapDp          = clamp(s.keyGapDp,        0f,  20f);
        keyCornerRadiusDp = clamp(s.keyRadiusDp,     0f,  20f);
        tabRowHeightDp    = clamp(s.tabRowHeightDp, 20f,  80f);

        textColor           = s.textColor;
        bgColor             = s.bgColor;
        keyAlpha            = Math.max(0, Math.min(255, s.keyAlpha));
        keyShrinkEnabled    = s.keyShrinkEnabled;
        keyShrinkIntensity  = Math.max(0.5f, Math.min(1.0f, s.keyShrinkIntensity));
        keyShrinkDownMs     = Math.max(20L, Math.min(200L, s.keyShrinkDownMs));

        wallpaperEnabled    = s.wallpaperEnabled;
        wallpaperDim        = Math.max(0, Math.min(100, s.wallpaperDim));
        wallpaperImagePath  = s.wallpaperImagePath;
        wallpaperVideoPath  = s.wallpaperVideoPath;
    }

    

    private static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}