package vaddempudi.gnanesh.syntaxcli.ime;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public final class ColorPickerDialog {

    public interface OnColorPicked {
        void onPicked(int color);
    }

    public static void show(Context ctx, String title, int initialColor, OnColorPicked listener) {
        final AlertDialog.Builder b = new AlertDialog.Builder(ctx);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        int pad = dp(ctx, 20);
        root.setPadding(pad, pad, pad, pad);

        // ── Title ──
        TextView header = new TextView(ctx);
        header.setText(title);
        header.setTextColor(0xFF33FF33);
        header.setTextSize(16);
        header.setTypeface(Typeface.MONOSPACE);
        root.addView(header);

        // ── Live preview swatch ──
        final View preview = new View(ctx);
        GradientDrawable pvBg = new GradientDrawable();
        pvBg.setCornerRadius(dp(ctx, 8));
        pvBg.setStroke(dp(ctx, 1), 0xFF333333);
        preview.setBackground(pvBg);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 56));
        pvLp.topMargin = dp(ctx, 14);
        pvLp.bottomMargin = dp(ctx, 6);
        root.addView(preview, pvLp);

        // ── Hex label ──
        final TextView hexLabel = new TextView(ctx);
        hexLabel.setTextColor(0xFF00AA00);
        hexLabel.setTextSize(12);
        hexLabel.setTypeface(Typeface.MONOSPACE);
        hexLabel.setGravity(Gravity.CENTER);
        root.addView(hexLabel);

        // ── R / G / B sliders ──
        int initR = Color.red(initialColor);
        int initG = Color.green(initialColor);
        int initB = Color.blue(initialColor);

        final int[] rgb = {initR, initG, initB};

        SeekBar sbR = makeSlider(ctx, "R", 0xFFFF4040, initR, root);
        SeekBar sbG = makeSlider(ctx, "G", 0xFF40FF40, initG, root);
        SeekBar sbB = makeSlider(ctx, "B", 0xFF4060FF, initB, root);

        final Runnable refresh = () -> {
            int color = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
            GradientDrawable g = new GradientDrawable();
            g.setColor(color);
            g.setCornerRadius(dp(ctx, 8));
            g.setStroke(dp(ctx, 1), 0xFF333333);
            preview.setBackground(g);
            hexLabel.setText(String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]));
        };
        refresh.run();

        sbR.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                rgb[0] = p; refresh.run();
            }
        });
        sbG.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                rgb[1] = p; refresh.run();
            }
        });
        sbB.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                rgb[2] = p; refresh.run();
            }
        });

        b.setView(root);
        b.setPositiveButton("APPLY", (d, w) -> {
            if (listener != null) {
                listener.onPicked(0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
            }
        });
        b.setNegativeButton("CANCEL", null);
        b.show();
    }

    private static SeekBar makeSlider(Context ctx, String label, int color,
                                      int initial, LinearLayout parent) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52));
        rowLp.topMargin = dp(ctx, 6);
        row.setLayoutParams(rowLp);

        TextView tag = new TextView(ctx);
        tag.setText(label);
        tag.setTextColor(color);
        tag.setTextSize(14);
        tag.setTypeface(Typeface.MONOSPACE);
        tag.setGravity(Gravity.CENTER);
        row.addView(tag, new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 32)));

        SeekBar sb = new SeekBar(ctx);
        sb.setMax(255);
        sb.setProgress(initial);

        // Thicker track + fat round thumb → much easier to grab.
        sb.setSplitTrack(false);
        sb.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(color));
        sb.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF444444));

        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                0, dp(ctx, 48), 1f);
        sbLp.leftMargin = dp(ctx, 10);
        row.addView(sb, sbLp);

        parent.addView(row);
        return sb;
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static abstract class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }
}