package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * Cross-process live-config channel.
 *
 * The editor process writes a full JSON snapshot. The IME process reads it
 * on demand (no SharedPreferences, no broadcasts, no restart).
 *
 * The public entry point the editor calls is
 * {@link BasicImeService#pushLiveLayout(LayoutSnapshot)}.
 */
public final class LiveLayoutBridge {

    public static final class LayoutSnapshot {
        // Layout (existing)
        public final float rowHeightDp;
        public final float rowGapDp;
        public final float keyGapDp;
        public final float keyRadiusDp;
        public final float tabRowHeightDp;

        // Colors
        public final int  textColor;
        public final int  bgColor;
        public final int  keyAlpha;

        // Shrink animation
        public final boolean keyShrinkEnabled;
        public final float   keyShrinkIntensity;
        public final long    keyShrinkDownMs;

        // Wallpaper
        public final boolean wallpaperEnabled;
        public final int     wallpaperDim;
        public final String  wallpaperImagePath;
        public final String  wallpaperVideoPath;

        // Numeric pad
        public final String  numericExtraChar;
        public final String  numericDigitOrder;
        public final boolean numericReverseRows;

        public final long stamp;

        public LayoutSnapshot(float rowHeightDp, float rowGapDp, float keyGapDp,
                              float keyRadiusDp, float tabRowHeightDp,
                              int textColor, int bgColor, int keyAlpha,
                              boolean keyShrinkEnabled, float keyShrinkIntensity,
                              long keyShrinkDownMs,
                              boolean wallpaperEnabled, int wallpaperDim,
                              String wallpaperImagePath, String wallpaperVideoPath,
                              String numericExtraChar, String numericDigitOrder,
                              boolean numericReverseRows,
                              long stamp) {
            this.rowHeightDp         = rowHeightDp;
            this.rowGapDp            = rowGapDp;
            this.keyGapDp            = keyGapDp;
            this.keyRadiusDp         = keyRadiusDp;
            this.tabRowHeightDp      = tabRowHeightDp;
            this.textColor           = textColor;
            this.bgColor             = bgColor;
            this.keyAlpha            = keyAlpha;
            this.keyShrinkEnabled    = keyShrinkEnabled;
            this.keyShrinkIntensity  = keyShrinkIntensity;
            this.keyShrinkDownMs     = keyShrinkDownMs;
            this.wallpaperEnabled    = wallpaperEnabled;
            this.wallpaperDim        = wallpaperDim;
            this.wallpaperImagePath  = wallpaperImagePath;
            this.wallpaperVideoPath  = wallpaperVideoPath;
            this.numericExtraChar    = numericExtraChar;
            this.numericDigitOrder   = numericDigitOrder;
            this.numericReverseRows  = numericReverseRows;
            this.stamp               = stamp;
        }

        /** Build a full snapshot from the three config stores. */
        public static LayoutSnapshot capture(Context ctx) {
            ImeColorStore    c = ImeColorStore.get(ctx);
            NumericPadConfig n = NumericPadConfig.get(ctx);
            return new LayoutSnapshot(
                    c.getRowHeightDp(),
                    c.getRowGapDp(),
                    c.getKeyGapDp(),
                    c.getKeyCornerRadiusDp(),
                    c.getTabRowHeightDp(),
                    c.getTextColor(),
                    c.getBgColor(),
                    c.getKeyAlpha(),
                    c.isKeyShrinkEnabled(),
                    c.getKeyShrinkIntensity(),
                    c.getKeyShrinkDownMs(),
                    c.isWallpaperEnabled(),
                    c.getWallpaperDim(),
                    c.getWallpaperImagePath(),
                    c.getWallpaperVideoPath(),
                    n.getExtraChar(),
                    n.getDigitOrder(),
                    n.isReverseRows(),
                    System.currentTimeMillis());
        }
    }

    private static final String FILE = "ime_live_layout.json";

    private LiveLayoutBridge() {}

    private static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), FILE);
    }

    /** Called from the editor process — writes the snapshot atomically. */
    public static void write(Context ctx, LayoutSnapshot s) {
        if (ctx == null || s == null) return;
        File tmp = new File(ctx.getApplicationContext().getFilesDir(), FILE + ".tmp");
        File dst = file(ctx);
        try {
            JSONObject o = new JSONObject();
            o.put("rh", s.rowHeightDp);
            o.put("rg", s.rowGapDp);
            o.put("kg", s.keyGapDp);
            o.put("kr", s.keyRadiusDp);
            o.put("th", s.tabRowHeightDp);
            o.put("tc", s.textColor);
            o.put("bc", s.bgColor);
            o.put("ka", s.keyAlpha);
            o.put("ske", s.keyShrinkEnabled);
            o.put("ski", s.keyShrinkIntensity);
            o.put("skd", s.keyShrinkDownMs);
            o.put("wpe", s.wallpaperEnabled);
            o.put("wpd", s.wallpaperDim);
            if (s.wallpaperImagePath != null) o.put("wpi", s.wallpaperImagePath);
            if (s.wallpaperVideoPath != null) o.put("wpv", s.wallpaperVideoPath);
            o.put("nxc", s.numericExtraChar);
            o.put("nxo", s.numericDigitOrder);
            o.put("nxr", s.numericReverseRows);
            o.put("ts",  s.stamp);

            try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                fos.write(o.toString().getBytes("UTF-8"));
                fos.getFD().sync();
            }
            if (!tmp.renameTo(dst)) {
                try (FileOutputStream fos = new FileOutputStream(dst, false)) {
                    fos.write(o.toString().getBytes("UTF-8"));
                    fos.getFD().sync();
                }
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** mtime of the channel file, or 0 if absent. */
    public static long stamp(Context ctx) {
        File f = file(ctx);
        return f.exists() ? f.lastModified() : 0L;
    }

    /** Read + parse. Returns null if missing or corrupt. */
    public static LayoutSnapshot read(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            JSONObject o = new JSONObject(new String(buf, "UTF-8"));
            return new LayoutSnapshot(
                    (float) o.optDouble("rh", KeyboardConfig.DEFAULT_ROW_HEIGHT_DP),
                    (float) o.optDouble("rg", KeyboardConfig.DEFAULT_ROW_GAP_DP),
                    (float) o.optDouble("kg", KeyboardConfig.DEFAULT_KEY_HORIZONTAL_GAP_DP),
                    (float) o.optDouble("kr", KeyboardConfig.DEFAULT_KEY_CORNER_RADIUS_DP),
                    (float) o.optDouble("th", KeyboardConfig.DEFAULT_TAB_ROW_HEIGHT_DP),
                    o.optInt("tc",  KeyboardConfig.COLOR_TEXT),
                    o.optInt("bc",  KeyboardConfig.COLOR_BACKGROUND),
                    o.optInt("ka",  255),
                    o.optBoolean("ske", KeyboardConfig.DEFAULT_KEY_SHRINK_ENABLED),
                    (float) o.optDouble("ski", KeyboardConfig.DEFAULT_KEY_SHRINK_INTENSITY),
                    o.optLong("skd", KeyboardConfig.DEFAULT_KEY_SHRINK_MS),
                    o.optBoolean("wpe", false),
                    o.optInt("wpd", KeyboardConfig.DEFAULT_WALLPAPER_DIM),
                    o.optString("wpi", null),
                    o.optString("wpv", null),
                    o.optString("nxc", NumericPadConfig.DEFAULT_CHAR),
                    o.optString("nxo", NumericPadConfig.DEFAULT_ORDER),
                    o.optBoolean("nxr", false),
                    o.optLong("ts", 0L));
        } catch (Throwable t) {
            return null;
        }
    }
}