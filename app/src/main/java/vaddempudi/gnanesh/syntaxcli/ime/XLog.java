package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Release-safe logger. Writes ALL lines to a single SharedPreferences key.
 * Read from host with:
 *
 *   adb shell run-as vaddempudi.gnanesh.syntaxcli.ime \
 *       cat shared_prefs/ime_debug_log.xml
 *
 * or, if the app is debuggable:
 *
 *   adb shell run-as vaddempudi.gnanesh.syntaxcli.ime \
 *       cat /data/data/vaddempudi.gnanesh.syntaxcli.ime/shared_prefs/ime_debug_log.xml
 *
 * Or live-tap: adb shell run-as <pkg> cat shared_prefs/ime_debug_log.xml | tail
 */
public final class XLog {

    public static final String PREFS = "ime_debug_log";
    public static final String KEY   = "log";

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final Object LOCK = new Object();

    private static volatile Context appCtx;
    private static volatile SharedPreferences prefs;

    private XLog() {}

    /** Call once from Application/Service onCreate. Safe to call many times. */
    public static void init(Context ctx) {
        if (ctx == null) return;
        if (prefs != null) return;
        synchronized (LOCK) {
            if (prefs != null) return;
            appCtx = ctx.getApplicationContext();
            prefs  = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    /** Same signature as android.util.Log.d — drop-in replacement. */
    public static int d(String tag, String msg) {
        write(tag, msg, null);
        return 0;
    }

    public static int d(String tag, String msg, Throwable t) {
        write(tag, msg, t);
        return 0;
    }

    public static int w(String tag, String msg) {
        write(tag, msg, null);
        return 0;
    }

    public static int w(String tag, String msg, Throwable t) {
        write(tag, msg, t);
        return 0;
    }

    public static int e(String tag, String msg) {
        write(tag, msg, null);
        return 0;
    }

    public static int e(String tag, String msg, Throwable t) {
        write(tag, msg, t);
        return 0;
    }

    public static int i(String tag, String msg) {
        write(tag, msg, null);
        return 0;
    }

    public static int i(String tag, String msg, Throwable t) {
        write(tag, msg, t);
        return 0;
    }

    /** Wipe the log. */
    public static void clear() {
        synchronized (LOCK) {
            if (prefs == null) return;
            prefs.edit().remove(KEY).apply();
        }
    }

    /** Full log text (for the in-app viewer). */
    public static String dump() {
        synchronized (LOCK) {
            if (prefs == null) return "";
            return prefs.getString(KEY, "");
        }
    }

    private static void write(String tag, String msg, Throwable t) {
        if (msg == null) msg = "null";
        String line = FMT.format(new Date()) + " [" + tag + "] " + msg;
        if (t != null) {
            line += " :: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        line += "\n";

        synchronized (LOCK) {
            if (prefs == null) return;
            // Append-only. SharedPreferences holds the full string; simple and
            // survives process death. Keep the last ~200 KB.
            String existing = prefs.getString(KEY, "");
            String combined = existing + line;
            if (combined.length() > 200 * 1024) {
                combined = combined.substring(combined.length() - 200 * 1024);
                int nl = combined.indexOf('\n');
                if (nl > 0) combined = combined.substring(nl + 1);
            }
            prefs.edit().putString(KEY, combined).apply();
        }
    }
}