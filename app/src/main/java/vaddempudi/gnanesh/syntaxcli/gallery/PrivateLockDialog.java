package vaddempudi.gnanesh.syntaxcli.gallery;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


public final class PrivateLockDialog {

    public interface OnResult {
        /** Called when the user is authenticated (create succeeded, or verify succeeded). */
        void onUnlocked();
        /** Called if the user cancelled or was rejected. */
        void onCancelled();
    }

    private PrivateLockDialog() {}

    public static void show(Context ctx, PrivateVault vault, OnResult cb) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCancelable(false);

        final boolean firstTime = !vault.hasPassword();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 24), dp(ctx, 24), dp(ctx, 24), dp(ctx, 24));
        root.setBackgroundColor(0xFF000000);

        // ── Title ────────────────────────────────────────────────────
        TextView title = new TextView(ctx);
        title.setText(firstTime ? "CREATE PRIVATE PASSWORD" : "UNLOCK PRIVATE");
        title.setTextColor(0xFF33FF33);
        title.setTypeface(Typeface.MONOSPACE);
        title.setTextSize(14);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(ctx, 12));
        root.addView(title);

        // ── Password field ──────────────────────────────────────────
        EditText pw = new EditText(ctx);
        pw.setHint(firstTime ? "new password" : "password");
        pw.setHintTextColor(0xFF00AA00);
        pw.setTextColor(0xFF33FF33);
        pw.setTypeface(Typeface.MONOSPACE);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setBackgroundColor(0xFF0A0A0A);
        pw.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        root.addView(pw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Confirm field (first time only) ─────────────────────────
        final EditText confirm;
        if (firstTime) {
            confirm = new EditText(ctx);
            confirm.setHint("confirm password");
            confirm.setHintTextColor(0xFF00AA00);
            confirm.setTextColor(0xFF33FF33);
            confirm.setTypeface(Typeface.MONOSPACE);
            confirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            confirm.setBackgroundColor(0xFF0A0A0A);
            confirm.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.topMargin = dp(ctx, 8);
            root.addView(confirm, cLp);
        } else {
            confirm = null;
        }

        // ── Buttons (CANCEL / UNLOCK) ───────────────────────────────
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(ctx, 20), 0, 0);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRow.setLayoutParams(btnLp);

        TextView cancel = makeButton(ctx, "CANCEL", 0xFF666666);
        cancel.setOnClickListener(v -> {
            dlg.dismiss();
            if (cb != null) cb.onCancelled();
        });
        btnRow.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ok = makeButton(ctx, firstTime ? "CREATE" : "UNLOCK", 0xFF33FF33);
        ok.setOnClickListener(v -> {
            String p1 = pw.getText().toString();
            if (p1.isEmpty()) {
                Toast.makeText(ctx, "Password cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            if (firstTime) {
                if (p1.length() < 4) {
                    Toast.makeText(ctx, "At least 4 characters", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (confirm != null) {
                    String p2 = confirm.getText().toString();
                    if (!p1.equals(p2)) {
                        Toast.makeText(ctx, "Passwords do not match", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                if (!vault.createPassword(p1)) {
                    Toast.makeText(ctx, "Could not save password", Toast.LENGTH_SHORT).show();
                    return;
                }
                dlg.dismiss();
                if (cb != null) cb.onUnlocked();
            } else {
                if (!vault.verifyPassword(p1)) {
                    Toast.makeText(ctx, "Wrong password", Toast.LENGTH_SHORT).show();
                    return;
                }
                dlg.dismiss();
                if (cb != null) cb.onUnlocked();
            }
        });
        btnRow.addView(ok, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(btnRow);

        // ── CHANGE PASSWORD — only in unlock mode ──────────────────
        // Opens the existing three-field change dialog. The unlock dialog
        // is dismissed so the two dialogs never stack.
        if (!firstTime) {
            TextView changePw = makeButton(ctx, "CHANGE PASSWORD", 0xFF33FF33);
            changePw.setTextSize(11);
            LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chLp.topMargin = dp(ctx, 10);
            changePw.setLayoutParams(chLp);
            changePw.setOnClickListener(v -> {
                dlg.dismiss();
                if (cb != null) cb.onCancelled();   // user backed out of unlock
                showChange(ctx, vault, new OnResult() {
                    @Override public void onUnlocked() {
                        // Password changed — the user still needs to unlock
                        // with the NEW password. Re-open the unlock dialog.
                        show(ctx, vault, cb);
                    }
                    @Override public void onCancelled() { }
                });
            });
            root.addView(changePw);
        }

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
            w.setDimAmount(0.7f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(
                    (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.9f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    private static TextView makeButton(Context ctx, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0A0A0A);
        bg.setCornerRadius(dp(ctx, 6));
        bg.setStroke(dp(ctx, 1), color);
        tv.setBackground(bg);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }



    public static void showChange(Context ctx, PrivateVault vault, PrivateLockDialog.OnResult cb) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCancelable(false);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 24), dp(ctx, 24), dp(ctx, 24), dp(ctx, 24));
        root.setBackgroundColor(0xFF000000);

        TextView title = new TextView(ctx);
        title.setText("CHANGE PRIVATE PASSWORD");
        title.setTextColor(0xFF33FF33);
        title.setTypeface(Typeface.MONOSPACE);
        title.setTextSize(14);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(ctx, 12));
        root.addView(title);

        EditText oldPw = new EditText(ctx);
        oldPw.setHint("current password");
        oldPw.setHintTextColor(0xFF00AA00);
        oldPw.setTextColor(0xFF33FF33);
        oldPw.setTypeface(Typeface.MONOSPACE);
        oldPw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        oldPw.setBackgroundColor(0xFF0A0A0A);
        oldPw.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        root.addView(oldPw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText newPw = new EditText(ctx);
        newPw.setHint("new password");
        newPw.setHintTextColor(0xFF00AA00);
        newPw.setTextColor(0xFF33FF33);
        newPw.setTypeface(Typeface.MONOSPACE);
        newPw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        newPw.setBackgroundColor(0xFF0A0A0A);
        newPw.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        LinearLayout.LayoutParams newLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        newLp.topMargin = dp(ctx, 8);
        root.addView(newPw, newLp);

        EditText confirmPw = new EditText(ctx);
        confirmPw.setHint("confirm new password");
        confirmPw.setHintTextColor(0xFF00AA00);
        confirmPw.setTextColor(0xFF33FF33);
        confirmPw.setTypeface(Typeface.MONOSPACE);
        confirmPw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirmPw.setBackgroundColor(0xFF0A0A0A);
        confirmPw.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(ctx, 8);
        root.addView(confirmPw, cLp);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(ctx, 20), 0, 0);

        TextView cancel = makeButton(ctx, "CANCEL", 0xFF666666);
        cancel.setOnClickListener(v -> {
            dlg.dismiss();
            if (cb != null) cb.onCancelled();
        });
        btnRow.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ok = makeButton(ctx, "CHANGE", 0xFF33FF33);
        ok.setOnClickListener(v -> {
            String oldS = oldPw.getText().toString();
            String newS = newPw.getText().toString();
            String conS = confirmPw.getText().toString();

            if (oldS.isEmpty() || newS.isEmpty() || conS.isEmpty()) {
                Toast.makeText(ctx, "All fields are required", Toast.LENGTH_SHORT).show();
                return;
            }
            if (newS.length() < 4) {
                Toast.makeText(ctx, "New password must be at least 4 characters",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (!newS.equals(conS)) {
                Toast.makeText(ctx, "New passwords do not match", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!vault.verifyPassword(oldS)) {
                Toast.makeText(ctx, "Current password is wrong", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!vault.changePassword(oldS, newS)) {
                Toast.makeText(ctx, "Could not change password", Toast.LENGTH_SHORT).show();
                return;
            }
            dlg.dismiss();
            if (cb != null) cb.onUnlocked();
        });
        btnRow.addView(ok, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(btnRow);

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
            w.setDimAmount(0.7f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(
                    (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.9f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}