package vaddempudi.gnanesh.syntaxcli.ime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.UUID;

public class GestureActionEditorActivity extends Activity {

    private static final int BG     = 0xFF000000;
    private static final int GREEN  = 0xFF33FF33;
    private static final int DIM    = 0xFF00AA00;
    private static final int CARD_BG     = 0xFF0A0A0A;
    private static final int CARD_BORDER = 0xFF1F3F1F;
    private static final int REQ_PICK_WALLPAPER = 4211;
    private static final int REQ_PICK_VIDEO     = 4212;

    private int pendingTextColor;
    private int pendingBgColor;
    private int pendingKeyAlpha;

    private boolean pendingWallpaperEnabled;
    private int pendingWallpaperDim;
    private String pendingWallpaperImagePath;
    private String pendingWallpaperVideoPath;

    private View textSwatch;
    private View bgSwatch;
    private TextView textHexLabel;
    private TextView bgHexLabel;

    private SeekBar keyAlphaSeek;
    private TextView keyAlphaLabel;

    private TextView wallpaperToggleLabel;
    private TextView wallpaperImageLabel;
    private TextView wallpaperVideoLabel;
    private SeekBar wallpaperDimSeek;
    private TextView wallpaperDimLabel;

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImeColorStore store = ImeColorStore.get(this);
        pendingTextColor          = store.getTextColor();
        pendingBgColor            = store.getBgColor();
        pendingKeyAlpha           = store.getKeyAlpha();
        pendingWallpaperEnabled   = store.isWallpaperEnabled();
        pendingWallpaperDim       = store.getWallpaperDim();
        pendingWallpaperImagePath = store.getWallpaperImagePath();
        pendingWallpaperVideoPath = store.getWallpaperVideoPath();
        build();
        renderList();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("GESTURE ACTIONS");
        title.setTextColor(GREEN);
        title.setTextSize(18);
        title.setTypeface(Typeface.MONOSPACE);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Assign an action to a key. Long-press that key on the keyboard to run it.\n"
                + "Long-press SPACE to open this screen.");
        sub.setTextColor(DIM);
        sub.setTextSize(11);
        sub.setPadding(0, dp(8), 0, dp(16));
        root.addView(sub);

        LinearLayout themeCard = new LinearLayout(this);
        themeCard.setOrientation(LinearLayout.VERTICAL);
        themeCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable themeBg = new GradientDrawable();
        themeBg.setColor(CARD_BG);
        themeBg.setCornerRadius(dp(8));
        themeBg.setStroke(dp(1), CARD_BORDER);
        themeCard.setBackground(themeBg);
        LinearLayout.LayoutParams themeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        themeLp.bottomMargin = dp(16);
        root.addView(themeCard, themeLp);

        TextView themeHeader = new TextView(this);
        themeHeader.setText("THEME COLORS");
        themeHeader.setTextColor(GREEN);
        themeHeader.setTextSize(13);
        themeHeader.setTypeface(Typeface.MONOSPACE);
        themeCard.addView(themeHeader);

        LinearLayout textRow = makeColorRow(
                "Text color",
                pendingTextColor,
                c -> {
                    pendingTextColor = c;
                    ImeColorStore.get(this).save(pendingTextColor, pendingBgColor);
                    refreshPreviews();
                });
        themeCard.addView(textRow);
        textSwatch = textRow.findViewWithTag("swatch");
        textHexLabel = textRow.findViewWithTag("hex");

        LinearLayout bgRow = makeColorRow(
                "Background color",
                pendingBgColor,
                c -> {
                    pendingBgColor = c;
                    ImeColorStore.get(this).save(pendingTextColor, pendingBgColor);
                    refreshPreviews();
                });
        themeCard.addView(bgRow);
        bgSwatch = bgRow.findViewWithTag("swatch");
        bgHexLabel = bgRow.findViewWithTag("hex");

        LinearLayout keyAlphaRow = new LinearLayout(this);
        keyAlphaRow.setOrientation(LinearLayout.HORIZONTAL);
        keyAlphaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams kaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        kaLp.topMargin = dp(8);
        keyAlphaRow.setLayoutParams(kaLp);

        TextView kaTag = new TextView(this);
        kaTag.setText("KEY ALPHA");
        kaTag.setTextColor(GREEN);
        kaTag.setTextSize(12);
        kaTag.setTypeface(Typeface.MONOSPACE);
        keyAlphaRow.addView(kaTag, new LinearLayout.LayoutParams(
                dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));

        keyAlphaSeek = new SeekBar(this);
        keyAlphaSeek.setMax(255);
        keyAlphaSeek.setProgress(pendingKeyAlpha);
        keyAlphaSeek.setSplitTrack(false);
        keyAlphaSeek.setPadding(dp(12), 0, dp(12), 0);
        keyAlphaSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        keyAlphaSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));
        keyAlphaSeek.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF444444));
        keyAlphaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                pendingKeyAlpha = p;
                ImeColorStore.get(GestureActionEditorActivity.this).saveKeyAlpha(p);
                refreshPreviews();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        keyAlphaRow.addView(keyAlphaSeek, new LinearLayout.LayoutParams(0, dp(48), 1f));

        keyAlphaLabel = new TextView(this);
        keyAlphaLabel.setTextColor(DIM);
        keyAlphaLabel.setTextSize(11);
        keyAlphaLabel.setTypeface(Typeface.MONOSPACE);
        keyAlphaLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        keyAlphaRow.addView(keyAlphaLabel, new LinearLayout.LayoutParams(
                dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));

        themeCard.addView(keyAlphaRow);

        LinearLayout wallCard = new LinearLayout(this);
        wallCard.setOrientation(LinearLayout.VERTICAL);
        wallCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable wallBg = new GradientDrawable();
        wallBg.setColor(0xFF050A05);
        wallBg.setCornerRadius(dp(8));
        wallBg.setStroke(dp(1), GREEN);
        wallCard.setBackground(wallBg);
        LinearLayout.LayoutParams wallLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wallLp.topMargin = dp(6);
        wallLp.bottomMargin = dp(16);
        root.addView(wallCard, wallLp);

        TextView wallHeader = new TextView(this);
        wallHeader.setText("▌ WALLPAPER");
        wallHeader.setTextColor(GREEN);
        wallHeader.setTextSize(13);
        wallHeader.setTypeface(Typeface.MONOSPACE);
        wallCard.addView(wallHeader);

        wallpaperToggleLabel = new TextView(this);
        wallpaperToggleLabel.setTextSize(13);
        wallpaperToggleLabel.setTypeface(Typeface.MONOSPACE);
        wallpaperToggleLabel.setTextColor(GREEN);
        wallpaperToggleLabel.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable wpBg = new GradientDrawable();
        wpBg.setColor(0xFF111111);
        wpBg.setCornerRadius(dp(6));
        wpBg.setStroke(dp(1), CARD_BORDER);
        wallpaperToggleLabel.setBackground(wpBg);
        wallpaperToggleLabel.setClickable(true);
        wallpaperToggleLabel.setOnClickListener(v -> {
            pendingWallpaperEnabled = !pendingWallpaperEnabled;
            saveWallpaper();
            refreshPreviews();
        });
        LinearLayout.LayoutParams wpTogLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wpTogLp.topMargin = dp(10);
        wallCard.addView(wallpaperToggleLabel, wpTogLp);

        wallpaperImageLabel = new TextView(this);
        wallpaperImageLabel.setTextSize(12);
        wallpaperImageLabel.setTypeface(Typeface.MONOSPACE);
        wallpaperImageLabel.setTextColor(GREEN);
        wallpaperImageLabel.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable imgBg = new GradientDrawable();
        imgBg.setColor(0xFF0A0A0A);
        imgBg.setCornerRadius(dp(6));
        imgBg.setStroke(dp(1), CARD_BORDER);
        wallpaperImageLabel.setBackground(imgBg);
        wallpaperImageLabel.setClickable(true);
        wallpaperImageLabel.setOnClickListener(v -> openImagePicker());
        wallpaperImageLabel.setOnLongClickListener(v -> {
            pendingWallpaperImagePath = null;
            saveWallpaper();
            refreshPreviews();
            Toast.makeText(this, "Image source cleared", Toast.LENGTH_SHORT).show();
            return true;
        });
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        imgLp.topMargin = dp(8);
        wallCard.addView(wallpaperImageLabel, imgLp);

        wallpaperVideoLabel = new TextView(this);
        wallpaperVideoLabel.setTextSize(12);
        wallpaperVideoLabel.setTypeface(Typeface.MONOSPACE);
        wallpaperVideoLabel.setTextColor(GREEN);
        wallpaperVideoLabel.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable vidBg = new GradientDrawable();
        vidBg.setColor(0xFF0A0A0A);
        vidBg.setCornerRadius(dp(6));
        vidBg.setStroke(dp(1), CARD_BORDER);
        wallpaperVideoLabel.setBackground(vidBg);
        wallpaperVideoLabel.setClickable(true);
        wallpaperVideoLabel.setOnClickListener(v -> openVideoPicker());
        wallpaperVideoLabel.setOnLongClickListener(v -> {
            pendingWallpaperVideoPath = null;
            saveWallpaper();
            refreshPreviews();
            Toast.makeText(this, "Video source cleared", Toast.LENGTH_SHORT).show();
            return true;
        });
        LinearLayout.LayoutParams vidLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vidLp.topMargin = dp(8);
        wallCard.addView(wallpaperVideoLabel, vidLp);

        LinearLayout dimRow = new LinearLayout(this);
        dimRow.setOrientation(LinearLayout.HORIZONTAL);
        dimRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dimLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        dimLp.topMargin = dp(8);
        dimRow.setLayoutParams(dimLp);

        TextView dimTag = new TextView(this);
        dimTag.setText("DIM");
        dimTag.setTextColor(GREEN);
        dimTag.setTextSize(13);
        dimTag.setTypeface(Typeface.MONOSPACE);
        dimRow.addView(dimTag, new LinearLayout.LayoutParams(
                dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));

        wallpaperDimSeek = new SeekBar(this);
        wallpaperDimSeek.setMax(100);
        wallpaperDimSeek.setProgress(pendingWallpaperDim);
        wallpaperDimSeek.setSplitTrack(false);
        wallpaperDimSeek.setPadding(dp(12), 0, dp(12), 0);
        wallpaperDimSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        wallpaperDimSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));
        wallpaperDimSeek.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF444444));
        wallpaperDimSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                pendingWallpaperDim = p;
                saveWallpaper();
                refreshPreviews();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        dimRow.addView(wallpaperDimSeek, new LinearLayout.LayoutParams(0, dp(48), 1f));

        wallpaperDimLabel = new TextView(this);
        wallpaperDimLabel.setTextColor(DIM);
        wallpaperDimLabel.setTextSize(11);
        wallpaperDimLabel.setTypeface(Typeface.MONOSPACE);
        wallpaperDimLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        dimRow.addView(wallpaperDimLabel, new LinearLayout.LayoutParams(
                dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));

        wallCard.addView(dimRow);

        LinearLayout actionsCard = new LinearLayout(this);
        actionsCard.setOrientation(LinearLayout.VERTICAL);
        actionsCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable actionsBg = new GradientDrawable();
        actionsBg.setColor(CARD_BG);
        actionsBg.setCornerRadius(dp(8));
        actionsBg.setStroke(dp(1), CARD_BORDER);
        actionsCard.setBackground(actionsBg);
        root.addView(actionsCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button add = new Button(this);
        add.setText("+ ADD ACTION");
        add.setTextColor(GREEN);
        add.setBackgroundColor(BG);
        add.setTypeface(Typeface.MONOSPACE);
        add.setOnClickListener(v -> showAddDialog());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.bottomMargin = dp(12);
        actionsCard.addView(add, addLp);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        actionsCard.addView(list);

        scroll.addView(root);
        setContentView(scroll);
        refreshPreviews();
    }

    private void saveWallpaper() {
        ImeColorStore.get(this).saveWallpaper(
                pendingWallpaperEnabled,
                pendingWallpaperDim,
                pendingWallpaperImagePath,
                pendingWallpaperVideoPath);
    }

    private LinearLayout makeColorRow(String label, int currentColor,
                                      ColorPickedCallback onPicked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(12);
        row.setLayoutParams(rowLp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(GREEN);
        name.setTextSize(13);
        name.setTypeface(Typeface.MONOSPACE);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View swatch = new View(this);
        swatch.setTag("swatch");
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(currentColor);
        sg.setCornerRadius(dp(6));
        sg.setStroke(dp(2), GREEN);
        swatch.setBackground(sg);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        swLp.leftMargin = dp(10);
        swLp.rightMargin = dp(10);
        row.addView(swatch, swLp);
        swatch.setClickable(true);
        swatch.setFocusable(true);
        swatch.setOnClickListener(v ->
                ColorPickerDialog.show(this, label.toUpperCase(),
                        currentColorFor(label), c -> {
                            onPicked.onPicked(c);
                            updateSwatch(swatch, c);
                        }));

        TextView hex = new TextView(this);
        hex.setTag("hex");
        hex.setText(hex(currentColor));
        hex.setTextColor(0xFF00AA00);
        hex.setTextSize(11);
        hex.setTypeface(Typeface.MONOSPACE);
        hex.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(hex, new LinearLayout.LayoutParams(dp(90),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private int currentColorFor(String label) {
        if (label.equalsIgnoreCase("Text color")) return pendingTextColor;
        return pendingBgColor;
    }

    private void updateSwatch(View swatch, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(6));
        g.setStroke(dp(2), GREEN);
        swatch.setBackground(g);
    }

    private void refreshPreviews() {
        if (textSwatch != null) {
            updateSwatch(textSwatch, pendingTextColor);
            if (textHexLabel != null) textHexLabel.setText(hex(pendingTextColor));
        }
        if (bgSwatch != null) {
            updateSwatch(bgSwatch, pendingBgColor);
            if (bgHexLabel != null) bgHexLabel.setText(hex(pendingBgColor));
        }
        if (keyAlphaSeek != null) keyAlphaSeek.setProgress(pendingKeyAlpha);
        if (keyAlphaLabel != null) {
            int pct = Math.round(pendingKeyAlpha * 100f / 255f);
            keyAlphaLabel.setText(pct + "%");
        }
        if (wallpaperToggleLabel != null) {
            wallpaperToggleLabel.setText(pendingWallpaperEnabled
                    ? "WALLPAPER:  ON   (tap to disable)"
                    : "WALLPAPER:  OFF  (tap to enable)");
            wallpaperToggleLabel.setTextColor(pendingWallpaperEnabled ? 0xFFFFAA00 : GREEN);
        }
        if (wallpaperImageLabel != null) {
            String src = (pendingWallpaperImagePath == null || pendingWallpaperImagePath.isEmpty())
                    ? "IMAGE SOURCE:  SYSTEM WALLPAPER  (tap to pick, long-press to clear)"
                    : "IMAGE SOURCE:  CUSTOM IMAGE  (tap to change)";
            wallpaperImageLabel.setText(src);
            wallpaperImageLabel.setEnabled(pendingWallpaperEnabled);
            wallpaperImageLabel.setAlpha(pendingWallpaperEnabled ? 1f : 0.4f);
        }
        if (wallpaperVideoLabel != null) {
            String src = (pendingWallpaperVideoPath == null || pendingWallpaperVideoPath.isEmpty())
                    ? "VIDEO SOURCE:  NONE  (tap to pick MP4, long-press to clear)"
                    : "VIDEO SOURCE:  CUSTOM MP4  (tap to change)";
            wallpaperVideoLabel.setText(src);
            wallpaperVideoLabel.setEnabled(pendingWallpaperEnabled);
            wallpaperVideoLabel.setAlpha(pendingWallpaperEnabled ? 1f : 0.4f);
        }
        if (wallpaperDimSeek != null) {
            wallpaperDimSeek.setEnabled(pendingWallpaperEnabled);
            wallpaperDimSeek.setAlpha(pendingWallpaperEnabled ? 1f : 0.4f);
        }
        if (wallpaperDimLabel != null) {
            wallpaperDimLabel.setText(pendingWallpaperDim + "%");
            wallpaperDimLabel.setAlpha(pendingWallpaperEnabled ? 1f : 0.4f);
        }
    }

    private void openImagePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK_WALLPAPER);
        } catch (Exception e) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void openVideoPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        try {
            startActivityForResult(i, REQ_PICK_VIDEO);
        } catch (Exception e) {
            Toast.makeText(this, "No video picker found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        android.net.Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }

        if (requestCode == REQ_PICK_WALLPAPER) {
            try {
                java.io.File dst = new java.io.File(
                        getFilesDir(),
                        "kbd_wallpaper_" + System.currentTimeMillis() + ".jpg");
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                    if (in == null) throw new java.io.IOException("null stream");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                pendingWallpaperImagePath = dst.getAbsolutePath();
                pendingWallpaperVideoPath = null;
                pendingWallpaperEnabled = true;
                saveWallpaper();
                Toast.makeText(this, "Custom image set", Toast.LENGTH_SHORT).show();
                recreate();
            } catch (Exception e) {
                Toast.makeText(this, "Failed to read image: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_PICK_VIDEO) {
            try {
                java.io.File dst = new java.io.File(
                        getFilesDir(),
                        "kbd_wallpaper_" + System.currentTimeMillis() + ".mp4");
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                    if (in == null) throw new java.io.IOException("null stream");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                pendingWallpaperVideoPath = dst.getAbsolutePath();
                pendingWallpaperImagePath = null;
                pendingWallpaperEnabled = true;
                saveWallpaper();
                Toast.makeText(this, "Custom video set", Toast.LENGTH_SHORT).show();
                recreate();
            } catch (Exception e) {
                Toast.makeText(this, "Failed to read video: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }
    }
    private static String hex(int c) {
        return String.format("#%06X", 0xFFFFFF & c);
    }

    private void renderList() {
        list.removeAllViews();
        List<KeyAction> actions = GestureActionStore.get(this).all();
        if (actions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No actions yet.");
            empty.setTextColor(DIM);
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setPadding(0, dp(8), 0, dp(8));
            list.addView(empty);
            return;
        }
        for (KeyAction a : actions) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(0xFF111111);
            rowBg.setCornerRadius(dp(6));
            rowBg.setStroke(dp(1), CARD_BORDER);
            row.setBackground(rowBg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, dp(4), 0, dp(4));
            row.setLayoutParams(rlp);

            TextView line1 = new TextView(this);
            line1.setText("KEY: [" + a.keyChar + "]  →  "
                    + (a.type == KeyAction.TYPE_APP_LAUNCH ? "LAUNCH " : "TYPE ") + a.payload);
            line1.setTextColor(GREEN);
            line1.setTypeface(Typeface.MONOSPACE);
            line1.setTextSize(13);
            row.addView(line1);

            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setPadding(0, dp(6), 0, 0);

            TextView edit = new TextView(this);
            edit.setText("EDIT");
            edit.setTextColor(GREEN);
            edit.setTypeface(Typeface.MONOSPACE);
            edit.setPadding(dp(10), dp(6), dp(10), dp(6));
            edit.setOnClickListener(v -> showEditDialog(a));
            buttons.addView(edit);

            TextView del = new TextView(this);
            del.setText("DELETE");
            del.setTextColor(0xFFFF6666);
            del.setTypeface(Typeface.MONOSPACE);
            del.setPadding(dp(10), dp(6), dp(10), dp(6));
            del.setOnClickListener(v -> {
                GestureActionStore.get(this).remove(a.id);
                renderList();
            });
            buttons.addView(del);

            row.addView(buttons);
            list.addView(row);
        }
    }

    private void showAddDialog() { showEditDialog(null); }

    private void showEditDialog(KeyAction existing) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView keyLabel = new TextView(this);
        keyLabel.setText("Key (single letter, e.g. s)");
        keyLabel.setTextColor(DIM);
        keyLabel.setTypeface(Typeface.MONOSPACE);
        root.addView(keyLabel);

        EditText keyInput = new EditText(this);
        keyInput.setTextColor(GREEN);
        keyInput.setHintTextColor(DIM);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT);
        keyInput.setTypeface(Typeface.MONOSPACE);
        keyInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable keyBg = new GradientDrawable();
        keyBg.setColor(0xFF000000);
        keyBg.setCornerRadius(dp(6));
        keyBg.setStroke(dp(1), GREEN);
        keyInput.setBackground(keyBg);
        if (existing != null) keyInput.setText(existing.keyChar);
        root.addView(keyInput);

        TextView typeLabel = new TextView(this);
        typeLabel.setText("Action type");
        typeLabel.setTextColor(DIM);
        typeLabel.setTypeface(Typeface.MONOSPACE);
        typeLabel.setPadding(0, dp(10), 0, 0);
        root.addView(typeLabel);

        final int[] type = { existing != null ? existing.type : KeyAction.TYPE_TEXT };

        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        typeRow.setPadding(0, dp(6), 0, 0);

        TextView textBtn = new TextView(this);
        textBtn.setText("TYPE TEXT");
        textBtn.setTypeface(Typeface.MONOSPACE);
        textBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        typeRow.addView(textBtn);

        TextView appBtn = new TextView(this);
        appBtn.setText("LAUNCH APP");
        appBtn.setTypeface(Typeface.MONOSPACE);
        appBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        typeRow.addView(appBtn);

        root.addView(typeRow);

        TextView payloadLabel = new TextView(this);
        payloadLabel.setText("Text to insert");
        payloadLabel.setTextColor(DIM);
        payloadLabel.setTypeface(Typeface.MONOSPACE);
        payloadLabel.setPadding(0, dp(10), 0, 0);
        root.addView(payloadLabel);

        final EditText payloadInput = new EditText(this);
        payloadInput.setTextColor(GREEN);
        payloadInput.setHintTextColor(DIM);
        payloadInput.setTypeface(Typeface.MONOSPACE);
        payloadInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable payloadBg = new GradientDrawable();
        payloadBg.setColor(0xFF000000);
        payloadBg.setCornerRadius(dp(6));
        payloadBg.setStroke(dp(1), GREEN);
        payloadInput.setBackground(payloadBg);
        if (existing != null) payloadInput.setText(existing.payload);
        root.addView(payloadInput);

        final TextView pickAppBtn = new TextView(this);
        pickAppBtn.setText("PICK APP");
        pickAppBtn.setTextColor(GREEN);
        pickAppBtn.setTypeface(Typeface.MONOSPACE);
        pickAppBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable pickBg = new GradientDrawable();
        pickBg.setColor(0xFF0A0A0A);
        pickBg.setCornerRadius(dp(4));
        pickBg.setStroke(dp(1), GREEN);
        pickAppBtn.setBackground(pickBg);
        pickAppBtn.setVisibility(existing != null && existing.type == KeyAction.TYPE_APP_LAUNCH
                ? View.VISIBLE : View.GONE);
        pickAppBtn.setOnClickListener(v ->
                AppPickerDialog.show(this, (pkg, label) -> payloadInput.setText(pkg)));
        root.addView(pickAppBtn);

        Runnable refreshTypeUi = () -> {
            boolean isText = type[0] == KeyAction.TYPE_TEXT;
            textBtn.setTextColor(isText ? 0xFF000000 : GREEN);
            textBtn.setBackgroundColor(isText ? GREEN : 0xFF0A0A0A);
            appBtn.setTextColor(isText ? GREEN : 0xFF000000);
            appBtn.setBackgroundColor(isText ? 0xFF0A0A0A : GREEN);
            payloadLabel.setText(isText ? "Text to insert" : "App package (or use PICK APP)");
            payloadInput.setEnabled(isText);
            pickAppBtn.setVisibility(isText ? View.GONE : View.VISIBLE);
            if (!isText && existing != null) payloadInput.setText(existing.payload);
        };

        textBtn.setOnClickListener(v -> { type[0] = KeyAction.TYPE_TEXT; refreshTypeUi.run(); });
        appBtn.setOnClickListener(v -> { type[0] = KeyAction.TYPE_APP_LAUNCH; refreshTypeUi.run(); });
        refreshTypeUi.run();

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setView(root)
                .setPositiveButton("SAVE", null)
                .setNegativeButton("CANCEL", null)
                .create();

        dlg.setOnShowListener(d -> {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(GREEN);
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String key = keyInput.getText().toString().trim();
                String payload = payloadInput.getText().toString().trim();
                if (key.isEmpty() || payload.isEmpty()) {
                    Toast.makeText(this, "Key and payload required", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (key.length() > 1) key = key.substring(0, 1);

                KeyAction dup = GestureActionStore.get(this).byKeyChar(key);
                if (dup != null && (existing == null || !dup.id.equals(existing.id))) {
                    Toast.makeText(this, "Key '" + key + "' already bound to another action",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                KeyAction a = new KeyAction(
                        existing != null ? existing.id : UUID.randomUUID().toString(),
                        key, type[0], payload);

                if (existing != null) {
                    GestureActionStore.get(this).replace(existing.id, a);
                } else {
                    GestureActionStore.get(this).add(a);
                }
                renderList();
                dlg.dismiss();
            });
        });
        dlg.show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    public static void open(Context ctx) {
        Intent i = new Intent(ctx, GestureActionEditorActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    private interface ColorPickedCallback {
        void onPicked(int color);
    }
}