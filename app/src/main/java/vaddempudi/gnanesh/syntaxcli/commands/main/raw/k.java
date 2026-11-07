package vaddempudi.gnanesh.syntaxcli.commands.main.raw;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import vaddempudi.gnanesh.syntaxcli.BuildConfig;
import vaddempudi.gnanesh.syntaxcli.UIManager;
import vaddempudi.gnanesh.syntaxcli.commands.CommandAbstraction;
import vaddempudi.gnanesh.syntaxcli.commands.ExecutePack;
import vaddempudi.gnanesh.syntaxcli.commands.main.MainPack;
import vaddempudi.gnanesh.syntaxcli.tuils.Tuils;

public class k implements CommandAbstraction {

    private static final String TAG = "SYNTAXCLI_K";
    public static final String BANNER = "[k] dev-only — APK installer / backup\n";

    public static final String APK_DIR_NORMAL = "/storage/8B2E-101F/apks";
    public static final String APK_DIR_PW = "/storage/8B2E-101F/pw-apk";

    private static final int BUFFER = 64 * 1024;

    @Override
    public String exec(ExecutePack pack) {
        MainPack info = (MainPack) pack;
        if (info == null || info.context == null) return "[k] no context";

        String raw = info.getString();
        if (raw == null) raw = "";
        raw = raw.trim();
        if (raw.equals("k")) raw = "";
        else if (raw.startsWith("k ")) raw = raw.substring(2).trim();

        Context ctx = info.context;

        if (raw.isEmpty()) return usage();

        if (raw.equals("+") || raw.startsWith("+ ")) {
            String pkg = raw.equals("+") ? "" : raw.substring(2).trim();
            if (pkg.isEmpty()) return "[k] usage: k + <packageName or fragment>";
            final String userArg = pkg;
            new Thread(() -> doBackup(ctx, userArg), "k-backup").start();
            return "[k] backing up '" + userArg + "'  (progress: home screen / k_debug.log)";
        }

        if (raw.startsWith("+i ")) {
            String name = raw.substring(3).trim();
            if (name.isEmpty()) return "[k] usage: k +i <folderName>";
            return doInstallFromFolder(ctx, name);
        }

        if (raw.equals("+l") || raw.startsWith("+l ")) {
            return listBackups();
        }

        String first = raw.toLowerCase(Locale.US).split("\\s+")[0];
        return installSingle(ctx, first);
    }

