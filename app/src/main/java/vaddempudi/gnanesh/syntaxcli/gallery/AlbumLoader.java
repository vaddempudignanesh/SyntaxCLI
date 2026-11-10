// ═══════════════════════════════════════════════════════════════
// File: AlbumLoader.java — FAST VERSION
// ═══════════════════════════════════════════════════════════════
package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AlbumLoader {

    private final ContentResolver resolver;

    // ── Single-shot index. All albums come from here. ──────────────
    private volatile Map<String, List<GalleryMediaItem>> index = null;
    private final AtomicBoolean building = new AtomicBoolean(false);
    private final Object buildLock = new Object();

    public AlbumLoader(Context context) {
        this.resolver = context.getApplicationContext().getContentResolver();
    }

    /** Force the next loadAlbums/loadAlbumItems to rebuild the index. */
    public void invalidate() {
        synchronized (buildLock) {
            index = null;
        }
    }

    private Map<String, List<GalleryMediaItem>> ensureIndex() {
        Map<String, List<GalleryMediaItem>> snapshot = index;
        if (snapshot != null) return snapshot;

        if (!building.compareAndSet(false, true)) {
            // Another thread is building — wait for it.
            synchronized (buildLock) {
                while (index == null) {
                    try { buildLock.wait(50L); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
            return index;
        }

        try {
            Map<String, List<GalleryMediaItem>> built = new HashMap<>();
            queryIntoIndex(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    GalleryMediaItem.TYPE_IMAGE, built);
            queryIntoIndex(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    GalleryMediaItem.TYPE_VIDEO, built);

            for (List<GalleryMediaItem> l : built.values()) {
                Collections.sort(l, LATEST_FIRST);
            }

            synchronized (buildLock) {
                index = built;
                buildLock.notifyAll();
            }
            return built;
        } finally {
            building.set(false);
        }
    }

    private void queryIntoIndex(android.net.Uri uri, int type,
                                Map<String, List<GalleryMediaItem>> out) {
        Cursor c = null;
        try {
            c = resolver.query(uri,
                    new String[]{
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DATA,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.DATE_MODIFIED
                    },
                    null, null,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c == null) return;

            int idCol   = c.getColumnIndex(MediaStore.MediaColumns._ID);
            int dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            int nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int modCol  = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);

            while (c.moveToNext()) {
                String path = dataCol >= 0 ? c.getString(dataCol) : null;
                if (TextUtils.isEmpty(path)) continue;

                String fileName = new File(path).getName();
                if (fileName.startsWith(".trashed.")) continue;
                if (path.contains("/.trashed.")) continue;
                if (fileName.startsWith("p_")) continue;
                if (path.contains("/com.whatsapp/")) continue;

                File parent = new File(path).getParentFile();
                if (parent == null) continue;
                String albumPath;
                try { albumPath = parent.getCanonicalPath(); }
                catch (Exception e) { albumPath = parent.getAbsolutePath(); }

                long id   = idCol >= 0 ? c.getLong(idCol) : -1L;
                String name = nameCol >= 0 ? c.getString(nameCol) : null;
                if (TextUtils.isEmpty(name)) name = fileName;
                long modified = modCol >= 0 ? c.getLong(modCol) : 0L;

                List<GalleryMediaItem> bucket = out.get(albumPath);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    out.put(albumPath, bucket);
                }
                bucket.add(new GalleryMediaItem(id, path, name, albumPath, modified, type));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    // ── Album list ────────────────────────────────────────────────

    public List<AlbumRecord> loadAlbums() {
        Map<String, List<GalleryMediaItem>> idx = ensureIndex();

        List<AlbumRecord> out = new ArrayList<>(idx.size());
        for (Map.Entry<String, List<GalleryMediaItem>> e : idx.entrySet()) {
            List<GalleryMediaItem> items = e.getValue();
            if (items == null || items.isEmpty()) continue;
            String display = new File(e.getKey()).getName();
            String cover = items.get(0).path;
            out.add(new AlbumRecord(e.getKey(), display, items.size(), cover));
        }

        out.addAll(buildWhatsAppAlbums());

        Collections.sort(out, new Comparator<AlbumRecord>() {
            @Override public int compare(AlbumRecord a, AlbumRecord b) {
                boolean aw = a.isWhatsApp();
                boolean bw = b.isWhatsApp();
                if (aw != bw) return aw ? -1 : 1;
                String da = a.displayName == null ? "" : a.displayName;
                String db = b.displayName == null ? "" : b.displayName;
                return da.compareToIgnoreCase(db);
            }
        });

        return out;
    }

    private List<AlbumRecord> buildWhatsAppAlbums() {
        List<AlbumRecord> out = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        if (root == null) return out;

        File wa = new File(root,
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images");
        if (wa.isDirectory()) {
            File[] files = wa.listFiles();
            if (files != null) {
                int count = 0;
                String cover = null;
                for (File f : files) {
                    if (f.isFile() && !f.getName().startsWith(".")) {
                        count++;
                        if (cover == null) cover = f.getAbsolutePath();
                    }
                }
                if (count > 0) {
                    out.add(new AlbumRecord("whatsapp://all", "WhatsApp", count, cover));
                }
            }
        }

        File accounts = new File(root,
                "Android/media/com.whatsapp/WhatsApp/accounts");
        if (accounts.isDirectory()) {
            File[] accs = accounts.listFiles();
            if (accs != null) {
                for (File acc : accs) {
                    if (acc == null || !acc.isDirectory()) continue;
                    File waImg = new File(acc, "Media/WhatsApp Images");
                    if (!waImg.isDirectory()) continue;
                    File[] files = waImg.listFiles();
                    if (files == null) continue;

                    int count = 0;
                    String cover = null;
                    for (File f : files) {
                        if (f.isFile() && !f.getName().startsWith(".")) {
                            count++;
                            if (cover == null) cover = f.getAbsolutePath();
                        }
                    }
                    if (count > 0) {
                        out.add(new AlbumRecord(
                                "whatsapp://" + acc.getName(),
                                "WhatsApp (" + acc.getName() + ")",
                                count, cover));
                    }
                }
            }
        }
        return out;
    }

    // ── Album contents — O(1) via index ───────────────────────────

    public List<GalleryMediaItem> loadAlbumItems(AlbumRecord album) {
        if (album == null) return Collections.emptyList();
        if (album.isWhatsApp()) return loadWhatsAppItems(album);

        Map<String, List<GalleryMediaItem>> idx = ensureIndex();

        // Try direct canonical key first.
        List<GalleryMediaItem> direct = idx.get(album.path);
        if (direct != null) return new ArrayList<>(direct);

        // Fallback: canonicalise the album path and try again.
        String canonical;
        try { canonical = new File(album.path).getCanonicalPath(); }
        catch (Exception e) { canonical = album.path; }
        List<GalleryMediaItem> byCanon = idx.get(canonical);
        if (byCanon != null) return new ArrayList<>(byCanon);

        return Collections.emptyList();
    }

    private List<GalleryMediaItem> loadWhatsAppItems(AlbumRecord album) {
        List<GalleryMediaItem> out = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        if (root == null) return out;

        File dir;
        if ("whatsapp://all".equals(album.path)) {
            dir = new File(root,
                    "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images");
        } else {
            String accountId = album.path.substring("whatsapp://".length());
            dir = new File(root,
                    "Android/media/com.whatsapp/WhatsApp/accounts/"
                            + accountId + "/Media/WhatsApp Images");
        }

        if (!dir.isDirectory()) return out;

        File[] files = dir.listFiles();
        if (files == null) return out;

        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (name.startsWith(".")) continue;

            String lower = name.toLowerCase(Locale.ROOT);
            int type;
            if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                    || lower.endsWith(".avi") || lower.endsWith(".mov")
                    || lower.endsWith(".3gp") || lower.endsWith(".m4v")
                    || lower.endsWith(".flv") || lower.endsWith(".wmv")) {
                type = GalleryMediaItem.TYPE_VIDEO;
            } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".png") || lower.endsWith(".gif")
                    || lower.endsWith(".bmp") || lower.endsWith(".webp")
                    || lower.endsWith(".heic") || lower.endsWith(".heif")) {
                type = GalleryMediaItem.TYPE_IMAGE;
            } else {
                continue;
            }

            out.add(new GalleryMediaItem(
                    f.getAbsolutePath().hashCode(),
                    f.getAbsolutePath(),
                    name,
                    dir.getAbsolutePath(),
                    f.lastModified() / 1000L,
                    type));
        }

        Collections.sort(out, LATEST_FIRST);
        return out;
    }

    private static final Comparator<GalleryMediaItem> LATEST_FIRST =
            new Comparator<GalleryMediaItem>() {
                @Override public int compare(GalleryMediaItem a, GalleryMediaItem b) {
                    int c = Long.compare(b.lastModifiedMillis, a.lastModifiedMillis);
                    if (c != 0) return c;
                    String pa = a.path == null ? "" : a.path;
                    String pb = b.path == null ? "" : b.path;
                    return pb.compareTo(pa);
                }
            };
}