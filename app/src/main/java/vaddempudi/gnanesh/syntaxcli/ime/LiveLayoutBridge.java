package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * Cross-process live-layout channel.
 *
 * The editor process writes a small JSON snapshot to a single file.
 * The IME process reads it on demand (no SharedPreferences, no broadcasts).
 *
 * The public entry point the editor calls is
 * {@link BasicImeService#pushLiveLayout(LayoutSnapshot)}.
 */
public final class LiveLayoutBridge {

    public static final class LayoutSnapshot {
        public final float rowHeightDp;
        public final float rowGapDp;
        public final float keyGapDp;
        public final float keyRadiusDp;
        public final float tabRowHeightDp;
        public final long  stamp;

        public LayoutSnapshot(float rowHeightDp, float rowGapDp, float keyGapDp,
                              float keyRadiusDp, float tabRowHeightDp, long stamp) {
            this.rowHeightDp    = rowHeightDp;
            this.rowGapDp       = rowGapDp;
            this.keyGapDp       = keyGapDp;
            this.keyRadiusDp    = keyRadiusDp;
            this.tabRowHeightDp = tabRowHeightDp;
            this.stamp          = stamp;
        }

        public static LayoutSnapshot from(ImeColorStore s) {
            return new LayoutSnapshot(
                    s.getRowHeightDp(),
                    s.getRowGapDp(),
                    s.getKeyGapDp(),
                    s.getKeyCornerRadiusDp(),
                    s.getTabRowHeightDp(),
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
            o.put("ts", s.stamp);

            try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                fos.write(o.toString().getBytes("UTF-8"));
                fos.getFD().sync();
            }
            if (!tmp.renameTo(dst)) {
                // Fallback: direct write
                try (FileOutputStream fos = new FileOutputStream(dst, false)) {
                    fos.write(o.toString().getBytes("UTF-8"));
                    fos.getFD().sync();
                }
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** mtime of the channel file, or 0 if absent. Cheap stat only. */
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
                    o.optLong("ts", 0L));
        } catch (Throwable t) {
            return null;
        }
    }
}