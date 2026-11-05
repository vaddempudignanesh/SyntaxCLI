package vaddempudi.gnanesh.syntaxcli.ime;

public final class ClipEntry {

    public static final int TYPE_TEXT  = 1;
    public static final int TYPE_IMAGE = 2;
    public static final int TYPE_FILE  = 3;

    public final int type;
    public final String text;
    public final String localPath;
    public final String mimeType;
    public final String displayName;
    public final long timestamp;   // epoch millis

    public ClipEntry(int type, String text, String localPath,
                     String mimeType, String displayName, long timestamp) {
        this.type = type;
        this.text = text;
        this.localPath = localPath;
        this.mimeType = mimeType;
        this.displayName = displayName;
        this.timestamp = timestamp;
    }

    public String dedupeKey() {
        if (type == TYPE_TEXT) return "T:" + text;
        return type + ":" + localPath;
    }
}