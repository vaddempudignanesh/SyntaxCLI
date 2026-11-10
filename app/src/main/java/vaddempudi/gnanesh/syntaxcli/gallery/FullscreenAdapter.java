package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.TextureView;
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

public class FullscreenAdapter extends RecyclerView.Adapter<FullscreenAdapter.ViewHolder> {

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

    public HardwareVideoController activateControllerAt(int position, TextureView surface) {
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
        public final TextureView videoSurface;
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

        if (holder.boundPosition != -1 && holder.boundPosition != boundPosition) {
            controller.detachSurface(holder.videoSurface);
        }

        holder.boundPosition = boundPosition;
        holder.progressBar.setVisibility(View.VISIBLE);

        if (isVideoPath(path)) {
            holder.imageView.setVisibility(View.GONE);
            holder.imageView.setImageDrawable(null);
            holder.imageView.setTag(null);
            holder.videoSurface.setVisibility(View.VISIBLE);

            holder.videoSurface.setOnTouchListener((v, event) -> {
                if (videoTouchForwarder != null) videoTouchForwarder.onTouch(event);
                if (event.getActionMasked() == MotionEvent.ACTION_UP && tapCallback != null) {
                    tapCallback.onTap();
                }
                return true;
            });

            if (boundPosition == activePosition) {
                controller.setOnPreparedListener(d -> {
                    if (holder.boundPosition != boundPosition) return;
                    holder.progressBar.setVisibility(View.GONE);
                    if (pageTypeCallback != null && boundPosition == activePosition) {
                        pageTypeCallback.onVideoVisible(controller, boundPosition);
                    }
                });
                controller.setOnErrorListener(err ->
                        android.util.Log.e("gallery-tui", "shared onError pos=" + boundPosition, err));

                controller.pauseForPageChange();

                controller.showPath(path, holder.videoSurface, true);
                if (controller.isPreparedNow()) holder.progressBar.setVisibility(View.GONE);
            } else {
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

    public void pauseForClose() {
        activePosition = -1;
        controller.pauseOnly();
    }

    public void hardReset() {
        activePosition = -1;
        controller.release();
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
        if (holder.boundPosition == activePosition) {
            controller.pauseForPageChange();
        }
        controller.detachSurface(holder.videoSurface);
        holder.imageView.setTag(null);
        try {
            holder.imageView.setImageMatrix(new android.graphics.Matrix());
        } catch (Throwable ignored) {}
        holder.imageView.setImageDrawable(null);

        holder.videoSurface.setVisibility(View.GONE);
        holder.videoSurface.setOnTouchListener(null);
        holder.imageView.setVisibility(View.GONE);
        holder.progressBar.setVisibility(View.GONE);
        holder.boundPosition = -1;
        super.onViewRecycled(holder);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        executor.shutdownNow();
    }

    @Override
    public int getItemCount() {
        return paths != null ? paths.size() : 0;
    }
}