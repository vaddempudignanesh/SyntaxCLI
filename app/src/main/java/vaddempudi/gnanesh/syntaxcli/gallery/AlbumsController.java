package vaddempudi.gnanesh.syntaxcli.gallery;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import vaddempudi.gnanesh.syntaxcli.R;

final class AlbumsController {

    interface Host {
        View findView(int id);
        void onAlbumOpened(AlbumRecord album);
        void onAlbumClosed();
        void onAlbumMediaClicked(String path);
        void onAlbumMediaLongPress(String path);
        void onAlbumMediaToggled(String path);
        boolean isAlbumSelectionMode();
    }

    private final AtomicLong generation = new AtomicLong(0);
    private final AlbumLoader loader;
    private final AlbumAdapter albumAdapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Host host;

    private final RecyclerView albumRecycler;
    private final RecyclerView mediaRecycler;
    private final ProgressBar spinner;
    private final TextView title;
    private final View empty;

    private List<AlbumRecord> cachedAlbums = null;

    private boolean showingAlbums = false;
    private AlbumRecord openAlbum = null;

    // The currently-installed album adapter. Swapped on every album open.
    private AlbumMediaAdapter currentAlbumAdapter = null;

    AlbumsController(Host host, AlbumLoader loader, AlbumAdapter albumAdapter) {
        this.host = host;
        this.loader = loader;
        this.albumAdapter = albumAdapter;

        this.albumRecycler = (RecyclerView) host.findView(R.id.albumRecycler);
        this.mediaRecycler = (RecyclerView) host.findView(R.id.galleryRecycler);
        this.spinner = (ProgressBar) host.findView(R.id.loadingSpinner);
        this.title = (TextView) host.findView(R.id.titleGallery);
        this.empty = host.findView(R.id.emptyStateContainer);

        if (albumRecycler != null) {
            albumRecycler.setLayoutManager(
                    new GridLayoutManager(albumRecycler.getContext(), 2));
            albumRecycler.setAdapter(albumAdapter);
            albumRecycler.setItemViewCacheSize(12);
        }
    }

    boolean isShowingAlbums() { return showingAlbums; }
    AlbumRecord getOpenAlbum() { return openAlbum; }
    boolean isAlbumOpen() { return openAlbum != null; }

    // ── Show album grid ───────────────────────────────────────────

    void showAlbums() {
        if (showingAlbums && openAlbum == null) return;

        showingAlbums = true;
        openAlbum = null;

        if (mediaRecycler != null) {
            mediaRecycler.setAdapter(null);
            mediaRecycler.setVisibility(View.GONE);
        }
        currentAlbumAdapter = null;

        if (title != null) title.setText("Albums");
        if (albumRecycler != null) albumRecycler.setVisibility(View.GONE);
        if (empty != null) empty.setVisibility(View.GONE);

        // Fast path: we already have the album list from a previous scan.
        // Re-render it synchronously — no MediaStore query, no spinner.
        if (cachedAlbums != null) {
            albumAdapter.submit(cachedAlbums);
            if (spinner != null) spinner.setVisibility(View.GONE);
            if (albumRecycler != null) {
                albumRecycler.setVisibility(cachedAlbums.isEmpty() ? View.GONE : View.VISIBLE);
            }
            if (empty != null) {
                empty.setVisibility(cachedAlbums.isEmpty() ? View.VISIBLE : View.GONE);
                TextView et = (TextView) host.findView(R.id.emptyStateText);
                if (et != null) et.setText("No albums");
            }
            return;
        }

        // Slow path: first time only (or after invalidateAlbums()).
        if (spinner != null) spinner.setVisibility(View.VISIBLE);

        final long gen = generation.incrementAndGet();
        io.execute(() -> {
            final List<AlbumRecord> albums = loader.loadAlbums();
            main.post(() -> {
                if (gen != generation.get()) return;
                if (!showingAlbums || openAlbum != null) return;

                cachedAlbums = albums;

                albumAdapter.submit(albums);
                if (spinner != null) spinner.setVisibility(View.GONE);
                if (albumRecycler != null) {
                    albumRecycler.setVisibility(albums.isEmpty() ? View.GONE : View.VISIBLE);
                }
                if (empty != null) {
                    empty.setVisibility(albums.isEmpty() ? View.VISIBLE : View.GONE);
                    TextView et = (TextView) host.findView(R.id.emptyStateText);
                    if (et != null) et.setText("No albums");
                }
            });
        });
    }


