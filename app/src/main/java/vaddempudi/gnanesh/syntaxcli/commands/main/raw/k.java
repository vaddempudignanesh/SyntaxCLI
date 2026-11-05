package vaddempudi.gnanesh.syntaxcli.commands.main.raw;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;

import vaddempudi.gnanesh.syntaxcli.commands.CommandAbstraction;
import vaddempudi.gnanesh.syntaxcli.commands.ExecutePack;
import vaddempudi.gnanesh.syntaxcli.commands.main.MainPack;

public class k implements CommandAbstraction {

    public static final String BANNER =
            "[k] developer-only feature — APK auto-installer\n";

    /** Hardcoded external SD-card path only. No internal fallback. */
    public static final String APK_DIR = "/storage/8B2E-101F/apks";

    @Override
    public String exec(ExecutePack pack) {
        MainPack info = (MainPack) pack;
        if (info == null || info.context == null) return null;

        String raw = info.getString();
        if (raw == null) raw = "";
        raw = raw.trim();
        if (raw.equals("k")) raw = "";
        else if (raw.startsWith("k ")) raw = raw.substring(2).trim();

        if (raw.isEmpty()) {
            return BANNER + "usage: k <apkName>\npath: " + APK_DIR;
        }

        Context ctx = info.context;

        File dir = new File(APK_DIR);
        if (!dir.isDirectory()) {
            return BANNER + "SD-card apk directory not accessible:\n  " + APK_DIR
                    + "\n(make sure the SD card is mounted and the folder exists)";
        }

        String[] files = dir.list();
        if (files == null || files.length == 0) {
            return BANNER + "no files in " + APK_DIR;
        }

        String q = raw.toLowerCase();
        int sp = q.indexOf(' ');
        if (sp != -1) q = q.substring(0, sp);

        String best = null;
        int bestScore = Integer.MAX_VALUE;

        for (String f : files) {
            if (!f.toLowerCase().endsWith(".apk")) continue;
            String stem = f.substring(0, f.length() - 4).toLowerCase();
            int score = score(q, stem);
            if (score < bestScore) {
                bestScore = score;
                best = f;
            }
        }

        if (best == null) {
            return BANNER + "no .apk files in " + APK_DIR;
        }

        int maxAllowed = Math.max(3, q.length());
        if (bestScore > maxAllowed) {
            return BANNER + "no good match for '" + raw + "'\nclosest: "
                    + APK_DIR + "/" + best;
        }

        File apk = new File(dir, best);
        if (!apk.exists()) {
            return BANNER + "matched " + best + " but file is gone";
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    i.setData(Uri.parse("package:" + ctx.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Throwable ignored) { }
                return BANNER + "allow 'Install unknown apps' then retry\n" + best;
            }
        }

        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(apk),
                    "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
            return BANNER + "installing " + best + "\npath: " + apk.getAbsolutePath();
        } catch (Throwable t) {
            return BANNER + "install failed: " + t;
        }
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
        for (int si = 0; si < s.length() && qi < q.length(); si++) {
            if (s.charAt(si) == q.charAt(qi)) qi++;
        }
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
        return new int[] {
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
        };
    }
    @Override public int priority() { return 1; }
    @Override public int helpRes() { return 0; }
    @Override public String onArgNotFound(ExecutePack pack, int index) { return null; }
    @Override public String onNotArgEnough(ExecutePack pack, int nArgs) { return null; }
}