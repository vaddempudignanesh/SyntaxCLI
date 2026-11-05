package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class GestureActionStore {

    private static final String PREFS = "ime_gesture_actions";
    private static final String KEY   = "actions";
    private static final String DIR   = "t-ui";
    private static final String FILE  = "gesture_actions.txt";

    private static volatile GestureActionStore INSTANCE;
    private static final Object LOCK = new Object();

    private final Context appCtx;
    private final SharedPreferences prefs;
    private final List<KeyAction> actions = new ArrayList<>();

    private GestureActionStore(Context ctx) {
        appCtx = ctx.getApplicationContext();
        prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public static GestureActionStore get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) INSTANCE = new GestureActionStore(ctx);
            }
        }
        return INSTANCE;
    }

    private void load() {
        actions.clear();

        boolean loadedFromPrefs = false;
        try {
            String raw = prefs.getString(KEY, null);
            if (raw != null && !raw.isEmpty()) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    KeyAction a = fromJson(arr.getJSONObject(i));
                    if (a != null) actions.add(a);
                }
                loadedFromPrefs = !actions.isEmpty();
            }
        } catch (Throwable ignored) { }

        if (loadedFromPrefs) return;

        List<KeyAction> fromFile = loadFromFile();
        if (!fromFile.isEmpty()) {
            actions.addAll(fromFile);
            persistPrefs();
        }
    }

    private List<KeyAction> loadFromFile() {
        List<KeyAction> out = new ArrayList<>();
        File f = new File(filePath());
        if (!f.exists() || !f.canRead()) return out;

        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    KeyAction a = fromJson(o);
                    if (a != null) out.add(a);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static KeyAction fromJson(JSONObject o) {
        try {
            return new KeyAction(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optString("keyChar", ""),
                    o.optInt("type", KeyAction.TYPE_TEXT),
                    o.optString("payload", "")
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void persist() {
        persistPrefs();
        persistFile();
    }

    private void persistPrefs() {
        try {
            JSONArray arr = new JSONArray();
            for (KeyAction a : actions) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("keyChar", a.keyChar);
                o.put("type", a.type);
                o.put("payload", a.payload);
                arr.put(o);
            }
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private void persistFile() {
        File dir = new File(filePath()).getParentFile();
        if (dir == null) return;
        try {
            if (!dir.exists()) dir.mkdirs();
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(filePath()))) {
                for (KeyAction a : actions) {
                    JSONObject o = new JSONObject();
                    o.put("id", a.id);
                    o.put("keyChar", a.keyChar);
                    o.put("type", a.type);
                    o.put("payload", a.payload);
                    w.write(o.toString());
                    w.write('\n');
                }
            }
        } catch (Throwable ignored) { }
    }

    private static String filePath() {
        return Environment.getExternalStorageDirectory()
                + File.separator + DIR + File.separator + FILE;
    }

    public List<KeyAction> all() {
        return Collections.unmodifiableList(actions);
    }

    public KeyAction byKeyChar(String keyChar) {
        if (keyChar == null) return null;
        for (KeyAction a : actions) {
            if (keyChar.equalsIgnoreCase(a.keyChar)) return a;
        }
        return null;
    }

    public KeyAction byId(String id) {
        if (id == null) return null;
        for (KeyAction a : actions) {
            if (id.equals(a.id)) return a;
        }
        return null;
    }

    public void add(KeyAction a) {
        actions.add(a);
        persist();
    }

    public void replace(String id, KeyAction a) {
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).id.equals(id)) {
                actions.set(i, a);
                persist();
                return;
            }
        }
        add(a);
    }

    public void remove(String id) {
        for (int i = actions.size() - 1; i >= 0; i--) {
            if (actions.get(i).id.equals(id)) {
                actions.remove(i);
            }
        }
        persist();
    }
}