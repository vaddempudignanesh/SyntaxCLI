package vaddempudi.gnanesh.syntaxcli.browser;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts, sanitizes, and hard-truncates a download filename.
 *
 * Handles:
 *   • Content-Disposition with BOTH filename= and filename*=UTF-8''
 *   • RFC 5987 percent-encoding
 *   • Null bytes, control chars, Windows-illegal chars
 *   • Directory separators
 *   • The 255-byte ext4 / 255-char FAT limit — truncates the *base* name,
 *     preserving the extension.
 */
public final class SafeFileName {

    // Safety margin below the 255-byte ext4 limit. Some Android FS also
    // add the ".state" suffix on top, so leave room for that too.
    private static final int MAX_FILENAME_LEN = 120;

    // Matches  filename*=UTF-8''...  (RFC 5987) — captured FIRST
    private static final Pattern RFC5987 =
            Pattern.compile("filename\\*\\s*=\\s*([^;]+)", Pattern.CASE_INSENSITIVE);

    // Matches  filename="..."  or  filename=...
    private static final Pattern PLAIN =
            Pattern.compile("filename\\s*=\\s*(\"([^\"]*)\"|([^;]+))", Pattern.CASE_INSENSITIVE);

    private SafeFileName() {}

    /**
     * @param url      source URL (used to derive a fallback name)
     * @param cdHeader the raw Content-Disposition value, may be null
     * @param mime     MIME type, may be null — used to pick an extension
     * @return a safe, short, sanitized filename
     */
    public static String derive(String url, String cdHeader, String mime) {
        String name = fromContentDisposition(cdHeader);

        if (name == null || name.trim().isEmpty()) {
            name = fromUrl(url);
        }
        if (name == null || name.trim().isEmpty()) {
            name = "download_" + System.currentTimeMillis();
        }

        // Ensure extension from MIME if the name has none
        if (!hasExtension(name) && mime != null) {
            String ext = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mime);
            if (ext != null && !ext.isEmpty()) {
                name = name + "." + ext;
            }
        }

        name = sanitize(name);
        return truncatePreservingExtension(name);
    }

    // ────────────────────────────────────────────────────────────────

    private static String fromContentDisposition(String cd) {
        if (cd == null || cd.isEmpty()) return null;

        // 1. RFC 5987 form is authoritative (UTF-8 percent-encoded)
        Matcher m = RFC5987.matcher(cd);
        if (m.find()) {
            String raw = m.group(1).trim();
            // Format: charset'lang'encoded
            int q1 = raw.indexOf('\'');
            if (q1 >= 0) {
                int q2 = raw.indexOf('\'', q1 + 1);
                if (q2 >= 0) {
                    String encoded = raw.substring(q2 + 1);
                    try {
                        String decoded = java.net.URLDecoder.decode(encoded, "UTF-8");
                        if (decoded != null && !decoded.trim().isEmpty()) {
                            return decoded;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // 2. Plain quoted form
        m = PLAIN.matcher(cd);
        if (m.find()) {
            String quoted = m.group(2);
            String bare   = m.group(3);
            String v = (quoted != null && !quoted.isEmpty()) ? quoted
                    : (bare != null ? bare.trim() : null);
            if (v != null && !v.isEmpty()) {
                // Some servers percent-encode even the plain form
                if (v.contains("%")) {
                    try {
                        String dec = java.net.URLDecoder.decode(v, "UTF-8");
                        if (dec != null && !dec.isEmpty()) return dec;
                    } catch (Exception ignored) {}
                }
                return v;
            }
        }
        return null;
    }

    private static String fromUrl(String url) {
        if (url == null) return null;
        try {
            String path = url;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int hash = path.indexOf('#');
            if (hash >= 0) path = path.substring(0, hash);
            int slash = path.lastIndexOf('/');
            String name = (slash >= 0) ? path.substring(slash + 1) : path;
            try {
                name = java.net.URLDecoder.decode(name, "UTF-8");
            } catch (Exception ignored) {}
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasExtension(String name) {
        int dot = name.lastIndexOf('.');
        // Must have something after the dot and be reasonably short
        return dot > 0 && dot < name.length() - 1
                && name.length() - dot <= 6;
    }

    /** Replace path/control/illegal chars; collapse whitespace. */
    private static String sanitize(String name) {
        if (name == null) return "download";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) continue;                    // control
            switch (c) {
                case '\\': case '/': case ':': case '*':
                case '?': case '"': case '<': case '>': case '|':
                    sb.append('_');
                    break;
                default:
                    sb.append(c);
            }
        }
        String s = sb.toString().trim();
        // Collapse multiple underscores / spaces
        s = s.replaceAll("_+", "_");
        s = s.replaceAll("\\s+", " ");
        // Never allow a leading dot (hidden file)
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty()) s = "download_" + System.currentTimeMillis();
        return s;
    }

    /** Sanitize + truncate an already-derived name. */
    public static String sanitizeOnly(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "download_" + System.currentTimeMillis();
        }
        return truncatePreservingExtension(sanitize(name));
    }

    /** Truncate the basename while keeping the extension intact. */
    private static String truncatePreservingExtension(String name) {
        if (name.length() <= MAX_FILENAME_LEN) return name;

        int dot = name.lastIndexOf('.');
        String base, ext = "";
        if (dot > 0 && name.length() - dot <= 6) {
            base = name.substring(0, dot);
            ext  = name.substring(dot);
        } else {
            base = name;
        }

        int keep = MAX_FILENAME_LEN - ext.length();
        if (keep < 20) keep = 20;
        if (base.length() > keep) base = base.substring(0, keep);

        // Avoid trailing dot before extension
        while (base.endsWith(".")) base = base.substring(0, base.length() - 1);

        return base + ext;
    }
}