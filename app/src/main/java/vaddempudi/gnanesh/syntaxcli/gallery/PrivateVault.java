package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Private folder storage.
 *
 * Credential model:
 *   - Password is hashed with PBKDF2-HMAC-SHA256, 100,000 iterations, 16-byte salt.
 *   - Storage format: "salt_hex:hash_hex" (both lowercase hex).
 *   - SharedPreferences is tried first. If missing or malformed, the file at
 *     /storage/emulated/0/t-ui/private_password.txt is read. If the file is
 *     valid and the prefs entry is not, the file value is written back into
 *     prefs so subsequent reads use the fast path.
 *
 * File model:
 *   - All private media lives in getFilesDir()/private/.
 *   - Naming: "p_<epoch_ms>_<sanitized-original-name>". Keeps the extension
 *     (needed for the video/image type detection) and makes files unique.
 *   - Files are COPIED in, then the source is deleted. renameTo() is not
 *     used because /storage/emulated/0 and /data/data are different
 *     filesystems and cross-device renameTo fails silently.
 */
public final class PrivateVault {

    private static final String TAG = "PrivateVault";
    private static final String PREFS = "private_vault";
    private static final String K_HASH = "private_pw_hash";
    private static final String K_SALT = "private_pw_salt";
    private static final String FILE_DIR = "t-ui";
    private static final String FILE_NAME = "private_password.txt";

    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private final Context appCtx;
    private final SharedPreferences prefs;
    private final File privateDir;

    public PrivateVault(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.privateDir = new File(appCtx.getFilesDir(), "private");
        if (!privateDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            privateDir.mkdirs();
        }
    }

    // ── Password ─────────────────────────────────────────────────────

    public boolean hasPassword() {
        return loadStoredPassword() != null;
    }

    /**
     * Creates the password. Returns false if a password already exists
     * (use {@link #changePassword(String, String)} for that path).
     */
    public boolean createPassword(String plain) {
        if (plain == null || plain.isEmpty()) return false;
        if (hasPassword()) return false;
        return writePassword(plain);
    }

    /** Verifies a password against the stored hash. */
    public boolean verifyPassword(String plain) {
        if (plain == null || plain.isEmpty()) return false;
        Stored s = loadStoredPassword();
        if (s == null) return false;
        byte[] candidate = pbkdf2(plain, s.salt, s.hash.length * 8);
        if (candidate == null) return false;
        return constantTimeEquals(candidate, s.hash);
    }

    /** Changes the password. Requires the old one. */
    public boolean changePassword(String oldPlain, String newPlain) {
        if (!verifyPassword(oldPlain)) return false;
        if (newPlain == null || newPlain.isEmpty()) return false;
        return writePassword(newPlain);
    }

    private boolean writePassword(String plain) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(plain, salt, PBKDF2_KEY_BITS);
        if (hash == null) return false;

        String saltHex = toHex(salt);
        String hashHex = toHex(hash);

        // 1) SharedPreferences (primary).
        boolean prefsOk = prefs.edit()
                .putString(K_SALT, saltHex)
                .putString(K_HASH, hashHex)
                .commit();

        // 2) File (fallback / backup). Same format "salt:hash".
        boolean fileOk = writePasswordFile(saltHex + ":" + hashHex);

