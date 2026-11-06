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

    private volatile long lastCaptureMs = 0L;
    private static final long CAPTURE_DEBOUNCE_MS = 400L;

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
        long now = System.currentTimeMillis();
        if (now - lastCaptureMs < CAPTURE_DEBOUNCE_MS) return;
        lastCaptureMs = now;
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
        File dst = null;
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

            // Write to a temp file first so we can hash before committing
            File tmp = new File(clipDir(), "img_tmp_" + System.currentTimeMillis() + ".png");
            try (OutputStream out = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 92, out);
            }
            bmp.recycle();

            // Hash the actual PNG bytes
            String hash = hashOfFile(tmp);
            if (hash == null) { tmp.delete(); return; }

            // If we already have this image (same SHA-256 of the PNG), reuse it.
            for (ClipEntry e : items) {
                if (e.type != ClipEntry.TYPE_IMAGE) continue;
                if (e.localPath == null) continue;
                String existingHash = hashOfFile(new File(e.localPath));
                if (hash.equals(existingHash)) {
                    // Bump to top, keep old file, skip writing the temp.
                    tmp.delete();
                    ClipEntry bumped = new ClipEntry(
                            e.type, e.text, e.localPath, e.mimeType, e.displayName,
                            System.currentTimeMillis());
                    items.remove(e);
                    items.add(0, bumped);
                    persist();
                    return;
                }
            }

            // New image — move temp into place with a stable name derived from hash
            File dstFinal = new File(clipDir(), "img_" + hash.substring(0, 16) + ".png");
            if (!tmp.renameTo(dstFinal)) {
                // fallback: copy then delete
                try (java.io.InputStream in = new java.io.FileInputStream(tmp);
                     OutputStream out = new FileOutputStream(dstFinal)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                tmp.delete();
            }

            addEntry(new ClipEntry(ClipEntry.TYPE_IMAGE, null,
                    dstFinal.getAbsolutePath(), "image/png", dstFinal.getName(),
                    System.currentTimeMillis()));
        } catch (Throwable t) {
            if (dst != null) dst.delete();
            XLog.e("ClipboardStore", "captureImageUri failed", t);
        }
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

    /**
     * Copy the clip's PNG into MediaStore.Images.Media so that any app with
     * READ_EXTERNAL_STORAGE (WhatsApp, Gmail, Telegram, Instagram, Slack, ...)
     * can read it via the returned content:// URI. This is what makes
     * manual long-press → Paste work; a FileProvider URI cannot.
     *
     * Returns null on failure.
     */
    public android.net.Uri publicImageUriFor(ClipEntry e) {
        if (e == null || e.type != ClipEntry.TYPE_IMAGE || e.localPath == null) return null;

        File src = new File(e.localPath);
        if (!src.exists() || src.length() == 0) return null;

        String displayName = "kbd_" + System.currentTimeMillis() + ".png";
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName);
        cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/KeyboardPaste");
            cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
        }

        android.net.Uri dest = null;
        try {
            dest = appCtx.getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (dest == null) return null;

            try (java.io.InputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream out = appCtx.getContentResolver().openOutputStream(dest)) {
                if (out == null) { appCtx.getContentResolver().delete(dest, null, null); return null; }
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.content.ContentValues done = new android.content.ContentValues();
                done.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                appCtx.getContentResolver().update(dest, done, null, null);
            }
            return dest;
        } catch (Throwable t) {
            XLog.e("ClipboardStore", "publicImageUriFor failed", t);
            if (dest != null) {
                try { appCtx.getContentResolver().delete(dest, null, null); } catch (Throwable ignored) { }
            }
            return null;
        }
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

    private static String sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private String hashOfFile(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}