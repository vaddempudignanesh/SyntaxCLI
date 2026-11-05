package vaddempudi.gnanesh.syntaxcli.commands.main.raw;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Arrays;

import vaddempudi.gnanesh.syntaxcli.BuildConfig;
import vaddempudi.gnanesh.syntaxcli.commands.CommandAbstraction;
import vaddempudi.gnanesh.syntaxcli.commands.ExecutePack;
import vaddempudi.gnanesh.syntaxcli.commands.main.MainPack;
import vaddempudi.gnanesh.syntaxcli.tuils.Tuils;

public class k implements CommandAbstraction {

    private static final String TAG = "SYNTAXCLI_K";
    public static final String BANNER = "[k] dev-only — APK installer\n";

    /** Normal single-APK folder. */
    public static final String APK_DIR_NORMAL = "/storage/8B2E-101F/apks";

    /** Split-set root (p / w shortcuts). */
    public static final String APK_DIR_PW = "/storage/8B2E-101F/pw-apk";

    @Override
    public String exec(ExecutePack pack) {
        MainPack info = (MainPack) pack;
        if (info == null || info.context == null) return "[k] no context";

        StringBuilder log = new StringBuilder(BANNER);

        // ---- 1. Normalise argument ----------------------------------
        String raw = info.getString();
        if (raw == null) raw = "";
        raw = raw.trim();
        if (raw.equals("k") || raw.equals("k ")) raw = "";
        else if (raw.startsWith("k ")) raw = raw.substring(2).trim();

        log.append("raw arg: '").append(raw).append("'\n");

        if (raw.isEmpty()) {
            log.append("usage: k <apkName>\n");
            log.append("       k p    (phonepe split-set)\n");
            log.append("       k w    (whatsapp split-set)\n");
            log.append("normal dir : ").append(APK_DIR_NORMAL).append('\n');
            log.append("split  dir : ").append(APK_DIR_PW);
            devLog(info.context, log.toString());
            return log.toString();
        }

        String first = raw.toLowerCase().split("\\s+")[0];
        Context ctx = info.context;

        // ---- 2. p / w → split-APK install ---------------------------
        if (first.equals("p") || first.equals("w")) {
            String folder = first.equals("p") ? "phonepe_apks" : "whatsapp";
            log.append("shortcut '").append(first).append("' -> ").append(folder).append('\n');
            String out = installSplitSet(ctx, folder, log);
            devLog(ctx, log.toString());
            return out;
        }

        // ---- 3. Anything else → normal single-APK install -----------
        String out = installSingle(ctx, first, log);
        devLog(ctx, log.toString());
        return out;
    }

    // ==================================================================
    // p / w — split-set via PackageInstaller.Session
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
                && f.getName().toLowerCase().endsWith(".apk"));
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
                    byte[] buf = new byte[64 * 1024];
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
    // Normal single-APK install (your original working path)
    // ==================================================================
    private String installSingle(Context ctx, String q, StringBuilder log) {
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
        log.append("files in dir: ").append(files.length).append('\n');

        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String f : files) {
            if (!f.toLowerCase().endsWith(".apk")) continue;
            String stem = f.substring(0, f.length() - 4).toLowerCase();
            int score = score(q, stem);
            log.append("  candidate ").append(f).append(" score=").append(score).append('\n');
            if (score < bestScore) { bestScore = score; best = f; }
        }

        if (best == null) {
            return log.append("no .apk files in ").append(APK_DIR_NORMAL).toString();
        }

        int maxAllowed = Math.max(3, q.length());
        if (bestScore > maxAllowed) {
            log.append("no good match for '").append(q)
                    .append("' (bestScore=").append(bestScore)
                    .append(", maxAllowed=").append(maxAllowed)
                    .append(")\nclosest: ").append(APK_DIR_NORMAL).append('/').append(best);
            return log.toString();
        }

        File apk = new File(dir, best);
        log.append("chosen: ").append(apk.getAbsolutePath())
                .append(" exists=").append(apk.exists())
                .append(" size=").append(apk.length()).append('\n');

        if (!apk.exists() || apk.length() == 0) {
            return log.append("matched ").append(best)
                    .append(" but file is missing or empty").toString();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean canInstall = ctx.getPackageManager().canRequestPackageInstalls();
            log.append("canRequestPackageInstalls=").append(canInstall).append('\n');
            if (!canInstall) {
                openUnknownSourcesSettings(ctx, log);
                return log.append("allow 'Install unknown apps' then retry\n")
                        .append(best).toString();
            }
        }

        try {
            Uri uri = FileProvider.getUriForFile(
                    ctx,
                    BuildConfig.APPLICATION_ID + ".fileprovider",
                    apk);

            log.append("fileprovider uri: ").append(uri).append('\n');

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

    private static void devLog(Context ctx, String msg) {
        android.util.Log.d(TAG, msg);
        try {
            File out = new File(ctx.getFilesDir(), "k_debug.log");
            java.io.FileWriter fw = new java.io.FileWriter(out, true);
            fw.write("----- " + new java.util.Date() + " -----\n");
            fw.write(msg);
            fw.write("\n\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    // ---- fuzzy scoring (unchanged from your original working version) --
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
            if (s.charAt(si) == s.charAt(qi) || s.charAt(si) == q.charAt(qi)) qi++;
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

    @Override public int[] argType() {
        return new int[] { CommandAbstraction.PLAIN_TEXT };
    }
    @Override public int priority() { return 1; }
    @Override public int helpRes() { return 0; }
    @Override public String onArgNotFound(ExecutePack pack, int index) { return null; }
    @Override public String onNotArgEnough(ExecutePack pack, int nArgs) { return null; }
}