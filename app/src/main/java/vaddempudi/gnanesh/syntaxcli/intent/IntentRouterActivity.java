package vaddempudi.gnanesh.syntaxcli.intent;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class IntentRouterActivity extends AppCompatActivity {


    private static final String CLS_PDF    = "vaddempudi.gnanesh.syntaxcli.mediareader.PdfViewerActivity";
    private static final String CLS_DOCX   = "vaddempudi.gnanesh.syntaxcli.mediareader.DocxViewerActivity";
    private static final String CLS_GALLERY= "vaddempudi.gnanesh.syntaxcli.gallery.GalleryActivity";
    private static final String CLS_MUSIC  = "vaddempudi.gnanesh.syntaxcli.musicplayer.MusicPlayerActivity";
    private static final String CLS_FILE   = "vaddempudi.gnanesh.syntaxcli.filemanager.FileManagerActivity";

    // Public extras your target activities already read.
    private static final String EXTRA_PDF_URI  = "pdf_uri";
    private static final String EXTRA_PDF_NAME = "pdf_name";
    private static final String EXTRA_DOCX_PATH = "docx_path";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }

        try {
            dispatch(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "Cannot open file: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }

    private void dispatch(Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        // --- Collect incoming Uri(s) ---
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) uris.add(stream);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list =
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else if (Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_EDIT.equals(action)
                || Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
            Uri data = intent.getData();
            if (data != null) uris.add(data);
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to open", Toast.LENGTH_SHORT).show();
            return;
        }

        // Resolve the first Uri (VIEW / single SEND case).
        if (uris.size() == 1) {
            File file = resolveFile(uris.get(0));
            if (file == null) {
                Toast.makeText(this, "Cannot read file", Toast.LENGTH_LONG).show();
                return;
            }
            openOne(file, mimeFromIntent(intent));
            return;
        }

        // Multi-file: route to FileManager at the parent folder of the first.
        File first = resolveFile(uris.get(0));
        if (first == null || first.getParentFile() == null) {
            Toast.makeText(this, "Cannot read files", Toast.LENGTH_LONG).show();
            return;
        }
        Intent fm = new Intent()
                .setComponent(new ComponentName(this, CLS_FILE));
        startActivity(fm);
    }
    @Nullable
    private String mimeFromIntent(Intent intent) {
        String t = intent.getType();
        if (t != null && !t.isEmpty()) return t;

        String[] types = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        if (types != null && types.length > 0) return types[0];

        return null;
    }

    @Nullable
    private File resolveFile(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();

        if ("file".equalsIgnoreCase(scheme)) {
            File f = new File(uri.getPath());
            return f.exists() ? f : null;
        }

        if ("content".equalsIgnoreCase(scheme)) {
            // Try DATA column first.
            try (Cursor c = getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DATA},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                    if (idx >= 0) {
                        String p = c.getString(idx);
                        if (p != null) {
                            File f = new File(p);
                            if (f.exists() && f.canRead()) return f;
                        }
                    }
                }
            } catch (Exception ignored) { }

            // Fall back to copying into the cache.
            String name = queryDisplayName(uri);
            if (name == null) {
                name = "incoming_" + System.currentTimeMillis();
            }
            File cache = new File(getCacheDir(), "router");
            if (!cache.exists() && !cache.mkdirs()) return null;
            File out = new File(cache, name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(out)) {
                if (in == null) return null;
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            } catch (Exception e) {
                return null;
            }
            return out;
        }

        return null;
    }

    @Nullable
    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void openOne(File file, String hintMime) {
        String ext = extensionOf(file.getName());
        String mime = hintMime;
        if (mime == null) mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext);

        // ---- PDF ----
        if ("pdf".equals(ext) || "application/pdf".equals(mime)) {
            Intent i = new Intent().setComponent(new ComponentName(this, CLS_PDF));
            i.putExtra(EXTRA_PDF_URI, file.getAbsolutePath());
            i.putExtra(EXTRA_PDF_NAME, file.getName());
            startActivity(i);
            return;
        }

        // ---- DOCX / DOCM / DOC ----
        if ("docx".equals(ext) || "docm".equals(ext) || "doc".equals(ext)) {
            Intent i = new Intent().setComponent(new ComponentName(this, CLS_DOCX));
            i.putExtra(EXTRA_DOCX_PATH, file.getAbsolutePath());
            startActivity(i);
            return;
        }

        // ---- Images & videos -> Gallery ----
        if (isImageExt(ext) || isVideoExt(ext)
                || (mime != null && (mime.startsWith("image/")
                || mime.startsWith("video/")))) {
            Intent i = new Intent().setComponent(new ComponentName(this, CLS_GALLERY));
            i.setAction(Intent.ACTION_VIEW);
            i.setData(uriFor(file));
            startActivity(i);
            return;
        }

        // ---- Audio -> MusicPlayerActivity ----
        if (isAudioExt(ext)
                || (mime != null && mime.startsWith("audio/"))) {
            Intent i = new Intent().setComponent(new ComponentName(this, CLS_MUSIC));
            i.setAction(Intent.ACTION_VIEW);
            i.setData(uriFor(file));
            startActivity(i);
            return;
        }

        // ---- APK -> FileManager (installer prompt) ----
        if ("apk".equals(ext)) {
            Intent i = new Intent().setComponent(new ComponentName(this, CLS_FILE));
            i.putExtra("open_path", file.getAbsolutePath());
            startActivity(i);
            return;
        }

        // ---- Text-like -> FileManager editor ----
        if (isTextLikeExt(ext)
                || (mime != null && (mime.startsWith("text/")
                || "application/json".equals(mime)
                || "application/xml".equals(mime)))) {
            Intent i = new Intent().setComponent(new ComponentName(this, CLS_FILE));
            i.putExtra("open_path", file.getAbsolutePath());
            startActivity(i);
            return;
        }

        // ---- Anything else -> FileManager generic open ----
        Intent i = new Intent().setComponent(new ComponentName(this, CLS_FILE));
        i.putExtra("open_path", file.getAbsolutePath());
        startActivity(i);
    }

    private Uri uriFor(File f) {
        return Uri.fromFile(f);
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static boolean isImageExt(String e) {
        switch (e) {
            case "jpg": case "jpeg": case "png": case "gif":
            case "bmp": case "webp": case "heic": case "heif":
            case "svg": return true;
            default: return false;
        }
    }

    private static boolean isVideoExt(String e) {
        switch (e) {
            case "mp4": case "mkv": case "webm": case "avi":
            case "mov": case "3gp": case "m4v": case "flv":
            case "wmv": return true;
            default: return false;
        }
    }

    private static boolean isAudioExt(String e) {
        switch (e) {
            case "mp3": case "m4a": case "aac": case "ogg":
            case "oga": case "opus": case "wav": case "flac":
            case "amr": case "mid": case "midi": case "wma":
            case "mp2": case "mka": return true;
            default: return false;
        }
    }

    private static boolean isTextLikeExt(String e) {
        switch (e) {
            case "txt": case "log": case "md": case "json":
            case "xml": case "html": case "htm": case "css":
            case "js": case "py": case "java": case "kt":
            case "c": case "cpp": case "h": case "sh":
            case "bat": case "properties": case "config":
            case "cfg": case "conf": case "ini": case "yml":
            case "yaml": case "csv": case "tsv": case "sql":
            case "env": case "gradle": case "pro": case "toml":
            case "php": case "xhtml": case "db": case "cs":
            case "rb": case "go": case "rs": case "swift": return true;
            default: return false;
        }
    }
}