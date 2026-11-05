package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings for the numeric (password / number) keypad.
 *
 *  - padExtraChar : the character shown on the bottom-left key that used to be ",".
 *                   Set it to any single letter, e.g. "b", "z", "q", or back to ",".
 *                   A gesture action bound to that letter will fire when the key
 *                   is long-pressed (LETTER_LONG_PRESS_TRIGGER already dispatches
 *                   the key's lower-case char).
 *
 *  - digitOrder   : a 10-character permutation of "0123456789" that controls the
 *                   visual order of the digit keys in the grid. Only the FIRST
 *                   nine characters are placed on the 3x3 digit block (rows 1..3);
 *                   the 10th character is placed on the zero key in row 4.
 *
 *  - reverseRows  : if true, each of the first three rows is drawn right-to-left
 *                   instead of left-to-right. This gives the "bottom-right to
 *                   top-left" visual flow the user asked for.
 */
public final class NumericPadConfig {

    private static final String PREFS = "ime_numeric_pad";
    private static final String K_CHAR = "extra_char";
    private static final String K_ORDER = "digit_order";
    private static final String K_REV = "reverse_rows";

    public static final String DEFAULT_CHAR  = ",";
    public static final String DEFAULT_ORDER = "7894561230";

    private static volatile NumericPadConfig INSTANCE;
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;

    private NumericPadConfig(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static NumericPadConfig get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) INSTANCE = new NumericPadConfig(ctx);
            }
        }
        return INSTANCE;
    }

    public String getExtraChar() {
        String s = prefs.getString(K_CHAR, DEFAULT_CHAR);
        if (s == null || s.isEmpty()) return DEFAULT_CHAR;
        return s;
    }

    public void setExtraChar(String c) {
        if (c == null) c = DEFAULT_CHAR;
        c = c.trim();
        if (c.isEmpty()) c = DEFAULT_CHAR;
        if (c.length() > 1) c = c.substring(0, 1);
        prefs.edit().putString(K_CHAR, c).apply();
    }

    public String getDigitOrder() {
        String s = prefs.getString(K_ORDER, DEFAULT_ORDER);
        if (!isValidOrder(s)) return DEFAULT_ORDER;
        return s;
    }

    public void setDigitOrder(String order) {
        if (!isValidOrder(order)) return;
        prefs.edit().putString(K_ORDER, order).apply();
    }

    public boolean isReverseRows() {
        return prefs.getBoolean(K_REV, false);
    }

    public void setReverseRows(boolean v) {
        prefs.edit().putBoolean(K_REV, v).apply();
    }

    /** A valid order is exactly the digits 0..9 in some permutation. */
    public static boolean isValidOrder(String s) {
        if (s == null || s.length() != 10) return false;
        boolean[] seen = new boolean[10];
        for (int i = 0; i < 10; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
            int d = c - '0';
            if (seen[d]) return false;
            seen[d] = true;
        }
        return true;
    }
}