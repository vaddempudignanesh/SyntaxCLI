package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import vaddempudi.gnanesh.syntaxcli.R;

public class AlbumMediaAdapter extends RecyclerView.Adapter<AlbumMediaAdapter.VH> {

    public interface Listener {
        void onImageClick(String path);
        void onVideoClick(String path);
        void onItemLongPress(String path);
        void onItemClick(String path);
        boolean isSelectionMode();
    }

    private static final Object PAYLOAD_SELECTION = new Object();

    private final Context context;
    private final Listener listener;
    private final AsyncListDiffer<GalleryMediaItem> differ;

    private final Set<String> selected = new HashSet<>();

    private static final DiffUtil.ItemCallback<GalleryMediaItem> DIFF =
            new DiffUtil.ItemCallback<GalleryMediaItem>() {
                @Override public boolean areItemsTheSame(@NonNull GalleryMediaItem a,
                                                         @NonNull GalleryMediaItem b) {
                    return a.stableKey().equals(b.stableKey());
                }
                @Override public boolean areContentsTheSame(@NonNull GalleryMediaItem a,
                                                            @NonNull GalleryMediaItem b) {
                    return a.isFavorite == b.isFavorite
                            && a.isTrashed == b.isTrashed
                            && a.dateModifiedSeconds == b.dateModifiedSeconds
                            && a.type == b.type
                            && (a.path == null ? b.path == null : a.path.equals(b.path));
                }
                @Override public Object getChangePayload(@NonNull GalleryMediaItem oldItem,
                                                         @NonNull GalleryMediaItem newItem) {
                    return null;
                }
            };

    public AlbumMediaAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.differ = new AsyncListDiffer<>(this, DIFF);
        setHasStableIds(true);
    }

    public void setItems(List<GalleryMediaItem> newItems) {
        differ.submitList(newItems == null
                ? Collections.<GalleryMediaItem>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(newItems)));
    }

    public void clear() {
        differ.submitList(Collections.<GalleryMediaItem>emptyList());
    }

    public List<GalleryMediaItem> getItems() {
        return differ.getCurrentList();
    }

    public List<GalleryMediaItem> getItemsSnapshot() {
        return new ArrayList<>(differ.getCurrentList());
    }

    public void setSelectedItems(Collection<String> sel) {
        Set<String> oldSelected = new HashSet<>(selected);
        selected.clear();
        if (sel != null) selected.addAll(sel);

        if (oldSelected.equals(selected)) return;

        List<GalleryMediaItem> current = differ.getCurrentList();
        for (int i = 0; i < current.size(); i++) {
            String path = current.get(i).path;
            if (path == null) continue;
            boolean was = oldSelected.contains(path);
            boolean now = selected.contains(path);
            if (was != now) notifyItemChanged(i, PAYLOAD_SELECTION);
        }
    }

    public int indexOfPath(String path) {
        if (path == null) return -1;
        List<GalleryMediaItem> current = differ.getCurrentList();
        for (int i = 0; i < current.size(); i++) {
            if (path.equals(current.get(i).path)) return i;
        }
        return -1;
    }

    public GalleryMediaItem getItemAt(int pos) {
        List<GalleryMediaItem> current = differ.getCurrentList();
        if (pos < 0 || pos >= current.size()) return null;
        return current.get(pos);
    }

    @Override public int getItemCount() {
        return differ.getCurrentList().size();
    }

    @Override public long getItemId(int position) {
        GalleryMediaItem item = differ.getCurrentList().get(position);
        return item.id ^ (((long) item.type) << 61);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_gallery, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position,
                                 @NonNull List<Object> payloads) {
        GalleryMediaItem item = differ.getCurrentList().get(position);
        if (payloads != null && !payloads.isEmpty()) {
            for (Object p : payloads) {
                if (p == PAYLOAD_SELECTION) updateSelection(h, item);
            }
            return;
        }
        onBindViewHolder(h, position);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        GalleryMediaItem item = differ.getCurrentList().get(position);

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
        if (path.equals(tag) && holder.imageView.getDrawable() != null) {
            return;   // already showing the correct image — no Glide churn
        }
        holder.imageView.setTag(path);

        RequestOptions opts = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(new ObjectKey(item.dateModifiedSeconds + "_" + path.hashCode()))
                .override(512, 512)
                .placeholder(android.R.drawable.ic_menu_gallery);

        Glide.with(holder.imageView.getContext())
                .load(path)
                .apply(opts)
                .into(holder.imageView);
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        Glide.with(holder.imageView.getContext()).clear(holder.imageView);
        holder.imageView.setTag(null);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView imageView;
        final ImageView videoIcon;
        final ImageView favIcon;
        final ImageView checkIcon;
        final RelativeLayout videoOverlay;

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