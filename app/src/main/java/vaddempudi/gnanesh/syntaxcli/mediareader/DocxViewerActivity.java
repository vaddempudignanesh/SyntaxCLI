package vaddempudi.gnanesh.syntaxcli.mediareader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.UnderlineSpan;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
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
    private ZoomableFrameLayout zoomContainer;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        zoomContainer = new ZoomableFrameLayout(this);
        root.addView(zoomContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
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
                    Toast.makeText(this, "Cannot open docx", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    private void render(DocxReader.Result result, File imageDir) {
        progressBar.setVisibility(View.GONE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.WHITE);

        String marked = result.markedText;
        List<DocxReader.ImageRef> images = result.images;

        int imageIdx = 0;
        StringBuilder segment = new StringBuilder();

        for (int i = 0; i < marked.length(); i++) {
            char c = marked.charAt(i);
            if (c == DocxReader.IMAGE_MARKER) {
                flushTextSegment(column, segment, false);

                if (imageIdx < images.size()) {
                    addImage(column, images.get(imageIdx).extractedFile);
                    imageIdx++;
                }
                if (i + 1 < marked.length() && marked.charAt(i + 1) == '\u000B') i++;
            } else {
                segment.append(c);
            }
        }
        flushTextSegment(column, segment, true);

        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setFillViewport(false);
        hScroll.addView(column, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));

        ScrollView vScroll = new ScrollView(this);
        vScroll.setVerticalScrollBarEnabled(true);
        vScroll.setFillViewport(false);
        vScroll.addView(hScroll, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.WRAP_CONTENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        zoomContainer.addView(vScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void flushTextSegment(LinearLayout column, StringBuilder segment, boolean last) {
        if (segment.length() == 0) return;

        TextView tv = new TextView(this);
        tv.setBackgroundColor(Color.WHITE);
        tv.setTextColor(Color.BLACK);
        tv.setTextSize(14);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setHorizontallyScrolling(true);
        tv.setSingleLine(false);
        tv.setText(applyFormatting(segment.toString()));
        tv.setPadding(16, 8, 16, 8);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        column.addView(tv, lp);

        segment.setLength(0);
    }

    private Spanned applyFormatting(String marked) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int[] boldStart = {-1}, italicStart = {-1}, underStart = {-1}, strikeStart = {-1};

        for (int i = 0; i < marked.length(); i++) {
            char c = marked.charAt(i);
            int cleanLen = sb.length();

            if (c == 0x01) { if (boldStart[0] < 0) boldStart[0] = cleanLen; }
            else if (c == 0x02) {
                if (boldStart[0] >= 0) {
                    sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                            boldStart[0], cleanLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    boldStart[0] = -1;
                }
            }
            else if (c == 0x03) { if (italicStart[0] < 0) italicStart[0] = cleanLen; }
            else if (c == 0x04) {
                if (italicStart[0] >= 0) {
                    sb.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC),
                            italicStart[0], cleanLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    italicStart[0] = -1;
                }
            }
            else if (c == 0x05) { if (underStart[0] < 0) underStart[0] = cleanLen; }
            else if (c == 0x06) {
                if (underStart[0] >= 0) {
                    sb.setSpan(new UnderlineSpan(),
                            underStart[0], cleanLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    underStart[0] = -1;
                }
            }
            else if (c == 0x07) { if (strikeStart[0] < 0) strikeStart[0] = cleanLen; }
            else if (c == 0x08) {
                if (strikeStart[0] >= 0) {
                    sb.setSpan(new StrikethroughSpan(),
                            strikeStart[0], cleanLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    strikeStart[0] = -1;
                }
            }
            else {
                sb.append(c);
            }
        }
        return sb;
    }

    private void addImage(LinearLayout column, File file) {
        if (file == null || !file.exists()) return;

        Bitmap bmp = decodeSampled(file, 1080);
        if (bmp == null) {
            TextView placeholder = new TextView(this);
            placeholder.setText("[Image]");
            placeholder.setTextColor(Color.GRAY);
            placeholder.setPadding(16, 8, 16, 8);
            column.addView(placeholder);
            return;
        }

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_START);
        iv.setImageBitmap(bmp);
        iv.setPadding(16, 8, 16, 8);

        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w > maxWidth) {
            h = (int) (h * (maxWidth / (float) w));
            w = maxWidth;
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        column.addView(iv, lp);
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
        if (zoomContainer != null) zoomContainer.release();
    }

    private static class ZoomableFrameLayout extends FrameLayout {

        private static final float MAX_SCALE = 5.0f;

        private float scale = 1.0f;
        private float translationX = 0f, translationY = 0f;
        private final Matrix drawMatrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private float lastTouchX, lastTouchY;
        private boolean isPanning = false;

        private final GestureDetector doubleTapDetector;

        ZoomableFrameLayout(@NonNull android.content.Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
            setLayerType(LAYER_TYPE_HARDWARE, null);

            scaleDetector = new ScaleGestureDetector(c, new ScaleListener());

            doubleTapDetector = new GestureDetector(c,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(@NonNull MotionEvent e) { return true; }
                        @Override public boolean onDoubleTap(@NonNull MotionEvent e) {
                            float target = scale > 1.01f ? 1f : 2.0f;
                            float prev = scale;
                            scale = target;
                            if (scale <= 1.0f) {
                                scale = 1.0f;
                                translationX = 0f;
                                translationY = 0f;
                            } else {
                                float fx = e.getX();
                                float fy = e.getY();
                                translationX = fx - (fx - translationX) * (scale / prev);
                                translationY = fy - (fy - translationY) * (scale / prev);
                                clampTranslation();
                            }
                            updateMatrix();
                            invalidate();
                            return true;
                        }
                    });
        }

        void release() {
            setOnTouchListener(null);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);
            doubleTapDetector.onTouchEvent(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = ev.getX();
                    lastTouchY = ev.getY();
                    return false;
                case MotionEvent.ACTION_POINTER_DOWN:
                    isPanning = false;
                    if (getParent() != null)
                        getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (scale > 1.01f && ev.getPointerCount() == 1) return true;
                    return false;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            doubleTapDetector.onTouchEvent(event);

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
                    if (isPanning && scale > 1.01f && event.getPointerCount() == 1) {
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
                    int remaining = idx == 0 ? 1 : 0;
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
                    if (getParent() != null)
                        getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return true;
        }

        private void clampTranslation() {
            if (scale <= 1.0f) { translationX = 0f; translationY = 0f; return; }
            int w = getWidth(), h = getHeight();
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
        protected void dispatchDraw(android.graphics.Canvas canvas) {
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
            @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
                isPanning = false; return true;
            }
            @Override public boolean onScale(@NonNull ScaleGestureDetector d) {
                float prev = scale;
                float next = Math.max(1.0f, Math.min(prev * d.getScaleFactor(), MAX_SCALE));
                if (next == prev) return false;
                float fx = d.getFocusX(), fy = d.getFocusY();
                translationX = fx - (fx - translationX) * (next / prev);
                translationY = fy - (fy - translationY) * (next / prev);
                scale = next;
                if (scale <= 1.0f) {
                    scale = 1.0f; translationX = 0f; translationY = 0f;
                } else {
                    clampTranslation();
                }
                updateMatrix();
                invalidate();
                return true;
            }
            @Override public void onScaleEnd(@NonNull ScaleGestureDetector d) {
                isPanning = scale > 1.01f;
            }
        }
    }
}