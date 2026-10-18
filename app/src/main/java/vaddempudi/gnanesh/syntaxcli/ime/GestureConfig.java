package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.content.SharedPreferences;

public final class GestureConfig {

    private static final String PREFS = "ime_gesture_config";
    private static final String K_SPACE_LONG_ACTION   = "space_long_action";
    private static final String K_SPACE_DOUBLE_ACTION = "space_double_action";
    private static final String K_TEXT_COLOR          = "text_color";
    private static final String K_BG_COLOR            = "bg_color";
    private static final String K_KEY_COLOR           = "key_color";

    private static volatile GestureConfig INSTANCE;
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;

    private volatile String spaceLongActionId   = null;
    private volatile String spaceDoubleActionId = null;
    private volatile int textColor  = KeyboardConfig.COLOR_TEXT;
    private volatile int bgColor    = KeyboardConfig.COLOR_BACKGROUND;
    private volatile int keyColor   = KeyboardConfig.COLOR_KEY;

    private GestureConfig(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadAll();
    }

    public static GestureConfig get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) INSTANCE = new GestureConfig(ctx);
            }
        }
        return INSTANCE;
    }

    private void loadAll() {
        spaceLongActionId   = prefs.getString(K_SPACE_LONG_ACTION, null);
        spaceDoubleActionId = prefs.getString(K_SPACE_DOUBLE_ACTION, null);
        textColor = prefs.getInt(K_TEXT_COLOR, KeyboardConfig.COLOR_TEXT);
        bgColor   = prefs.getInt(K_BG_COLOR,   KeyboardConfig.COLOR_BACKGROUND);
        keyColor  = prefs.getInt(K_KEY_COLOR,  KeyboardConfig.COLOR_KEY);
    }

    public String getSpaceLongActionId()   { return spaceLongActionId; }
    public String getSpaceDoubleActionId() { return spaceDoubleActionId; }
    public int getTextColor() { return textColor; }
    public int getBgColor()   { return bgColor; }
    public int getKeyColor()  { return keyColor; }

    public void setSpaceLongActionId(String id) {
        spaceLongActionId = id;
        prefs.edit().putString(K_SPACE_LONG_ACTION, id).apply();
    }

    public void setSpaceDoubleActionId(String id) {
        spaceDoubleActionId = id;
        prefs.edit().putString(K_SPACE_DOUBLE_ACTION, id).apply();
    }

    public void setTextColor(int c) {
        textColor = c;
        prefs.edit().putInt(K_TEXT_COLOR, c).apply();
    }

    public void setBgColor(int c) {
        bgColor = c;
        prefs.edit().putInt(K_BG_COLOR, c).apply();
    }

    public void setKeyColor(int c) {
        keyColor = c;
        prefs.edit().putInt(K_KEY_COLOR, c).apply();
    }
}