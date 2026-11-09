package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vaddempudi.gnanesh.syntaxcli.R;

/**
 * Single-controller architecture:
 *
 *  - ONE HardwareVideoController for the entire adapter. It owns ONE ExoPlayer.
 *  - Each video ViewHolder owns its own SurfaceView (as before).
 *  - When a page becomes active, FullscreenAdapter.activateControllerAt binds
 *    the controller to that page's SurfaceView via showPath().
 *  - When a page goes inactive, the controller's surface binding is swapped
 *    synchronously to the new active page's SurfaceView by the next showPath().
 *  - The adapter never creates or releases the controller during normal swiping;
 *    only reset() (called on close/back) releases it.
 */
public class FullscreenAdapter extends RecyclerView.Adapter<FullscreenAdapter.ViewHolder> {

    private static final String LOG_TAG = "gallery-tui";

    public interface TapCallback { void onTap(); }
    public interface PageTypeCallback {
        void onImageVisible(int position);
        void onVideoVisible(HardwareVideoController ctrl, int position);
    }
    public interface VideoTouchForwarder { void onTouch(MotionEvent event); }

    private final List<String> paths;
    private final Context context;
    private TapCallback tapCallback;
    private PageTypeCallback pageTypeCallback;
    private VideoTouchForwarder videoTouchForwarder;

    private int activePosition = -1;
    private final HardwareVideoController controller;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public FullscreenAdapter(List<String> paths, Context context) {
        this.paths = paths;
        this.context = context.getApplicationContext();
        this.controller = new HardwareVideoController(context);
    }

    public void setTapCallback(TapCallback cb) { this.tapCallback = cb; }
    public void setPageTypeCallback(PageTypeCallback cb) { this.pageTypeCallback = cb; }
    public void setVideoTouchForwarder(VideoTouchForwarder f) { this.videoTouchForwarder = f; }

    public void setActivePosition(int pos) { this.activePosition = pos; }
    public int getActivePosition() { return activePosition; }

    public HardwareVideoController getController(int position) { return controller; }

    /**
     * Called by GalleryActivity when a page becomes the active one. Binds the
     * shared controller to this page's SurfaceView and starts playback. If the
     * incoming page isn't a video, we don't touch the controller at all (the
     * previous video's surface is left attached so swipe-back is instant).
     */
    public HardwareVideoController activateControllerAt(int position, SurfaceView surface) {
        this.activePosition = position;
        if (position < 0 || position >= paths.size()) return null;
        String path = paths.get(position);
        if (!isVideoPath(path)) return null;
        if (surface == null) return null;
        if (surface.getVisibility() != View.VISIBLE) surface.setVisibility(View.VISIBLE);
        controller.showPath(path, surface, /* startWhenReady = */ true);
        return controller;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final ProgressBar progressBar;
        public final ZoomableImageView imageView;
        public final SurfaceView videoSurface;
        public int boundPosition = -1;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            progressBar = itemView.findViewById(R.id.fullscreen_progress);
            imageView = itemView.findViewById(R.id.fullscreen_image);
            videoSurface = itemView.findViewById(R.id.fullscreen_video);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_fullscreen_media, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final int boundPosition = position;
        final String path = paths.get(position);
        Log.d(LOG_TAG, "bind pos=" + boundPosition + " active=" + activePosition + " path=" + path);

        holder.boundPosition = boundPosition;
        holder.progressBar.setVisibility(View.VISIBLE);

        if (isVideoPath(path)) {
            holder.imageView.setVisibility(View.GONE);
            holder.imageView.setImageDrawable(null);
            holder.imageView.setTag(null);

            // SurfaceView must be VISIBLE for its Surface to be produced.
            holder.videoSurface.setVisibility(View.VISIBLE);
            holder.videoSurface.setOnTouchListener((v, event) -> {
                if (videoTouchForwarder != null) videoTouchForwarder.onTouch(event);
                if (event.getActionMasked() == MotionEvent.ACTION_UP && tapCallback != null) {
                    tapCallback.onTap();
                }
                return true;
            });

            if (boundPosition == activePosition) {
                // Active page — bind the shared controller to this surface.
                controller.setOnPreparedListener(d -> {
                    if (holder.boundPosition != boundPosition) return;
                    holder.progressBar.setVisibility(View.GONE);
                    if (pageTypeCallback != null && boundPosition == activePosition) {
                        pageTypeCallback.onVideoVisible(controller, boundPosition);
                    }
                });
                controller.setOnErrorListener(err ->
                        Log.e(LOG_TAG, "shared onError pos=" + boundPosition, err));

                controller.showPath(path, holder.videoSurface, true);
                if (controller.isPreparedNow()) holder.progressBar.setVisibility(View.GONE);
            } else {
                // Off-screen neighbor — no player activity, no spinner.
                holder.progressBar.setVisibility(View.GONE);
            }
            return;
        }

        // Image page.
        holder.videoSurface.setVisibility(View.GONE);
        holder.videoSurface.setOnTouchListener(null);

        Object tag = holder.imageView.getTag();
        if (path.equals(tag) && holder.imageView.getDrawable() != null) {
            holder.progressBar.setVisibility(View.GONE);
            holder.imageView.setVisibility(View.VISIBLE);
            holder.imageView.setOnTapListener(() -> {
                if (tapCallback != null) tapCallback.onTap();
            });
            if (pageTypeCallback != null) pageTypeCallback.onImageVisible(boundPosition);
            return;
        }

        holder.imageView.setImageDrawable(null);
        holder.imageView.setTag(path);

        executor.execute(() -> {
            Bitmap bmp = decodeSampled(path);
            holder.itemView.post(() -> {
                if (holder.boundPosition != boundPosition) return;
                holder.progressBar.setVisibility(View.GONE);
                holder.imageView.setVisibility(View.VISIBLE);
                if (bmp != null) holder.imageView.setImageBitmap(bmp);
                else holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
                holder.imageView.setOnTapListener(() -> {
                    if (tapCallback != null) tapCallback.onTap();
                });
                if (pageTypeCallback != null) pageTypeCallback.onImageVisible(boundPosition);
            });
        });
    }

    public static boolean isVideoPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".3gp")
                || lower.endsWith(".m4v") || lower.endsWith(".flv") || lower.endsWith(".wmv");
    }

    /** Release everything. Called on close/back. */
    public void reset() {
        Log.d(LOG_TAG, "adapter.reset");
        activePosition = -1;
        // IMPORTANT: do not call controller.release() here. The adapter owns the
        // controller for its own lifetime; the activity owns the adapter. We only
        // stop playback + detach the surface, so the next open reuses the same
        // ExoPlayer instance (no decoder churn, no black screen).
        controller.stopAndDetach();
    }

    private Bitmap decodeSampled(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int reqW = 1080, reqH = 1920, scale = 1;
            while ((o.outWidth / scale) > reqW * 2 && (o.outHeight / scale) > reqH * 2) {
                scale *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Drawable d = holder.imageView.getDrawable();
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) {
                try { b.recycle(); } catch (Exception ignored) {}
            }
        }
        holder.videoSurface.setVisibility(View.GONE);
        holder.videoSurface.setOnTouchListener(null);
        holder.imageView.setVisibility(View.GONE);
        holder.imageView.setImageDrawable(null);
        holder.imageView.setTag(null);
        holder.progressBar.setVisibility(View.GONE);
        holder.boundPosition = -1;
        super.onViewRecycled(holder);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        executor.shutdownNow();
        controller.stopAndDetach();
        controller.release();   // safe: adapter is being torn down for good
    }

    @Override
    public int getItemCount() {
        return paths != null ? paths.size() : 0;
    }
}