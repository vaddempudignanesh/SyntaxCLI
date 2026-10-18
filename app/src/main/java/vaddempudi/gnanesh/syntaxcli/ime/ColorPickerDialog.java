package vaddempudi.gnanesh.syntaxcli.ime;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ColorPickerDialog {

    public interface OnColorPicked {
        void onPicked(int color);
    }

    private static final int[] PALETTE = {
            0xFF000000, 0xFF33FF33, 0xFF00FF00, 0xFF00AA00,
            0xFFFFFFFF, 0xFFCCCCCC, 0xFFFF0000, 0xFFFF5555,
            0xFF0000FF, 0xFF33CCFF, 0xFFFFFF00, 0xFFFFAA00,
            0xFFFF00FF, 0xFFCC00CC, 0xFF00FFFF, 0xFFFF6600,
    };

    public static void show(Context ctx, String title, int initialColor, OnColorPicked listener) {
        final AlertDialog.Builder b = new AlertDialog.Builder(ctx);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16));

        TextView header = new TextView(ctx);
        header.setText(title);
        header.setTextColor(0xFF33FF33);
        header.setTextSize(16);
        header.setTypeface(Typeface.MONOSPACE);
        root.addView(header);

        // 4×4 grid of swatches
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(ctx, 12);
        grid.setLayoutParams(glp);
        root.addView(grid);

        final AlertDialog[] dlgRef = new AlertDialog[1];

        for (int row = 0; row < 4; row++) {
            LinearLayout rowView = new LinearLayout(ctx);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            rowView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 56)));
            for (int col = 0; col < 4; col++) {
                int idx = row * 4 + col;
                int color = PALETTE[idx];

                View swatch = new View(ctx);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(color);
                bg.setCornerRadius(dp(ctx, 6));
                bg.setStroke(dp(ctx, 1), 0xFF333333);
                swatch.setBackground(bg);

                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                int m = dp(ctx, 4);
                slp.setMargins(m, m, m, m);
                swatch.setLayoutParams(slp);
                swatch.setClickable(true);
                swatch.setOnClickListener(v -> {
                    if (listener != null) listener.onPicked(color);
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                });
                rowView.addView(swatch);
            }
            grid.addView(rowView);
        }

        // Custom hex
        TextView hexLabel = new TextView(ctx);
        hexLabel.setText("Custom (hex, e.g. 33FF33)");
        hexLabel.setTextColor(0xFF00AA00);
        hexLabel.setTextSize(12);
        hexLabel.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(ctx, 12);
        hexLabel.setLayoutParams(hlp);
        root.addView(hexLabel);

        EditText hexInput = new EditText(ctx);
        hexInput.setHint("AARRGGBB or RRGGBB");
        hexInput.setHintTextColor(0xFF00AA00);
        hexInput.setTextColor(0xFF33FF33);
        hexInput.setTypeface(Typeface.MONOSPACE);
        hexInput.setBackgroundColor(0xFF0A0A0A);
        hexInput.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(hexInput);

        b.setView(root);
        b.setPositiveButton("APPLY", (d, w) -> {
            String raw = hexInput.getText().toString().trim().replace("#", "");
            int color = initialColor;
            try {
                if (raw.length() == 6)      color = 0xFF000000 | (int) Long.parseLong(raw, 16);
                else if (raw.length() == 8) color = (int) Long.parseLong(raw, 16);
            } catch (Exception ignored) { }
            if (listener != null) listener.onPicked(color);
        });
        b.setNegativeButton("CANCEL", null);

        AlertDialog dlg = b.create();
        dlgRef[0] = dlg;
        dlg.show();
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}