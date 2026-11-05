package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClipboardStore {

    private static final String PREFS   = "ime_clipboard_history";
    private static final String KEY     = "items";
    private static final int    MAX     = 30;

    private static volatile ClipboardStore INSTANCE;
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;
    private final List<String> items = new ArrayList<>();

    private ClipboardStore(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public static ClipboardStore get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) INSTANCE = new ClipboardStore(ctx);
            }
        }
        return INSTANCE;
    }

    private void load() {
        items.clear();
        try {
            String raw = prefs.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null && !s.isEmpty()) items.add(s);
            }
        } catch (Exception ignored) { }
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (String s : items) arr.put(s);
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    public void capture(Context ctx) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(ctx);
            if (text == null) return;
            String s = text.toString();
            if (s.isEmpty()) return;
            add(s);
        } catch (Exception ignored) { }
    }

    public void add(String text) {
        if (text == null) return;
        String s = text;
        if (s.length() > 4000) s = s.substring(0, 4000);
        items.remove(s);
        items.add(0, s);
        while (items.size() > MAX) items.remove(items.size() - 1);
        persist();
    }

    public List<String> all() {
        return Collections.unmodifiableList(items);
    }

    public void clear() {
        items.clear();
        persist();
    }
}