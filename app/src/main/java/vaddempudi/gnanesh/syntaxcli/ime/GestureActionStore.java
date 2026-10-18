package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class GestureActionStore {

    private static final String PREFS = "ime_gesture_actions";
    private static final String KEY   = "actions";

    private static volatile GestureActionStore INSTANCE;
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;
    private final List<KeyAction> actions = new ArrayList<>();

    private GestureActionStore(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        try {
            String raw = prefs.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                actions.add(new KeyAction(
                        o.optString("id", UUID.randomUUID().toString()),
                        o.optString("keyChar", ""),
                        o.optInt("type", KeyAction.TYPE_TEXT),
                        o.optString("payload", "")
                ));
            }
        } catch (Exception ignored) { }
    }

    private void persist() {
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
        } catch (Exception ignored) { }
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