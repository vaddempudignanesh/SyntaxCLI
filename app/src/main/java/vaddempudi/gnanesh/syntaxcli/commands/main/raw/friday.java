package vaddempudi.gnanesh.syntaxcli.commands.main.raw;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.io.File;
import java.util.List;

import vaddempudi.gnanesh.syntaxcli.commands.CommandAbstraction;
import vaddempudi.gnanesh.syntaxcli.commands.ExecutePack;
import vaddempudi.gnanesh.syntaxcli.commands.main.MainPack;
import vaddempudi.gnanesh.syntaxcli.managers.AdminPasswordStore;
import vaddempudi.gnanesh.syntaxcli.managers.AppActionStore;

public class friday implements CommandAbstraction {

    public static final String[] MENU = {
            "configure-app", "show-actions", "clear-actions", "change-pwd", "rm", "ls", "exit"
    };
    public static final String[] ACTION = { "app", "text", "image" };

    // ── state ──
    public static String pendingPrefix = null;
    public static String pendingInput = null;
    public static int stage = 0;   // 0 = password, 1 = menu, 2 = pick-first-app, 3 = pick-second-app, 4 = pick-action, 5 = text body, 6 = rm list

    // selected state
    public static String selFirstApp = null;
    public static String selFirstPkg = null;
    public static String selSecondApp = null;
    public static String selSecondPkg = null;

