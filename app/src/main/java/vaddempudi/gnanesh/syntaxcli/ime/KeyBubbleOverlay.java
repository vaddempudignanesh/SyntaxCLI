package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

public class KeyBubbleOverlay {

    private static final String TAG = "KB-BUBBLE";

    private final Context context;
    private final WindowManager windowManager;
    private final TextView bubbleView;
    private final WindowManager.LayoutParams params;
    private final int sizePx;
    private final float density;
    private boolean showing = false;
    private boolean overlayBroken = false;   // only set after a real addView failure
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public KeyBubbleOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);

        DisplayMetrics dm = this.context.getResources().getDisplayMetrics();
        this.density = dm.density;

        sizePx = (int) (KeyboardConfig.PREVIEW_SIZE_DP * density);

        bubbleView = new TextView(this.context);
        bubbleView.setGravity(Gravity.CENTER);
        bubbleView.setTypeface(Typeface.MONOSPACE);
        bubbleView.setTextSize(KeyboardConfig.PREVIEW_TEXT_SIZE_SP);
        bubbleView.setIncludeFontPadding(false);

        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                sizePx, sizePx,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // ★ Do NOT check Settings.canDrawOverlays() here — it is unreliable from
        //   an IME service context. The addView() call is the truth.
    }

    private void applyTheme() {
        ImeColorStore colors = ImeColorStore.get(context);
        int textColor = colors.getTextColor();
        int bgColor   = colors.getBgColor();

        bubbleView.setTextColor(textColor);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(KeyboardConfig.PREVIEW_CORNER_RADIUS_DP * density);
        bg.setStroke(
                (int) (KeyboardConfig.PREVIEW_BORDER_WIDTH_DP * density),
                textColor);
        bubbleView.setBackground(bg);
    }

    public void show(String label, int screenCenterX, int screenTopY) {
        if (windowManager == null) return;
        if (overlayBroken) return;

        applyTheme();

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int gap = (int) (KeyboardConfig.PREVIEW_GAP_DP * density);
        int screenH = dm.heightPixels;
        int screenW = dm.widthPixels;

        int x = screenCenterX - sizePx / 2;
        int y = screenTopY - sizePx - gap;

        // Clamp horizontal
        if (x < 0) x = 0;
        if (x + sizePx > screenW) x = screenW - sizePx;

        // Clamp vertical — do NOT flip below anymore. Just pin to top of screen.
        if (y < 0) y = 0;
        if (y + sizePx > screenH) y = screenH - sizePx;

        params.x = x;
        params.y = y;

        bubbleView.setText(label);

        try {
            if (showing) {
                windowManager.updateViewLayout(bubbleView, params);
            } else {
                windowManager.addView(bubbleView, params);
                showing = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "overlay addView failed — disabling", t);
            overlayBroken = true;
            showing = false;
        }
    }

    public void hide() {
        if (windowManager == null || !showing) return;
        try {
            windowManager.removeView(bubbleView);
        } catch (Throwable ignored) { }
        showing = false;
    }
}