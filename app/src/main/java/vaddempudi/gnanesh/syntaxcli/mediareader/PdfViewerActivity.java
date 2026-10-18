package vaddempudi.gnanesh.syntaxcli.mediareader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PDF_URI = "pdf_uri";
    public static final String EXTRA_PDF_NAME = "pdf_name";

    private static final int PAGE_WIDTH_PX = 1240;
    private static final int PAGE_HEIGHT_PX = 1754;
    private static final int CACHE_BYTES = 24 * 1024 * 1024;

    private RecyclerView recyclerPages;
    private ProgressBar progressBar;
    private PageSliderView pageSlider;

    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pfd;
    private int pageCount = 0;
    private int currentPage = 1;

    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PdfPageAdapter adapter;

    private final LruCache<Integer, Bitmap> bitmapCache =
            new LruCache<Integer, Bitmap>(CACHE_BYTES) {
                @Override
                protected int sizeOf(Integer key, Bitmap value) {
                    return value.getAllocationByteCount();
                }
            };

    private final Set<Integer> inFlightRenders =
            java.util.Collections.synchronizedSet(new HashSet<Integer>());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildUi();
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open PDF viewer", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        backgroundExecutor.execute(() -> {
            File pdfFile = resolvePdfFile();
            if (pdfFile == null) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "PDF file not found", Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }
            openPdfAsync(pdfFile);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdownNow();
        closeRenderer();
        bitmapCache.evictAll();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));

        FrameLayout contentArea = new FrameLayout(this);

        ZoomableFrameLayout zoomContainer = new ZoomableFrameLayout(this);

        recyclerPages = new RecyclerView(this);
        recyclerPages.setLayoutManager(new LinearLayoutManager(this));
        recyclerPages.setItemViewCacheSize(4);
        recyclerPages.setItemAnimator(null);
        recyclerPages.setHasFixedSize(true);
        recyclerPages.setItemViewCacheSize(4);
        recyclerPages.setDrawingCacheEnabled(false);
        recyclerPages.setItemAnimator(null);
        adapter = new PdfPageAdapter();
        recyclerPages.setAdapter(adapter);

        recyclerPages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                updateSliderFromScroll();
            }
        });

        zoomContainer.addView(recyclerPages, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        contentArea.addView(zoomContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams sliderLp = new FrameLayout.LayoutParams(
                (int) (40 * getResources().getDisplayMetrics().density),
                FrameLayout.LayoutParams.MATCH_PARENT);
        sliderLp.gravity = Gravity.END;

        pageSlider = new PageSliderView(this);
        pageSlider.setListener((position01) -> {
            if (pageCount <= 0) return;
            int target = (int) Math.round(position01 * (pageCount - 1));
            if (target < 0) target = 0;
            if (target >= pageCount) target = pageCount - 1;
            if (target != currentPage - 1) {
                currentPage = target + 1;
                LinearLayoutManager lm =
                        (LinearLayoutManager) recyclerPages.getLayoutManager();
                if (lm != null) lm.scrollToPositionWithOffset(target, 0);
            }
        });

        contentArea.addView(pageSlider, sliderLp);

        root.addView(contentArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        progressBar = new ProgressBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.CENTER;
        root.addView(progressBar, pp);

        setContentView(root);
    }

    private void updateSliderFromScroll() {
        if (pageSlider == null || pageCount <= 0) return;
        LinearLayoutManager lm = (LinearLayoutManager) recyclerPages.getLayoutManager();
        if (lm == null) return;
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) return;

        float position01;
        if (pageCount <= 1) {
            position01 = 0f;
        } else {
            View firstChild = lm.findViewByPosition(first);
            View lastChild = lm.findViewByPosition(last);
            if (firstChild == null || lastChild == null) {
                position01 = first / (float) (pageCount - 1);
            } else {
                float startTop = firstChild.getTop();
                float endBottom = lastChild.getBottom();
                float visibleSpan = (endBottom - startTop) > 0 ? (endBottom - startTop) : 1f;
                float scrolled = -startTop;
                float local01 = scrolled / visibleSpan;
                if (local01 < 0) local01 = 0;
                if (local01 > 1) local01 = 1;
                float pageFloat = first + local01;
                position01 = pageFloat / (float) (pageCount - 1);
                if (position01 < 0) position01 = 0;
                if (position01 > 1) position01 = 1;
            }
        }
        pageSlider.setScrollFraction(position01, currentPage, pageCount);
    }

    @Nullable
    private File resolvePdfFile() {
        String path = getIntent().getStringExtra(EXTRA_PDF_URI);
        if (path != null) {
            File f = new File(path);
            if (f.exists() && f.canRead()) return f;
            try {
                Uri u = Uri.parse(path);
                if ("content".equals(u.getScheme())) return copyUriToCache(u);
            } catch (Exception ignored) {}
        }
        Uri data = getIntent().getData();
        if (data != null && "content".equals(data.getScheme())) {
            return copyUriToCache(data);
        }
        return null;
    }

    @Nullable
    private File copyUriToCache(Uri uri) {
        try {
            File outFile = new File(getCacheDir(),
                    "pdf_view_" + System.currentTimeMillis() + ".pdf");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                out.flush();
                return outFile;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void openPdfAsync(File pdfFile) {
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(pfd);
            pageCount = pdfRenderer.getPageCount();
            mainHandler.post(this::onPdfReady);
        } catch (Exception e) {
            mainHandler.post(this::showError);
        }
    }

    private void onPdfReady() {
        progressBar.setVisibility(View.GONE);
        currentPage = 1;
        adapter.notifyDataSetChanged();
        if (pageSlider != null) {
            pageSlider.setScrollFraction(0f, 1, pageCount);
        }
    }

    private void showError() {
        progressBar.setVisibility(View.GONE);
        Toast.makeText(this, "Failed to open PDF", Toast.LENGTH_SHORT).show();
    }

    private void renderPageIfNeeded(final int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pageCount || pdfRenderer == null) return;
        if (bitmapCache.get(pageIndex) != null) return;

        synchronized (inFlightRenders) {
            if (inFlightRenders.contains(pageIndex)) return;
            inFlightRenders.add(pageIndex);
        }

        backgroundExecutor.execute(() -> {
            Bitmap bmp = null;
            boolean ok = false;
            try {
                synchronized (PdfRenderer.class) {
                    if (pdfRenderer == null) return;
                    PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
                    bmp = Bitmap.createBitmap(PAGE_WIDTH_PX, PAGE_HEIGHT_PX,
                            Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();
                }
                bitmapCache.put(pageIndex, bmp);
                ok = true;
                final int idx = pageIndex;
                mainHandler.post(() -> {
                    if (adapter != null) adapter.notifyItemChanged(idx);
                });
            } catch (Exception ignored) {
            } finally {
                if (!ok && bmp != null && !bmp.isRecycled()) bmp.recycle();
                synchronized (inFlightRenders) {
                    inFlightRenders.remove(pageIndex);
                }
            }
        });
    }

    private void closeRenderer() {
        try { if (pdfRenderer != null) { pdfRenderer.close(); pdfRenderer = null; } }
        catch (Exception ignored) {}
        try { if (pfd != null) { pfd.close(); pfd = null; } }
        catch (Exception ignored) {}
    }

    private class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.PageViewHolder> {
        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();

            LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setBackgroundColor(Color.parseColor("#2C2C2C"));

            LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            int marginPx = (int) (12 * context.getResources().getDisplayMetrics().density);
            wrapperParams.setMargins(0, marginPx, 0, marginPx);
            wrapper.setLayoutParams(wrapperParams);

            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int calculatedHeight = (int) (screenWidth * ((float) PAGE_HEIGHT_PX / PAGE_WIDTH_PX));

            ImageView iv = new ImageView(context);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(Color.WHITE);

            wrapper.addView(iv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, calculatedHeight));

            return new PageViewHolder(wrapper, iv);
        }

        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            Bitmap cached = bitmapCache.get(position);
            if (cached != null && !cached.isRecycled()) {
                holder.imageView.setImageBitmap(cached);
            } else {
                holder.imageView.setTag(position);
                renderPageIfNeeded(position);
            }
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }

        class PageViewHolder extends RecyclerView.ViewHolder {
            final ImageView imageView;
            PageViewHolder(@NonNull View itemView, ImageView imageView) {
                super(itemView);
                this.imageView = imageView;
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
                    if (!insideThumb && !nearTrack) {
                        return false;
                    }
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

    private static class ZoomableFrameLayout extends FrameLayout {
        private static final float MAX_SCALE = 5.0f;

        private float scale = 1.0f;
        private float translationX = 0f;
        private float translationY = 0f;

        private final Matrix drawMatrix = new Matrix();

        private float lastTouchX, lastTouchY;
        private boolean isPanning = false;
        private boolean intercepting = false;

        private final ScaleGestureDetector scaleDetector;

        ZoomableFrameLayout(@NonNull Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    intercepting = false;
                    lastTouchX = ev.getX();
                    lastTouchY = ev.getY();
                    return false;

                case MotionEvent.ACTION_POINTER_DOWN:
                    intercepting = true;
                    isPanning = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (scale > 1.01f && ev.getPointerCount() == 1) {
                        intercepting = true;
                        return true;
                    }
                    return intercepting;
            }
            return intercepting;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    isPanning = scale > 1.01f;
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    isPanning = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isPanning
                            && !scaleDetector.isInProgress()
                            && scale > 1.01f
                            && event.getPointerCount() == 1) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        translationX += dx;
                        translationY += dy;
                        clampTranslation();
                        updateMatrix();
                        invalidate();
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP: {
                    int idx = event.getActionIndex();
                    int remaining = (idx == 0) ? 1 : 0;
                    if (remaining < event.getPointerCount()) {
                        lastTouchX = event.getX(remaining);
                        lastTouchY = event.getY(remaining);
                        isPanning = scale > 1.01f;
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isPanning = false;
                    intercepting = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
            }
            return true;
        }

        private void clampTranslation() {
            if (scale <= 1.0f) {
                translationX = 0f;
                translationY = 0f;
                return;
            }
            int w = getWidth();
            int h = getHeight();
            float maxX = (w * scale - w) / 2f;
            float maxY = (h * scale - h) / 2f;
            if (maxX < 0) maxX = 0;
            if (maxY < 0) maxY = 0;
            if (translationX > maxX) translationX = maxX;
            if (translationX < -maxX) translationX = -maxX;
            if (translationY > maxY) translationY = maxY;
            if (translationY < -maxY) translationY = -maxY;
        }

        private void updateMatrix() {
            drawMatrix.reset();
            drawMatrix.postScale(scale, scale, getWidth() / 2f, getHeight() / 2f);
            drawMatrix.postTranslate(translationX, translationY);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (scale == 1f && translationX == 0f && translationY == 0f) {
                super.dispatchDraw(canvas);
                return;
            }
            int save = canvas.save();
            canvas.concat(drawMatrix);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
        }

        private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                isPanning = false;
                return true;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float prevScale = scale;
                float newScale = prevScale * detector.getScaleFactor();
                newScale = Math.max(1.0f, Math.min(newScale, MAX_SCALE));

                if (newScale == prevScale) return false;

                float focalX = detector.getFocusX();
                float focalY = detector.getFocusY();

                translationX = focalX - (focalX - translationX) * (newScale / prevScale);
                translationY = focalY - (focalY - translationY) * (newScale / prevScale);

                scale = newScale;

                if (scale <= 1.0f) {
                    scale = 1.0f;
                    translationX = 0f;
                    translationY = 0f;
                } else {
                    clampTranslation();
                }

                updateMatrix();
                invalidate();
                return true;
            }

            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                isPanning = scale > 1.01f;
            }
        }
    }
}