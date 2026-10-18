package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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
    private boolean showing = false;

    public KeyBubbleOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);

        DisplayMetrics dm = this.context.getResources().getDisplayMetrics();
        float density = dm.density;

        ImeColorStore colors = ImeColorStore.get(this.context);

        sizePx = (int) (KeyboardConfig.PREVIEW_SIZE_DP * density);

        bubbleView = new TextView(this.context);
        bubbleView.setGravity(Gravity.CENTER);
        bubbleView.setTypeface(Typeface.MONOSPACE);
        bubbleView.setTextColor(colors.getTextColor());
        bubbleView.setTextSize(KeyboardConfig.PREVIEW_TEXT_SIZE_SP);
        bubbleView.setIncludeFontPadding(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colors.getBgColor());
        bg.setCornerRadius(KeyboardConfig.PREVIEW_CORNER_RADIUS_DP * density);
        bg.setStroke(
                (int) (KeyboardConfig.PREVIEW_BORDER_WIDTH_DP * density),
                colors.getTextColor());
        bubbleView.setBackground(bg);

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
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
    }

    public void show(String label, int anchorCenterX, int anchorTopY) {
        if (windowManager == null) return;

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float density = dm.density;
        int gap = (int) (KeyboardConfig.PREVIEW_GAP_DP * density);

        int x = anchorCenterX - sizePx / 2;
        int y = anchorTopY - sizePx - gap;

        int screenW = dm.widthPixels;
        if (x < 0) x = 0;
        if (x + sizePx > screenW) x = screenW - sizePx;

        if (y < 0) {
            y = anchorTopY + gap;
        }

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
        } catch (Exception e) {
            Log.e(TAG, "overlay show failed", e);
        }
    }

    public void hide() {
        if (windowManager == null || !showing) return;
        try {
            windowManager.removeView(bubbleView);
        } catch (Exception ignored) { }
        showing = false;
    }
}