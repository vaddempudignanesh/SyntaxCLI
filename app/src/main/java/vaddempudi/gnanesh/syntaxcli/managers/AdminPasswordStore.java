package vaddempudi.gnanesh.syntaxcli.managers;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.util.Calendar;

/**
 * Admin password. Two backup stores:
 *   - SharedPreferences (ime_admin_password / "pwd")
 *   - /sdcard/t-ui/pwd.txt  (one line: the raw base password)
 *
 * Verification: current minute (0..59, 2 digits) is appended to the stored
 * base password before comparison. Hour is NOT considered — only the minute.
 */
public final class AdminPasswordStore {

    private static final String PREFS = "ime_admin_password";
    private static final String KEY   = "pwd";
    private static final String DIR   = "t-ui";
    private static final String FILE  = "pwd.txt";

    private static volatile AdminPasswordStore INSTANCE;
    private static final Object LOCK = new Object();

    private final Context appCtx;
    private final SharedPreferences prefs;

    private AdminPasswordStore(Context ctx) {
        appCtx = ctx.getApplicationContext();
        prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static AdminPasswordStore get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) INSTANCE = new AdminPasswordStore(ctx);
            }
        }
        return INSTANCE;
    }

    private File tuiDir() {
        File d = new File(android.os.Environment.getExternalStorageDirectory(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File backupFile() {
        return new File(tuiDir(), FILE);
    }

    /** Raw base password, or null if not set. Prefs first, then t-ui/pwd.txt. */
    public String getBase() {
        String s = prefs.getString(KEY, null);
        if (s != null && !s.isEmpty()) return s;

        File f = backupFile();
        if (f.exists() && f.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line = br.readLine();
                if (line != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        prefs.edit().putString(KEY, line).apply();
                        return line;
                    }
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    public boolean isSet() {
        return getBase() != null;
    }

    public void set(String base) {
        if (base == null) base = "";
        base = base.trim();
        prefs.edit().putString(KEY, base).apply();
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(backupFile(), false))) {
            w.write(base);
            w.write('\n');
        } catch (Throwable ignored) { }
    }

    public void clear() {
        prefs.edit().remove(KEY).apply();
        try { backupFile().delete(); } catch (Throwable ignored) { }
    }

    /** The expected value at the current minute. e.g. base="hi", minute=22 → "hi22". */
    public String expectedNow() {
        String base = getBase();
        if (base == null) return null;
        Calendar c = Calendar.getInstance();
        int minute = c.get(Calendar.MINUTE);
        String mm = (minute < 10 ? "0" : "") + minute;
        return base + mm;
    }

    /** True if `typed` equals base + (current minute as 2 digits). */
    public boolean verify(String typed) {
        if (typed == null) return false;
        String expected = expectedNow();
        return expected != null && expected.equals(typed.trim());
    }
}