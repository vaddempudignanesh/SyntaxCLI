package vaddempudi.gnanesh.syntaxcli.ime;

public final class KeyboardConfig {

    private KeyboardConfig() {}

    public static final float KEYBOARD_TOP_PADDING_DP    = 8f;
    public static final float KEYBOARD_BOTTOM_PADDING_DP = 8f;

    public static final float ROW_HEIGHT_DP              = 60f;
    public static final float ROW_GAP_DP                 = 9f;

    public static final float KEY_HORIZONTAL_GAP_DP      = 3f;
    public static final float KEY_CORNER_RADIUS_DP       = 6f;
    public static final float KEY_BORDER_WIDTH_DP        = 1f;

    public static final float ASDF_SIDE_PADDING_DP       = 17f;

    public static final float TEXT_SIZE_LETTER_SP        = 22f;
    public static final float TEXT_SIZE_SPECIAL_SP       = 16f;

    public static final float NAV_ICON_TEXT_SIZE_SP      = 20f;
    public static final float NAV_ICON_WEIGHT            = 0.85f;
    public static final float NAV_ICON_ROW_HEIGHT_EXTRA_DP = 4f;
    public static final String NAV_ICON_LEFT_LABEL       = "\u2022";
    public static final String NAV_ICON_RIGHT_LABEL      = "\u2022";

    public static final float SPECIAL_TEXT_SIZE_FACTOR   = 0.72f;
    public static final float SPECIAL_BORDER_WIDTH_DP    = 1f;

    public static final long REPEAT_START_MS             = 400L;
    public static final long REPEAT_INTERVAL_MS          = 60L;

    public static final long SHIFT_DOUBLE_TAP_MS         = 300L;

    public static final float PREVIEW_SIZE_DP            = 62f;
    public static final float PREVIEW_GAP_DP             = 60f;
    public static final float PREVIEW_TEXT_SIZE_SP       = 34f;
    public static final float PREVIEW_CORNER_RADIUS_DP   = 10f;
    public static final float PREVIEW_BORDER_WIDTH_DP    = 1.5f;

    public static final int COLOR_BACKGROUND             = 0xFF000000;
    public static final int COLOR_KEY                    = 0xFF000000;
    public static final int COLOR_KEY_PRESSED            = 0xFF1E4D1E;
    public static final int COLOR_TEXT                   = 0xFF33FF33;
    public static final int COLOR_BORDER                 = 0xFF1F3F1F;
    public static final int COLOR_SPECIAL_KEY            = 0xFF080808;
    public static final int COLOR_PREVIEW_BG             = 0xFF0A0A0A;
    public static final int COLOR_PREVIEW_TEXT           = 0xFF33FF33;
    public static final int COLOR_PREVIEW_BORDER         = 0xFF33FF33;

    public static Float rowHeightOverride(int rowIndex) {
        return null;
    }

    public static Float rowSidePaddingOverride(int rowIndex) {
        return null;
    }

    public static final float KEY_INNER_PADDING_TOP_DP    = 0f;
    public static final float KEY_INNER_PADDING_BOTTOM_DP = 0f;

    public static final boolean SHOW_NAV_DOTS             = true;
}