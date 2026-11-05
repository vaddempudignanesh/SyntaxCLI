package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClipboardStore {

    private static final String PREFS    = "ime_clipboard_history";
    private static final String KEY      = "items";
    private static final int    MAX      = 30;
    private static final long   MAX_FILE = 25L * 1024 * 1024;

    private static volatile ClipboardStore INSTANCE;
    private static final Object LOCK = new Object();

    private final Context appCtx;
    private final android.content.SharedPreferences prefs;
    private final List<ClipEntry> items = new ArrayList<>();

    private ClipboardStore(Context ctx) {
        appCtx = ctx.getApplicationContext();
        prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    private File clipDir() {
        File d = new File(appCtx.getFilesDir(), "clipboard");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void load() {
        items.clear();
        try {
            String raw = prefs.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int type = o.optInt("type", ClipEntry.TYPE_TEXT);
                String text = o.optString("text", null);
                String path = o.optString("path", null);
                String mime = o.optString("mime", null);
                String name = o.optString("name", null);
                long ts = o.optLong("ts", 0L);
                if (type == ClipEntry.TYPE_TEXT && (text == null || text.isEmpty())) continue;
                if (type != ClipEntry.TYPE_TEXT && (path == null || path.isEmpty())) continue;
                if (type != ClipEntry.TYPE_TEXT && !new File(path).exists()) continue;
                items.add(new ClipEntry(type, text, path, mime, name, ts));
            }
        } catch (Exception ignored) { }
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (ClipEntry e : items) {
                JSONObject o = new JSONObject();
                o.put("type", e.type);
                if (e.text != null) o.put("text", e.text);
                if (e.localPath != null) o.put("path", e.localPath);
                if (e.mimeType != null) o.put("mime", e.mimeType);
                if (e.displayName != null) o.put("name", e.displayName);
                o.put("ts", e.timestamp);
                arr.put(o);
            }
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
            ClipData.Item item = clip.getItemAt(0);
            Uri uri = item.getUri();

            if (uri != null) {
                String mime = safeMime(uri);
                if (mime == null) mime = clip.getDescription().getMimeType(0);

                if (mime != null && mime.startsWith("image/")) {
                    captureImageUri(uri);
                    return;
                }
                if (mime != null) {
                    captureFileUri(uri, mime);
                    return;
                }
            }

            CharSequence cs = item.coerceToText(ctx);
            if (cs != null) {
                String s = cs.toString();
                if (!s.isEmpty() && !s.startsWith("content://") && !s.startsWith("file://")) {
                    addText(s);
                }
            }
        } catch (Throwable ignored) { }
    }

    private String safeMime(Uri uri) {
        try {
            return appCtx.getContentResolver().getType(uri);
        } catch (Exception e) { return null; }
    }

    private void captureImageUri(Uri uri) {
        try {
            Bitmap bmp = MediaStore.Images.Media.getBitmap(appCtx.getContentResolver(), uri);
            if (bmp == null) return;
            int maxDim = 1600;
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (w > maxDim || h > maxDim) {
                float scale = Math.min((float) maxDim / w, (float) maxDim / h);
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, (int)(w * scale)),
                        Math.max(1, (int)(h * scale)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            File dst = new File(clipDir(), "img_" + System.currentTimeMillis() + ".png");
            try (OutputStream out = new FileOutputStream(dst)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 92, out);
            }
            bmp.recycle();
            addEntry(new ClipEntry(ClipEntry.TYPE_IMAGE, null,
                    dst.getAbsolutePath(), "image/png", dst.getName(),
                    System.currentTimeMillis()));
        } catch (Throwable ignored) { }
    }

    private void captureFileUri(Uri uri, String mime) {
        try {
            long size = -1;
            String name = null;
            try (android.database.Cursor c = appCtx.getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (si >= 0) size = c.getLong(si);
                    if (ni >= 0) name = c.getString(ni);
                }
            }
            if (size > MAX_FILE) return;
            if (name == null) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                name = "file_" + System.currentTimeMillis() + (ext != null ? "." + ext : "");
            }
            String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
            File dst = new File(clipDir(), "f_" + System.currentTimeMillis() + "_" + safe);
            try (InputStream in = appCtx.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dst)) {
                if (in == null) return;
                byte[] buf = new byte[8192];
                int n; long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_FILE) { dst.delete(); return; }
                    out.write(buf, 0, n);
                }
            }
            addEntry(new ClipEntry(ClipEntry.TYPE_FILE, null,
                    dst.getAbsolutePath(), mime, name,
                    System.currentTimeMillis()));
        } catch (Throwable ignored) { }
    }

    public void addText(String text) {
        if (text == null || text.isEmpty()) return;
        String s = text.length() > 4000 ? text.substring(0, 4000) : text;
        addEntry(new ClipEntry(ClipEntry.TYPE_TEXT, s, null, null, null,
                System.currentTimeMillis()));
    }

    public void addEntry(ClipEntry e) {
        if (e == null) return;
        String key = e.dedupeKey();
        long existingTs = 0L;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).dedupeKey().equals(key)) {
                existingTs = items.get(i).timestamp;
                items.remove(i);
            }
        }

        long ts = existingTs > 0 ? existingTs : e.timestamp;
        if (ts <= 0) ts = System.currentTimeMillis();

        ClipEntry stored = new ClipEntry(
                e.type, e.text, e.localPath, e.mimeType, e.displayName, ts);

        items.add(0, stored);
        while (items.size() > MAX) {
            ClipEntry removed = items.remove(items.size() - 1);
            deleteLocalFile(removed);
        }
        persist();
    }

    private void deleteLocalFile(ClipEntry e) {
        if (e.type == ClipEntry.TYPE_TEXT) return;
        try { if (e.localPath != null) new File(e.localPath).delete(); }
        catch (Exception ignored) { }
    }

    public List<ClipEntry> all() {
        return Collections.unmodifiableList(items);
    }

    public void clear() {
        for (ClipEntry e : items) deleteLocalFile(e);
        items.clear();
        persist();
    }

    public Uri fileProviderUriFor(ClipEntry e) {
        if (e == null || e.localPath == null) return null;
        try {
            File f = new File(e.localPath);
            return androidx.core.content.FileProvider.getUriForFile(
                    appCtx,
                    appCtx.getPackageName() + ".ime.fileprovider",
                    f);
        } catch (Throwable t) {
            return null;
        }
    }
}