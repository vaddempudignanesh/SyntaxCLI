package vaddempudi.gnanesh.syntaxcli.ime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.UUID;

public class GestureActionEditorActivity extends Activity {

    private static final int BG     = 0xFF000000;
    private static final int GREEN  = 0xFF33FF33;
    private static final int DIM    = 0xFF00AA00;

    private LinearLayout list;

    // ★ Pending colors — not committed until the user taps RESTART
    private int pendingTextColor;
    private int pendingBgColor;
    private boolean colorsDirty = false;

    private TextView colorStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImeColorStore store = ImeColorStore.get(this);
        pendingTextColor = store.getTextColor();
        pendingBgColor   = store.getBgColor();
        build();
        renderList();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(BG);

        // ── Title row with color icon on the right ──
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("GESTURE ACTIONS");
        title.setTextColor(GREEN);
        title.setTextSize(18);
        title.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(title, titleLp);

        ImageButton colorBtn = new ImageButton(this);
        colorBtn.setImageResource(android.R.drawable.ic_menu_edit);
        colorBtn.setColorFilter(GREEN);
        colorBtn.setBackgroundColor(0x00000000);
        colorBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        colorBtn.setOnClickListener(this::showColorMenu);
        LinearLayout.LayoutParams colorLp =
                new LinearLayout.LayoutParams(dp(40), dp(40));
        titleRow.addView(colorBtn, colorLp);

        root.addView(titleRow);

        TextView sub = new TextView(this);
        sub.setText("Assign an action to a key. Long-press that key on the keyboard to run it.\n" +
                "Long-press SPACE to open this screen.");
        sub.setTextColor(DIM);
        sub.setTextSize(11);
        sub.setPadding(0, dp(8), 0, dp(12));
        root.addView(sub);

        // ── Color status + RESTART row ──
        colorStatus = new TextView(this);
        colorStatus.setTextColor(GREEN);
        colorStatus.setTextSize(12);
        colorStatus.setTypeface(Typeface.MONOSPACE);
        colorStatus.setPadding(0, dp(4), 0, dp(4));
        refreshColorStatus();
        root.addView(colorStatus);

        Button restartBtn = new Button(this);
        restartBtn.setText("RESTART");
        restartBtn.setTextColor(0xFF000000);
        restartBtn.setBackgroundColor(GREEN);
        restartBtn.setTypeface(Typeface.MONOSPACE);
        restartBtn.setEnabled(false);
        restartBtn.setOnClickListener(v -> {
            ImeColorStore.get(this).save(pendingTextColor, pendingBgColor);
            Toast.makeText(this, "Saved. Reopening…", Toast.LENGTH_SHORT).show();
            // Recreate the activity to apply the new theme
            recreate();
        });
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(4);
        rLp.bottomMargin = dp(8);
        root.addView(restartBtn, rLp);
        this.restartButton = restartBtn;

        Button add = new Button(this);
        add.setText("+ ADD ACTION");
        add.setTextColor(GREEN);
        add.setBackgroundColor(BG);
        add.setTypeface(Typeface.MONOSPACE);
        add.setOnClickListener(v -> showAddDialog());
        root.addView(add);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(12), 0, 0);
        root.addView(list);

        scroll.addView(root);
        setContentView(scroll);
    }

    private Button restartButton;

    private void refreshColorStatus() {
        if (colorStatus == null) return;
        colorStatus.setText(
                "Text color:  #" + hex(pendingTextColor) + "\n" +
                        "Background:  #" + hex(pendingBgColor)
                        + (colorsDirty ? "\n\n⚠ Tap RESTART to apply" : ""));
        if (restartButton != null) restartButton.setEnabled(colorsDirty);
    }

    private static String hex(int c) {
        return String.format("%08X", c);
    }

    private void showColorMenu(View anchor) {
        ContextThemeWrapper w = new ContextThemeWrapper(this, R.style.PopupMenu_Black);
        PopupMenu popup = new PopupMenu(w, anchor);
        popup.getMenu().add(0, 1, 0, "Text color");
        popup.getMenu().add(0, 2, 1, "Background color");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    ColorPickerDialog.show(this, "TEXT COLOR", pendingTextColor, c -> {
                        pendingTextColor = c;
                        colorsDirty = true;
                        refreshColorStatus();
                    });
                    return true;
                case 2:
                    ColorPickerDialog.show(this, "BACKGROUND COLOR", pendingBgColor, c -> {
                        pendingBgColor = c;
                        colorsDirty = true;
                        refreshColorStatus();
                    });
                    return true;
            }
            return false;
        });
        try {
            java.lang.reflect.Field[] fields = popup.getClass().getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                if (!"mPopup".equals(f.getName())) continue;
                f.setAccessible(true);
                Object helper = f.get(popup);
                try {
                    java.lang.reflect.Field pf = helper.getClass()
                            .getDeclaredField("mPopup");
                    pf.setAccessible(true);
                    Object lp = pf.get(helper);
                    java.lang.reflect.Method bg = lp.getClass()
                            .getMethod("setBackgroundDrawable",
                                    android.graphics.drawable.Drawable.class);
                    bg.invoke(lp,
                            getDrawable(R.drawable.popup_bg));
                } catch (Exception ignored) { }
                break;
            }
        } catch (Exception ignored) { }
        popup.show();
    }

    // ────────────────────────────────────────────────────────────
    //  The rest is UNCHANGED from your file
    // ────────────────────────────────────────────────────────────

    private void renderList() {
        list.removeAllViews();
        List<KeyAction> actions = GestureActionStore.get(this).all();
        if (actions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No actions yet.");
            empty.setTextColor(DIM);
            empty.setTypeface(Typeface.MONOSPACE);
            list.addView(empty);
            return;
        }

        for (KeyAction a : actions) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(0xFF0A0A0A);
            rowBg.setCornerRadius(dp(6));
            rowBg.setStroke(dp(1), 0xFF1F3F1F);
            row.setBackground(rowBg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, dp(4), 0, dp(4));
            row.setLayoutParams(rlp);

            TextView line1 = new TextView(this);
            line1.setText("KEY: [" + a.keyChar + "]  →  " +
                    (a.type == KeyAction.TYPE_APP_LAUNCH ? "LAUNCH " : "TYPE ") + a.payload);
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
                AppPickerDialog.show(this, (pkg, label) -> {
                    payloadInput.setText(pkg);
                }));
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
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(GREEN);
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        String key = keyInput.getText().toString().trim();
                        String payload = payloadInput.getText().toString().trim();
                        if (key.isEmpty() || payload.isEmpty()) {
                            Toast.makeText(this, "Key and payload required",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (key.length() > 1) key = key.substring(0, 1);

                        KeyAction dup = GestureActionStore.get(this).byKeyChar(key);
                        if (dup != null && (existing == null || !dup.id.equals(existing.id))) {
                            Toast.makeText(this,
                                    "Key '" + key + "' already bound to another action",
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
}