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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GestureActionEditorActivity extends Activity {

    private static final int BG = 0xFF000000;
    private static final int GREEN = 0xFF33FF33;
    private static final int DIM = 0xFF00AA00;
    private static final int CARD_BG = 0xFF0A0A0A;
    private static final int CARD_BORDER = 0xFF1F3F1F;
    private static final int REQ_PICK_WALLPAPER = 4211;
    private static final int REQ_PICK_VIDEO = 4212;

    private EditText testerField;
    private TextView testerStatus;

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
        pendingTextColor = store.getTextColor();
        pendingBgColor = store.getBgColor();
        pendingKeyAlpha = store.getKeyAlpha();
        pendingWallpaperEnabled = store.isWallpaperEnabled();
        pendingWallpaperDim = store.getWallpaperDim();
        pendingWallpaperImagePath = store.getWallpaperImagePath();
        pendingWallpaperVideoPath = store.getWallpaperVideoPath();
        build();
        renderList();
        maybePromptOverlayPermission();
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
        root.addView(buildTesterCard());

        LinearLayout layoutCard = new LinearLayout(this);
        layoutCard.setOrientation(LinearLayout.VERTICAL);
        layoutCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable layoutBg = new GradientDrawable();
        layoutBg.setColor(CARD_BG);
        layoutBg.setCornerRadius(dp(8));
        layoutBg.setStroke(dp(1), CARD_BORDER);
        layoutCard.setBackground(layoutBg);
        LinearLayout.LayoutParams layoutLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutLp.bottomMargin = dp(16);
        root.addView(layoutCard, layoutLp);

        TextView layoutHeader = new TextView(this);
        layoutHeader.setText("▌ LAYOUT");
        layoutHeader.setTextColor(GREEN);
        layoutHeader.setTextSize(13);
        layoutHeader.setTypeface(Typeface.MONOSPACE);
        layoutCard.addView(layoutHeader);

        TextView layoutHint = new TextView(this);
        layoutHint.setText("All sliders apply LIVE to the keyboard on this device.");
        layoutHint.setTextColor(DIM);
        layoutHint.setTextSize(11);
        layoutHint.setTypeface(Typeface.MONOSPACE);
        layoutHint.setPadding(0, dp(4), 0, dp(10));
        layoutCard.addView(layoutHint);

        final ImeColorStore layoutStore = ImeColorStore.get(this);

        java.util.function.BiFunction<String, float[], LinearLayout> makeLayoutSlider =
                (label, rangeMinMaxDefaultStep) -> {
                    float min = rangeMinMaxDefaultStep[0];
                    float max = rangeMinMaxDefaultStep[1];
                    float dflt = rangeMinMaxDefaultStep[2];
                    float step = rangeMinMaxDefaultStep[3];
                    int steps = Math.round((max - min) / step);

                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
                    rlp.topMargin = dp(4);
                    row.setLayoutParams(rlp);

                    TextView tag = new TextView(this);
                    tag.setText(label);
                    tag.setTextColor(GREEN);
                    tag.setTextSize(12);
                    tag.setTypeface(Typeface.MONOSPACE);
                    row.addView(tag, new LinearLayout.LayoutParams(
                            dp(160), ViewGroup.LayoutParams.WRAP_CONTENT));

                    SeekBar sb = new SeekBar(this);
                    sb.setMax(steps);
                    int cur = Math.round((dflt - min) / step);
                    sb.setProgress(Math.max(0, Math.min(steps, cur)));
                    sb.setSplitTrack(false);
                    sb.setPadding(dp(12), 0, dp(12), 0);
                    sb.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
                    sb.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));
                    sb.setProgressBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(0xFF444444));
                    row.addView(sb, new LinearLayout.LayoutParams(0, dp(40), 1f));

                    TextView value = new TextView(this);
                    value.setTextColor(DIM);
                    value.setTextSize(11);
                    value.setTypeface(Typeface.MONOSPACE);
                    value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                    value.setText(String.format(java.util.Locale.US, "%.1f", dflt));
                    row.addView(value, new LinearLayout.LayoutParams(
                            dp(60), ViewGroup.LayoutParams.WRAP_CONTENT));

                    sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                            float v = min + p * step;
                            value.setText(String.format(java.util.Locale.US, "%.1f", v));
                            switch (label) {
                                case "ROW HEIGHT": layoutStore.saveRowHeightDp(v); break;
                                case "ROW GAP": layoutStore.saveRowGapDp(v); break;
                                case "KEY GAP": layoutStore.saveKeyGapDp(v); break;
                                case "KEY RADIUS": layoutStore.saveKeyCornerRadiusDp(v); break;
                                case "TAB ROW HEIGHT": layoutStore.saveTabRowHeightDp(v); break;
                            }
                            broadcastLayoutChanged();          // ← live, every tick
                        }
                        @Override public void onStartTrackingTouch(SeekBar s) { }
                        @Override public void onStopTrackingTouch(SeekBar s) {
                            broadcastLayoutChanged();
                        }
                    });

                    row.setTag(new float[]{ min, max, step });

                    return row;
                };

        LinearLayout rowHeightRow = makeLayoutSlider.apply(
                "ROW HEIGHT", new float[]{30f, 100f,
                        layoutStore.getRowHeightDp(), 1f});
        layoutCard.addView(rowHeightRow);

        LinearLayout rowGapRow = makeLayoutSlider.apply(
                "ROW GAP", new float[]{0f, 30f,
                        layoutStore.getRowGapDp(), 0.5f});
        layoutCard.addView(rowGapRow);

        LinearLayout keyGapRow = makeLayoutSlider.apply(
                "KEY GAP", new float[]{0f, 20f,
                        layoutStore.getKeyGapDp(), 0.5f});
        layoutCard.addView(keyGapRow);

        LinearLayout keyRadiusRow = makeLayoutSlider.apply(
                "KEY RADIUS", new float[]{0f, 20f,
                        layoutStore.getKeyCornerRadiusDp(), 0.5f});
        layoutCard.addView(keyRadiusRow);

        LinearLayout tabRowHeightRow = makeLayoutSlider.apply(
                "TAB ROW HEIGHT", new float[]{20f, 80f,
                        layoutStore.getTabRowHeightDp(), 1f});
        layoutCard.addView(tabRowHeightRow);

        TextView resetBtn = new TextView(this);
        resetBtn.setText("RESET TO DEFAULTS");
        resetBtn.setTextColor(0xFFFFAA00);
        resetBtn.setTypeface(Typeface.MONOSPACE);
        resetBtn.setGravity(Gravity.CENTER);
        resetBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable resetBg = new GradientDrawable();
        resetBg.setColor(0xFF111111);
        resetBg.setCornerRadius(dp(6));
        resetBg.setStroke(dp(1), CARD_BORDER);
        resetBtn.setBackground(resetBg);
        resetBtn.setClickable(true);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = dp(8);
        layoutCard.addView(resetBtn, resetLp);
        resetBtn.setOnClickListener(v -> {
            layoutStore.saveRowHeightDp(KeyboardConfig.DEFAULT_ROW_HEIGHT_DP);
            layoutStore.saveRowGapDp(KeyboardConfig.DEFAULT_ROW_GAP_DP);
            layoutStore.saveKeyGapDp(KeyboardConfig.DEFAULT_KEY_HORIZONTAL_GAP_DP);
            layoutStore.saveKeyCornerRadiusDp(KeyboardConfig.DEFAULT_KEY_CORNER_RADIUS_DP);
            layoutStore.saveTabRowHeightDp(KeyboardConfig.DEFAULT_TAB_ROW_HEIGHT_DP);
            broadcastLayoutChanged();
            Toast.makeText(this, "Layout reset", Toast.LENGTH_SHORT).show();
            recreate();
        });

        TextView restartImeBtn = new TextView(this);
        restartImeBtn.setText("⟳  RESTART IME SERVICE");
        restartImeBtn.setTextColor(0xFFFFAA00);
        restartImeBtn.setTypeface(Typeface.MONOSPACE);
        restartImeBtn.setTextSize(13);
        restartImeBtn.setGravity(Gravity.CENTER);
        restartImeBtn.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable restartBg = new GradientDrawable();
        restartBg.setColor(0xFF1A1200);
        restartBg.setCornerRadius(dp(6));
        restartBg.setStroke(dp(1), 0xFFFFAA00);
        restartImeBtn.setBackground(restartBg);
        restartImeBtn.setClickable(true);
        restartImeBtn.setFocusable(true);
        LinearLayout.LayoutParams restartLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        restartLp.topMargin = dp(10);
        layoutCard.addView(restartImeBtn, restartLp);

        restartImeBtn.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Restart keyboard?")
                    .setMessage("The keyboard will restart with the new settings applied.")
                    .setPositiveButton("RESTART", (d, w) -> killImeProcess())
                    .setNegativeButton("CANCEL", null)
                    .show();
        });
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

        TextView shrinkToggle = new TextView(this);
        shrinkToggle.setTextSize(13);
        shrinkToggle.setTypeface(Typeface.MONOSPACE);
        shrinkToggle.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable stBg = new GradientDrawable();
        stBg.setColor(0xFF111111);
        stBg.setCornerRadius(dp(6));
        stBg.setStroke(dp(1), CARD_BORDER);
        shrinkToggle.setBackground(stBg);
        shrinkToggle.setClickable(true);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(12);
        themeCard.addView(shrinkToggle, stLp);

        LinearLayout intensRow = new LinearLayout(this);
        intensRow.setOrientation(LinearLayout.HORIZONTAL);
        intensRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams intLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        intLp.topMargin = dp(8);
        intensRow.setLayoutParams(intLp);

        TextView intTag = new TextView(this);
        intTag.setText("SHRINK");
        intTag.setTextColor(GREEN);
        intTag.setTextSize(12);
        intTag.setTypeface(Typeface.MONOSPACE);
        intensRow.addView(intTag, new LinearLayout.LayoutParams(
                dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar intensSeek = new SeekBar(this);
        intensSeek.setMax(50);
        int currentInt = Math.round((1f - ImeColorStore.get(this).getKeyShrinkIntensity()) * 100f);
        intensSeek.setProgress(currentInt);
        intensSeek.setSplitTrack(false);
        intensSeek.setPadding(dp(12), 0, dp(12), 0);
        intensSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        intensSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));
        intensSeek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF444444));
        intensRow.addView(intensSeek, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView intensLabel = new TextView(this);
        intensLabel.setTextColor(DIM);
        intensLabel.setTextSize(11);
        intensLabel.setTypeface(Typeface.MONOSPACE);
        intensLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        intensRow.addView(intensLabel, new LinearLayout.LayoutParams(
                dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));

        themeCard.addView(intensRow);

        Runnable updateIntensLabel = () -> {
            float scale = 1f - (intensSeek.getProgress() / 100f);
            intensLabel.setText(Math.round(scale * 100f) + "%");
        };
        updateIntensLabel.run();
        intensSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                float scale = 1f - (p / 100f);
                ImeColorStore.get(GestureActionEditorActivity.this)
                        .saveKeyShrinkIntensity(scale);
                updateIntensLabel.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams spdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        spdLp.topMargin = dp(4);
        speedRow.setLayoutParams(spdLp);

        TextView spdTag = new TextView(this);
        spdTag.setText("SPEED");
        spdTag.setTextColor(GREEN);
        spdTag.setTextSize(12);
        spdTag.setTypeface(Typeface.MONOSPACE);
        speedRow.addView(spdTag, new LinearLayout.LayoutParams(
                dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar speedSeek = new SeekBar(this);
        speedSeek.setMax(180);
        int curSpeed = (int) (ImeColorStore.get(this).getKeyShrinkDownMs() - 20L);
        speedSeek.setProgress(Math.max(0, Math.min(180, curSpeed)));
        speedSeek.setSplitTrack(false);
        speedSeek.setPadding(dp(12), 0, dp(12), 0);
        speedSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        speedSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));
        speedSeek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF444444));
        speedRow.addView(speedSeek, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView speedLabel = new TextView(this);
        speedLabel.setTextColor(DIM);
        speedLabel.setTextSize(11);
        speedLabel.setTypeface(Typeface.MONOSPACE);
        speedLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        speedRow.addView(speedLabel, new LinearLayout.LayoutParams(
                dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

        themeCard.addView(speedRow);

        Runnable updateSpeedLabel = () -> {
            long ms = 20L + speedSeek.getProgress();
            speedLabel.setText(ms + "ms");
        };
        updateSpeedLabel.run();
        speedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                ImeColorStore.get(GestureActionEditorActivity.this)
                        .saveKeyShrinkDownMs(20L + p);
                updateSpeedLabel.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        final Runnable[] refreshShrink = new Runnable[1];
        refreshShrink[0] = () -> {
            boolean on = ImeColorStore.get(this).isKeyShrinkEnabled();
            shrinkToggle.setText(on
                    ? "KEY SHRINK ANIMATION:  ON   (tap to disable)"
                    : "KEY SHRINK ANIMATION:  OFF  (tap to enable)");
            shrinkToggle.setTextColor(on ? 0xFFFFAA00 : GREEN);
        };
        shrinkToggle.setOnClickListener(v -> {
            ImeColorStore store = ImeColorStore.get(this);
            store.saveKeyShrinkEnabled(!store.isKeyShrinkEnabled());
            refreshShrink[0].run();
        });
        refreshShrink[0].run();

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
        TextView numHeader = new TextView(this);
        numHeader.setText("NUMERIC PAD");
        numHeader.setTextColor(GREEN);
        numHeader.setTextSize(13);
        numHeader.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams nhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nhLp.topMargin = dp(8);
        nhLp.bottomMargin = dp(6);
        actionsCard.addView(numHeader, nhLp);

        NumericPadConfig numCfg = NumericPadConfig.get(this);

        LinearLayout extraRow = new LinearLayout(this);
        extraRow.setOrientation(LinearLayout.HORIZONTAL);
        extraRow.setGravity(Gravity.CENTER_VERTICAL);
        extraRow.setPadding(0, dp(6), 0, dp(6));

        TextView extraLabel = new TextView(this);
        extraLabel.setText("Bottom-left key char");
        extraLabel.setTextColor(GREEN);
        extraLabel.setTextSize(12);
        extraLabel.setTypeface(Typeface.MONOSPACE);
        extraRow.addView(extraLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText extraInput = new EditText(this);
        extraInput.setText(numCfg.getExtraChar());
        extraInput.setTextColor(GREEN);
        extraInput.setHintTextColor(DIM);
        extraInput.setInputType(InputType.TYPE_CLASS_TEXT);
        extraInput.setTypeface(Typeface.MONOSPACE);
        extraInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable extraBg = new GradientDrawable();
        extraBg.setColor(0xFF000000);
        extraBg.setCornerRadius(dp(6));
        extraBg.setStroke(dp(1), GREEN);
        extraInput.setBackground(extraBg);
        LinearLayout.LayoutParams eiLp = new LinearLayout.LayoutParams(
                dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
        extraRow.addView(extraInput, eiLp);

        TextView extraApply = new TextView(this);
        extraApply.setText("SET");
        extraApply.setTextColor(GREEN);
        extraApply.setTypeface(Typeface.MONOSPACE);
        extraApply.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable applyBg = new GradientDrawable();
        applyBg.setColor(0xFF0A0A0A);
        applyBg.setCornerRadius(dp(4));
        applyBg.setStroke(dp(1), GREEN);
        extraApply.setBackground(applyBg);
        extraApply.setClickable(true);
        extraApply.setOnClickListener(v -> {
            numCfg.setExtraChar(extraInput.getText().toString());
            extraInput.setText(numCfg.getExtraChar());
            Toast.makeText(this, "Extra char saved: " + numCfg.getExtraChar(),
                    Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams ea = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ea.leftMargin = dp(8);
        extraRow.addView(extraApply, ea);

        actionsCard.addView(extraRow);

        LinearLayout orderRow = new LinearLayout(this);
        orderRow.setOrientation(LinearLayout.HORIZONTAL);
        orderRow.setGravity(Gravity.CENTER_VERTICAL);
        orderRow.setPadding(0, dp(6), 0, dp(6));

        TextView orderLabel = new TextView(this);
        orderLabel.setText("Digit order (0-9 each once)");
        orderLabel.setTextColor(GREEN);
        orderLabel.setTextSize(12);
        orderLabel.setTypeface(Typeface.MONOSPACE);
        orderRow.addView(orderLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText orderInput = new EditText(this);
        orderInput.setText(numCfg.getDigitOrder());
        orderInput.setTextColor(GREEN);
        orderInput.setHintTextColor(DIM);
        orderInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        orderInput.setTypeface(Typeface.MONOSPACE);
        orderInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable orderBg = new GradientDrawable();
        orderBg.setColor(0xFF000000);
        orderBg.setCornerRadius(dp(6));
        orderBg.setStroke(dp(1), GREEN);
        orderInput.setBackground(orderBg);
        LinearLayout.LayoutParams oiLp = new LinearLayout.LayoutParams(
                dp(140), ViewGroup.LayoutParams.WRAP_CONTENT);
        orderRow.addView(orderInput, oiLp);

        TextView orderApply = new TextView(this);
        orderApply.setText("SET");
        orderApply.setTextColor(GREEN);
        orderApply.setTypeface(Typeface.MONOSPACE);
        orderApply.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable oApplyBg = new GradientDrawable();
        oApplyBg.setColor(0xFF0A0A0A);
        oApplyBg.setCornerRadius(dp(4));
        oApplyBg.setStroke(dp(1), GREEN);
        orderApply.setBackground(oApplyBg);
        orderApply.setClickable(true);
        orderApply.setOnClickListener(v -> {
            String s = orderInput.getText().toString().trim();
            if (!NumericPadConfig.isValidOrder(s)) {
                Toast.makeText(this, "Must be 10 digits, 0-9 each once",
                        Toast.LENGTH_LONG).show();
                return;
            }
            numCfg.setDigitOrder(s);
            Toast.makeText(this, "Digit order saved", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams oa = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        oa.leftMargin = dp(8);
        orderRow.addView(orderApply, oa);

        actionsCard.addView(orderRow);
        TextView reverseToggle = new TextView(this);
        reverseToggle.setTextSize(12);
        reverseToggle.setTypeface(Typeface.MONOSPACE);
        reverseToggle.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable rvBg = new GradientDrawable();
        rvBg.setColor(0xFF111111);
        rvBg.setCornerRadius(dp(6));
        rvBg.setStroke(dp(1), CARD_BORDER);
        reverseToggle.setBackground(rvBg);
        reverseToggle.setClickable(true);
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rvLp.topMargin = dp(6);
        rvLp.bottomMargin = dp(6);
        actionsCard.addView(reverseToggle, rvLp);

        Runnable refreshReverse = () -> {
            boolean on = numCfg.isReverseRows();
            reverseToggle.setText("DIGIT ROWS:  " + (on ? "REVERSED (right-to-left)" : "NORMAL (left-to-right)") + "   (tap to toggle)");
            reverseToggle.setTextColor(on ? 0xFFFFAA00 : GREEN);
        };
        refreshReverse.run();
        reverseToggle.setOnClickListener(v -> {
            numCfg.setReverseRows(!numCfg.isReverseRows());
            refreshReverse.run();
        });
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

    private void broadcastRestartIme() {
        try {
            java.io.File marker = new java.io.File(
                    getFilesDir(), "ime_restart.requested");
            marker.getParentFile().mkdirs();
            java.io.FileOutputStream fos =
                    new java.io.FileOutputStream(marker, false);
            fos.write(1);
            fos.getFD().sync();
            fos.close();
            marker.setLastModified(System.currentTimeMillis());

            XLog.d("IME-EDITOR", "restart marker written: " + marker.getAbsolutePath());

            try {
                android.content.Intent i = new android.content.Intent(
                        BasicImeService.ACTION_RESTART_IME);
                sendBroadcast(i);
            } catch (Throwable ignored) { }
        } catch (Throwable t) {
            XLog.e("IME-EDITOR", "restart marker write failed", t);
        }
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

    private void maybePromptOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return;
        if (android.provider.Settings.canDrawOverlays(this)) return;

        new android.app.AlertDialog.Builder(this)
                .setTitle("Enable key popup")
                .setMessage("To show the key-preview bubble above the keyboard, " +
                        "please allow 'Display over other apps' for this keyboard.")
                .setPositiveButton("OPEN SETTINGS", (d, w) -> {
                    try {
                        Intent i = new Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception ignored) { }
                })
                .setNegativeButton("SKIP", null)
                .show();
    }

    private static final class ActivityEntry {
        final String component;
        final String label;
        ActivityEntry(String component, String label) {
            this.component = component;
            this.label = label;
        }
    }

    private void killImeProcess() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                Toast.makeText(this, "ActivityManager unavailable", Toast.LENGTH_LONG).show();
                return;
            }

            // killBackgroundProcesses kills every process of the given package
            // that is *not* in the foreground. The main process (this Activity)
            // is in the foreground, so it survives. The :keyboard IME process
            // is not foregrounded (the keyboard is hidden behind this Activity),
            // so it gets killed. The IME restarts on next focus and reloads
            // everything from disk — every pref, every setting, every gesture.
            am.killBackgroundProcesses(getPackageName());

            // Also try forceStopPackage (hidden API) so ROMs that ignore
            // killBackgroundProcesses still get the memo.
            try {
                java.lang.reflect.Method m = android.app.ActivityManager.class
                        .getMethod("forceStopPackage", String.class);
                m.invoke(am, getPackageName());
            } catch (Throwable ignored) { }

            Toast.makeText(this,
                    "Keyboard process killed — it will restart on next focus",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            XLog.e("IME-EDITOR", "killImeProcess failed", t);
            Toast.makeText(this, "Restart failed: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String friendlyName(String shortClassName) {
        switch (shortClassName) {
            case "LauncherActivity":       return "HOME SCREEN";
            case "CalculatorActivity":     return "CALCULATOR";
            case "MusicPlayerActivity":    return "MUSIC PLAYER";
            case "GalleryActivity":        return "GALLERY";
            case "AlarmActivity":          return "ALARM";
            case "AuraBrowserActivity":    return "BROWSER";
            case "FileManagerActivity":    return "FILE MANAGER";
            default: {
                String s = shortClassName;
                if (s.endsWith("Activity")) s = s.substring(0, s.length() - 8);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (i > 0 && Character.isUpperCase(c)
                            && !Character.isUpperCase(s.charAt(i - 1))) {
                        sb.append(' ');
                    }
                    sb.append(Character.toUpperCase(c));
                }
                return sb.toString();
            }
        }
    }

    private List<ActivityEntry> ownLaunchableActivities() {
        final java.util.Set<String> ALLOW = new java.util.HashSet<>(java.util.Arrays.asList(
                "LauncherActivity",
                "CalculatorActivity",
                "MusicPlayerActivity",
                "GalleryActivity",
                "AlarmActivity",
                "AuraBrowserActivity",
                "FileManagerActivity"
        ));

        List<ActivityEntry> out = new ArrayList<>();
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(
                    getPackageName(),
                    android.content.pm.PackageManager.GET_ACTIVITIES);
            if (pi.activities == null) return out;

            for (android.content.pm.ActivityInfo ai : pi.activities) {
                if (ai.name == null) continue;
                if (!ai.enabled) continue;

                String shortName = ai.name;
                int dot = shortName.lastIndexOf('.');
                if (dot >= 0) shortName = shortName.substring(dot + 1);

                if (!ALLOW.contains(shortName)) continue;

                out.add(new ActivityEntry(
                        getPackageName() + "/" + ai.name,
                        friendlyName(shortName)));
            }
        } catch (Throwable ignored) { }
        java.util.Collections.sort(out, (a, b) ->
                a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private void showActivityPicker(final android.widget.EditText payloadInput) {
        final List<ActivityEntry> entries = ownLaunchableActivities();

        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFF000000);
        int pad = dp(10);
        container.setPadding(pad, pad, pad, pad);

        TextView header = new TextView(this);
        header.setText("PICK ACTIVITY");
        header.setTextColor(GREEN);
        header.setTextSize(14);
        header.setTypeface(Typeface.MONOSPACE);
        header.setPadding(dp(6), dp(6), dp(6), dp(10));
        container.addView(header);

        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No launchable activities.");
            empty.setTextColor(DIM);
            empty.setTextSize(12);
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setPadding(dp(6), dp(12), dp(6), dp(12));
            container.addView(empty);
        } else {
            android.widget.ScrollView sc = new android.widget.ScrollView(this);
            sc.setBackgroundColor(0xFF000000);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            sc.addView(list);

            for (ActivityEntry e : entries) {
                TextView row = new TextView(this);
                row.setText(e.label);
                row.setTextColor(GREEN);
                row.setTextSize(13);
                row.setTypeface(Typeface.MONOSPACE);
                row.setPadding(dp(14), dp(12), dp(14), dp(12));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF000000);
                bg.setCornerRadius(dp(4));
                bg.setStroke(dp(1), CARD_BORDER);
                row.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(2), dp(3), dp(2), dp(3));
                row.setLayoutParams(lp);
                row.setClickable(true);
                row.setOnClickListener(v -> {
                    payloadInput.setText(e.component);
                    dlg.dismiss();
                });
                list.addView(row);
            }

            LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
            container.addView(sc, scLp);
        }

        TextView cancel = new TextView(this);
        cancel.setText("CANCEL");
        cancel.setTextColor(GREEN);
        cancel.setTextSize(12);
        cancel.setTypeface(Typeface.MONOSPACE);
        cancel.setPadding(dp(14), dp(10), dp(14), dp(10));
        cancel.setGravity(Gravity.END);
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> dlg.dismiss());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(6);
        container.addView(cancel, cLp);

        dlg.setContentView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
            w.setDimAmount(0.7f);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        dlg.show();
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
            String kindLabel;
            if (a.type == KeyAction.TYPE_APP_LAUNCH)           kindLabel = "LAUNCH APP ";
            else if (a.type == KeyAction.TYPE_LAUNCH_ACTIVITY) kindLabel = "OPEN ACTIVITY ";
            else                                               kindLabel = "TYPE TEXT ";
            String auto = "";
            if (a.type == KeyAction.TYPE_TEXT) {
                auto = a.autoEnter ? "  [ENTER: ON]" : "  [ENTER: OFF]";
            }
            line1.setText("KEY: [" + a.keyChar + "]  →  " + kindLabel + a.payload + auto);
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


    private LinearLayout buildTesterCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(CARD_BG);
        cardBg.setCornerRadius(dp(8));
        cardBg.setStroke(dp(1), GREEN);   // bright border so it stands out
        card.setBackground(cardBg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(16);
        card.setLayoutParams(cardLp);

        TextView header = new TextView(this);
        header.setText("▌ TEST KEYBOARD");
        header.setTextColor(GREEN);
        header.setTextSize(13);
        header.setTypeface(Typeface.MONOSPACE);
        card.addView(header);

        TextView hint = new TextView(this);
        hint.setText("Tap the box below to focus it. The keyboard will slide up. "
                + "Type here to test the layout, gestures, emoji and clipboard "
                + "without leaving this screen.");
        hint.setTextColor(DIM);
        hint.setTextSize(11);
        hint.setTypeface(Typeface.MONOSPACE);
        hint.setPadding(0, dp(4), 0, dp(10));
        card.addView(hint);

        testerField = new EditText(this);
        testerField.setHint("type here...");
        testerField.setTextColor(GREEN);
        testerField.setHintTextColor(DIM);
        testerField.setTypeface(Typeface.MONOSPACE);
        testerField.setTextSize(14);
        testerField.setMinLines(3);
        testerField.setMaxLines(6);
        testerField.setGravity(Gravity.TOP | Gravity.START);
        testerField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        testerField.setPadding(dp(10), dp(8), dp(10), dp(8));

        GradientDrawable testBg = new GradientDrawable();
        testBg.setColor(0xFF000000);
        testBg.setCornerRadius(dp(6));
        testBg.setStroke(dp(1), GREEN);
        testerField.setBackground(testBg);

        LinearLayout.LayoutParams tfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(testerField, tfLp);

        // Tapping the box explicitly requests focus + shows the IME.
        testerField.setOnClickListener(v -> {
            testerField.requestFocus();
            testerField.post(() -> {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(testerField,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
        });
        testerField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                testerField.postDelayed(() -> {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(testerField,
                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }, 50L);
            }
            updateTesterStatus();
        });

        // Tiny character counter + clear button row.
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.setPadding(0, dp(8), 0, 0);

        testerStatus = new TextView(this);
        testerStatus.setTextColor(DIM);
        testerStatus.setTextSize(11);
        testerStatus.setTypeface(Typeface.MONOSPACE);
        testerStatus.setText("");
        bottomRow.addView(testerStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clearBtn = new TextView(this);
        clearBtn.setText("CLEAR");
        clearBtn.setTextColor(GREEN);
        clearBtn.setTextSize(11);
        clearBtn.setTypeface(Typeface.MONOSPACE);
        clearBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable clearBg = new GradientDrawable();
        clearBg.setColor(0xFF0A1A0A);
        clearBg.setCornerRadius(dp(4));
        clearBg.setStroke(dp(1), CARD_BORDER);
        clearBtn.setBackground(clearBg);
        clearBtn.setClickable(true);
        clearBtn.setOnClickListener(v -> {
            testerField.setText("");
            testerField.requestFocus();
            updateTesterStatus();
        });
        bottomRow.addView(clearBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(bottomRow);

        // Live char counter.
        testerField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                updateTesterStatus();
            }
        });

        // Focus on attach so a single tap (or the showSoftInput post above)
        // reliably brings up the IME.
        testerField.post(() -> updateTesterStatus());

        return card;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (testerField != null) {
            // Refresh status when coming back from the overlay permission
            // dialog or a picker.
            updateTesterStatus();
        }
    }
    private void updateTesterStatus() {
        if (testerStatus == null || testerField == null) return;
        int n = testerField.getText() == null ? 0 : testerField.getText().length();
        boolean focused = testerField.hasFocus();
        testerStatus.setText((focused ? "FOCUSED" : "IDLE") + "  ·  " + n + " chars");
        testerStatus.setTextColor(focused ? 0xFFFFAA00 : DIM);
    }

    private void broadcastLayoutChanged() {
        ImeColorStore s = ImeColorStore.get(this);
        LiveLayoutBridge.LayoutSnapshot snap =
                new LiveLayoutBridge.LayoutSnapshot(
                        s.getRowHeightDp(),
                        s.getRowGapDp(),
                        s.getKeyGapDp(),
                        s.getKeyCornerRadiusDp(),
                        s.getTabRowHeightDp(),
                        System.currentTimeMillis());

        // Direct call into the service's public entry point.
        // (Writes the live channel file; applied by the IME process within ~180ms.)
        BasicImeService.pushLiveLayout(this, snap);

        // Keep the legacy broadcast too — harmless, and lets an older
        // service build in the same APK react until it's rebuilt.
        try {
            Intent i = new Intent(BasicImeService.ACTION_LAYOUT_CHANGED);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable ignored) { }
    }
    private View makeOnOffRow(String label, boolean initial,
                              java.util.function.Consumer<Boolean> onChange) {
        final boolean[] state = { initial };

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(GREEN);
        text.setTextSize(12);
        text.setTypeface(Typeface.MONOSPACE);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView btn = new TextView(this);
        btn.setTypeface(Typeface.MONOSPACE);
        btn.setTextSize(12);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), dp(8), dp(14), dp(8));
        btn.setClickable(true);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(4));
            if (state[0]) {
                btn.setText("ON");
                btn.setTextColor(0xFF000000);
                bg.setColor(GREEN);
                bg.setStroke(dp(1), GREEN);
            } else {
                btn.setText("OFF");
                btn.setTextColor(DIM);
                bg.setColor(0xFF0A0A0A);
                bg.setStroke(dp(1), CARD_BORDER);
            }
            btn.setBackground(bg);
        };
        refresh[0].run();

        btn.setOnClickListener(v -> {
            state[0] = !state[0];
            refresh[0].run();
            onChange.accept(state[0]);
        });

        row.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

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

        TextView actBtn = new TextView(this);
        actBtn.setText("ACTIVITY");
        actBtn.setTypeface(Typeface.MONOSPACE);
        actBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        typeRow.addView(actBtn);

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
        boolean storedAutoEnter = false;
        if (existing != null) {
            KeyAction fresh = GestureActionStore.get(this).byId(existing.id);
            storedAutoEnter = (fresh != null) ? fresh.autoEnter : existing.autoEnter;
        }
        final boolean[] autoEnterState = { storedAutoEnter };
        final View autoEnterRow = makeOnOffRow(
                "Send ENTER after this action",
                storedAutoEnter,
                v -> autoEnterState[0] = v);
        root.addView(autoEnterRow);
        Runnable refreshTypeUi = () -> {
            boolean isText     = type[0] == KeyAction.TYPE_TEXT;
            boolean isApp      = type[0] == KeyAction.TYPE_APP_LAUNCH;
            boolean isActivity = type[0] == KeyAction.TYPE_LAUNCH_ACTIVITY;

            textBtn.setTextColor(isText ? 0xFF000000 : GREEN);
            textBtn.setBackgroundColor(isText ? GREEN : 0xFF0A0A0A);
            appBtn.setTextColor(isApp ? 0xFF000000 : GREEN);
            appBtn.setBackgroundColor(isApp ? GREEN : 0xFF0A0A0A);
            actBtn.setTextColor(isActivity ? 0xFF000000 : GREEN);
            actBtn.setBackgroundColor(isActivity ? GREEN : 0xFF0A0A0A);

            if (isText) {
                payloadLabel.setText("Text to insert");
                payloadInput.setEnabled(true);
                pickAppBtn.setVisibility(View.GONE);
            } else if (isApp) {
                payloadLabel.setText("App package (or use PICK APP)");
                payloadInput.setEnabled(false);
                pickAppBtn.setText("PICK APP");
                pickAppBtn.setVisibility(View.VISIBLE);
                pickAppBtn.setOnClickListener(v ->
                        AppPickerDialog.show(this, (pkg, label) -> payloadInput.setText(pkg)));
            } else {
                payloadLabel.setText("Activity (or use PICK ACTIVITY)");
                payloadInput.setEnabled(false);
                pickAppBtn.setText("PICK ACTIVITY");
                pickAppBtn.setVisibility(View.VISIBLE);
                pickAppBtn.setOnClickListener(v -> showActivityPicker(payloadInput));
            }
            autoEnterRow.setVisibility(isText ? View.VISIBLE : View.GONE);
            if (!isText && existing != null) payloadInput.setText(existing.payload);
        };

        textBtn.setOnClickListener(v -> { type[0] = KeyAction.TYPE_TEXT;            refreshTypeUi.run(); });
        appBtn .setOnClickListener(v -> { type[0] = KeyAction.TYPE_APP_LAUNCH;      refreshTypeUi.run(); });
        actBtn .setOnClickListener(v -> { type[0] = KeyAction.TYPE_LAUNCH_ACTIVITY; refreshTypeUi.run(); });
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
                        key, type[0], payload, autoEnterState[0]);

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