    // ==================================================================
    // k + <arg>  —  fuzzy resolve, then copy EVERY apk from pm path
    // ==================================================================
    private void doBackup(Context ctx, String userArg) {
        StringBuilder log = new StringBuilder();
        log.append("[k] backup '").append(userArg).append("'\n");

        broadcastVia(ctx, "… resolving package: " + userArg);
        String packageName = resolvePackage(ctx, userArg);
        if (packageName == null) {
            String msg = "✗ no package matching '" + userArg + "'";
            log.append(msg).append('\n');
            devLog(log.toString());
            broadcastVia(ctx, msg);
            return;
        }
        log.append("resolved '").append(userArg).append("' → ").append(packageName).append('\n');

        String label = safeLabel(ctx.getPackageManager(), packageName);
        String folder = folderForLabelOrPackage(label, packageName);
        String destPath = APK_DIR_PW + "/" + folder;
        broadcastVia(ctx, "✓ matched " + packageName
                + (label != null && !label.isEmpty() ? "  (" + label + ")" : "")
                + "  →  folder " + folder);

        broadcastVia(ctx, "… reading apk list via pm path");
        List<String> paths = queryPackageApks(packageName);
        if (paths.isEmpty()) {
            String msg = "✗ pm path returned nothing for " + packageName;
            log.append(msg).append('\n');
            devLog(log.toString());
            broadcastVia(ctx, msg);
            return;
        }
        log.append("pm path → ").append(paths.size()).append(" apk(s)\n");
        broadcastVia(ctx, "✓ found " + paths.size() + " apk(s) on disk");

        // ★ Copy EVERY apk. No ABI / language / density filter.
        //   Split install fails if any split the package expects is missing.
        List<File> toCopy = new ArrayList<>();
        long totalBytes = 0L;
        int missing = 0;
        for (String p : paths) {
            File f = new File(p);
            if (!f.exists() || !f.isFile()) {
                missing++;
                log.append("  missing ").append(p).append('\n');
                continue;
            }
            toCopy.add(f);
            totalBytes += f.length();
            log.append("  keep ").append(f.getName())
                    .append(" (").append(f.length()).append(" B)\n");
        }
        if (missing > 0) {
            broadcastVia(ctx, "… " + missing + " apk(s) not readable, will skip");
        }

        if (toCopy.isEmpty()) {
            String msg = "✗ nothing readable to copy for " + packageName;
            log.append(msg).append('\n');
            devLog(log.toString());
            broadcastVia(ctx, msg);
            return;
        }

        Collections.sort(toCopy, (a, b) -> {
            boolean ab = a.getName().equalsIgnoreCase("base.apk");
            boolean bb = b.getName().equalsIgnoreCase("base.apk");
            if (ab && !bb) return -1;
            if (!ab && bb) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        File destDir = new File(destPath);
        if (!destDir.exists() && !destDir.mkdirs()) {
            String msg = "✗ cannot create " + destPath;
            log.append(msg).append('\n');
            devLog(log.toString());
            broadcastVia(ctx, msg);
            return;
        }
        broadcastVia(ctx, "… writing to " + destPath
                + "  (" + toCopy.size() + " apk, " + human(totalBytes) + ")");

        long done = 0L;
        long startMs = System.currentTimeMillis();
        final long[] lastPostMs = {0L};
        int okCount = 0, skipExisting = 0, failCount = 0;

        for (File src : toCopy) {
            File dst = new File(destDir, src.getName());

            if (dst.exists() && dst.length() == src.length()) {
                log.append("  skip-existing ").append(src.getName()).append('\n');
                done += src.length();
                skipExisting++;
                broadcastVia(ctx, "… " + src.getName()
                        + " already present, skipping  ("
                        + human(done) + " / " + human(totalBytes) + ")");
                continue;
            }

            broadcastVia(ctx, "… copying " + src.getName()
                    + "  (" + human(src.length()) + ")");
            log.append("  copy ").append(src.getName())
                    .append(" (").append(src.length()).append(" B)\n");

            final long finalDone = done;
            final String finalName = src.getName();
            final long finalFileLen = src.length();
            long finalTotalBytes = totalBytes;
            boolean ok = copyWithProgress(src, dst, new CopyProgress() {
                @Override public void onChunk(long bytesDone) {
                    long now = System.currentTimeMillis();
                    if (now - lastPostMs[0] < 250L) return;
                    lastPostMs[0] = now;
                    broadcastVia(ctx, "… " + finalName + " "
                            + human(bytesDone) + " / " + human(finalFileLen)
                            + "   (" + human(finalDone + bytesDone)
                            + " / " + human(finalTotalBytes) + ")");
                }
            });

            if (ok) {
                done += src.length();
                okCount++;
                broadcastVia(ctx, "✓ " + finalName + " done  ("
                        + human(done) + " / " + human(totalBytes) + ")");
            } else {
                failCount++;
                log.append("  FAILED ").append(src.getName()).append('\n');
                broadcastVia(ctx, "✗ " + finalName + " failed");
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        String rate = elapsedMs > 0
                ? human(done * 1000L / elapsedMs) + "/s" : "n/a";

        writeManifest(destDir, packageName, toCopy);
        broadcastVia(ctx, "… manifest written");

        boolean allOk = failCount == 0 && missing == 0;
        String summary = (allOk ? "✓" : "✗") + " backup " + packageName
                + " → " + folder
                + "  —  " + okCount + " copied, "
                + skipExisting + " already present, "
                + failCount + " failed"
                + (missing > 0 ? ", " + missing + " unreadable" : "")
                + "  in " + (elapsedMs / 1000) + "s (" + rate + ")";
        log.append(summary).append('\n');
        devLog(log.toString());
        broadcastVia(ctx, summary);
    }

    /**
     * Resolves a user-typed fragment to a real package name, with high
     * accuracy against false positives.
     *
     * Scoring sources, best → worst:
     *   1. Full package name (e.g. "com.whatsapp") → cheap, used directly
     *   2. Application label (e.g. "Blinkit")       → authoritative
     *   3. Last package segment (e.g. "whatsapp")   → good default
     *   4. Whole package name (substring/levenshtein) → fallback
     *
     * Tie-breakers (applied at equal score):
     *   - user-installed apps beat system apps
     *   - shorter package names beat longer ones
     *
     * Rules that make "blinkit" resolve to Blinkit, not the "Blink"
     * system app:
     *   - The label pass runs BEFORE the package-name pass, and any
     *     exact label match short-circuits everything else.
     *   - System apps are penalised on tie.
     */
    private String resolvePackage(Context ctx, String userArg) {
        if (userArg == null) return null;
        String arg = userArg.trim();
        if (arg.isEmpty()) return null;

        // Fast path: caller already gave us a real package name.
        if (arg.indexOf('.') >= 0 && isValidPackageName(arg)
                && isInstalledPackage(ctx, arg)) {
            return arg;
        }

        List<String> packages = listAllPackages();
        if (packages.isEmpty()) return null;

        String q = arg.toLowerCase(Locale.US);
        final int maxAllowed = Math.max(3, q.length());

        String best = null;
        int bestScore = Integer.MAX_VALUE;
        boolean bestIsSystem = true;
        int bestNameLen = Integer.MAX_VALUE;

        android.content.pm.PackageManager pm = ctx.getPackageManager();

        for (String pkg : packages) {
            String lower = pkg.toLowerCase(Locale.US);

            // ── Score sources ───────────────────────────────────────
            int sFull = score(q, lower);
            String lastSeg = lower.substring(lower.lastIndexOf('.') + 1);
            int sLast = score(q, lastSeg);

            int sLabel = Integer.MAX_VALUE;
            String label = safeLabel(pm, pkg);
            if (label != null && !label.isEmpty()) {
                sLabel = score(q, label.toLowerCase(Locale.US));
            }

            // Prefer the best match across all sources.
            int s = Math.min(Math.min(sFull, sLast), sLabel);

            // ── Tie-breakers ────────────────────────────────────────
            boolean isSystem = isSystemApp(pm, pkg);

            boolean better;
            if (s < bestScore) better = true;
            else if (s > bestScore) better = false;
            else {
                // Equal score. Prefer user app over system, then shorter name.
                if (bestIsSystem && !isSystem) better = true;
                else if (!bestIsSystem && isSystem) better = false;
                else better = lower.length() < bestNameLen;
            }

            if (better) {
                bestScore = s;
                best = pkg;
                bestIsSystem = isSystem;
                bestNameLen = lower.length();
            }
        }

        return bestScore <= maxAllowed ? best : null;
    }

    private boolean isInstalledPackage(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String safeLabel(android.content.pm.PackageManager pm, String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            return cs != null ? cs.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSystemApp(android.content.pm.PackageManager pm, String pkg) {
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            int mask = android.content.pm.ApplicationInfo.FLAG_SYSTEM
                    | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            return (ai.flags & mask) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> listAllPackages() {
        List<String> out = new ArrayList<>();
        Process p = null;
        BufferedReader r = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"pm", "list", "packages"});
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("package:")) out.add(line.substring("package:".length()));
            }
            try { p.waitFor(); } catch (InterruptedException ignored) {}
        } catch (Exception e) {
            devLog("[k] pm list packages failed: " + e);
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
        return out;
    }

    private List<String> queryPackageApks(String packageName) {
        List<String> out = new ArrayList<>();
        Process p = null;
        BufferedReader r = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"pm", "path", packageName});
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("package:")) out.add(line.substring("package:".length()));
            }
            try { p.waitFor(); } catch (InterruptedException ignored) {}
        } catch (Exception e) {
            devLog("[k] pm path failed: " + e);
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
        return out;
    }

    /**
     * Prefers the human-readable app label for the folder name
     * ("Blinkit" → "blinkit"), falling back to the package-derived name
     * ("customerapp") only when the label is empty or unresolvable.
     *
     * Collision safety: if a folder with the same name already exists
     * and its manifest.txt records a *different* package, we keep the
     * existing folder (the user already has a backup there) and reuse
     * it — this prevents two different packages from silently sharing
     * a folder just because they have the same label (e.g. two apps
     * called "Notes").
     */
    private static String folderForLabelOrPackage(String label, String packageName) {
        String fromLabel = sanitizeFolderName(label);
        String fromPkg = folderFor(packageName);

        if (fromLabel == null || fromLabel.isEmpty()) return fromPkg;

        // If a folder of that name already exists and is bound to a
        // DIFFERENT package, fall back to the package-derived name so
        // we don't mix two apps' apks in one folder.
        File candidate = new File(APK_DIR_PW, fromLabel);
        if (candidate.isDirectory()) {
            String existingPkg = readManifestPackageStatic(candidate);
            if (existingPkg != null
                    && !existingPkg.equalsIgnoreCase(packageName)) {
                return fromPkg;
            }
        }
        return fromLabel;
    }

    /** Lowercase, strip everything except [a-z0-9_-]. */
    private static String sanitizeFolderName(String label) {
        if (label == null) return null;
        String lower = label.trim().toLowerCase(Locale.US);
        if (lower.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(lower.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (ok) {
                sb.append(c);
                lastUnderscore = (c == '_' || c == '-');
            } else if (!lastUnderscore) {
                // Replace runs of non-alnum with a single underscore.
                sb.append('_');
                lastUnderscore = true;
            }
        }
        // Trim trailing underscores.
        while (sb.length() > 0 && (sb.charAt(sb.length() - 1) == '_'
                || sb.charAt(sb.length() - 1) == '-')) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** Static variant of readManifestPackage used above. */
    private static String readManifestPackageStatic(File dir) {
        File mf = new File(dir, "manifest.txt");
        if (!mf.exists()) return null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(mf), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("package=")) return line.substring(8).trim();
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    // ---- folderFor / isValidPackageName / densityBucket unchanged ----
    private static final Set<String> GENERIC_SEGMENTS = new HashSet<>(Arrays.asList(
            "android", "app", "apps", "application", "mobile", "client",
            "release", "prod", "production", "debug", "dev",
            "main", "phone", "tablet", "lite", "free", "paid", "pro",
            "beta", "canary", "nightly",
            "music", "video", "player", "audio", "weather", "mail",
            "chat", "messenger", "browser", "camera", "gallery",
            "notes", "note", "calc", "calculator", "calendar", "clock",
            "launcher", "home", "settings", "filemanager", "fileexplorer",
            "reader", "viewer", "editor", "storage", "photos", "photo",
            "gallery2", "maps2"
    ));

    private static final Set<String> TLD_SEGMENTS = new HashSet<>(Arrays.asList(
            "com", "org", "net", "io", "me", "co", "dev", "app",
            "in", "us", "uk", "de", "fr", "it", "es", "ru", "jp",
            "cn", "br", "au", "ca", "mx", "nl", "se", "no", "fi",
            "info", "biz", "xyz", "edu", "gov", "ac", "ai"
    ));

    private static String folderFor(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "app";
        String[] raw = packageName.split("\\.");
        if (raw.length == 0) return "app";

        List<String> segs = new ArrayList<>();
        for (int i = 0; i < raw.length; i++) {
            String s = raw[i].toLowerCase(Locale.US);
            if (s.isEmpty()) continue;
            if (i == 0 && TLD_SEGMENTS.contains(s)) continue;
            segs.add(s);
        }
        if (segs.isEmpty()) return packageName.replace('.', '_').toLowerCase(Locale.US);

        List<String> nonGeneric = new ArrayList<>();
        for (String s : segs) if (!GENERIC_SEGMENTS.contains(s)) nonGeneric.add(s);

        if (nonGeneric.isEmpty()) return segs.get(segs.size() - 1);

        String pick = nonGeneric.get(nonGeneric.size() - 1);
        if (GENERIC_SEGMENTS.contains(pick) && nonGeneric.size() >= 2) {
            pick = nonGeneric.get(nonGeneric.size() - 2);
        }

        StringBuilder sb = new StringBuilder(pick.length());
        for (int i = 0; i < pick.length(); i++) {
            char c = pick.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
        }
        String out = sb.toString();
        return out.isEmpty() ? "app" : out;
    }

    private static boolean isValidPackageName(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isLetter(s.charAt(0))) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.')) return false;
        }
        return true;
    }

    private static String densityBucket() {
        int dpi = 0;
        try {
            dpi = android.content.res.Resources.getSystem().getDisplayMetrics().densityDpi;
        } catch (Exception ignored) {}
        if (dpi <= 0) return "xxhdpi";
        if (dpi <= 140) return "ldpi";
        if (dpi <= 200) return "mdpi";
        if (dpi <= 280) return "hdpi";
        if (dpi <= 400) return "xhdpi";
        if (dpi <= 560) return "xxhdpi";
        return "xxxhdpi";
    }

    private void writeManifest(File destDir, String packageName, List<File> files) {
        try {
            File mf = new File(destDir, "manifest.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("package=").append(packageName).append('\n');
            sb.append("created=").append(new java.util.Date()).append('\n');
            for (File f : files) {
                sb.append(f.getName()).append('=').append(f.length()).append('\n');
            }
            FileOutputStream fos = new FileOutputStream(mf, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }

    // ==================================================================
    // k +i <folder>  —  install
    // ==================================================================
    private String doInstallFromFolder(Context ctx, String name) {
        String folder = resolveFolder(name);
        if (folder == null) {
            return "[k] no backup folder matching '" + name + "'\n"
                    + "available: " + availableFolders();
        }
        StringBuilder log = new StringBuilder();
        log.append("[k] install from pw-apk/").append(folder).append('\n');
        return installSplitSet(ctx, folder, log);
    }

    private String resolveFolder(String name) {
        File root = new File(APK_DIR_PW);
        if (!root.isDirectory()) return null;
        String[] dirs = root.list((d, n) -> new File(d, n).isDirectory());
        if (dirs == null || dirs.length == 0) return null;

        String lower = name.toLowerCase(Locale.US);
        for (String d : dirs) if (d.equalsIgnoreCase(lower)) return d;

        int bestScore = Integer.MAX_VALUE;
        String best = null;
        for (String d : dirs) {
            int s = score(lower, d.toLowerCase(Locale.US));
            if (s < bestScore) { bestScore = s; best = d; }
        }
        int maxAllowed = Math.max(3, lower.length());
        return bestScore <= maxAllowed ? best : null;
    }

    private String availableFolders() {
        File root = new File(APK_DIR_PW);
        if (!root.isDirectory()) return "(none)";
        String[] dirs = root.list((d, n) -> new File(d, n).isDirectory());
        if (dirs == null || dirs.length == 0) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (String d : dirs) sb.append(d).append(' ');
        return sb.toString().trim();
    }

    private String listBackups() {
        File root = new File(APK_DIR_PW);
        if (!root.isDirectory()) return "[k] no backups at " + APK_DIR_PW;
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return "[k] no backups";

        Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        StringBuilder sb = new StringBuilder("[k] backups in " + APK_DIR_PW + "\n");
        for (File d : dirs) {
            File[] apks = d.listFiles(f -> f.isFile()
                    && f.getName().toLowerCase(Locale.US).endsWith(".apk"));
            long bytes = 0L; int n = 0;
            if (apks != null) { for (File f : apks) { bytes += f.length(); n++; } }
            String pkg = readManifestPackage(d);
            sb.append("  ").append(d.getName())
                    .append("  ").append(n).append(" apk, ").append(human(bytes));
            if (pkg != null) sb.append("  pkg=").append(pkg);
            sb.append('\n');
        }
        return sb.toString();
    }

    private String readManifestPackage(File dir) {
        File mf = new File(dir, "manifest.txt");
        if (!mf.exists()) return null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(mf), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("package=")) return line.substring(8).trim();
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    // ==================================================================
    // installSplitSet — unchanged
    // ==================================================================
    private String installSplitSet(Context ctx, String folder, StringBuilder log) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
            openUnknownSourcesSettings(ctx, log);
            return log.append("allow 'Install unknown apps' then retry").toString();
        }

        File dir = new File(APK_DIR_PW, folder);
        log.append("dir=").append(dir.getAbsolutePath())
                .append(" exists=").append(dir.exists())
                .append(" isDir=").append(dir.isDirectory())
                .append(" canRead=").append(dir.canRead()).append('\n');

        if (!dir.isDirectory()) {
            return log.append("not a directory: ").append(dir.getAbsolutePath()).toString();
        }

        File[] apks = dir.listFiles(f -> f.isFile()
                && f.getName().toLowerCase(Locale.US).endsWith(".apk"));
        if (apks == null || apks.length == 0) {
            return log.append("no .apk files in ").append(dir.getAbsolutePath()).toString();
        }

        Arrays.sort(apks, (a, b) -> {
            boolean ab = a.getName().equalsIgnoreCase("base.apk");
            boolean bb = b.getName().equalsIgnoreCase("base.apk");
            if (ab && !bb) return -1;
            if (!ab && bb) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        log.append("found ").append(apks.length).append(" apks:\n");
        for (File f : apks)
            log.append("  ").append(f.getName())
                    .append("  (").append(f.length()).append(" bytes)\n");

        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        long totalSize = 0;
        for (File f : apks) totalSize += f.length();

        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        try { params.setSize(totalSize); } catch (Throwable ignored) {}

        int sessionId;
        try {
            sessionId = pi.createSession(params);
        } catch (Throwable t) {
            return log.append("createSession failed: ").append(t).toString();
        }

        try (PackageInstaller.Session session = pi.openSession(sessionId)) {
            for (File apk : apks) {
                try (FileInputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite(apk.getName(), 0, apk.length())) {
                    byte[] buf = new byte[BUFFER];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                } catch (Throwable t) {
                    session.abandon();
                    return log.append("write ").append(apk.getName())
                            .append(" failed: ").append(t).toString();
                }
            }

            Intent callback = new Intent(BuildConfig.APPLICATION_ID + ".INSTALL_RESULT");
            callback.setPackage(ctx.getPackageName());

            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                flags |= android.app.PendingIntent.FLAG_MUTABLE;
            android.app.PendingIntent piIntent = android.app.PendingIntent.getBroadcast(
                    ctx, sessionId, callback, flags);

            session.commit(piIntent.getIntentSender());

            return log.append("commit sent for session ").append(sessionId)
                    .append(" (").append(apks.length).append(" apks)")
                    .toString();
        } catch (Throwable t) {
            return log.append("session failed: ").append(t).toString();
        }
    }

    // ==================================================================
    // installSingle — unchanged
    // ==================================================================
    private String installSingle(Context ctx, String q) {
        StringBuilder log = new StringBuilder(BANNER);
        File dir = new File(APK_DIR_NORMAL);
        log.append("dir=").append(dir.getAbsolutePath())
                .append(" exists=").append(dir.exists())
                .append(" isDir=").append(dir.isDirectory())
                .append(" canRead=").append(dir.canRead()).append('\n');

        if (!dir.isDirectory()) {
            return log.append("SD-card apk directory not accessible: ")
                    .append(APK_DIR_NORMAL).toString();
        }

        String[] files = dir.list();
        if (files == null || files.length == 0) {
            return log.append("no files in ").append(APK_DIR_NORMAL).toString();
        }

        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String f : files) {
            if (!f.toLowerCase(Locale.US).endsWith(".apk")) continue;
            String stem = f.substring(0, f.length() - 4).toLowerCase(Locale.US);
            int score = score(q, stem);
            if (score < bestScore) { bestScore = score; best = f; }
        }

        if (best == null) {
            return log.append("no .apk files in ").append(APK_DIR_NORMAL).toString();
        }

        int maxAllowed = Math.max(3, q.length());
        if (bestScore > maxAllowed) {
            return log.append("no good match for '").append(q)
                    .append("' (bestScore=").append(bestScore)
                    .append(", maxAllowed=").append(maxAllowed)
                    .append(")\nclosest: ").append(APK_DIR_NORMAL).append('/').append(best)
                    .toString();
        }

        File apk = new File(dir, best);
        if (!apk.exists() || apk.length() == 0) {
            return log.append("matched ").append(best)
                    .append(" but file is missing or empty").toString();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                openUnknownSourcesSettings(ctx, log);
                return log.append("allow 'Install unknown apps' then retry\n")
                        .append(best).toString();
            }
        }

        try {
            Uri uri = FileProvider.getUriForFile(
                    ctx, BuildConfig.APPLICATION_ID + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (i.resolveActivity(ctx.getPackageManager()) == null) {
                return log.append("no package installer activity resolved for ACTION_VIEW")
                        .toString();
            }
            ctx.startActivity(i);
            log.append("install intent started for ").append(best);
        } catch (Throwable t) {
            log.append("install failed: ").append(t);
            Tuils.log(t);
            Tuils.toFile(t);
        }
        return log.toString();
    }

    // ==================================================================
    // helpers
    // ==================================================================
    private interface CopyProgress {
        void onChunk(long bytesCopiedSoFarInCurrentFile);
    }

    private static boolean copyWithProgress(File src, File dst, CopyProgress cb) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new java.io.BufferedInputStream(new FileInputStream(src), BUFFER);
            out = new java.io.BufferedOutputStream(new FileOutputStream(dst), BUFFER);
            byte[] buf = new byte[BUFFER];
            long copied = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                copied += n;
                if (cb != null) cb.onChunk(copied);
            }
            out.flush();
            return dst.length() == src.length();
        } catch (Throwable t) {
            Tuils.log(t);
            try { dst.delete(); } catch (Throwable ignored) {}
            return false;
        } finally {
            if (in  != null) try { in.close();  } catch (Throwable ignored) {}
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L)
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static void openUnknownSourcesSettings(Context ctx, StringBuilder log) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            log.append("settings launch failed: ").append(t).append('\n');
        }
    }

    private void broadcastVia(Context ctx, String msg) {
        try {
            Intent i = new Intent(UIManager.ACTION_K_PROGRESS);
            i.putExtra(UIManager.EXTRA_K_PROGRESS, msg);
            LocalBroadcastManager.getInstance(ctx.getApplicationContext()).sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    private static void devLog(String msg) {
        android.util.Log.d(TAG, msg);
        try {
            File out = new File("/storage/8B2E-101F/k_debug.log");
            if (!out.getParentFile().exists()) out.getParentFile().mkdirs();
            java.io.FileWriter fw = new java.io.FileWriter(out, true);
            fw.write("----- " + new java.util.Date() + " -----\n");
            fw.write(msg);
            fw.write("\n\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    private static String usage() {
        StringBuilder sb = new StringBuilder(BANNER);
        sb.append("  k <name>          install single APK from ").append(APK_DIR_NORMAL).append('\n');
        sb.append("  k + <package>     back up base + every split from pm path\n");
        sb.append("                    to ").append(APK_DIR_PW).append("/<folder>/\n");
        sb.append("  k +i <folder>     install split-set from ").append(APK_DIR_PW).append("/<folder>/\n");
        sb.append("  k +l              list existing backups\n");
        return sb.toString();
    }

    private static int score(String q, String s) {
        if (s.equals(q)) return 0;
        if (s.startsWith(q)) return 1;
        if (s.contains(q)) return 2;
        if (isSubsequence(q, s)) return 2;
        String candidate = s.length() > q.length() ? s.substring(0, q.length()) : s;
        return 3 + levenshtein(q, candidate);
    }

    private static boolean isSubsequence(String q, String s) {
        int qi = 0;
        for (int si = 0; si < s.length() && qi < q.length(); si++)
            if (s.charAt(si) == q.charAt(qi)) qi++;
        return qi == q.length();
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur  = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    @Override public int[] argType() { return new int[] { CommandAbstraction.PLAIN_TEXT }; }
    @Override public int priority() { return 1; }
    @Override public int helpRes() { return 0; }
    @Override public String onArgNotFound(ExecutePack pack, int index) { return null; }
    @Override public String onNotArgEnough(ExecutePack pack, int nArgs) { return null; }
}