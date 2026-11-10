package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A modal-looking prompt for use from an {@link android.inputmethodservice.InputMethodService}.
 *
 * Do NOT use {@code android.app.Dialog} from an IME: the service's window
 * context is TYPE_INPUT_METHOD (2011), while Dialog demands TYPE_APPLICATION
 * (2), and WindowManager.addView throws BadTokenException on API 30+.
 *
 * This helper adds its own overlay window (TYPE_APPLICATION_OVERLAY), dims
 * the background, blocks input outside the card, and calls the supplied
 * listener when the user taps a button.
 *
 * Requires the "Display over other apps" permission. If it has not been
 * granted, {@link #show} is a no-op (returns false) so callers can fall
 * back to the host app's own Activity to prompt the user.
 */
final class ImePopupDialog {

    interface OnResult { void onResult(boolean positive); }

    private static ImePopupDialog sCurrent;

    private final WindowManager wm;
    private final FrameLayout root;
    private final WindowManager.LayoutParams rootLp;
    private final LinearLayout card;
    private final Runnable onDismiss;
    private boolean shown = false;

    private ImePopupDialog(Context ctx, Runnable onDismiss) {
        this.wm = (WindowManager) ctx.getApplicationContext()
                .getSystemService(Context.WINDOW_SERVICE);
        this.onDismiss = onDismiss;

        float density = ctx.getResources().getDisplayMetrics().density;

        root = new FrameLayout(ctx);
        root.setBackgroundColor(0xB3000000);          // ~70% dim
        root.setClickable(true);
        root.setFocusable(true);

        rootLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        rootLp.gravity = Gravity.CENTER;
        rootLp.dimAmount = 0.7f;

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * density);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF0A0A0A);
        cardBg.setCornerRadius(12 * density);
        cardBg.setStroke(Math.round(1 * density), 0xFF33FF33);
        card.setBackground(cardBg);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.86f),
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        root.addView(card, cardLp);
    }

    /**
     * Returns true if the prompt was shown. Returns false if the
     * "Display over other apps" permission has not been granted.
     */
    static boolean show(Context ctx,
                        String title,
                        String message,
                        String positiveLabel,
                        String negativeLabel,
                        OnResult cb) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(ctx)) {
            return false;
        }

        dismissCurrent();

        final ImePopupDialog[] self = new ImePopupDialog[1];
        self[0] = new ImePopupDialog(ctx, () -> { /* cleanup handled in dismiss() */ });

        float density = ctx.getResources().getDisplayMetrics().density;

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(0xFF33FF33);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(15);
        t.setPadding(0, 0, 0, Math.round(10 * density));
        self[0].card.addView(t);

        TextView m = new TextView(ctx);
        m.setText(message);
        m.setTextColor(0xFFCCCCCC);
        m.setTypeface(Typeface.MONOSPACE);
        m.setTextSize(12);
        m.setPadding(0, 0, 0, Math.round(16 * density));
        self[0].card.addView(m);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView cancel = makeButton(ctx, negativeLabel, 0xFF666666);
        cancel.setOnClickListener(v -> {
            self[0].dismiss();
            if (cb != null) cb.onResult(false);
        });
        row.addView(cancel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView ok = makeButton(ctx, positiveLabel, 0xFF33FF33);
        ok.setOnClickListener(v -> {
            self[0].dismiss();
            if (cb != null) cb.onResult(true);
        });
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        okLp.leftMargin = Math.round(8 * density);
        row.addView(ok, okLp);

        self[0].card.addView(row);

        try {
            self[0].wm.addView(self[0].root, self[0].rootLp);
            self[0].shown = true;
            sCurrent = self[0];
            return true;
        } catch (Throwable t2) {
            XLog.e("IME-POPUP", "addView failed", t2);
            return false;
        }
    }

    private static TextView makeButton(Context ctx, String label, int color) {
        float density = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        int p = Math.round(10 * density);
        tv.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF111111);
        bg.setCornerRadius(6 * density);
        bg.setStroke(Math.round(1 * density), color);
        tv.setBackground(bg);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    void dismiss() {
        if (!shown) return;
        shown = false;
        try {
            wm.removeView(root);
        } catch (Throwable ignored) { }
        if (sCurrent == this) sCurrent = null;
        if (onDismiss != null) onDismiss.run();
    }

    static void dismissCurrent() {
        ImePopupDialog c = sCurrent;
        if (c != null) c.dismiss();
    }
}