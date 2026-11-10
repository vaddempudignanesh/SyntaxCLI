package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import vaddempudi.gnanesh.syntaxcli.R;

public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {

    private static final Object PAYLOAD_SELECTION = new Object();
    private static final Object PAYLOAD_FAVORITE = new Object();
    private volatile boolean forcedEmpty = false;

    public interface OnItemClickListener {
        void onImageClick(String path);
        void onVideoClick(String path);
        void onFavoriteToggle(GalleryMediaItem item);
        void onDelete(GalleryMediaItem item);
        void onItemClick(String path);
        void onItemLongPress(String path);
        boolean isSelectionMode();
        void onRestore(GalleryMediaItem item);
    }

    private final Context context;
    private final OnItemClickListener listener;

    private final AsyncListDiffer<GalleryMediaItem> differ;
    private List<String> selectedItems = Collections.emptyList();

    public GalleryAdapter(Context context, OnItemClickListener listener) {
        this.context = context;
        this.listener = listener;
        setHasStableIds(true);
        this.differ = new AsyncListDiffer<>(this, DIFF);
    }

    private static final DiffUtil.ItemCallback<GalleryMediaItem> DIFF =
            new DiffUtil.ItemCallback<GalleryMediaItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull GalleryMediaItem a,
                                               @NonNull GalleryMediaItem b) {
                    return a.stableKey().equals(b.stableKey());
                }

                @Override
                public boolean areContentsTheSame(@NonNull GalleryMediaItem a,
                                                  @NonNull GalleryMediaItem b) {
                    return a.path.equals(b.path)
                            && a.displayName.equals(b.displayName)
                            && a.dateModifiedSeconds == b.dateModifiedSeconds
                            && a.type == b.type
                            && a.isFavorite == b.isFavorite
                            && a.isTrashed == b.isTrashed;
                }

                @Override
                public Object getChangePayload(@NonNull GalleryMediaItem oldItem,
                                               @NonNull GalleryMediaItem newItem) {
                    if (oldItem.isFavorite != newItem.isFavorite) return PAYLOAD_FAVORITE;
                    return null;
                }
            };

    public void notifyFavoriteChanged(String path) {
        if (path == null) return;
        List<GalleryMediaItem> current = differ.getCurrentList();
        for (int i = 0; i < current.size(); i++) {
            if (path.equals(current.get(i).path)) {
                notifyItemChanged(i, PAYLOAD_FAVORITE);
                return;
            }
        }
    }

    public void submitList(List<GalleryMediaItem> items) {
        List<GalleryMediaItem> immutable = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        forcedEmpty = false;
        differ.submitList(immutable);
    }

    public void setSelectedItems(List<String> selected) {
        List<String> oldSelected = this.selectedItems;
        this.selectedItems = selected == null
                ? Collections.emptyList()
                : new ArrayList<>(selected);

        List<GalleryMediaItem> current = differ.getCurrentList();
        for (int i = 0; i < current.size(); i++) {
            String path = current.get(i).path;
            boolean wasSelected = oldSelected.contains(path);
            boolean nowSelected = this.selectedItems.contains(path);
            if (wasSelected != nowSelected) notifyItemChanged(i, PAYLOAD_SELECTION);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_gallery, parent, false);
        return new ViewHolder(v);
    }
    

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (forcedEmpty) return;
        if (payloads != null && !payloads.isEmpty()) {
            GalleryMediaItem item = differ.getCurrentList().get(position);
            for (Object p : payloads) {
                if (p == PAYLOAD_SELECTION) updateSelection(holder, item);
                else if (p == PAYLOAD_FAVORITE) updateFavorite(holder, item);
            }
            return;
        }
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        GalleryMediaItem item = differ.getCurrentList().get(position);
        holder.bind(item);
    }

    public List<GalleryMediaItem> getItems() {
        if (forcedEmpty) return Collections.emptyList();
        return differ.getCurrentList();
    }

    public void clearNow() {
        differ.submitList(Collections.emptyList());
    }

    public void publishImmediately(List<GalleryMediaItem> items) {
        differ.submitList(items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items)));
    }

    @Override public int getItemCount() {
        return differ.getCurrentList().size();
    }

    @Override public long getItemId(int position) {
        GalleryMediaItem item = differ.getCurrentList().get(position);
        return item.id ^ (((long) item.type) << 61);
    }

    private void updateSelection(ViewHolder holder, GalleryMediaItem item) {
        boolean selectionMode = listener.isSelectionMode();
        holder.checkIcon.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        if (selectionMode) {
            boolean selected = selectedItems.contains(item.path);
            holder.checkIcon.setImageResource(selected
                    ? R.drawable.ic_checkbox_checked
                    : R.drawable.ic_checkbox_empty);
        }
    }

    private void updateFavorite(ViewHolder holder, GalleryMediaItem item) {
        if (item.isFavorite) {
            holder.favIcon.setColorFilter(Color.parseColor("#FFD700"));
            holder.favIcon.setVisibility(View.VISIBLE);
        } else {
            holder.favIcon.clearColorFilter();
            holder.favIcon.setVisibility(View.INVISIBLE);
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;
        final ImageView videoIcon;
        final ImageView favIcon;
        final ImageView checkIcon;
        final RelativeLayout videoOverlay;

        ViewHolder(View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.gallery_image);
            videoIcon = itemView.findViewById(R.id.video_icon);
            favIcon = itemView.findViewById(R.id.fav_icon);
            checkIcon = itemView.findViewById(R.id.check_icon);
            videoOverlay = itemView.findViewById(R.id.video_overlay);
        }

        void bind(GalleryMediaItem item) {
            videoIcon.setVisibility(View.GONE);
            checkIcon.setVisibility(View.GONE);
            videoOverlay.setVisibility(View.GONE);

            updateFavorite(this, item);

            if (item.isTrashed && item.type == GalleryMediaItem.TYPE_VIDEO) {
                videoOverlay.setVisibility(View.VISIBLE);
            }
            if (item.type == GalleryMediaItem.TYPE_VIDEO && !item.isTrashed) {
                videoIcon.setVisibility(View.VISIBLE);
            }

            loadThumbnail(this, item);

            itemView.setOnClickListener(v -> {
                animateClickBounce(v);
                if (item.isTrashed) {
                    if (listener.isSelectionMode()) listener.onItemClick(item.path);
                    else listener.onRestore(item);
                    return;
                }
                if (listener.isSelectionMode()) {
                    listener.onItemClick(item.path);
                } else {
                    if (item.type == GalleryMediaItem.TYPE_IMAGE) listener.onImageClick(item.path);
                    else listener.onVideoClick(item.path);
                }
            });

            itemView.setOnLongClickListener(v -> {
                animateClickBounce(v);
                listener.onItemLongPress(item.path);
                return true;
            });

            updateSelection(this, item);

            favIcon.setOnClickListener(v -> {
                animateClickBounce(v);
                listener.onFavoriteToggle(item);
            });
        }
    }

    private void animateClickBounce(View v) {
        if (v == null) return;
        try {
            v.startAnimation(AnimationUtils.loadAnimation(
                    v.getContext(), R.anim.bounce_animation));
        } catch (Exception ignored) {}
    }

    /**
     * Glide-based thumbnail load. This is the ONLY functional change from
     * the previous BitmapFactory implementation. Every call site, every
     * bitmap, every size, every content — identical.
     *
     * The ObjectKey signature is (dateModifiedSeconds + path hash) so Glide
     * can safely reuse the cached bitmap until the file is actually modified.
     * Disk cache = RESOURCE, so a cold second-launch is instant.
     */
    private void loadThumbnail(ViewHolder holder, GalleryMediaItem item) {
        final String path = item.path;

        // Placeholder first so the view never shows a stale bitmap.
        holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);

        RequestOptions opts = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(new ObjectKey(item.dateModifiedSeconds + "_" + path.hashCode()))
                .override(512, 512);   // fixed decode size → constant memory

        Glide.with(holder.imageView.getContext())
                .load(path)
                .apply(opts)
                .transition(DrawableTransitionOptions.withCrossFade(80))
                .into(holder.imageView);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        // Glide wants the request cleared so it can be re-targeted on the next bind.
        Glide.with(holder.imageView.getContext()).clear(holder.imageView);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
    }
}