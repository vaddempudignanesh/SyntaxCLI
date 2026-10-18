package vaddempudi.gnanesh.syntaxcli.mediareader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.VelocityTracker;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Scroller;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vaddempudi.gnanesh.syntaxcli.filemanager.FileLog;

public class DocxViewerActivity extends AppCompatActivity {

    public static final String EXTRA_DOCX_PATH = "docx_path";

    private ProgressBar progressBar;
    private ZoomableScrollHost zoomHost;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final boolean IMAGE_SCALE_TO_WIDTH = true;

    private static final int BG_OUTER      = 0xFF121212;
    private static final int BG_CARD       = 0xFF2C2C2C;
    private static final int BG_PAPER      = 0xFFFFFFFF;
    private static final int BORDER_PAPER  = 0xFF404040;
    private static final int BORDER_PX_DP  = 1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG_OUTER);

        zoomHost = new ZoomableScrollHost(this);
        root.addView(zoomHost, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.CENTER;
        root.addView(progressBar, pp);

        setContentView(root);

        final String path = getIntent().getStringExtra(EXTRA_DOCX_PATH);
        if (path == null) {
            Toast.makeText(this, "No docx path", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        io.execute(() -> {
            try {
                File docx = new File(path);
                File imageDir = new File(getCacheDir(), "docx_images");
                DocxReader.Result result = DocxReader.readDocx(docx, imageDir);
                ui.post(() -> render(result, imageDir));
            } catch (Throwable t) {
                FileLog.e("DocxViewerActivity: " + t);
                ui.post(() -> {
                    Toast.makeText(this, "Cannot open docx: " + t.getMessage(),
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private void render(DocxReader.Result result, File imageDir) {
        progressBar.setVisibility(View.GONE);

        if (result == null || result.markedText == null || result.markedText.isEmpty()) {
            FileLog.e("render: empty result");
            Toast.makeText(this, "Document has no readable content", Toast.LENGTH_LONG).show();
            return;
        }

        FileLog.e("render: len=" + result.markedText.length()
                + " images=" + result.images.size());

        List<DocxReader.ImageRef> images = result.images;
        int imageIdx = 0;
        int pageCount = 1;

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BG_CARD);
        column.setPadding(dp(4), dp(6), dp(4), dp(6));

        LinearLayout pageCard = newPageCard();
        column.addView(pageCard);

        StringBuilder segment = new StringBuilder();
        String marked = result.markedText;

        for (int i = 0; i < marked.length(); i++) {
            char c = marked.charAt(i);

            if (c == DocxReader.IMAGE_MARKER) {
                flushTextRow(pageCard, segment);
                if (imageIdx < images.size()) {
                    addImageRow(pageCard, images.get(imageIdx).extractedFile);
                    imageIdx++;
                }
            } else if (c == DocxReader.PAGE_BREAK) {
                flushTextRow(pageCard, segment);
                pageCount++;
                pageCard = newPageCard();
                column.addView(pageCard);
            } else {
                segment.append(c);
            }
        }
        flushTextRow(pageCard, segment);

        zoomHost.setDocument(column, pageCount);
    }

    private LinearLayout newPageCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(BG_PAPER);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_PAPER);
        bg.setStroke(dp(BORDER_PX_DP), BORDER_PAPER);
        bg.setCornerRadius(dp(2));
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int marginH = dp(6);
        int marginV = dp(10);
        lp.setMargins(marginH, marginV, marginH, marginV);
        card.setLayoutParams(lp);
        return card;
    }

    private void flushTextRow(LinearLayout pageCard, StringBuilder segment) {
        if (segment.length() == 0) return;

        Spanned text = applyFormatting(segment.toString());
        segment.setLength(0);

        TextView tv = new TextView(this);
        tv.setTextColor(Color.parseColor("#111111"));
        tv.setTextSize(14);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setHorizontallyScrolling(true);
        tv.setSingleLine(false);
        tv.setLineSpacing(0f, 1.15f);
        tv.setText(text);
        tv.setPadding(dp(4), dp(2), dp(4), dp(2));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsv.setFillViewport(false);
        hsv.addView(tv, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pageCard.addView(hsv, lp);
    }

    private Spanned applyFormatting(String marked) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int[] b = {-1}, i_ = {-1}, u = {-1}, s = {-1};
        for (int k = 0; k < marked.length(); k++) {
            char c = marked.charAt(k);
            int len = sb.length();
            if (c == DocxReader.BOLD_ON) { if (b[0] < 0) b[0] = len; }
            else if (c == DocxReader.BOLD_OFF) {
                if (b[0] >= 0) {
                    sb.setSpan(new StyleSpan(Typeface.BOLD), b[0], len,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    b[0] = -1;
                }
            } else if (c == DocxReader.ITAL_ON) { if (i_[0] < 0) i_[0] = len; }
            else if (c == DocxReader.ITAL_OFF) {
                if (i_[0] >= 0) {
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), i_[0], len,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i_[0] = -1;
                }
            } else if (c == DocxReader.UNDER_ON) { if (u[0] < 0) u[0] = len; }
            else if (c == DocxReader.UNDER_OFF) {
                if (u[0] >= 0) {
                    sb.setSpan(new UnderlineSpan(), u[0], len,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    u[0] = -1;
                }
            } else if (c == DocxReader.STRIKE_ON) { if (s[0] < 0) s[0] = len; }
            else if (c == DocxReader.STRIKE_OFF) {
                if (s[0] >= 0) {
                    sb.setSpan(new StrikethroughSpan(), s[0], len,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s[0] = -1;
                }
            } else {
                sb.append(c);
            }
        }
        return sb;
    }

    private void addImageRow(LinearLayout pageCard, File file) {
        if (file == null || !file.exists()) return;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            addPlaceholderRow(pageCard, "[Image]");
            return;
        }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int availableW = screenW - dp(4) * 2 - dp(6) * 2 - dp(6) * 2;

        float aspect = bounds.outHeight / (float) bounds.outWidth;
        int dispW, dispH;

        if (IMAGE_SCALE_TO_WIDTH) {
            dispW = Math.min(bounds.outWidth, availableW);
            dispH = (int) (dispW * aspect);
            int maxH = (int) (screenH * 0.60f);
            if (dispH > maxH) {
                dispH = maxH;
                dispW = (int) (dispH / aspect);
            }
        } else {
            dispW = bounds.outWidth;
            dispH = bounds.outHeight;
        }

        Bitmap bmp = decodeSampled(file, Math.max(dispW, dispH));
        if (bmp == null) {
            addPlaceholderRow(pageCard, "[Image: " + file.getName() + "]");
            return;
        }

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(bmp);
        iv.setBackgroundColor(Color.parseColor("#F5F5F5"));
        iv.setPadding(dp(4), dp(4), dp(4), dp(4));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dispW, dispH);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        pageCard.addView(iv, lp);
    }

    private void addPlaceholderRow(LinearLayout pageCard, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.GRAY);
        tv.setTextSize(12);
        tv.setPadding(dp(4), dp(6), dp(4), dp(6));
        pageCard.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private Bitmap decodeSampled(File f, int targetPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        int minSide = Math.min(bounds.outWidth, bounds.outHeight);
        while (minSide / (sample * 2) >= targetPx) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        if (zoomHost != null) zoomHost.release();
    }

    // =====================================================================
    // ZoomableScrollHost — one touch engine, one coordinate space.
    //
    // Layout:
    //   0: UnboundedFrameLayout (contentLayer) — hosts the document column,
    //      measured with UNSPECIFIED height so it can be taller than the
    //      viewport. Drawn through the matrix in dispatchDraw.
    //   1: PageSlider — right-edge thumb, never scaled.
    //
    // Matrix:
    //   screenY = ty + (contentY - viewH/2) * scale + viewH/2
    //   contentY is measured from the document's top (y = 0..childH).
    //
    // Scroll-at-1x and pan-while-zoomed both just modify (tx, ty):
    //   scale == 1 : drag moves ty (bounded so content top/bottom stay at
    //                viewport edges). Horizontal is 0.
    //   scale  > 1 : drag moves (tx, ty), bounded so content top/bottom can
    //                reach viewport edges (no blank).
    //
    // Gesture feed rule (fixes flicker): ScaleGestureDetector + GestureDetector
    // are fed EXACTLY ONCE per MotionEvent, only in dispatchTouchEvent. Never
    // again in onTouchEvent. Feeding twice corrupts the detector's internal
    // span history and makes getScaleFactor() jitter -> zoom flicker.
    //
    // Smooth-scroll rule (fixes jerky vertical scroll): the intercept does
    // not steal the first MOVE. It waits until the finger passes touchSlop,
    // then applies the *entire* delta from ACTION_DOWN in one shot and sets
    // lastX/lastY to the current position. No frame is dropped, and an
    // inner HorizontalScrollView can't consume a vertical drag first.
    //
    // Fling rule (adds inertial scroll): implemented with android.widget.Scroller
    // using its native fling model (distance ~ velocity^2, ~1.5s decay). This
    // gives the same physics as a ScrollView, so fast flick + release glides
    // and stops at the content edges.
    // =====================================================================
    static class ZoomableScrollHost extends FrameLayout {

        private static final float HARD_MAX_SCALE   = 100f;
        private static final float ABSOLUTE_MIN     = 0.5f;
        private static final float MIN_SNAP_SCALE   = 1.0f;
        private static final float DOUBLE_TAP_SCALE = 2.5f;

        private static final int MIN_FLING_VELOCITY = 30;    // px/s
        private static final int MAX_FLING_VELOCITY = 20000; // px/s

        private final UnboundedFrameLayout contentLayer;
        private View content;
        private PageSliderView pageSlider;

        private float scale = 1f;
        private float tx = 0f, ty = 0f;
        private final Matrix matrix = new Matrix();

        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;

        private VelocityTracker velocityTracker;
        private final Scroller scroller;

        private float downX, downY;
        private float lastX, lastY;
        private boolean isPanning = false;
        private boolean intercepting = false;
        private boolean isPinching = false;
        private boolean slopExceeded = false;
        private int touchSlop;

        private boolean flinging = false;

        ZoomableScrollHost(@NonNull Context c) {
            super(c);
            setClipChildren(true);
            setWillNotDraw(false);
            touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
            scroller = new Scroller(c);
            contentLayer = new UnboundedFrameLayout(c);
            contentLayer.setClipChildren(true);
            addView(contentLayer, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            scaleDetector = new ScaleGestureDetector(c, new ScaleListener());
            scaleDetector.setQuickScaleEnabled(false);

            tapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(@NonNull MotionEvent e) { return true; }
                @Override public boolean onDoubleTap(@NonNull MotionEvent e) {
                    float target = scale > 1.01f ? 1f : DOUBLE_TAP_SCALE;
                    animateZoomTo(target, e.getX(), e.getY());
                    return true;
                }
            });
        }

        private static class UnboundedFrameLayout extends FrameLayout {
            UnboundedFrameLayout(Context c) { super(c); }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int parentW = MeasureSpec.getSize(widthMeasureSpec);
                if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    parentW = getResources().getDisplayMetrics().widthPixels;
                }

                int childWidthSpec  = MeasureSpec.makeMeasureSpec(parentW, MeasureSpec.EXACTLY);
                int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

                int maxChildH = 0;
                int count = getChildCount();
                for (int i = 0; i < count; i++) {
                    View child = getChildAt(i);
                    if (child.getVisibility() == GONE) continue;
                    child.measure(childWidthSpec, childHeightSpec);
                    maxChildH = Math.max(maxChildH, child.getMeasuredHeight());
                }

                int w = resolveSize(parentW, widthMeasureSpec);
                setMeasuredDimension(w, maxChildH);
            }
        }

        void setDocument(View doc, int pageCount) {
            this.content = doc;
            contentLayer.removeAllViews();
            contentLayer.addView(doc, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));

            if (pageSlider != null) removeView(pageSlider);

            FrameLayout.LayoutParams sliderLp = new FrameLayout.LayoutParams(
                    dp(40), LayoutParams.MATCH_PARENT);
            sliderLp.gravity = Gravity.END;
            pageSlider = new PageSliderView(getContext());
            pageSlider.setListener(position01 -> {
                cancelFling();
                float range = maxScrollY();
                ty = -position01 * range;
                if (scale > MIN_SNAP_SCALE + 0.001f) {
                    tx = 0f;
                    clampPan();
                } else {
                    clampScroll();
                }
                applyMatrix();
                invalidate();
            });
            pageSlider.setScrollFraction(0f, 1, pageCount);
            addView(pageSlider, sliderLp);
        }

        private int dp(int v) {
            return (int) (getResources().getDisplayMetrics().density * v);
        }

        private float contentHeight() {
            return content == null ? 0f : content.getHeight();
        }

        private float maxScrollY() {
            return Math.max(0f, contentHeight() - getHeight());
        }


        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);
            tapDetector.onTouchEvent(ev);

            final int action = ev.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                cancelFling();
            }

            if (scaleDetector.isInProgress()) {
                isPinching = true;
                isPanning = false;
                intercepting = true;

                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }

            // Let onTouchEvent process ACTION_UP before resetting gesture state.
            boolean handled = super.dispatchTouchEvent(ev);

            if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {

                isPinching = false;
                isPanning = false;
                intercepting = false;
                slopExceeded = false;

                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
            }

            return handled;
        }


        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (isPinching || scaleDetector.isInProgress()) {
                intercepting = true;
                return true;
            }

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    lastX = downX;
                    lastY = downY;
                    isPanning = false;
                    intercepting = false;
                    slopExceeded = false;
                    startVelocityTracking(ev);
                    return false;

                case MotionEvent.ACTION_POINTER_DOWN:
                    intercepting = true;
                    isPanning = false;
                    addVelocitySample(ev);
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    addVelocitySample(ev);
                    if (ev.getPointerCount() != 1) return intercepting;

                    if (!slopExceeded) {
                        float ddx = Math.abs(ev.getX() - downX);
                        float ddy = Math.abs(ev.getY() - downY);
                        if (ddx < touchSlop && ddy < touchSlop) {
                            return false;
                        }
                        slopExceeded = true;

                        float dx = ev.getX() - lastX;
                        float dy = ev.getY() - lastY;
                        if (scale <= MIN_SNAP_SCALE + 0.001f) {
                            ty += dy;
                            clampScroll();
                        } else {
                            tx += dx;
                            ty += dy;
                            clampPan();
                        }
                        applyMatrix();
                        invalidate();

                        lastX = ev.getX();
                        lastY = ev.getY();
                        isPanning = true;
                        intercepting = true;

                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return true;
                    }

                    intercepting = true;
                    return true;
                }
            }
            return intercepting;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            addVelocitySample(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    lastX = ev.getX();
                    lastY = ev.getY();
                    slopExceeded = false;
                    isPanning = true;
                    startVelocityTracking(ev);
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    isPanning = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isPanning && !isPinching
                            && ev.getPointerCount() == 1) {
                        float dx = ev.getX() - lastX;
                        float dy = ev.getY() - lastY;
                        if (scale <= MIN_SNAP_SCALE + 0.001f) {
                            ty += dy;
                            clampScroll();
                        } else {
                            tx += dx;
                            ty += dy;
                            clampPan();
                        }
                        applyMatrix();
                        invalidate();
                        lastX = ev.getX();
                        lastY = ev.getY();
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP: {
                    int lifted = ev.getActionIndex();
                    int keep = lifted == 0 ? 1 : 0;
                    if (keep < ev.getPointerCount()) {
                        lastX = ev.getX(keep);
                        lastY = ev.getY(keep);
                        isPanning = false;
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    if (slopExceeded && !isPinching && isPanning) {
                        startFling();
                    }
                    isPanning = false;
                    intercepting = false;
                    slopExceeded = false;
                    stopVelocityTracking();
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    isPanning = false;
                    intercepting = false;
                    slopExceeded = false;
                    stopVelocityTracking();
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
            }
            return true;
        }

        // ---- velocity tracking ----
        private void startVelocityTracking(MotionEvent ev) {
            stopVelocityTracking();
            velocityTracker = VelocityTracker.obtain();
            velocityTracker.clear();
            velocityTracker.addMovement(ev);
        }

        private void addVelocitySample(MotionEvent ev) {
            if (velocityTracker != null) velocityTracker.addMovement(ev);
        }

        private void stopVelocityTracking() {
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }

        // ---- fling ----
        private void cancelFling() {
            if (flinging) {
                scroller.forceFinished(true);
                flinging = false;
            }
        }


        private void startFling() {
            if (velocityTracker == null) return;

            velocityTracker.computeCurrentVelocity(1000, MAX_FLING_VELOCITY);

            int vx = (int) velocityTracker.getXVelocity();
            int vy = (int) velocityTracker.getYVelocity();

            if (scale <= MIN_SNAP_SCALE + 0.001f) {
                // Normal zoom: convert finger velocity to scroll-offset velocity.
                if (Math.abs(vy) < MIN_FLING_VELOCITY) return;

                float range = maxScrollY();
                if (range <= 0f) return;

                int startY = Math.max(0,
                        Math.min((int) range, Math.round(-ty)));

                scroller.forceFinished(true);
                scroller.fling(
                        0, startY,
                        0, -vy,
                        0, 0,
                        0, (int) range
                );

                flinging = true;
                postInvalidateOnAnimation();

            } else {
                // Zoomed: pan naturally in the direction of the finger's velocity.
                if (Math.abs(vx) < MIN_FLING_VELOCITY
                        && Math.abs(vy) < MIN_FLING_VELOCITY) {
                    return;
                }

                float viewW = getWidth();
                float viewH = getHeight();
                float childH = contentHeight();

                if (viewW <= 0f || viewH <= 0f || childH <= 0f) return;

                float topScreenNoTy =
                        (-viewH / 2f) * scale + viewH / 2f;

                float bottomScreenNoTy =
                        (childH - viewH / 2f) * scale + viewH / 2f;

                float tyA = -topScreenNoTy;
                float tyB = viewH - bottomScreenNoTy;

                int tyMin = (int) Math.ceil(Math.min(tyA, tyB));
                int tyMax = (int) Math.floor(Math.max(tyA, tyB));

                float scaledContentW = viewW * scale;

                int txMin;
                int txMax;

                if (scaledContentW <= viewW) {
                    txMin = 0;
                    txMax = 0;
                } else {
                    int maxX = (int) Math.ceil(
                            (scaledContentW - viewW) / 2f);

                    txMin = -maxX;
                    txMax = maxX;
                }

                scroller.forceFinished(true);
                scroller.fling(
                        Math.round(tx), Math.round(ty),
                        vx, vy,
                        txMin, txMax,
                        tyMin, tyMax
                );

                flinging = true;
                postInvalidateOnAnimation();
            }
        }



        @Override
        public void computeScroll() {
            if (!flinging) return;

            if (scroller.computeScrollOffset()) {
                if (scale <= MIN_SNAP_SCALE + 0.001f) {
                    ty = -scroller.getCurrY();
                    tx = 0f;
                    clampScroll();
                } else {
                    tx = scroller.getCurrX();
                    ty = scroller.getCurrY();
                    clampPan();
                }

                applyMatrix();
                postInvalidateOnAnimation();
            } else {
                flinging = false;
            }
        }


        // ---- zoom ----

        private void zoomTo(float targetScale, float focusX, float focusY) {
            targetScale = Math.max(
                    ABSOLUTE_MIN,
                    Math.min(targetScale, HARD_MAX_SCALE)
            );

            float previousScale = scale;
            if (Math.abs(targetScale - previousScale) < 0.0005f) return;

            cancelFling();

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            // Preserve the exact document point beneath the pinch focus.
            float contentX = (focusX - cx - tx) / previousScale;
            float contentY = (focusY - cy - ty) / previousScale;

            float newTx = focusX - cx - contentX * targetScale;
            float newTy = focusY - cy - contentY * targetScale;

            scale = targetScale;
            tx = newTx;
            ty = newTy;

            if (scale < MIN_SNAP_SCALE - 0.001f) {
                // Do not clamp using 1x bounds while temporarily zoomed out.
                // snapBackToMin() will restore a valid 1x position.
                tx = 0f;
            } else if (scale <= MIN_SNAP_SCALE + 0.001f) {
                scale = MIN_SNAP_SCALE;
                tx = 0f;
                clampScroll();
            } else {
                clampPan();
            }

            applyMatrix();
            invalidate();
        }



        private void animateZoomTo(float targetScale, float focusX, float focusY) {
            cancelFling();

            final float finalTarget = Math.max(
                    MIN_SNAP_SCALE,
                    Math.min(targetScale, HARD_MAX_SCALE)
            );

            final float startScale = scale;
            final float startTx = tx;
            final float startTy = ty;

            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;

            final float contentX = (focusX - cx - startTx) / startScale;
            final float contentY = (focusY - cy - startTy) / startScale;

            final float endTx = focusX - cx - contentX * finalTarget;
            final float endTy = focusY - cy - contentY * finalTarget;

            android.animation.ValueAnimator anim =
                    android.animation.ValueAnimator.ofFloat(0f, 1f);

            anim.setDuration(240);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());

            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();

                scale = startScale + (finalTarget - startScale) * f;
                tx = startTx + (endTx - startTx) * f;
                ty = startTy + (endTy - startTy) * f;

                if (scale > MIN_SNAP_SCALE) {
                    clampPan();
                }

                applyMatrix();
                invalidate();
            });

            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    scale = finalTarget;
                    tx = endTx;
                    ty = endTy;

                    if (scale <= MIN_SNAP_SCALE + 0.001f) {
                        scale = MIN_SNAP_SCALE;
                        tx = 0f;
                        clampScroll();
                    } else {
                        clampPan();
                    }

                    applyMatrix();
                    invalidate();
                }
            });

            anim.start();
        }



        private void snapBackToMin() {
            if (scale >= MIN_SNAP_SCALE - 0.001f) {
                scale = MIN_SNAP_SCALE;
                tx = 0f;
                clampScroll();
                applyMatrix();
                invalidate();
                return;
            }

            cancelFling();

            final float startScale = scale;
            final float startTx = tx;
            final float startTy = ty;

            // Preserve the document point currently at the viewport center.
            final float contentY = -startTy / startScale;
            final float targetTy = -contentY;

            android.animation.ValueAnimator anim =
                    android.animation.ValueAnimator.ofFloat(0f, 1f);

            anim.setDuration(200);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());

            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();

                scale = startScale + (MIN_SNAP_SCALE - startScale) * f;
                tx = startTx * (1f - f);
                ty = startTy + (targetTy - startTy) * f;

                // Avoid 1x clamping while the animation is still below 1x.
                if (scale >= MIN_SNAP_SCALE) {
                    scale = MIN_SNAP_SCALE;
                    tx = 0f;
                    clampScroll();
                }

                applyMatrix();
                invalidate();
            });

            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    scale = MIN_SNAP_SCALE;
                    tx = 0f;
                    ty = targetTy;
                    clampScroll();
                    applyMatrix();
                    invalidate();
                }

                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    // Do not force the document back to the top on cancellation.
                }
            });

            anim.start();
        }


        // ---- clamping ----
        private void clampScroll() {
            float range = maxScrollY();
            if (ty > 0f) ty = 0f;
            if (ty < -range) ty = -range;
        }

        private void clampPan() {
            float viewW = getWidth();
            float viewH = getHeight();
            float childH = contentHeight();
            if (viewW <= 0 || viewH <= 0 || childH <= 0) return;

            float topScreenNoTy    = (-viewH / 2f) * scale + viewH / 2f;
            float bottomScreenNoTy = (childH - viewH / 2f) * scale + viewH / 2f;

            float tyA = 0f - topScreenNoTy;
            float tyB = viewH - bottomScreenNoTy;
            float tyMin = Math.min(tyA, tyB);
            float tyMax = Math.max(tyA, tyB);

            float scaledContentH = childH * scale;
            if (scaledContentH <= viewH) {
                float mid = (tyMin + tyMax) / 2f;
                tyMin = tyMax = mid;
            }

            if (ty > tyMax) ty = tyMax;
            if (ty < tyMin) ty = tyMin;

            float scaledContentW = viewW * scale;
            if (scaledContentW <= viewW) {
                tx = 0f;
            } else {
                float maxX = (scaledContentW - viewW) / 2f;
                if (tx >  maxX) tx =  maxX;
                if (tx < -maxX) tx = -maxX;
            }
        }

        private void applyMatrix() {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            matrix.reset();
            matrix.postScale(scale, scale, cx, cy);
            matrix.postTranslate(tx, ty);
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            int save = canvas.save();
            canvas.concat(matrix);
            if (contentLayer.getVisibility() == VISIBLE) {
                int cs = canvas.save();
                canvas.translate(contentLayer.getLeft(), contentLayer.getTop());
                contentLayer.draw(canvas);
                canvas.restoreToCount(cs);
            }
            canvas.restoreToCount(save);

            if (pageSlider != null && pageSlider.getVisibility() == VISIBLE) {
                int cs = canvas.save();
                canvas.translate(pageSlider.getLeft(), pageSlider.getTop());
                pageSlider.draw(canvas);
                canvas.restoreToCount(cs);
            }

            if (pageSlider != null) {
                float range = maxScrollY();
                float fraction = range > 0
                        ? Math.max(0f, Math.min(1f, (-ty) / range))
                        : 0f;
                int totalPages = pageSlider.getTotalPages();
                if (totalPages > 0) {
                    int page = Math.min(totalPages,
                            (int) Math.floor(fraction * totalPages) + 1);
                    pageSlider.setScrollFraction(fraction, page, totalPages);
                }
            }
        }

        void release() {
            cancelFling();
            stopVelocityTracking();
        }

        private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
            @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
                isPanning = false;
                cancelFling();
                return true;
            }

            @Override public boolean onScale(@NonNull ScaleGestureDetector d) {
                float desired = scale * d.getScaleFactor();
                zoomTo(desired, d.getFocusX(), d.getFocusY());
                return true;
            }

            @Override public void onScaleEnd(@NonNull ScaleGestureDetector d) {
                if (scale < MIN_SNAP_SCALE) {
                    snapBackToMin();
                } else {
                    isPanning = true;
                    lastX = d.getFocusX();
                    lastY = d.getFocusY();
                }
            }
        }
    }

    // =====================================================================
    // PageSliderView — unchanged behaviour; getTotalPages() drives the label.
    // =====================================================================
    private interface OnPageSeek {
        void onSeek(float position01);
    }

    private static class PageSliderView extends View {

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF thumbRect = new RectF();
        private final Path thumbPath = new Path();

        private final float density;
        private final float trackWidth;
        private final float thumbHeight;
        private final float thumbWidth;

        private float scrollFraction = 0f;
        private int currentPage = 1;
        private int totalPages = 0;

        private boolean dragging = false;
        private OnPageSeek listener;

        PageSliderView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            trackWidth = 3f * density;
            thumbWidth = 34f * density;
            thumbHeight = 32f * density;

            trackPaint.setColor(0x55FFFFFF);
            trackPaint.setStyle(Paint.Style.FILL);

            thumbPaint.setColor(0xFF00FF00);
            thumbPaint.setStyle(Paint.Style.FILL);
            thumbPaint.setShadowLayer(4f * density, 0f, 1f * density, 0x88000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);

            labelPaint.setColor(0xFF000000);
            labelPaint.setTextSize(10f * density);
            labelPaint.setTypeface(Typeface.MONOSPACE);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setFakeBoldText(true);
        }

        void setListener(OnPageSeek l) { this.listener = l; }

        int getTotalPages() { return totalPages; }

        void setScrollFraction(float fraction, int current, int total) {
            if (dragging) {
                this.currentPage = current;
                this.totalPages = total;
                invalidate();
                return;
            }
            if (fraction < 0f) fraction = 0f;
            if (fraction > 1f) fraction = 1f;
            if (fraction == scrollFraction && current == currentPage && total == totalPages) return;
            scrollFraction = fraction;
            currentPage = current;
            totalPages = total;
            invalidate();
        }

        private void computeTrackRect() {
            float cx = getWidth() / 2f;
            float top = thumbHeight / 2f + 8f * density;
            float bottom = getHeight() - thumbHeight / 2f - 8f * density;
            if (bottom < top) bottom = top + 1f;
            trackRect.set(cx - trackWidth / 2f, top, cx + trackWidth / 2f, bottom);
        }

        private void computeThumbRect() {
            float cx = getWidth() / 2f;
            float trackTop = trackRect.top;
            float trackBottom = trackRect.bottom;
            float usable = trackBottom - trackTop;
            float centerY = trackTop + scrollFraction * usable;
            thumbRect.set(
                    cx - thumbWidth / 2f,
                    centerY - thumbHeight / 2f,
                    cx + thumbWidth / 2f,
                    centerY + thumbHeight / 2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            computeTrackRect();
            computeThumbRect();

            canvas.drawRoundRect(trackRect, trackWidth / 2f, trackWidth / 2f, trackPaint);

            thumbPath.reset();
            float corner = 8f * density;
            thumbPath.addRoundRect(thumbRect, corner, corner, Path.Direction.CW);
            canvas.drawPath(thumbPath, thumbPaint);

            if (totalPages > 0) {
                String label = currentPage + "/" + totalPages;
                float textY = thumbRect.centerY()
                        - (labelPaint.descent() + labelPaint.ascent()) / 2f;
                canvas.drawText(label, thumbRect.centerX(), textY, labelPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float y = event.getY();
                    computeTrackRect();
                    computeThumbRect();
                    boolean insideThumb = thumbRect.contains(event.getX(), y);
                    boolean nearTrack = Math.abs(event.getX() - getWidth() / 2f)
                            < thumbWidth;
                    if (!insideThumb && !nearTrack) return false;
                    dragging = true;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    applyTouchY(y);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) return false;
                    applyTouchY(event.getY());
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    dragging = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                }
            }
            return super.onTouchEvent(event);
        }

        private void applyTouchY(float y) {
            computeTrackRect();
            float usable = trackRect.bottom - trackRect.top;
            if (usable <= 0) return;
            float f = (y - trackRect.top) / usable;
            if (f < 0f) f = 0f;
            if (f > 1f) f = 1f;
            scrollFraction = f;
            if (totalPages > 0) {
                int page = (int) Math.round(f * (totalPages - 1)) + 1;
                if (page < 1) page = 1;
                if (page > totalPages) page = totalPages;
                currentPage = page;
            }
            invalidate();
            if (listener != null) listener.onSeek(f);
        }
    }
}