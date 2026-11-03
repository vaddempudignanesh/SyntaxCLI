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

    private static volatile ImeColorStore INSTANCE;
    private static final Object LOCK = new Object();

    private volatile int textColor;
    private volatile int bgColor;
    private volatile int keyAlpha;
    private volatile boolean wallpaperEnabled;
    private volatile int wallpaperDim;
    private volatile String wallpaperImagePath;
    private volatile String wallpaperVideoPath;

    private final SharedPreferences prefs;

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
}