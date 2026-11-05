package vaddempudi.gnanesh.syntaxcli.managers;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

public final class AppActionStore {

    public static final int TYPE_APP   = 1;
    public static final int TYPE_TEXT  = 2;
    public static final int TYPE_VIDEO = 3;

    private static final String PREFS = "ime_app_actions";
    private static final String KEY   = "actions";
    private static final String DIR   = "t-ui";
    private static final String FILE  = "app_actions.txt";

    public static final int TYPE_IMAGE = 3;
    private static volatile AppActionStore INSTANCE;
    private static final Object LOCK = new Object();

    private final Context appCtx;
    private final SharedPreferences prefs;
    private final List<Entry> entries = new ArrayList<>();

    private AppActionStore(Context ctx) {
        appCtx = ctx.getApplicationContext();
        prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public static AppActionStore get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) INSTANCE = new AppActionStore(ctx);
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

    private void load() {
        entries.clear();
        try {
            String raw = prefs.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.packageName = o.optString("pkg", "");
                e.label       = o.optString("label", "");
                e.type        = o.optInt("type", TYPE_APP);
                e.payload     = o.optString("payload", "");
                e.secondLabel = o.optString("second", "");
                if (!e.packageName.isEmpty() || !e.label.isEmpty()) entries.add(e);
            }
        } catch (Throwable ignored) { }

        if (entries.isEmpty()) {
            File f = backupFile();
            if (f.exists() && f.canRead()) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        try {
                            JSONObject o = new JSONObject(line);
                            Entry e = new Entry();
                            e.packageName = o.optString("pkg", "");
                            e.label       = o.optString("label", "");
                            e.type        = o.optInt("type", TYPE_APP);
                            e.payload     = o.optString("payload", "");
                            e.secondLabel = o.optString("second", "");
                            entries.add(e);
                        } catch (Throwable ignored) { }
                    }
                } catch (Throwable ignored) { }
            }
        }
    }

    public void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("pkg", e.packageName);
                o.put("label", e.label);
                o.put("type", e.type);
                o.put("payload", e.payload);
                o.put("second", e.secondLabel);
                arr.put(o);
            }
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable ignored) { }

        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(backupFile()))) {
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("pkg", e.packageName);
                o.put("label", e.label);
                o.put("type", e.type);
                o.put("payload", e.payload);
                o.put("second", e.secondLabel);
                w.write(o.toString());
                w.write('\n');
            }
        } catch (Throwable ignored) { }
    }

    public Entry find(String packageName, String label) {
        for (Entry e : entries) {
            if (packageName != null && !packageName.isEmpty()
                    && packageName.equalsIgnoreCase(e.packageName)) return e;
            if (label != null && !label.isEmpty()
                    && label.equalsIgnoreCase(e.label)) return e;
        }
        return null;
    }

    public void put(Entry e) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry old = entries.get(i);
            if ((!e.packageName.isEmpty() && e.packageName.equalsIgnoreCase(old.packageName))
                    || (!e.label.isEmpty() && e.label.equalsIgnoreCase(old.label))) {
                entries.remove(i);
            }
        }
        entries.add(e);
        save();
    }

    public boolean remove(String label) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).label.equalsIgnoreCase(label)) {
                entries.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    public void clear() {
        entries.clear();
        save();
    }

    public List<Entry> listAll() {
        return new java.util.ArrayList<>(entries);
    }

    public static final class Entry {
        public String packageName = "";
        public String label       = "";
        public int    type        = TYPE_APP;
        public String payload     = "";
        public String secondLabel = "";
    }
}