    @Override
    public String exec(ExecutePack pack) {
        MainPack info = (MainPack) pack;
        if (info == null || info.context == null) return null;
        Context ctx = info.context;

        String raw = pendingInput;
        pendingInput = null;
        if (raw == null) raw = info.getString();
        if (raw == null) raw = "";
        raw = raw.trim();
        if (raw.equals("friday")) raw = "";
        else if (raw.startsWith("friday ")) raw = raw.substring("friday ".length()).trim();

        if (stage == 0) {
            if (AdminPasswordStore.get(ctx).getBase() == null) {
                stage = 11;
                return "set new password:";
            }
            stage = 1;
            pendingPrefix = "friday";
            return "password:";
        }

        if (stage == 1) {
            if (raw.isEmpty()) return "password:";
            if (!AdminPasswordStore.get(ctx).verify(raw)) {
                return "denied — try again, type password:";
            }
            stage = 2;
            return renderMenu();
        }

        if (stage == 2) {
            String cmd = raw.trim();
            switch (cmd) {
                case "configure-app":
                    selFirstApp = null; selFirstPkg = null;
                    selSecondApp = null; selSecondPkg = null;
                    stage = 3;
                    return "pick first app:";
                case "show-actions":
                case "ls":
                    stage = 2;
                    return renderActions(ctx);
                case "clear-actions":
                    AppActionStore.get(ctx).clear();
                    stage = 2;
                    return "cleared\n" + renderMenu();
                case "change-pwd":
                    AdminPasswordStore.get(ctx).clear();
                    stage = 11;
                    return "set new password:";
                case "rm":
                    stage = 7;
                    return "which action to remove?";
                case "exit":
                    stage = 0;
                    pendingPrefix = null;
                    return "friday closed";
                default:
                    return "unknown admin command: '" + cmd + "'\n" + renderMenu();
            }
        }

        if (stage == 3) {
            String app = raw.trim();
            String pkg = findPackageForLabel(ctx, app);
            if (pkg == null) return "app not found\nfirst app:";
            selFirstApp = app;
            selFirstPkg = pkg;
            stage = 5;                              // ← go to action chips
            return "action: app / text / video";
        }
        if (stage == 4) {
            String app = raw.trim();
            String pkg = findPackageForLabel(ctx, app);
            if (pkg == null) return "app not found\nsecond app:";
            if (app.equalsIgnoreCase(selFirstApp)) return "second app must be different\nsecond app:";
            selSecondApp = app;
            selSecondPkg = pkg;
            saveAction(ctx, selFirstPkg, selFirstApp, selSecondApp);
            stage = 2;
            return "saved: " + selFirstApp + " -> launch " + selSecondApp + "\n" + renderMenu();
        }

        if (stage == 5) {
            String action = raw.trim().toLowerCase();
            if (action.equals("app")) {
                stage = 4;
                return "pick second app:";
            }
            if (action.equals("text")) {
                stage = 6;
                return "text body:";
            }
            if (action.equals("image")) {
                stage = 9;
                return null;
            }
            return "action: app / text / image";
        }

        if (stage == 6) {
            saveTextAction(ctx, selFirstPkg, selFirstApp, raw);
            stage = 2;
            return "saved: " + selFirstApp + " -> text\n" + renderMenu();
        }

        if (stage == 7) {
            String name = raw.trim();
            if (AppActionStore.get(ctx).remove(name)) {
                stage = 2;
                return "removed " + name + "\n" + renderMenu();
            }
            return "not found\nwhich action to remove?";
        }

        if (stage == 8) {
            String path = raw.trim();
            if (path.isEmpty()) return "pick image path:";
            String storedUri;
            if (path.startsWith("content://")) {
                storedUri = copyToPermanent(ctx, path);
                if (storedUri == null) return "could not copy image\npick image path:";
            } else if (path.startsWith("file://")) {
                storedUri = path;
            } else {
                File f = new File(path);
                if (!f.exists()) return "file not found\npick image path:";
                storedUri = "file://" + path;
            }
            saveImageAction(ctx, selFirstPkg, selFirstApp, storedUri);
            stage = 2;
            return "saved: " + selFirstApp + " -> image\n" + renderMenu();
        }

        if (stage == 9) {
            // Trigger the Android file picker from the Activity
            if (ctx instanceof android.app.Activity) {
                android.app.Activity act = (android.app.Activity) ctx;
                act.runOnUiThread(() -> {
                    try {
                        android.content.Intent intent = new android.content.Intent(
                                android.content.Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                        intent.setType("image/*");
                        act.startActivityForResult(intent, 4242);
                    } catch (Throwable ignored) { }
                });
            }
            return "pick image from picker...";
        }

        if (stage == 10) {
            String uri = raw.trim();
            if (uri.isEmpty()) { stage = 2; return "cancelled\n" + renderMenu(); }
            String storedUri = uri.startsWith("content://") ? copyToPermanent(ctx, uri) : uri;
            if (storedUri == null) { stage = 2; return "could not copy image\n" + renderMenu(); }
            saveImageAction(ctx, selFirstPkg, selFirstApp, storedUri);
            stage = 2;
            return "saved: " + selFirstApp + " -> image\n" + renderMenu();
        }

        if (stage == 11) {
            String newPwd = raw.trim();
            if (newPwd.isEmpty()) return "set new password:";
            AdminPasswordStore.get(ctx).set(newPwd);
            stage = 0;
            return "password updated";
        }

        stage = 0;
        return null;
    }

    private static String copyToPermanent(android.content.Context ctx, String contentUri) {
        try {
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), "t-ui/images");
            if (!dir.exists()) dir.mkdirs();

            String ext = ".bin";
            String name = "img_" + System.currentTimeMillis() + ext;

            // Try to determine extension from mime type
            String mime = ctx.getContentResolver().getType(android.net.Uri.parse(contentUri));
            if (mime != null) {
                if (mime.contains("png")) ext = ".png";
                else if (mime.contains("jpeg") || mime.contains("jpg")) ext = ".jpg";
                else if (mime.contains("webp")) ext = ".webp";
                else if (mime.contains("gif")) ext = ".gif";
                else if (mime.contains("bmp")) ext = ".bmp";
                name = "img_" + System.currentTimeMillis() + ext;
            }

            File out = new File(dir, name);
            try (java.io.InputStream is = ctx.getContentResolver().openInputStream(android.net.Uri.parse(contentUri));
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                if (is == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            }
            return "file://" + out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }
    private static void saveImageAction(Context ctx, String pkg, String label, String uri) {
        AppActionStore.Entry e = new AppActionStore.Entry();
        e.packageName = pkg;
        e.label = label;
        e.type = AppActionStore.TYPE_IMAGE;
        e.secondLabel = "";
        e.payload = uri;
        AppActionStore.get(ctx).put(e);
    }

    private String renderMenu() {
        StringBuilder sb = new StringBuilder("admin:\n");
        for (String s : MENU) sb.append("  ").append(s).append('\n');
        return sb.toString();
    }

    private String renderActions(Context ctx) {
        List<AppActionStore.Entry> all = AppActionStore.get(ctx).listAll();
        if (all.isEmpty()) return "ACTIONS:\n(none)";
        StringBuilder sb = new StringBuilder("ACTIONS:\n");
        for (AppActionStore.Entry e : all) {
            sb.append(e.label).append("  ->  ");
            if (e.type == AppActionStore.TYPE_APP) {
                sb.append("launch ").append(e.secondLabel == null ? "?" : e.secondLabel);
            } else if (e.type == AppActionStore.TYPE_TEXT) {
                sb.append("text: ").append(e.payload);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String findPackageForLabel(Context ctx, String label) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            if (ri.loadLabel(pm).toString().equalsIgnoreCase(label))
                return ri.activityInfo.packageName;
        }
        return null;
    }

    private static void saveAction(Context ctx, String pkg, String label, String secondLabel) {
        AppActionStore.Entry e = new AppActionStore.Entry();
        e.packageName = pkg;
        e.label = label;
        e.type = AppActionStore.TYPE_APP;
        e.secondLabel = secondLabel;   // ← keep
        e.payload = "";
        AppActionStore.get(ctx).put(e);
    }
    private static void saveTextAction(Context ctx, String pkg, String label, String body) {
        AppActionStore.Entry e = new AppActionStore.Entry();
        e.packageName = pkg;
        e.label = label;
        e.type = AppActionStore.TYPE_TEXT;
        e.secondLabel = "";
        e.payload = body;
        AppActionStore.get(ctx).put(e);
    }

    @Override
    public int[] argType() {
        return new int[] {
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
                CommandAbstraction.PLAIN_TEXT,
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