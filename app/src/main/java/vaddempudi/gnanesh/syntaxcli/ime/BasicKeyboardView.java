package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
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

    private final float density;
    private final float scaledDensity;

    private Listener listener;
    private BasicKeyboardLayout layout;
    private boolean shifted = false;
    private boolean symbols = false;
    private boolean symbols2 = false;
    private boolean symbols3 = false;

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

        keyPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(KeyboardConfig.KEY_BORDER_WIDTH_DP * density);
        borderPaint.setColor(KeyboardConfig.COLOR_BORDER);

        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(KeyboardConfig.COLOR_TEXT);

        setBackgroundColor(KeyboardConfig.COLOR_BACKGROUND);
        setFocusable(true);
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

        float radius = KeyboardConfig.KEY_CORNER_RADIUS_DP * density;

        for (HitKey hk : hitKeys) {
            if (hk == pressed) keyPaint.setColor(KeyboardConfig.COLOR_KEY_PRESSED);
            else if (hk.key.code != BasicKey.CODE_NONE) keyPaint.setColor(KeyboardConfig.COLOR_SPECIAL_KEY);
            else keyPaint.setColor(KeyboardConfig.COLOR_KEY);

            keyRect.set(hk.rect);
            canvas.drawRoundRect(keyRect, radius, radius, keyPaint);
            canvas.drawRoundRect(keyRect, radius, radius, borderPaint);

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
            if (isNav) {
                textSizeSp = KeyboardConfig.NAV_ICON_TEXT_SIZE_SP;
            } else if (isSpecial) {
                textSizeSp = KeyboardConfig.TEXT_SIZE_SPECIAL_SP;
            } else {
                textSizeSp = KeyboardConfig.TEXT_SIZE_LETTER_SP;
            }
            textPaint.setTextSize(textSizeSp * scaledDensity);

            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textHeight = fm.descent - fm.ascent;
            float baseline = hk.rect.centerY() - (fm.ascent + textHeight / 2f);

            canvas.drawText(label, hk.rect.centerX(), baseline, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                HitKey hk = findKey(event.getX(), event.getY());
                if (hk != null) {
                    pressed = hk;
                    showBubbleFor(hk);
                    invalidate();
                    if (hk.key.code == BasicKey.CODE_DELETE) startRepeat(hk);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                HitKey hk = findKey(event.getX(), event.getY());
                if (hk != pressed) {
                    pressed = hk;
                    if (hk != null) showBubbleFor(hk);
                    else if (bubbleOverlay != null) bubbleOverlay.hide();
                    invalidate();
                    stopRepeat();
                    if (hk != null && hk.key.code == BasicKey.CODE_DELETE) startRepeat(hk);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                HitKey hk = pressed;
                pressed = null;
                invalidate();
                stopRepeat();
                if (bubbleOverlay != null) bubbleOverlay.hide();
                if (hk != null && listener != null) dispatch(hk.key);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                pressed = null;
                invalidate();
                stopRepeat();
                if (bubbleOverlay != null) bubbleOverlay.hide();
                return true;
            }
        }
        return super.onTouchEvent(event);
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