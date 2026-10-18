
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
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
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

import android.widget.OverScroller;

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

    private final ExecutorService backgroundExecutor =
            Executors.newFixedThreadPool(2);

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

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
            Toast.makeText(this, "Cannot open PDF viewer",
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        backgroundExecutor.execute(() -> {
            File pdfFile = resolvePdfFile();

            if (pdfFile == null) {
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "PDF file not found",
                                Toast.LENGTH_SHORT).show();
                        finish();
                    }
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

        synchronized (PdfRenderer.class) {
            closeRenderer();
        }

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
        recyclerPages.setDrawingCacheEnabled(false);

        adapter = new PdfPageAdapter();
        recyclerPages.setAdapter(adapter);

        recyclerPages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(
                    @NonNull RecyclerView rv, int dx, int dy) {
                updateSliderFromScroll();
            }
        });

        zoomContainer.addView(recyclerPages,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

        contentArea.addView(zoomContainer,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams sliderLp =
                new FrameLayout.LayoutParams(
                        (int) (40 * getResources()
                                .getDisplayMetrics().density),
                        FrameLayout.LayoutParams.MATCH_PARENT);

        sliderLp.gravity = Gravity.END;

        pageSlider = new PageSliderView(this);

        pageSlider.setListener(position01 -> {
            if (pageCount <= 0 || recyclerPages == null) return;

            int target = (int) Math.round(
                    position01 * (pageCount - 1));

            target = Math.max(0, Math.min(target, pageCount - 1));

            if (target != currentPage - 1) {
                currentPage = target + 1;

                LinearLayoutManager lm =
                        (LinearLayoutManager)
                                recyclerPages.getLayoutManager();

                if (lm != null) {
                    lm.scrollToPositionWithOffset(target, 0);
                }
            }
        });

        contentArea.addView(pageSlider, sliderLp);

        root.addView(contentArea,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        progressBar = new ProgressBar(this);

        LinearLayout.LayoutParams progressParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);

        progressParams.gravity = Gravity.CENTER;
        root.addView(progressBar, progressParams);

        setContentView(root);
    }

    private void updateSliderFromScroll() {
        if (pageSlider == null || pageCount <= 0
                || recyclerPages == null) return;

        LinearLayoutManager lm =
                (LinearLayoutManager) recyclerPages.getLayoutManager();

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

                float visibleSpan = Math.max(
                        1f, endBottom - startTop);

                float local01 = -startTop / visibleSpan;
                local01 = Math.max(0f, Math.min(1f, local01));

                float pageFloat = first + local01;

                position01 = pageFloat / (pageCount - 1f);
                position01 = Math.max(
                        0f, Math.min(1f, position01));
            }
        }

        currentPage = Math.max(1, Math.min(pageCount,
                lm.findFirstVisibleItemPosition() + 1));

        pageSlider.setScrollFraction(
                position01, currentPage, pageCount);
    }

    @Nullable
    private File resolvePdfFile() {
        String path = getIntent().getStringExtra(EXTRA_PDF_URI);

        if (path != null) {
            File file = new File(path);

            if (file.exists() && file.canRead()) {
                return file;
            }

            try {
                Uri uri = Uri.parse(path);

                if ("content".equals(uri.getScheme())) {
                    return copyUriToCache(uri);
                }
            } catch (Exception ignored) {
            }
        }

        Uri data = getIntent().getData();

        if (data != null && "content".equals(data.getScheme())) {
            return copyUriToCache(data);
        }

        return null;
    }

    @Nullable
    private File copyUriToCache(Uri uri) {
        File outFile = new File(getCacheDir(),
                "pdf_view_" + System.currentTimeMillis() + ".pdf");

        try (InputStream in =
                     getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(outFile)) {

            if (in == null) return null;

            byte[] buffer = new byte[16384];
            int length;

            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
            }

            out.flush();
            return outFile;

        } catch (Exception e) {
            return null;
        }
    }

    private void openPdfAsync(File pdfFile) {
        try {
            synchronized (PdfRenderer.class) {
                ParcelFileDescriptor descriptor =
                        ParcelFileDescriptor.open(
                                pdfFile,
                                ParcelFileDescriptor.MODE_READ_ONLY);

                PdfRenderer renderer = new PdfRenderer(descriptor);

                pfd = descriptor;
                pdfRenderer = renderer;
                pageCount = renderer.getPageCount();
            }

            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    onPdfReady();
                }
            });

        } catch (Exception e) {
            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    showError();
                }
            });
        }
    }

    private void onPdfReady() {
        progressBar.setVisibility(View.GONE);
        currentPage = 1;

        adapter.notifyDataSetChanged();

        if (pageSlider != null) {
            pageSlider.setScrollFraction(0f, 1, pageCount);
        }

        renderPageIfNeeded(0);
    }

    private void showError() {
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }

        Toast.makeText(this, "Failed to open PDF",
                Toast.LENGTH_SHORT).show();
    }

    private void renderPageIfNeeded(final int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pageCount) return;

        if (bitmapCache.get(pageIndex) != null) return;

        synchronized (inFlightRenders) {
            if (inFlightRenders.contains(pageIndex)) return;
            inFlightRenders.add(pageIndex);
        }

        try {
            backgroundExecutor.execute(() -> {
                Bitmap bitmap = null;
                boolean success = false;

                try {
                    synchronized (PdfRenderer.class) {
                        if (pdfRenderer == null
                                || pageIndex >= pdfRenderer.getPageCount()) {
                            return;
                        }

                        PdfRenderer.Page page =
                                pdfRenderer.openPage(pageIndex);

                        try {
                            int width = PAGE_WIDTH_PX;
                            int height = PAGE_HEIGHT_PX;

                            bitmap = Bitmap.createBitmap(
                                    width, height,
                                    Bitmap.Config.ARGB_8888);

                            bitmap.eraseColor(Color.WHITE);

                            page.render(
                                    bitmap, null, null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                        } finally {
                            page.close();
                        }
                    }

                    if (bitmap != null) {
                        bitmapCache.put(pageIndex, bitmap);
                        success = true;

                        final int index = pageIndex;

                        mainHandler.post(() -> {
                            if (!isFinishing() && !isDestroyed()
                                    && adapter != null) {
                                adapter.notifyItemChanged(index);
                            }
                        });
                    }

                } catch (Exception ignored) {
                } finally {
                    if (!success && bitmap != null
                            && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }

                    synchronized (inFlightRenders) {
                        inFlightRenders.remove(pageIndex);
                    }
                }
            });
        } catch (Exception ignored) {
            synchronized (inFlightRenders) {
                inFlightRenders.remove(pageIndex);
            }
        }
    }

    private void closeRenderer() {
        try {
            if (pdfRenderer != null) {
                pdfRenderer.close();
                pdfRenderer = null;
            }
        } catch (Exception ignored) {
        }

        try {
            if (pfd != null) {
                pfd.close();
                pfd = null;
            }
        } catch (Exception ignored) {
        }
    }

    private class PdfPageAdapter
            extends RecyclerView.Adapter<PdfPageAdapter.PageViewHolder> {

        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent, int viewType) {

            Context context = parent.getContext();

            LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setBackgroundColor(Color.parseColor("#2C2C2C"));

            LinearLayout.LayoutParams wrapperParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);

            int marginPx = (int) (12 * context.getResources()
                    .getDisplayMetrics().density);

            wrapperParams.setMargins(0, marginPx, 0, marginPx);
            wrapper.setLayoutParams(wrapperParams);

            int screenWidth = context.getResources()
                    .getDisplayMetrics().widthPixels;

            int calculatedHeight = (int) (
                    screenWidth * ((float) PAGE_HEIGHT_PX / PAGE_WIDTH_PX));

            ImageView imageView = new ImageView(context);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setBackgroundColor(Color.WHITE);

            wrapper.addView(imageView,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            calculatedHeight));

            return new PageViewHolder(wrapper, imageView);
        }

        @Override
        public void onBindViewHolder(
                @NonNull PageViewHolder holder, int position) {

            Bitmap cached = bitmapCache.get(position);

            if (cached != null && !cached.isRecycled()) {
                holder.imageView.setImageBitmap(cached);
                holder.imageView.setTag(position);
            } else {
                holder.imageView.setImageDrawable(null);
                holder.imageView.setTag(position);
                renderPageIfNeeded(position);
            }
        }

        @Override
        public void onViewRecycled(@NonNull PageViewHolder holder) {
            holder.imageView.setImageDrawable(null);
            holder.imageView.setTag(null);
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }

        class PageViewHolder extends RecyclerView.ViewHolder {
            final ImageView imageView;

            PageViewHolder(
                    @NonNull View itemView, ImageView imageView) {
                super(itemView);
                this.imageView = imageView;
            }
        }
    }

    private interface OnPageSeek {
        void onSeek(float position01);
    }

    private static class PageSliderView extends View {

        private final Paint trackPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint thumbPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint labelPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

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

        PageSliderView(Context context) {
            super(context);

            density = context.getResources()
                    .getDisplayMetrics().density;

            trackWidth = 3f * density;
            thumbWidth = 34f * density;
            thumbHeight = 32f * density;

            trackPaint.setColor(0x55FFFFFF);
            trackPaint.setStyle(Paint.Style.FILL);

            thumbPaint.setColor(0xFF00FF00);
            thumbPaint.setStyle(Paint.Style.FILL);
            thumbPaint.setShadowLayer(
                    4f * density, 0f, density, 0x88000000);

            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            labelPaint.setColor(Color.BLACK);
            labelPaint.setTextSize(10f * density);
            labelPaint.setTypeface(Typeface.MONOSPACE);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setFakeBoldText(true);
        }

        void setListener(OnPageSeek listener) {
            this.listener = listener;
        }

        void setScrollFraction(
                float fraction, int current, int total) {

            currentPage = current;
            totalPages = total;

            if (dragging) {
                invalidate();
                return;
            }

            scrollFraction = Math.max(0f, Math.min(1f, fraction));
            invalidate();
        }

        private void computeTrackRect() {
            float centerX = getWidth() / 2f;

            float top = thumbHeight / 2f + 8f * density;
            float bottom = getHeight()
                    - thumbHeight / 2f - 8f * density;

            if (bottom < top) bottom = top + 1f;

            trackRect.set(
                    centerX - trackWidth / 2f,
                    top,
                    centerX + trackWidth / 2f,
                    bottom);
        }

        private void computeThumbRect() {
            float centerX = getWidth() / 2f;
            float centerY = trackRect.top
                    + scrollFraction * (trackRect.bottom - trackRect.top);

            thumbRect.set(
                    centerX - thumbWidth / 2f,
                    centerY - thumbHeight / 2f,
                    centerX + thumbWidth / 2f,
                    centerY + thumbHeight / 2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            computeTrackRect();
            computeThumbRect();

            canvas.drawRoundRect(
                    trackRect, trackWidth / 2f,
                    trackWidth / 2f, trackPaint);

            thumbPath.reset();

            float corner = 8f * density;

            thumbPath.addRoundRect(
                    thumbRect, corner, corner, Path.Direction.CW);

            canvas.drawPath(thumbPath, thumbPaint);

            if (totalPages > 0) {
                String label = currentPage + "/" + totalPages;

                float textY = thumbRect.centerY()
                        - (labelPaint.descent() + labelPaint.ascent()) / 2f;

                canvas.drawText(
                        label, thumbRect.centerX(), textY, labelPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    computeTrackRect();
                    computeThumbRect();

                    float x = event.getX();
                    float y = event.getY();

                    boolean insideThumb = thumbRect.contains(x, y);

                    boolean nearTrack =
                            Math.abs(x - getWidth() / 2f) < thumbWidth;

                    if (!insideThumb && !nearTrack) return false;

                    dragging = true;

                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }

                    applyTouchY(y);
                    return true;
                }

                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    applyTouchY(event.getY());
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return false;

                    dragging = false;

                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }

                    return true;
            }

            return true;
        }

        private void applyTouchY(float y) {
            computeTrackRect();

            float usable = trackRect.bottom - trackRect.top;

            if (usable <= 0f) return;

            float fraction = (y - trackRect.top) / usable;
            fraction = Math.max(0f, Math.min(1f, fraction));

            scrollFraction = fraction;

            if (totalPages > 0) {
                currentPage =
                        (int) Math.round(fraction * (totalPages - 1)) + 1;

                currentPage = Math.max(
                        1, Math.min(totalPages, currentPage));
            }

            invalidate();

            if (listener != null) {
                listener.onSeek(fraction);
            }
        }
    }

    private static class ZoomableFrameLayout extends FrameLayout {

        private static final float MIN_SCALE = 1f;
        private static final float MAX_SCALE = 5f;
        private static final float PAN_SCALE_THRESHOLD = 1.01f;
        private static final int FLING_DURATION_LIMIT = 12000;

        private float scale = 1f;
        private float translationX = 0f;
        private float translationY = 0f;

        private final Matrix drawMatrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final OverScroller scroller;

        private VelocityTracker velocityTracker;

        private final int touchSlop;
        private final int minimumFlingVelocity;
        private final int maximumFlingVelocity;

        private float lastTouchX;
        private float lastTouchY;

        private boolean isPanning;
        private boolean intercepting;
        private boolean scaling;
        private boolean draggingAfterScale;

        ZoomableFrameLayout(@NonNull Context context) {
            super(context);

            setClipChildren(true);
            setClipToPadding(true);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);

            ViewConfiguration configuration =
                    ViewConfiguration.get(context);

            touchSlop = configuration.getScaledTouchSlop();
            minimumFlingVelocity =
                    configuration.getScaledMinimumFlingVelocity();

            maximumFlingVelocity = Math.min(
                    configuration.getScaledMaximumFlingVelocity() * 4,
                    FLING_DURATION_LIMIT * 2);

            scroller = new OverScroller(context);

            scaleDetector = new ScaleGestureDetector(
                    context, new ScaleListener());

            scaleDetector.setQuickScaleEnabled(false);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            return super.dispatchTouchEvent(event);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    intercepting = false;
                    isPanning = false;
                    return false;

                case MotionEvent.ACTION_POINTER_DOWN:
                    cancelFling();

                    intercepting = true;
                    scaling = true;
                    isPanning = false;

                    lastTouchX = event.getX();
                    lastTouchY = event.getY();

                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }

                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (scaleDetector.isInProgress()
                            || scaling
                            || scale > PAN_SCALE_THRESHOLD) {

                        intercepting = true;
                        return true;
                    }

                    return intercepting;

                case MotionEvent.ACTION_POINTER_UP:
                    return intercepting;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return intercepting;
            }

            return intercepting;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    cancelFling();
                    obtainVelocityTracker();
                    velocityTracker.clear();
                    velocityTracker.addMovement(event);

                    lastTouchX = event.getX();
                    lastTouchY = event.getY();

                    isPanning = scale > PAN_SCALE_THRESHOLD;
                    draggingAfterScale = false;

                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    cancelFling();
                    obtainVelocityTracker();
                    velocityTracker.addMovement(event);

                    scaling = true;
                    isPanning = false;
                    draggingAfterScale = false;

                    lastTouchX = event.getX();
                    lastTouchY = event.getY();

                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                    }

                    if (scaleDetector.isInProgress()) {
                        scaling = true;
                        isPanning = false;
                        return true;
                    }

                    if (event.getPointerCount() == 1
                            && scale > PAN_SCALE_THRESHOLD) {

                        float x = event.getX();
                        float y = event.getY();

                        float dx = x - lastTouchX;
                        float dy = y - lastTouchY;

                        if (!isPanning) {
                            if (Math.abs(dx) > touchSlop
                                    || Math.abs(dy) > touchSlop) {
                                isPanning = true;
                                draggingAfterScale = true;
                            }
                        }

                        if (isPanning) {
                            translationX += dx;
                            translationY += dy;

                            clampTranslation();
                            updateMatrix();
                            postInvalidateOnAnimation();
                        }

                        lastTouchX = x;
                        lastTouchY = y;
                    }

                    return true;

                case MotionEvent.ACTION_POINTER_UP: {
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                    }

                    int liftedIndex = event.getActionIndex();
                    int remainingIndex = liftedIndex == 0 ? 1 : 0;

                    if (remainingIndex < event.getPointerCount()) {
                        lastTouchX = event.getX(remainingIndex);
                        lastTouchY = event.getY(remainingIndex);
                    }

                    scaling = scaleDetector.isInProgress();
                    isPanning = !scaling
                            && scale > PAN_SCALE_THRESHOLD;

                    if (velocityTracker != null) {
                        velocityTracker.clear();
                        velocityTracker.addMovement(event);
                    }

                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                    }

                    boolean shouldFling = isPanning
                            && !scaleDetector.isInProgress()
                            && scale > PAN_SCALE_THRESHOLD;

                    if (shouldFling) {
                        startFling();
                    }

                    isPanning = false;
                    scaling = false;
                    draggingAfterScale = false;
                    intercepting = false;

                    recycleVelocityTracker();

                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }

                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    cancelFling();

                    isPanning = false;
                    scaling = false;
                    draggingAfterScale = false;
                    intercepting = false;

                    recycleVelocityTracker();

                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }

                    return true;
            }

            return true;
        }

        private void obtainVelocityTracker() {
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain();
            }
        }

        private void recycleVelocityTracker() {
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }

        private void cancelFling() {
            if (!scroller.isFinished()) {
                scroller.abortAnimation();
            }

            removeCallbacks(flingRunnable);
        }

        private final Runnable flingRunnable = new Runnable() {
            @Override
            public void run() {
                computeScroll();
            }
        };

        private void startFling() {
            if (velocityTracker == null) return;

            velocityTracker.computeCurrentVelocity(
                    1000, maximumFlingVelocity);

            int velocityX = Math.round(velocityTracker.getXVelocity());
            int velocityY = Math.round(velocityTracker.getYVelocity());

            if (Math.abs(velocityX) < minimumFlingVelocity) {
                velocityX = 0;
            }

            if (Math.abs(velocityY) < minimumFlingVelocity) {
                velocityY = 0;
            }

            if (velocityX == 0 && velocityY == 0) return;

            float maxX = Math.max(
                    0f, (getWidth() * scale - getWidth()) / 2f);

            float maxY = Math.max(
                    0f, (getHeight() * scale - getHeight()) / 2f);

            int minX = Math.round(-maxX);
            int maxBoundX = Math.round(maxX);
            int minY = Math.round(-maxY);
            int maxBoundY = Math.round(maxY);

            translationX = Math.max(
                    minX, Math.min(maxBoundX, translationX));

            translationY = Math.max(
                    minY, Math.min(maxBoundY, translationY));

            scroller.fling(
                    Math.round(translationX),
                    Math.round(translationY),
                    velocityX,
                    velocityY,
                    minX,
                    maxBoundX,
                    minY,
                    maxBoundY);

            postInvalidateOnAnimation();
        }

        @Override
        public void computeScroll() {
            super.computeScroll();

            if (scroller.computeScrollOffset()) {
                translationX = scroller.getCurrX();
                translationY = scroller.getCurrY();

                clampTranslation();
                updateMatrix();

                postInvalidateOnAnimation();
            }
        }

        private void clampTranslation() {
            if (scale <= MIN_SCALE) {
                scale = MIN_SCALE;
                translationX = 0f;
                translationY = 0f;
                return;
            }

            float width = getWidth();
            float height = getHeight();

            float maxX = Math.max(
                    0f, (width * scale - width) / 2f);

            float maxY = Math.max(
                    0f, (height * scale - height) / 2f);

            translationX = Math.max(
                    -maxX, Math.min(maxX, translationX));

            translationY = Math.max(
                    -maxY, Math.min(maxY, translationY));
        }

        private void updateMatrix() {
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;

            drawMatrix.reset();
            drawMatrix.postScale(
                    scale, scale, centerX, centerY);

            drawMatrix.postTranslate(
                    translationX, translationY);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (scale == 1f
                    && translationX == 0f
                    && translationY == 0f) {
                super.dispatchDraw(canvas);
                return;
            }

            int saveCount = canvas.save();

            canvas.concat(drawMatrix);
            super.dispatchDraw(canvas);

            canvas.restoreToCount(saveCount);
        }

        @Override
        protected void onSizeChanged(
                int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);

            clampTranslation();
            updateMatrix();
        }

        private class ScaleListener
                extends ScaleGestureDetector.SimpleOnScaleGestureListener {

            @Override
            public boolean onScaleBegin(
                    @NonNull ScaleGestureDetector detector) {

                cancelFling();

                scaling = true;
                isPanning = false;
                intercepting = true;

                lastTouchX = detector.getFocusX();
                lastTouchY = detector.getFocusY();

                return true;
            }

            @Override
            public boolean onScale(
                    @NonNull ScaleGestureDetector detector) {

                float previousScale = scale;

                float newScale = previousScale
                        * detector.getScaleFactor();

                newScale = Math.max(
                        MIN_SCALE, Math.min(MAX_SCALE, newScale));

                if (Math.abs(newScale - previousScale) < 0.0001f) {
                    return true;
                }

                float centerX = getWidth() / 2f;
                float centerY = getHeight() / 2f;

                float focalX = detector.getFocusX();
                float focalY = detector.getFocusY();

                float ratio = newScale / previousScale;

                translationX = focalX - centerX
                        - (focalX - centerX - translationX) * ratio;

                translationY = focalY - centerY
                        - (focalY - centerY - translationY) * ratio;

                scale = newScale;

                if (scale <= MIN_SCALE) {
                    scale = MIN_SCALE;
                    translationX = 0f;
                    translationY = 0f;
                } else {
                    clampTranslation();
                }

                updateMatrix();
                postInvalidateOnAnimation();

                return true;
            }

            @Override
            public void onScaleEnd(
                    @NonNull ScaleGestureDetector detector) {

                scaling = false;

                isPanning = scale > PAN_SCALE_THRESHOLD;

                lastTouchX = detector.getFocusX();
                lastTouchY = detector.getFocusY();

                updateMatrix();
                postInvalidateOnAnimation();
            }
        }
    }
}