        return prefsOk || fileOk;
    }

    private boolean writePasswordFile(String content) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), FILE_DIR);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File out = new File(dir, FILE_NAME);
            try (OutputStream os = new FileOutputStream(out)) {
                os.write(content.getBytes("UTF-8"));
                os.flush();
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not write password file", t);
            return false;
        }
    }

    private Stored loadStoredPassword() {
        // Fast path: prefs.
        String saltHex = prefs.getString(K_SALT, null);
        String hashHex = prefs.getString(K_HASH, null);
        Stored fromPrefs = parse(saltHex, hashHex);
        if (fromPrefs != null) return fromPrefs;

        // Fallback: file.
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), FILE_DIR);
            File in = new File(dir, FILE_NAME);
            if (!in.exists()) return null;
            byte[] buf = new byte[(int) in.length()];
            try (InputStream is = new FileInputStream(in)) {
                int total = 0;
                while (total < buf.length) {
                    int n = is.read(buf, total, buf.length - total);
                    if (n <= 0) break;
                    total += n;
                }
            }
            String content = new String(buf, "UTF-8").trim();
            int colon = content.indexOf(':');
            if (colon <= 0 || colon >= content.length() - 1) return null;
            Stored fromFile = parse(
                    content.substring(0, colon),
                    content.substring(colon + 1));
            if (fromFile == null) return null;

            // Self-heal: file is valid but prefs was not. Write to prefs
            // so next launch takes the fast path.
            prefs.edit()
                    .putString(K_SALT, content.substring(0, colon))
                    .putString(K_HASH, content.substring(colon + 1))
                    .apply();
            return fromFile;
        } catch (Throwable t) {
            Log.w(TAG, "could not read password file", t);
            return null;
        }
    }

    private static Stored parse(String saltHex, String hashHex) {
        if (saltHex == null || hashHex == null) return null;
        try {
            byte[] salt = fromHex(saltHex);
            byte[] hash = fromHex(hashHex);
            if (salt == null || hash == null || salt.length != SALT_BYTES || hash.length == 0) {
                return null;
            }
            return new Stored(salt, hash);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Media moving ─────────────────────────────────────────────────

    /**
     * Copies {@code source} into the private directory under a unique name,
     * then deletes the source. Returns the new File, or null on failure.
     *
     * The extension is preserved so the type (image vs video) can still be
     * determined from the name. The original name is kept (sanitized) so
     * the user can recognise files in the private grid.
     */
    public File moveIntoPrivate(File source) {
        if (source == null || !source.exists() || !source.isFile()) return null;
        if (source.length() <= 0) return null;

        String name = source.getName();
        String sanitized = sanitizeName(name);
        File dest = new File(privateDir, "p_" + System.currentTimeMillis() + "_" + sanitized);

        // Avoid a collision (two moves in the same millisecond).
        int suffix = 1;
        while (dest.exists()) {
            dest = new File(privateDir, "p_" + System.currentTimeMillis()
                    + "_" + suffix + "_" + sanitized);
            suffix++;
        }

        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "copy failed for " + source, t);
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            return null;
        }

        // Only delete the source once the copy is verified.
        if (dest.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            return dest;
        }
        //noinspection ResultOfMethodCallIgnored
        dest.delete();
        return null;
    }

    /** Deletes a private file permanently. */
    public boolean deletePrivate(File f) {
        if (f == null) return false;
        return f.delete();
    }

    /** Lists the private files as GalleryMediaItem, newest first. */
    public List<GalleryMediaItem> listPrivateItems() {
        List<GalleryMediaItem> out = new ArrayList<>();
        File[] files = privateDir.listFiles();
        if (files == null) return out;

        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String lower = f.getName().toLowerCase(Locale.ROOT);
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

            // Strip the "p_<epoch>_" prefix for the display name.
            String display = f.getName();
            int firstUnderscore = display.indexOf('_');
            if (firstUnderscore >= 0) {
                int secondUnderscore = display.indexOf('_', firstUnderscore + 1);
                if (secondUnderscore > 0) display = display.substring(secondUnderscore + 1);
            }

            out.add(new GalleryMediaItem(
                    f.getAbsolutePath().hashCode(),
                    f.getAbsolutePath(),
                    display,
                    privateDir.getAbsolutePath(),
                    f.lastModified() / 1000L,
                    type));
        }
        Collections.sort(out, (a, b) -> {
            int c = Long.compare(b.lastModifiedMillis, a.lastModifiedMillis);
            if (c != 0) return c;
            return b.path.compareTo(a.path);
        });
        return out;
    }

    public File getPrivateDir() { return privateDir; }

    // ── helpers ─────────────────────────────────────────────────────

    private static String sanitizeName(String name) {
        // Strip any path separators and nulls; cap length.
        String s = name.replaceAll("[/\\\\\\x00]", "_");
        if (s.length() > 96) s = s.substring(0, 96);
        if (s.isEmpty()) s = "file";
        return s;
    }

    private static byte[] pbkdf2(String password, byte[] salt, int keyBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, keyBits);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format(Locale.US, "%02x", v));
        return sb.toString();
    }

    private static byte[] fromHex(String s) {
        if (s == null || (s.length() & 1) != 0) return null;
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            try {
                out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            } catch (Throwable t) {
                return null;
            }
        }
        return out;
    }

    private static final class Stored {
        final byte[] salt;
        final byte[] hash;
        Stored(byte[] salt, byte[] hash) { this.salt = salt; this.hash = hash; }
    }
}