package vaddempudi.gnanesh.syntaxcli.mediareader;

import android.animation.ValueAnimator;
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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
    private ZoomableView zoomView;

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

        zoomView = new ZoomableView(this);
        root.addView(zoomView, new FrameLayout.LayoutParams(
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

        zoomView.setContent(column, pageCount);
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
        if (zoomView != null) zoomView.release();
    }

    // =====================================================================
    // ZoomableView
    //
    // Gesture routing rule (IMPORTANT — this is what fixes the flicker):
    //   * The MotionEvent stream is fed to the ScaleGestureDetector and
    //     GestureDetector EXACTLY ONCE per event, from dispatchTouchEvent().
    //   * onTouchEvent() must NOT feed the ScaleGestureDetector again.
    //     Feeding it twice corrupts its internal span history, which makes
    //     getScaleFactor() jittery and produces the multi-times zoom flicker.
    //   * onScale() applies the factor incrementally (scale * factor) rather
    //     than re-deriving from startScale, so no base drift can accumulate.
    // =====================================================================
    static class ZoomableView extends FrameLayout {

        private static final float HARD_MAX_SCALE   = 100f;
        private static final float ABSOLUTE_MIN     = 0.5f;
        private static final float MIN_SNAP_SCALE   = 1.0f;
        private static final float DOUBLE_TAP_SCALE = 2.5f;

        private float scale = 1f;
        private float tx = 0f, ty = 0f;
        private final Matrix matrix = new Matrix();

        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;

        private ScrollView vScroll;
        private PageSliderView pageSlider;

        private float downX, downY;
        private float lastX, lastY;
        private boolean isPanning = false;
        private boolean intercepting = false;
        private int touchSlop;

        ZoomableView(@NonNull Context c) {
            super(c);
            setClipChildren(true);
            setClipToPadding(true);
            setWillNotDraw(false);
            touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();

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

        void setContent(View content, int pageCount) {
            removeAllViews();

            vScroll = new ScrollView(getContext());
            vScroll.setVerticalScrollBarEnabled(false);
            vScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            vScroll.setFillViewport(true);
            vScroll.addView(content, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT));

            addView(vScroll, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            FrameLayout.LayoutParams sliderLp = new FrameLayout.LayoutParams(
                    dp(40), LayoutParams.MATCH_PARENT);
            sliderLp.gravity = Gravity.END;

            pageSlider = new PageSliderView(getContext());
            pageSlider.setListener(position01 -> {
                if (pageCount <= 1) {
                    vScroll.scrollTo(0, 0);
                    return;
                }
                int range = scrollRange();
                int targetY = (int) (position01 * range);
                vScroll.scrollTo(0, targetY);
            });
            pageSlider.setScrollFraction(0f, 1, pageCount);
            addView(pageSlider, sliderLp);

            vScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
                if (pageSlider == null) return;
                int range = scrollRange();
                float fraction = range > 0
                        ? Math.max(0f, Math.min(1f, vScroll.getScrollY() / (float) range))
                        : 0f;
                int page = Math.min(pageCount,
                        (int) Math.floor(fraction * pageCount) + 1);
                pageSlider.setScrollFraction(fraction, page, pageCount);
            });
        }

        private int scrollRange() {
            if (vScroll == null || vScroll.getChildCount() == 0) return 0;
            return Math.max(0,
                    vScroll.getChildAt(0).getHeight() - vScroll.getHeight());
        }

        private int dp(int v) {
            return (int) (getResources().getDisplayMetrics().density * v);
        }

        // -----------------------------------------------------------------
        // Feed detectors ONCE per event, and only here.
        // -----------------------------------------------------------------
        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);
            tapDetector.onTouchEvent(ev);

            final int action = ev.getActionMasked();

            // While a pinch is active, we own the stream — swallow it so the
            // inner ScrollViews / HorizontalScrollViews don't fight us.
            if (scaleDetector.isInProgress()) {
                isPanning = false;
                intercepting = false;
                return true;
            }

            // Always reset gesture state on gesture end, regardless of who
            // consumed the event, otherwise a swallowed UP leaves us stuck.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                isPanning = false;
                intercepting = false;
                if (getParent() != null)
                    getParent().requestDisallowInterceptTouchEvent(false);
            }

            return super.dispatchTouchEvent(ev);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    lastX = downX;
                    lastY = downY;
                    isPanning = false;
                    intercepting = false;
                    return false;

                case MotionEvent.ACTION_POINTER_DOWN:
                    // Two fingers → steal for pan/zoom pipeline.
                    intercepting = true;
                    isPanning = false;
                    if (getParent() != null)
                        getParent().requestDisallowInterceptTouchEvent(true);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    // Zoomed in → steal single-finger drag for panning.
                    if (scale > MIN_SNAP_SCALE + 0.01f && ev.getPointerCount() == 1) {
                        intercepting = true;
                        return true;
                    }
                    return intercepting;
            }
            return intercepting;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            // NOTE: do NOT call scaleDetector.onTouchEvent(ev) here.
            // It was already fed in dispatchTouchEvent(). Feeding it twice
            // is what caused the zoom-in/zoom-out flickering.

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = ev.getX();
                    lastY = ev.getY();
                    isPanning = scale > MIN_SNAP_SCALE + 0.01f;
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    isPanning = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isPanning && !scaleDetector.isInProgress()
                            && scale > MIN_SNAP_SCALE + 0.01f
                            && ev.getPointerCount() == 1) {
                        float dx = ev.getX() - lastX;
                        float dy = ev.getY() - lastY;
                        tx += dx;
                        ty += dy;
                        clampTranslation();
                        rebuildMatrix();
                        invalidate();
                        lastX = ev.getX();
                        lastY = ev.getY();
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP: {
                    int idx = ev.getActionIndex();
                    int keep = idx == 0 ? 1 : 0;
                    if (keep < ev.getPointerCount()) {
                        lastX = ev.getX(keep);
                        lastY = ev.getY(keep);
                        isPanning = scale > MIN_SNAP_SCALE + 0.01f;
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isPanning = false;
                    intercepting = false;
                    if (getParent() != null)
                        getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return true;
        }

        /** Direct zoom (called from pinch). Idempotent per frame. */
        private void zoomTo(float targetScale, float focusX, float focusY) {
            targetScale = Math.max(ABSOLUTE_MIN, Math.min(targetScale, HARD_MAX_SCALE));

            float prev = scale;
            if (Math.abs(targetScale - prev) < 0.0005f) return;

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            float contentX = (focusX - cx - tx) / prev;
            float contentY = (focusY - cy - ty) / prev;

            tx = focusX - cx - contentX * targetScale;
            ty = focusY - cy - contentY * targetScale;
            scale = targetScale;

            if (scale >= MIN_SNAP_SCALE) {
                clampTranslation();
            }
            rebuildMatrix();
            invalidate();
        }

        /** Double-tap animation. */
        private void animateZoomTo(float targetScale, float focusX, float focusY) {
            final float finalTarget = Math.max(MIN_SNAP_SCALE, Math.min(targetScale, HARD_MAX_SCALE));
            final float startScale = scale;
            final float startTx = tx;
            final float startTy = ty;

            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;

            final float contentX = (focusX - cx - startTx) / startScale;
            final float contentY = (focusY - cy - startTy) / startScale;

            final float endTx = focusX - cx - contentX * finalTarget;
            final float endTy = focusY - cy - contentY * finalTarget;

            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(220);
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                scale = startScale + (finalTarget - startScale) * f;
                tx = startTx + (endTx - startTx) * f;
                ty = startTy + (endTy - startTy) * f;
                if (scale > MIN_SNAP_SCALE) clampTranslation();
                rebuildMatrix();
                invalidate();
            });
            anim.start();
        }

        /** Rubber-band snap-back to 1.0 when pinch ends below 1.0. */
        private void snapBackToMin() {
            if (scale >= MIN_SNAP_SCALE - 0.001f) return;

            final float startScale = scale;
            final float startTx = tx;
            final float startTy = ty;

            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(180);
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                scale = startScale + (MIN_SNAP_SCALE - startScale) * f;
                tx = startTx * (1f - f);
                ty = startTy * (1f - f);
                if (scale <= MIN_SNAP_SCALE + 0.001f) {
                    scale = MIN_SNAP_SCALE;
                    tx = 0f;
                    ty = 0f;
                }
                rebuildMatrix();
                invalidate();
            });
            anim.start();
        }

        /**
         * Clamp so the scaled content still intersects the viewport.
         */
        private void clampTranslation() {
            if (scale <= MIN_SNAP_SCALE + 0.001f) {
                if (scale <= MIN_SNAP_SCALE) { tx = 0f; ty = 0f; }
                return;
            }

            float viewW = getWidth();
            float viewH = getHeight();
            if (viewW <= 0 || viewH <= 0) return;

            float childH = viewH;
            if (vScroll != null && vScroll.getChildCount() > 0) {
                childH = vScroll.getChildAt(0).getHeight();
            }

            float maxX = Math.max(0f, (viewW * scale - viewW) / 2f);
            float maxY = Math.max(0f, (childH * scale - viewH) / 2f);

            if (tx >  maxX) tx =  maxX;
            if (tx < -maxX) tx = -maxX;
            if (ty >  maxY) ty =  maxY;
            if (ty < -maxY) ty = -maxY;
        }

        private void rebuildMatrix() {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            matrix.reset();
            matrix.postScale(scale, scale, cx, cy);
            matrix.postTranslate(tx, ty);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (scale == 1f && tx == 0f && ty == 0f) {
                super.dispatchDraw(canvas);
                return;
            }
            int save = canvas.save();
            canvas.concat(matrix);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
        }

        void release() {
            setOnTouchListener(null);
        }

        private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
            @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
                isPanning = false;
                return true;
            }

            @Override public boolean onScale(@NonNull ScaleGestureDetector d) {
                // Incremental: apply this event's factor to the CURRENT scale.
                // Do NOT recompute from startScale — that reintroduces base
                // drift if the detector ever jitters.
                float desired = scale * d.getScaleFactor();
                zoomTo(desired, d.getFocusX(), d.getFocusY());
                return true;
            }

            @Override public void onScaleEnd(@NonNull ScaleGestureDetector d) {
                if (scale < MIN_SNAP_SCALE) {
                    snapBackToMin();
                } else {
                    isPanning = true;
                }
            }
        }
    }

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