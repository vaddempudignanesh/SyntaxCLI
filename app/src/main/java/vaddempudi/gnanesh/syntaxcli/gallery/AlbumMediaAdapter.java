package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import vaddempudi.gnanesh.syntaxcli.R;

public class AlbumMediaAdapter extends RecyclerView.Adapter<AlbumMediaAdapter.VH> {

    public interface Listener {
        void onImageClick(String path);
        void onVideoClick(String path);
        void onItemLongPress(String path);
        void onItemClick(String path);
        boolean isSelectionMode();
    }

    /** Payload tag — tells onBindViewHolder to only refresh the checkbox. */
    private static final Object PAYLOAD_SELECTION = new Object();

    private final Context context;
    private final Listener listener;
    private final ThumbnailCache thumbnailCache;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<GalleryMediaItem> items = Collections.emptyList();
    private final Set<String> selected = new HashSet<>();

    public AlbumMediaAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.thumbnailCache = ThumbnailCache.getInstance(context);
    }

    public void setItems(List<GalleryMediaItem> newItems) {
        this.items = newItems == null
                ? Collections.<GalleryMediaItem>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(newItems));
        notifyDataSetChanged();
    }

    public void clear() {
        this.items = Collections.emptyList();
        notifyDataSetChanged();
    }

    public List<GalleryMediaItem> getItems() {
        return items;
    }

    public List<GalleryMediaItem> getItemsSnapshot() {
        return new ArrayList<>(items);
    }

    /**
     * Updates the selected set and notifies ONLY the rows whose selection
     * actually changed, using PAYLOAD_SELECTION so the image is left alone.
     * This is what kills the flicker during long-press drag.
     */
    public void setSelectedItems(Collection<String> sel) {
        Set<String> oldSelected = new HashSet<>(selected);
        selected.clear();
        if (sel != null) selected.addAll(sel);

        if (oldSelected.equals(selected)) return;   // nothing changed → no rebind

        for (int i = 0; i < items.size(); i++) {
            String path = items.get(i).path;
            if (path == null) continue;
            boolean was = oldSelected.contains(path);
            boolean now = selected.contains(path);
            if (was != now) {
                notifyItemChanged(i, PAYLOAD_SELECTION);
            }
        }
    }

    public int indexOfPath(String path) {
        if (path == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (path.equals(items.get(i).path)) return i;
        }
        return -1;
    }

    public GalleryMediaItem getItemAt(int pos) {
        if (pos < 0 || pos >= items.size()) return null;
        return items.get(pos);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_gallery, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position,
                                 @NonNull List<Object> payloads) {
        if (payloads != null && !payloads.isEmpty()) {
            GalleryMediaItem item = items.get(position);
            for (Object p : payloads) {
                if (p == PAYLOAD_SELECTION) {
                    updateSelection(h, item);
                }
            }
            return;
        }
        onBindViewHolder(h, position);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        GalleryMediaItem item = items.get(position);

        h.videoIcon.setVisibility(
                item.type == GalleryMediaItem.TYPE_VIDEO ? View.VISIBLE : View.GONE);
        h.videoOverlay.setVisibility(View.GONE);

        if (item.isFavorite) {
            h.favIcon.setColorFilter(Color.parseColor("#FFD700"));
            h.favIcon.setVisibility(View.VISIBLE);
        } else {
            h.favIcon.clearColorFilter();
            h.favIcon.setVisibility(View.INVISIBLE);
        }

        updateSelection(h, item);
        loadThumbnail(h, item);

        h.itemView.setOnClickListener(v -> {
            if (listener == null) return;
            if (listener.isSelectionMode()) {
                listener.onItemClick(item.path);
            } else if (item.type == GalleryMediaItem.TYPE_IMAGE) {
                listener.onImageClick(item.path);
            } else {
                listener.onVideoClick(item.path);
            }
        });

        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onItemLongPress(item.path);
            return true;
        });
    }

    private void updateSelection(VH h, GalleryMediaItem item) {
        boolean selMode = listener != null && listener.isSelectionMode();
        h.checkIcon.setVisibility(selMode ? View.VISIBLE : View.GONE);
        if (selMode) {
            boolean isSel = selected.contains(item.path);
            h.checkIcon.setImageResource(isSel
                    ? R.drawable.ic_checkbox_checked
                    : R.drawable.ic_checkbox_empty);
        }
    }

    private void loadThumbnail(VH holder, GalleryMediaItem item) {
        final String path = item.path;

        Object tag = holder.imageView.getTag();
        if (tag != null && tag.equals(path) && holder.imageView.getDrawable() != null) {
            return;
        }

        holder.imageView.setImageDrawable(null);
        holder.imageView.setTag(path);

        final long myGen = holder.bindGeneration.incrementAndGet();

        executor.execute(() -> {
            Bitmap bmp = thumbnailCache.getThumbnail(path, item.type);
            mainHandler.post(() -> {
                if (holder.bindGeneration.get() != myGen) return;
                Object t = holder.imageView.getTag();
                if (t == null || !t.equals(path)) return;
                if (bmp != null) holder.imageView.setImageBitmap(bmp);
                else holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
            });
        });
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView imageView;
        final ImageView videoIcon;
        final ImageView favIcon;
        final ImageView checkIcon;
        final RelativeLayout videoOverlay;
        final AtomicLong bindGeneration = new AtomicLong(0);

        VH(View v) {
            super(v);
            imageView = v.findViewById(R.id.gallery_image);
            videoIcon = v.findViewById(R.id.video_icon);
            favIcon   = v.findViewById(R.id.fav_icon);
            checkIcon = v.findViewById(R.id.check_icon);
            videoOverlay = v.findViewById(R.id.video_overlay);
        }
    }
}