    AlbumMediaAdapter getCurrentAlbumAdapter() { return currentAlbumAdapter; }
    RecyclerView getMediaRecycler() { return mediaRecycler; }

    void invalidateAlbums() {
        cachedAlbums = null;
    }

    // ── Open a single album ───────────────────────────────────────

    void openAlbum(AlbumRecord album) {
        if (album == null || mediaRecycler == null) return;

        openAlbum = album;
        showingAlbums = false;

        if (title != null) title.setText(album.displayName);
        if (albumRecycler != null) albumRecycler.setVisibility(View.GONE);
        if (empty != null) empty.setVisibility(View.GONE);
        if (spinner != null) spinner.setVisibility(View.VISIBLE);

        final AlbumMediaAdapter freshAdapter = new AlbumMediaAdapter(
                mediaRecycler.getContext(),
                new AlbumMediaAdapter.Listener() {
                    @Override public void onImageClick(String path) {
                        if (host != null) host.onAlbumMediaClicked(path);
                    }
                    @Override public void onVideoClick(String path) {
                        if (host != null) host.onAlbumMediaClicked(path);
                    }
                    @Override public void onItemLongPress(String path) {
                        if (host != null) host.onAlbumMediaLongPress(path);
                    }
                    @Override public void onItemClick(String path) {
                        if (host != null) host.onAlbumMediaToggled(path);
                    }
                    @Override public boolean isSelectionMode() {
                        return host != null && host.isAlbumSelectionMode();
                    }
                });
        currentAlbumAdapter = freshAdapter;
        mediaRecycler.setVisibility(View.GONE);
        mediaRecycler.setAdapter(freshAdapter);
        // ──────────────────────────────────────────────────────────

        final long gen = generation.incrementAndGet();
        io.execute(() -> {
            final List<GalleryMediaItem> items = loader.loadAlbumItems(album);
            main.post(() -> {
                if (gen != generation.get()) return;
                if (openAlbum == null || !openAlbum.path.equals(album.path)) return;
                if (currentAlbumAdapter != freshAdapter) return;

                boolean hasItems = items != null && !items.isEmpty();
                freshAdapter.setItems(hasItems ? items : new ArrayList<GalleryMediaItem>());

                if (spinner != null) spinner.setVisibility(View.GONE);
                if (mediaRecycler != null) {
                    mediaRecycler.setVisibility(hasItems ? View.VISIBLE : View.GONE);
                }
                if (empty != null) {
                    empty.setVisibility(hasItems ? View.GONE : View.VISIBLE);
                    TextView et = (TextView) host.findView(R.id.emptyStateText);
                    if (et != null) et.setText("No media found");
                }

                host.onAlbumOpened(album);
            });
        });
    }

    List<GalleryMediaItem> getOpenAlbumItems() {
        if (currentAlbumAdapter == null) return java.util.Collections.emptyList();
        return currentAlbumAdapter.getItems();
    }
    boolean onBack() {
        if (openAlbum != null) { closeAlbum(); return true; }
        if (showingAlbums) { closeAlbumList(); return true; }
        return false;
    }

    private void closeAlbum() {
        openAlbum = null;
        generation.incrementAndGet();

        if (mediaRecycler != null) {
            mediaRecycler.setAdapter(null);
            mediaRecycler.setVisibility(View.GONE);
        }
        currentAlbumAdapter = null;
        host.onAlbumClosed();
        showAlbums();
    }

    void leaveAlbums() {
        showingAlbums = false;
        openAlbum = null;
        generation.incrementAndGet();

        if (albumRecycler != null) albumRecycler.setVisibility(View.GONE);
        if (mediaRecycler != null) {
            mediaRecycler.setAdapter(null);
            mediaRecycler.setVisibility(View.GONE);
        }
        currentAlbumAdapter = null;
    }

    private void closeAlbumList() {
        showingAlbums = false;
        generation.incrementAndGet();
        if (albumRecycler != null) albumRecycler.setVisibility(View.GONE);
        if (mediaRecycler != null) {
            mediaRecycler.setAdapter(null);
            mediaRecycler.setVisibility(View.GONE);
        }
        currentAlbumAdapter = null;
    }
}