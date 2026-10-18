package vaddempudi.gnanesh.syntaxcli.ime;

public final class KeyAction {

    public static final int TYPE_TEXT       = 1;
    public static final int TYPE_APP_LAUNCH = 2;

    public final String id;
    public final String keyChar;      // the letter that triggers this, e.g. "s"
    public final int type;
    public final String payload;      // text to insert OR package name to launch

    public KeyAction(String id, String keyChar, int type, String payload) {
        this.id = id;
        this.keyChar = keyChar;
        this.type = type;
        this.payload = payload;
    }
}