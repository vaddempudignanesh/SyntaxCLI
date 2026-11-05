package vaddempudi.gnanesh.syntaxcli.managers;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.InputStream;

public final class AppActionLauncher {

    private AppActionLauncher() {}

    public static boolean handle(Context ctx, String packageName, String label, Intent defaultIntent) {
        AppActionStore.Entry entry = AppActionStore.get(ctx).find(packageName, label);
        if (entry == null) return false;
        switch (entry.type) {
            case AppActionStore.TYPE_TEXT:
                showTextFlash(ctx, entry.payload);
                return true;
            case AppActionStore.TYPE_IMAGE:
                showImageFlash(ctx, entry.payload);
                return true;
            case AppActionStore.TYPE_APP:
                if (entry.secondLabel != null && !entry.secondLabel.isEmpty()) {
                    launchSecond(ctx, entry.secondLabel);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private static void showTextFlash(Context ctx, String text) {
        if (text == null) text = "";
        try {
            final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;

            final TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextColor(Color.GREEN);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 60);
            tv.setPadding(80, 80, 80, 80);
            tv.setBackgroundColor(Color.TRANSPARENT);
            tv.setGravity(Gravity.CENTER);
            tv.setShadowLayer(6f, 0f, 0f, Color.GREEN);

            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;

            try { wm.addView(tv, lp); } catch (Throwable t) { return; }

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { wm.removeView(tv); } catch (Throwable ignored) { }
            }, 1200L);
        } catch (Throwable ignored) { }
    }

    private static void showImageFlash(Context ctx, String fileUri) {
        if (fileUri == null || fileUri.isEmpty()) {
            Toast.makeText(ctx, "No image configured", Toast.LENGTH_SHORT).show();
            return;
        }

        Bitmap bmp = decodeBitmap(ctx, fileUri);
        if (bmp == null) {
            Toast.makeText(ctx, "Image file not found or unreadable", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) { bmp.recycle(); return; }

            final ImageView iv = new ImageView(ctx);
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(Color.TRANSPARENT);

            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;

            try { wm.addView(iv, lp); } catch (Throwable t) { bmp.recycle(); return; }

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { wm.removeView(iv); } catch (Throwable ignored) { }
                bmp.recycle();
            }, 1200L);
        } catch (Throwable t) {
            bmp.recycle();
        }
    }

    private static Bitmap decodeBitmap(Context ctx, String fileUri) {
        try {
            Uri uri = Uri.parse(fileUri);
            String scheme = uri.getScheme();

            if (scheme == null) {
                File f = new File(fileUri);
                if (!f.exists()) return null;
                return BitmapFactory.decodeFile(f.getAbsolutePath());
            }
            if ("file".equalsIgnoreCase(scheme)) {
                File f = new File(uri.getPath());
                if (!f.exists()) return null;
                return BitmapFactory.decodeFile(f.getAbsolutePath());
            }
            if ("content".equalsIgnoreCase(scheme)) {
                try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                    if (is == null) return null;
                    return BitmapFactory.decodeStream(is);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private static void launchSecond(Context ctx, String label) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> ris = pm.queryIntentActivities(main, 0);
            for (android.content.pm.ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                if (ri.loadLabel(pm).toString().equalsIgnoreCase(label)) {
                    Intent i = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    }
                    return;
                }
            }
        } catch (Throwable ignored) { }
    }
}