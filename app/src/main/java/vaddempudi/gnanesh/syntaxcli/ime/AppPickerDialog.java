package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AppPickerDialog {

    private static final int BG    = 0xFF000000;
    private static final int GREEN = 0xFF33FF33;
    private static final int DIM   = 0xFF00AA00;

    public interface OnAppPicked {
        void onPicked(String packageName, String label);
    }

    public static void show(Context ctx, OnAppPicked listener) {
        final android.app.AlertDialog.Builder builder =
                new android.app.AlertDialog.Builder(ctx);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16));

        TextView title = new TextView(ctx);
        title.setText("SELECT APP");
        title.setTextColor(GREEN);
        title.setTextSize(16);
        title.setTypeface(Typeface.MONOSPACE);
        root.addView(title);

        EditText filter = new EditText(ctx);
        filter.setHint("filter...");
        filter.setTextColor(GREEN);
        filter.setHintTextColor(DIM);
        filter.setTypeface(Typeface.MONOSPACE);
        filter.setBackgroundColor(0xFF0A0A0A);
        filter.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        root.addView(filter);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 380)));
        LinearLayout listView = new LinearLayout(ctx);
        listView.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listView);
        root.addView(scroll);

        // Collect launchable apps
        final List<String[]> apps = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            String label = ri.loadLabel(pm).toString();
            apps.add(new String[]{pkg, label});
        }
        Collections.sort(apps, (a, b) ->
                a[1].compareToIgnoreCase(b[1]));

        final android.app.AlertDialog[] dlgRef = new android.app.AlertDialog[1];

        final Runnable[] renderRef = new Runnable[1];
        renderRef[0] = () -> {
            listView.removeAllViews();
            String q = filter.getText().toString().trim().toLowerCase(java.util.Locale.US);
            for (String[] row : apps) {
                if (!q.isEmpty()
                        && !row[1].toLowerCase(java.util.Locale.US).contains(q)
                        && !row[0].toLowerCase(java.util.Locale.US).contains(q)) continue;

                final String pkg = row[0];
                final String label = row[1];

                TextView tv = new TextView(ctx);
                tv.setText(label + "\n" + pkg);
                tv.setTextColor(GREEN);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextSize(12);
                tv.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
                GradientDrawable bgr = new GradientDrawable();
                bgr.setColor(0xFF0A0A0A);
                bgr.setCornerRadius(dp(ctx, 4));
                tv.setBackground(bgr);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, dp(ctx, 2), 0, dp(ctx, 2));
                tv.setLayoutParams(lp);
                tv.setOnClickListener(v -> {
                    if (listener != null) listener.onPicked(pkg, label);
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                });
                listView.addView(tv);
            }
        };

        renderRef[0].run();

        filter.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                renderRef[0].run();
            }
        });

        builder.setView(root);
        builder.setNegativeButton("CANCEL", null);
        final android.app.AlertDialog dlg = builder.create();
        dlgRef[0] = dlg;
        dlg.show();
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}