package vaddempudi.gnanesh.syntaxcli.ime;

public final class KeyAction {

    public static final int TYPE_TEXT            = 1;
    public static final int TYPE_APP_LAUNCH      = 2;
    public static final int TYPE_LAUNCH_ACTIVITY = 3;

    public final String  id;
    public final String  keyChar;
    public final int     type;
    public final String  payload;
    public final boolean autoEnter;

    public KeyAction(String id, String keyChar, int type, String payload) {
        this(id, keyChar, type, payload, false);
    }

    public KeyAction(String id, String keyChar, int type, String payload,
                     boolean autoEnter) {
        this.id = id;
        this.keyChar = keyChar;
        this.type = type;
        this.payload = payload;
        this.autoEnter = autoEnter;
    }
}