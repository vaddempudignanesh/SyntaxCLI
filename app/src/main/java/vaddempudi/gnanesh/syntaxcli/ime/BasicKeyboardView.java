package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class BasicKeyboardView extends View {

    public interface Listener {
        void onKey(int code, String text);
    }

    private boolean shiftLocked = false;

    public static final int CLIPBOARD_TRIGGER         = -1005;

    private float cDownX = 0f;
    private float cDownY = 0f;
    private boolean cSwipeArmed = false;
    private boolean cSwipeFired = false;


    private long enterDownTime = 0L;
    private long spaceDownTime = 0L;
    private long lastSpaceUpTime = 0L;

    public static final int LETTER_LONG_PRESS_TRIGGER = -1004;
    private HitKey letterLongPressKey = null;
    private Runnable letterLongPressRunnable = null;

    private Runnable pendingLongPress = null;
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());

    public static final int EMOJI_PANEL_TRIGGER       = -1001;
    public static final int GESTURE_EDITOR_TRIGGER    = -1002;
    public static final int GESTURE_DOUBLE_TRIGGER    = -1003;

    private boolean longPressHandled = false;

    private final float density;
    private final float scaledDensity;

    private HitKey shrinkKey = null;
    private long    shrinkStartMs = 0L;

    private Listener listener;
    private BasicKeyboardLayout layout;
    private boolean shifted = false;
    private boolean symbols = false;
    private boolean symbols2 = false;
    private boolean symbols3 = false;

    private Bitmap wallpaperBitmap;
    private String wallpaperBitmapPath;
    private final Paint wallpaperBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF wallpaperDst = new RectF();

    private final Paint wallpaperDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF keyRect = new RectF();

    private final List<HitKey> hitKeys = new ArrayList<>();
    private HitKey pressed = null;

    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private HitKey repeatKey = null;
    private Runnable repeatRunnable;

    private KeyBubbleOverlay bubbleOverlay;

    public BasicKeyboardView(Context context) {
        super(context);
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        density = dm.density;
        scaledDensity = dm.scaledDensity;
        layout = new BasicKeyboardLayout();

        ImeColorStore colors = ImeColorStore.get(context);

        keyPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(KeyboardConfig.KEY_BORDER_WIDTH_DP * density);
        borderPaint.setColor(KeyboardConfig.COLOR_BORDER);

        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(colors.getTextColor());

        if (colors.isWallpaperEnabled() && !colors.hasVideoWallpaper()) {
            reloadWallpaperBitmap(colors);
        } else if (!colors.isWallpaperEnabled()) {
            setBackgroundColor(colors.getBgColor());
        }

        setFocusableInTouchMode(true);

        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (repeatKey != null && listener != null) {
                    dispatch(repeatKey.key);
                    repeatHandler.postDelayed(this, KeyboardConfig.REPEAT_INTERVAL_MS);
                }
            }
        };
    }

    public void setBubbleOverlay(KeyBubbleOverlay overlay) { this.bubbleOverlay = overlay; }

    public boolean isShiftLocked() { return shiftLocked; }
    public void setShiftLocked(boolean s) { shiftLocked = s; invalidate(); }

    private long lastShiftTapMs = 0L;

    public boolean registerShiftTap() {
        long now = System.currentTimeMillis();
        boolean second = (now - lastShiftTapMs) <= KeyboardConfig.SHIFT_DOUBLE_TAP_MS;
        lastShiftTapMs = now;
        return second;
    }

    public void clearManualShift() { shifted = false; invalidate(); }
    public void clearShiftLock()   { shiftLocked = false; shifted = false; invalidate(); }

    public void setListener(Listener l) { this.listener = l; }

    private void setPressedKey(HitKey hk) {
        if (pressed == hk) return;

        ImeColorStore colors = ImeColorStore.get(getContext());
        boolean animOn = colors.isKeyShrinkEnabled();

        if (animOn) {
            shrinkKey = hk;
            shrinkStartMs = System.currentTimeMillis();
            postInvalidateOnAnimation();
            long down = colors.getKeyShrinkDownMs();
            if (hk != null) {
                postDelayed(this::invalidate, down + 5);
            } else {
                postDelayed(this::invalidate, down + 100);
            }
        } else {
            shrinkKey = null;
        }

        pressed = hk;
        invalidate();
    }

    private float currentShrinkScale(HitKey hk) {
        ImeColorStore colors = ImeColorStore.get(getContext());
        if (!colors.isKeyShrinkEnabled()) return 1f;
        if (hk == null || hk != shrinkKey) return 1f;

        float target = colors.getKeyShrinkIntensity();
        long  down   = colors.getKeyShrinkDownMs();
        long  up     = down + 95L;                 // release takes a bit longer for snap

        long elapsed = System.currentTimeMillis() - shrinkStartMs;

        if (pressed == hk) {
            if (elapsed >= down) return target;
            float t = elapsed / (float) down;
            return 1f - (1f - target) * (float) Math.sin(t * Math.PI / 2f);
        } else {
            if (elapsed >= up) return 1f;
            float t = elapsed / (float) up;
            float eased = 1f - (float) Math.pow(1f - t, 3);
            return target + (1f - target) * eased;
        }
    }
    public boolean isShifted()  { return shifted; }
    public void setShifted(boolean s) { shifted = s; invalidate(); }

    public boolean isSymbols()  { return symbols; }
    public boolean isSymbols2() { return symbols2; }
    public boolean isSymbols3() { return symbols3; }

    public void setPage(int page) {
        symbols = false; symbols2 = false; symbols3 = false;
        switch (page) {
            case 1: symbols = true; break;
            case 2: symbols2 = true; break;
            case 3: symbols3 = true; break;
            default: break;
        }
        shifted = false;
        shiftLocked = false;
        if (bubbleOverlay != null) bubbleOverlay.hide();
        rebuildHitKeys();
        requestLayout();
        invalidate();
    }

    private List<BasicKey[]> rows() {
        if (symbols3) return layout.symbolPage3;
        if (symbols2) return layout.symbolPage2;
        if (symbols)  return layout.symbolPage1;
        return layout.letterRows;
    }

    private int rowCount() { return rows().size(); }

    private float rowHeightDp(int rowIndex) {
        Float override = KeyboardConfig.rowHeightOverride(rowIndex);
        return override != null ? override : KeyboardConfig.ROW_HEIGHT_DP;
    }

    private int desiredHeightPx() {
        int rows = rowCount();
        float total = 0f;
        for (int r = 0; r < rows; r++) total += rowHeightDp(r);
        total += (rows - 1) * KeyboardConfig.ROW_GAP_DP;
        total += KeyboardConfig.KEYBOARD_TOP_PADDING_DP;
        total += KeyboardConfig.KEYBOARD_BOTTOM_PADDING_DP;
        return (int) (total * density);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, desiredHeightPx());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuildHitKeys();
        ImeColorStore colors = ImeColorStore.get(getContext());
        if (colors.isWallpaperEnabled() && !colors.hasVideoWallpaper()) {
            wallpaperBitmapPath = null;
            reloadWallpaperBitmap(colors);
        }
    }

    private void rebuildHitKeys() {
        hitKeys.clear();
        int rows = rowCount();
        if (rows == 0) return;

        float gap = KeyboardConfig.ROW_GAP_DP * density;
        float keyGap = KeyboardConfig.KEY_HORIZONTAL_GAP_DP * density;
        float width = getWidth();
        if (width <= 0) return;

        float top = KeyboardConfig.KEYBOARD_TOP_PADDING_DP * density;
        float y = top;

        for (int r = 0; r < rows; r++) {
            BasicKey[] row = rows().get(r);
            float rowHeight = rowHeightDp(r) * density;

            float sideInset = 0f;
            if (isAsdfRow(row)) {
                Float ov = KeyboardConfig.rowSidePaddingOverride(r);
                sideInset = (ov != null ? ov : KeyboardConfig.ASDF_SIDE_PADDING_DP) * density;
            }

            float totalWeight = 0f;
            for (BasicKey k : row) totalWeight += k.weight;

            float usableWidth = width - sideInset * 2 - keyGap * (row.length - 1);
            float x = sideInset;

            float rowTop    = y + KeyboardConfig.KEY_INNER_PADDING_TOP_DP * density;
            float rowBottom = y + rowHeight - KeyboardConfig.KEY_INNER_PADDING_BOTTOM_DP * density;

            for (BasicKey k : row) {
                float keyWidth = usableWidth * (k.weight / totalWeight);
                RectF rect = new RectF(x, rowTop, x + keyWidth, rowBottom);
                hitKeys.add(new HitKey(k, rect, r));
                x += keyWidth + keyGap;
            }

            y += rowHeight + gap;
        }
    }

    private boolean isAsdfRow(BasicKey[] row) {
        if (row == null || row.length != 9) return false;
        return "a".equals(row[0].lower)
                && "s".equals(row[1].lower)
                && "d".equals(row[2].lower)
                && "f".equals(row[3].lower)
                && "g".equals(row[4].lower)
                && "h".equals(row[5].lower)
                && "j".equals(row[6].lower)
                && "k".equals(row[7].lower)
                && "l".equals(row[8].lower);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        ImeColorStore colors = ImeColorStore.get(getContext());

        boolean videoMode = colors.isWallpaperEnabled() && colors.hasVideoWallpaper();
        boolean imageMode = colors.isWallpaperEnabled()
                && !colors.hasVideoWallpaper()
                && wallpaperBitmap != null;

        if (imageMode) {
            drawCustomWallpaper(canvas);
        }

        if (colors.isWallpaperEnabled()) {
            int dim = colors.getWallpaperDim();
            if (dim > 0) {
                int alpha = Math.round(dim * 255f / 100f);
                wallpaperDimPaint.setColor((alpha << 24)
                        | (KeyboardConfig.COLOR_WALLPAPER_DIM_FILL & 0x00FFFFFF));
                canvas.drawRect(0, 0, getWidth(), getHeight(), wallpaperDimPaint);
            }
        } else {
            wallpaperDimPaint.setColor(colors.getBgColor());
            canvas.drawRect(0, 0, getWidth(), getHeight(), wallpaperDimPaint);
        }

        int textColor = colors.getTextColor();
        int keyAlpha  = colors.getKeyAlpha();

        int keyFill;
        int base = colors.getBgColor();
        if (colors.isWallpaperEnabled()) {
            keyFill = (keyAlpha << 24) | (base & 0x00FFFFFF);
        } else {
            keyFill = base;
        }

        int specialFill = blend(keyFill, 0xFF000000, 0.35f);
        if (colors.isWallpaperEnabled()) {
            specialFill = (keyAlpha << 24) | (specialFill & 0x00FFFFFF);
        }

        int pressedFill = blend(keyFill, textColor, 0.45f);
        if (colors.isWallpaperEnabled()) {
            pressedFill = (keyAlpha << 24) | (pressedFill & 0x00FFFFFF);
        }

        textPaint.setColor(textColor);

        float radius = KeyboardConfig.KEY_CORNER_RADIUS_DP * density;

        int borderColor = KeyboardConfig.COLOR_BORDER;
        int borderAlpha = (borderColor >>> 24) & 0xFF;
        int finalBorderAlpha = (borderAlpha * keyAlpha) / 255;
        borderPaint.setColor((finalBorderAlpha << 24) | (borderColor & 0x00FFFFFF));

        boolean keysHidden = keyAlpha <= 0;
        boolean shrinkEnabled = colors.isKeyShrinkEnabled();

        for (HitKey hk : hitKeys) {
            // ★ Shrink animation transform — only active for the pressed key
            float shrink = shrinkEnabled ? currentShrinkScale(hk) : 1f;
            int save = canvas.save();
            if (shrink != 1f) {
                canvas.scale(shrink, shrink, hk.rect.centerX(), hk.rect.centerY());
            }

            if (keysHidden) {
                if (hk == pressed) {
                    keyPaint.setColor(pressedFill);
                    keyRect.set(hk.rect);
                    canvas.drawRoundRect(keyRect, radius, radius, keyPaint);
                }
            } else {
                if (hk == pressed)                          keyPaint.setColor(pressedFill);
                else if (hk.key.code != BasicKey.CODE_NONE) keyPaint.setColor(specialFill);
                else                                        keyPaint.setColor(keyFill);

                keyRect.set(hk.rect);
                canvas.drawRoundRect(keyRect, radius, radius, keyPaint);
                canvas.drawRoundRect(keyRect, radius, radius, borderPaint);
            }

            String label;
            if (hk.key.code == BasicKey.CODE_SHIFT && shiftLocked) {
                label = "\u21EA";
            } else {
                label = hk.key.displayLabel(shifted || shiftLocked);
            }
            if (label == null) label = "";

            boolean isSpecial = hk.key.code != BasicKey.CODE_NONE;
            boolean isNav = hk.key.code == BasicKey.CODE_LEFT
                    || hk.key.code == BasicKey.CODE_RIGHT;

            float textSizeSp;
            if (isNav)          textSizeSp = KeyboardConfig.NAV_ICON_TEXT_SIZE_SP;
            else if (isSpecial) textSizeSp = KeyboardConfig.TEXT_SIZE_SPECIAL_SP;
            else                textSizeSp = KeyboardConfig.TEXT_SIZE_LETTER_SP;
            textPaint.setTextSize(textSizeSp * scaledDensity);

            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textHeight = fm.descent - fm.ascent;
            float baseline = hk.rect.centerY() - (fm.ascent + textHeight / 2f);

            canvas.drawText(label, hk.rect.centerX(), baseline, textPaint);

            canvas.restoreToCount(save);
        }
    }

    private void reloadWallpaperBitmap(ImeColorStore colors) {
        String path = colors.getWallpaperImagePath();
        if (path == null || path.isEmpty()) {
            wallpaperBitmap = null;
            wallpaperBitmapPath = null;
            return;
        }
        if (path.equals(wallpaperBitmapPath) && wallpaperBitmap != null) return;

        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);

            int reqW = Math.max(1, getWidth() > 0 ? getWidth() : 1080);
            int reqH = Math.max(1, getHeight() > 0 ? getHeight() : 500);

            int sample = 1;
            while ((bounds.outWidth / sample) > reqW * 2
                    || (bounds.outHeight / sample) > reqH * 2) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap raw = BitmapFactory.decodeFile(path, opts);
            if (raw == null) {
                wallpaperBitmap = null;
                wallpaperBitmapPath = null;
                return;
            }
            wallpaperBitmap = raw;
            wallpaperBitmapPath = path;
        } catch (Throwable t) {
            android.util.Log.e("BasicKeyboardView", "wallpaper load failed", t);
            wallpaperBitmap = null;
            wallpaperBitmapPath = null;
        }
    }

    private void drawCustomWallpaper(Canvas canvas) {
        if (wallpaperBitmap == null) return;
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        int bw = wallpaperBitmap.getWidth();
        int bh = wallpaperBitmap.getHeight();
        if (bw <= 0 || bh <= 0) return;

        float scale = Math.max((float) vw / bw, (float) vh / bh);
        float drawW = bw * scale;
        float drawH = bh * scale;

        wallpaperDst.set(
                (vw - drawW) / 2f,
                (vh - drawH) / 2f,
                (vw + drawW) / 2f,
                (vh + drawH) / 2f);

        canvas.drawBitmap(wallpaperBitmap, null, wallpaperDst, wallpaperBitmapPaint);
    }

    public void refreshWallpaper() {
        ImeColorStore colors = ImeColorStore.get(getContext());
        if (colors.isWallpaperEnabled() && !colors.hasVideoWallpaper()) {
            wallpaperBitmapPath = null;
            wallpaperBitmap = null;
            reloadWallpaperBitmap(colors);
        } else {
            wallpaperBitmap = null;
            wallpaperBitmapPath = null;
        }
        invalidate();
    }

    private static int blend(int dst, int src, float ratio) {
        if (ratio <= 0f) return dst;
        if (ratio >= 1f) return src;
        int dr = (dst >> 16) & 0xFF, dg = (dst >> 8) & 0xFF, db = dst & 0xFF;
        int sr = (src >> 16) & 0xFF, sg = (src >> 8) & 0xFF, sb = src & 0xFF;
        int r = Math.round(dr * (1 - ratio) + sr * ratio);
        int g = Math.round(dg * (1 - ratio) + sg * ratio);
        int b = Math.round(db * (1 - ratio) + sb * ratio);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public KeyAction actionForKey(String keyChar) {
        return GestureActionStore.get(getContext()).byKeyChar(keyChar);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                HitKey hk = findKey(event.getX(), event.getY());
                if (hk != null) {
                    setPressedKey(hk);
                    longPressHandled = false;
                    showBubbleFor(hk);
                    invalidate();

                    cSwipeArmed = isCKey(hk);
                    cSwipeFired = false;
                    if (cSwipeArmed) {
                        cDownX = event.getX();
                        cDownY = event.getY();
                    }

                    if (hk.key.code == BasicKey.CODE_DELETE) startRepeat(hk);
                    if (hk.key.code == BasicKey.CODE_NONE
                            && hk.key.lower != null
                            && hk.key.lower.length() == 1
                            && GestureActionStore.get(getContext())
                            .byKeyChar(hk.key.lower) != null) {
                        scheduleLetterLongPress(hk, KeyboardConfig.LONG_PRESS_ENTER_MS);
                    }

                    if (hk.key.code == BasicKey.CODE_ENTER) {
                        enterDownTime = System.currentTimeMillis();
                        scheduleLongPress(hk, KeyboardConfig.LONG_PRESS_ENTER_MS);
                    } else if (hk.key.code == BasicKey.CODE_SPACE) {
                        spaceDownTime = System.currentTimeMillis();
                        scheduleLongPress(hk, KeyboardConfig.LONG_PRESS_SPACE_MS);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (cSwipeArmed && !cSwipeFired) {
                    float dx = event.getX() - cDownX;
                    float dy = event.getY() - cDownY;
                    if (dx > dp(60) && Math.abs(dy) < dp(50)) {
                        cSwipeFired = true;
                        longPressHandled = true;
                        pressed = null;
                        invalidate();
                        stopRepeat();
                        if (bubbleOverlay != null) bubbleOverlay.hide();
                        if (listener != null) listener.onKey(CLIPBOARD_TRIGGER, null);
                        return true;
                    }
                }
                HitKey hk = findKey(event.getX(), event.getY());
                if (hk != pressed) {
                    cancelPendingLongPress();
                    setPressedKey(hk);
                    if (hk != null) showBubbleFor(hk);
                    else if (bubbleOverlay != null) bubbleOverlay.hide();
                    invalidate();
                    stopRepeat();
                    if (hk != null && hk.key.code == BasicKey.CODE_DELETE) startRepeat(hk);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                cancelPendingLongPress();
                boolean wasSwipe = cSwipeFired;
                cSwipeArmed = false;
                cSwipeFired = false;

                HitKey hk = pressed;
                setPressedKey(null);
                invalidate();
                stopRepeat();
                if (bubbleOverlay != null) bubbleOverlay.hide();

                if (wasSwipe) return true;

                if (hk != null && listener != null) {
                    if (hk.key.code == BasicKey.CODE_SPACE) {
                        long now = System.currentTimeMillis();
                        boolean doubleTap = (now - lastSpaceUpTime) <= KeyboardConfig.DOUBLE_TAP_SPACE_MS;
                        if (doubleTap && !longPressHandled) {
                            listener.onKey(GESTURE_DOUBLE_TRIGGER, null);
                            longPressHandled = true;
                        }
                        lastSpaceUpTime = now;
                    }

                    if (!longPressHandled) {
                        dispatch(hk.key);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                cancelPendingLongPress();
                cSwipeArmed = false;
                cSwipeFired = false;
                setPressedKey(null);
                invalidate();
                stopRepeat();
                if (bubbleOverlay != null) bubbleOverlay.hide();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private boolean isCKey(HitKey hk) {
        if (hk == null || hk.key == null) return false;
        if (hk.key.code != BasicKey.CODE_NONE) return false;
        if (hk.key.lower == null) return false;
        return "c".equals(hk.key.lower);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }


    private void scheduleLetterLongPress(final HitKey hk, long delayMs) {
        cancelLetterLongPress();
        letterLongPressKey = hk;
        letterLongPressRunnable = () -> {
            letterLongPressRunnable = null;
            if (pressed != hk || listener == null || longPressHandled) return;
            longPressHandled = true;
            if (bubbleOverlay != null) bubbleOverlay.hide();
            setPressedKey(null);
            invalidate();
            listener.onKey(LETTER_LONG_PRESS_TRIGGER, hk.key.lower);
        };
        repeatHandler.postDelayed(letterLongPressRunnable, delayMs);
    }

    private void cancelLetterLongPress() {
        if (letterLongPressRunnable != null) {
            repeatHandler.removeCallbacks(letterLongPressRunnable);
            letterLongPressRunnable = null;
            letterLongPressKey = null;
        }
    }

    private void scheduleLongPress(final HitKey hk, long delayMs) {
        cancelPendingLongPress();
        pendingLongPress = () -> {
            pendingLongPress = null;
            if (pressed != hk || listener == null || longPressHandled) return;

            if (hk.key.code == BasicKey.CODE_ENTER) {
                longPressHandled = true;
                if (bubbleOverlay != null) bubbleOverlay.hide();
                setPressedKey(null);
                invalidate();
                listener.onKey(EMOJI_PANEL_TRIGGER, null);
            } else if (hk.key.code == BasicKey.CODE_SPACE) {
                longPressHandled = true;
                if (bubbleOverlay != null) bubbleOverlay.hide();
                setPressedKey(null);
                invalidate();
                listener.onKey(GESTURE_EDITOR_TRIGGER, null);
            }
        };
        longPressHandler.postDelayed(pendingLongPress, delayMs);
    }

    private void cancelPendingLongPress() {
        if (pendingLongPress != null) {
            longPressHandler.removeCallbacks(pendingLongPress);
            pendingLongPress = null;
        }
    }

    private void showBubbleFor(HitKey hk) {
        if (bubbleOverlay == null) return;
        if (hk == null || hk.key.code != BasicKey.CODE_NONE) {
            bubbleOverlay.hide();
            return;
        }
        String label = hk.key.displayLabel(shifted || shiftLocked);
        if (label == null || label.isEmpty()) {
            bubbleOverlay.hide();
            return;
        }
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int screenX = (int) (loc[0] + hk.rect.centerX());
        int screenY = (int) (loc[1] + hk.rect.top);
        bubbleOverlay.show(label, screenX, screenY);
    }
    private void startRepeat(HitKey hk) {
        stopRepeat();
        repeatKey = hk;
        repeatHandler.postDelayed(repeatRunnable, KeyboardConfig.REPEAT_START_MS);
    }

    private void stopRepeat() {
        repeatKey = null;
        repeatHandler.removeCallbacks(repeatRunnable);
    }

    private HitKey findKey(float x, float y) {
        for (HitKey hk : hitKeys) {
            if (hk.rect.contains(x, y)) return hk;
        }
        return null;
    }

    private void dispatch(BasicKey key) {
        if (listener == null) return;

        if (key.code == BasicKey.CODE_SHIFT
                || key.code == BasicKey.CODE_DELETE
                || key.code == BasicKey.CODE_ENTER
                || key.code == BasicKey.CODE_SPACE
                || key.code == BasicKey.CODE_SYMBOLS
                || key.code == BasicKey.CODE_SYMBOLS_2
                || key.code == BasicKey.CODE_LEFT
                || key.code == BasicKey.CODE_RIGHT
                || key.code == BasicKey.CODE_SYM_PAGE_1
                || key.code == BasicKey.CODE_SYM_PAGE_2
                || key.code == BasicKey.CODE_SYM_PAGE_3
                || key.code == BasicKey.CODE_SYM_PAGE_4
                || key.code == BasicKey.CODE_ABC) {
            listener.onKey(key.code, null);
            return;
        }

        String text = key.commitText(shifted);
        listener.onKey(key.code, text);
    }

    private static final class HitKey {
        final BasicKey key;
        final RectF rect;
        final int rowIndex;
        HitKey(BasicKey key, RectF rect, int rowIndex) {
            this.key = key;
            this.rect = rect;
            this.rowIndex = rowIndex;
        }
    }
}