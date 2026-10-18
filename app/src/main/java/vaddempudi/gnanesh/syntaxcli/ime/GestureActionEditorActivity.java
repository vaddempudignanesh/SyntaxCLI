package vaddempudi.gnanesh.syntaxcli.ime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.UUID;

public class GestureActionEditorActivity extends Activity {

    private static final int BG     = 0xFF000000;
    private static final int GREEN  = 0xFF33FF33;
    private static final int DIM    = 0xFF00AA00;

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        TextView title = new TextView(this);
        title.setText("GESTURE ACTIONS");
        title.setTextColor(GREEN);
        title.setTextSize(18);
        title.setTypeface(Typeface.MONOSPACE);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Assign an action to a key. Long-press that key on the keyboard to run it.\n" +
                "Long-press SPACE to open this screen.");
        sub.setTextColor(DIM);
        sub.setTextSize(11);
        sub.setPadding(0, dp(8), 0, dp(12));
        root.addView(sub);

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

                        // ★ Duplicate-key check
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