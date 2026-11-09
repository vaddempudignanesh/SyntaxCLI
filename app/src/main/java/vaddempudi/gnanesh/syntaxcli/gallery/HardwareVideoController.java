package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * One ExoPlayer per process. Hardware decoder only. No fallback. No probing.
 *
 * The player is created once and never released until the gallery is torn down.
 *   1. Swaps the player's video surface to the new SurfaceView (fast — ExoPlayer
 *      internally re-targets the existing Surface to the new SurfaceHolder)
 *   2. Calls {@code setMediaItem} with the new file. ExoPlayer releases the
 *      current hardware MediaCodec and allocates a new one for the new format.
 *      This is the "release codes and reassign" step; it runs in ~15–40 ms on
 *      Qualcomm because the Surface and AudioTrack are reused.
 *
 * NOTHING else in the app may create a new ExoPlayer. If it does, we are back
 * to OOM/black-screen territory.
 */
@OptIn(markerClass = UnstableApi.class)
public class HardwareVideoController {

    private static final String TAG = "video-cli";
    private static final long SWAP_DEBOUNCE_MS = 16L;

    public interface PreparedCallback { void onPrepared(long durationMs); }
    public interface ErrorCallback { void onError(Throwable error); }
    public interface CompletionCallback { void onCompletion(); }

    private final Context context;
    private final String id = Integer.toHexString(System.identityHashCode(this));
    private String me() { return "ctrl[" + id + "]"; }

    private ExoPlayer player;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PreparedCallback onPrepared;
    private ErrorCallback onError;
    private CompletionCallback onCompletion;

    private String pendingPath;
    private boolean pendingStartWhenReady = true;
    private boolean isPrepared = false;
    private boolean released = false;
    private boolean userPaused = false;
    private long lastKnownDurationMs = 0L;

    // ── Current surface binding ────────────────────────────────────
    private SurfaceView boundSurfaceView = null;
    private SurfaceHolder boundHolder = null;
    private SurfaceHolder.Callback boundCallback = null;
    private boolean waitingForSurface = false;

    /** Hardware-only selector. Returns the list as given; ExoPlayer will pick
     *  the first hardware decoder. Because we set {@code setEnableDecoderFallback(false)},
     *  if only software decoders exist the player will throw — which is what we want. */
    private final MediaCodecSelector hardwareOnlySelector = new MediaCodecSelector() {
        @NonNull
        @Override
        public List<MediaCodecInfo> getDecoderInfos(
                @NonNull String mimeType,
                boolean requiresSecureDecoder,
                boolean requiresTunnelingDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            ArrayList<MediaCodecInfo> out = new ArrayList<>();
            if (all != null) {
                for (MediaCodecInfo info : all) {
                    if (info.hardwareAccelerated) out.add(info);
                }
            }
            Log.d(TAG, me() + " decoderQuery mime=" + mimeType
                    + " total=" + (all != null ? all.size() : 0)
                    + " hardware=" + out.size());
            return out;   // may be empty; ExoPlayer will throw on empty, which we want
        }
    };

    public HardwareVideoController(Context context) {
        this.context = context.getApplicationContext();
        Log.d(TAG, me() + " constructed");
    }

    // ─── Public lifecycle ─────────────────────────────────────────

    /**
     * Shows the given file on the given SurfaceView. Called from
     * FullscreenAdapter.onBindViewHolder (via activateControllerAt) and from
     * GalleryActivity.findAndStartVideoForPosition.
     *
     * Fast path: the SurfaceView's Surface is already valid → swap surface, set
     * media item, prepare. ~15–40 ms.
     *
     * Slow path: the Surface doesn't exist yet → arm the holder callback, wait
     * for surfaceCreated, then swap. surfaceCreated fires within ~1 frame.
     */
    public void showPath(@NonNull String path, @NonNull SurfaceView surfaceView, boolean startWhenReady) {
        if (path.isEmpty()) return;
        if (released) {
            Log.e(TAG, me() + " showPath() after release() — ignoring. "
                    + "This means a caller kept a controller that was torn down.");
            return;
        }
        this.pendingPath = path;
        this.pendingStartWhenReady = startWhenReady;
        if (startWhenReady) userPaused = false;
        isPrepared = false;

        ensurePlayer();

        // If we're already bound to this exact SurfaceView, skip the swap.
        if (boundSurfaceView == surfaceView && boundHolder != null
                && boundHolder.getSurface() != null && boundHolder.getSurface().isValid()) {
            applyMediaItem(path, startWhenReady);
            return;
        }

        bindToSurfaceView(surfaceView, path, startWhenReady);
    }


    public void start() {
        userPaused = false;
        pendingStartWhenReady = true;
        if (player != null) player.play();
    }

    public void pause() {
        userPaused = true;
        pendingStartWhenReady = false;
        if (player != null) player.pause();
    }

    /** Detaches the surface without releasing the player. Used when the current
     *  page goes off-screen but might come back. */
    public void detachSurfaceOnly() {
        unbindSurface();
    }

    // ─── Surface binding ──────────────────────────────────────────

    private void unbindSurface() {
        if (boundCallback != null && boundHolder != null) {
            try { boundHolder.removeCallback(boundCallback); }
            catch (Exception ignored) {}
        }
        if (player != null) {
            try { player.clearVideoSurface(); } catch (Exception ignored) {}
        }
        boundSurfaceView = null;
        boundHolder = null;
        boundCallback = null;
        waitingForSurface = false;
    }

    /** Stops playback and detaches the surface, but keeps the ExoPlayer instance
     *  alive so it can be re-used by the next showPath(). Use this from
     *  FullscreenAdapter.reset() and closeFullscreenViewer(). */
    public void stopAndDetach() {
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
                player.clearVideoSurface();
            } catch (Exception ignored) {}
        }
        unbindSurface();
        isPrepared = false;
        pendingPath = null;
        pendingStartWhenReady = false;
        userPaused = false;
        lastKnownDurationMs = 0L;
    }

    /** Full teardown — only safe at process exit / activity onDestroy when the
     *  controller object itself is being discarded. */
    public void release() {
        if (released) return;
        released = true;
        Log.d(TAG, me() + " release()");

        unbindSurface();

        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        isPrepared = false;
        pendingPath = null;
        pendingStartWhenReady = false;
        userPaused = false;
        lastKnownDurationMs = 0L;
    }

    private void bindToSurfaceView(@NonNull final SurfaceView surfaceView,
                                   @NonNull final String path,
                                   final boolean startWhenReady) {
        unbindSurface();
        boundSurfaceView = surfaceView;
        final SurfaceHolder holder = surfaceView.getHolder();

        // Surface is already valid → bind immediately, no callback round-trip.
        Surface s = holder.getSurface();
        if (s != null && s.isValid()) {
            boundHolder = holder;
            if (player != null) {
                try { player.setVideoSurfaceHolder(holder); } catch (Exception ignored) {}
            }
            applyMediaItem(path, startWhenReady);
            return;
        }

        // Not valid yet → register callback, wait for surfaceCreated.
        final SurfaceHolder.Callback[] selfRef = new SurfaceHolder.Callback[1];
        final SurfaceHolder.Callback cb = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder h) {
                if (boundCallback != selfRef[0] || released) return;
                Log.d(TAG, me() + " surfaceCreated");
                boundHolder = h;
                waitingForSurface = false;
                if (player != null) {
                    try { player.setVideoSurfaceHolder(h); } catch (Exception ignored) {}
                }
                if (pendingPath != null) {
                    applyMediaItem(pendingPath, pendingStartWhenReady);
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder h, int format, int width, int height) { }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder h) {
                if (boundCallback != selfRef[0]) return;
                Log.d(TAG, me() + " surfaceDestroyed");
                if (player != null) {
                    try { player.pause(); } catch (Exception ignored) {}
                    try { player.clearVideoSurface(); } catch (Exception ignored) {}
                }
                boundHolder = null;
                waitingForSurface = true;
            }
        };
        selfRef[0] = cb;
        boundCallback = cb;
        waitingForSurface = true;
        holder.addCallback(cb);
    }

    private void applyMediaItem(String path, boolean startWhenReady) {
        if (player == null) return;
        try {
            player.setMediaItem(MediaItem.fromUri(path));
            isPrepared = false;
            player.prepare();
            player.setPlayWhenReady(startWhenReady && !userPaused);
        } catch (Exception e) {
            Log.e(TAG, me() + " applyMediaItem threw", e);
        }
    }

    /** Lifecycle pause — auto-play can resume after this. Does NOT mark userPaused. */
    public void pauseForLifecycle() {
        if (player != null) {
            try { player.pause(); } catch (Exception ignored) {}
        }
    }

    /** Requests a layout pass on the currently bound SurfaceView, if any. */
    public void requestLayout() {
        if (boundSurfaceView != null) {
            try { boundSurfaceView.requestLayout(); } catch (Exception ignored) {}
        }
    }

    // ─── Player creation (once) ───────────────────────────────────

    private void ensurePlayer() {
        if (released) {
            Log.e(TAG, me() + " ensurePlayer() after release()");
            return;
        }
        if (player != null) return;
        Log.d(TAG, me() + " ensurePlayer (one-time)");

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(false)          // keep: hardware video only
                .setMediaCodecSelector(hardwareOnlySelector);   // keep: hardware video only

        player = new ExoPlayer.Builder(context, renderersFactory).build();

        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                /* handleAudioFocus = */ true);

        player.setRepeatMode(Player.REPEAT_MODE_ONE);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && !isPrepared) {
                    isPrepared = true;
                    final long dur = Math.max(0L, player != null ? player.getDuration() : 0L);
                    lastKnownDurationMs = dur;
                    mainHandler.post(() -> {
                        if (onPrepared != null) onPrepared.onPrepared(dur);
                    });
                } else if (state == Player.STATE_ENDED) {
                    mainHandler.post(() -> {
                        if (onCompletion != null) onCompletion.onCompletion();
                    });
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, me() + " onPlayerError code=" + error.errorCode, error);
                mainHandler.post(() -> {
                    if (onError != null) onError.onError(error);
                });
            }
        });
    }

    // ─── Playback helpers (unchanged API) ─────────────────────────

    public void stopPlayback() {
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (Exception ignored) {}
        }
        isPrepared = false;
        pendingPath = null;
        pendingStartWhenReady = false;
        lastKnownDurationMs = 0L;
    }

    public void seekTo(long ms) { if (player != null) player.seekTo(Math.max(0L, ms)); }

    public boolean seekToSafe(long ms) {
        if (player == null) return false;
        try { player.seekTo(Math.max(0L, ms)); return true; }
        catch (Exception e) { return false; }
    }

    public long getCurrentPosition() {
        return player != null ? Math.max(0L, player.getCurrentPosition()) : 0L;
    }
    public long getSafePosition() { return getCurrentPosition(); }
    public long getDuration() {
        return player != null ? Math.max(0L, player.getDuration()) : 0L;
    }
    public long getSafeDuration() { return getDuration(); }
    public boolean isPlaying() { return player != null && player.isPlaying(); }
    public boolean isPreparedNow() { return isPrepared; }

    public float getPlaybackSpeed() {
        if (player == null) return 1.0f;
        PlaybackParameters params = player.getPlaybackParameters();
        return params != null ? params.speed : 1.0f;
    }

    public boolean setPlaybackSpeed(float speed) {
        if (player == null) return false;
        try {
            float s = (speed <= 0f) ? 1.0f : speed;
            player.setPlaybackParameters(new PlaybackParameters(s));
            return true;
        } catch (Exception e) { return false; }
    }

    public String getVideoPath() { return pendingPath; }

    public void setOnPreparedListener(PreparedCallback cb) {
        this.onPrepared = cb;
        if (isPrepared && cb != null) {
            final long dur = lastKnownDurationMs;
            mainHandler.post(() -> cb.onPrepared(dur));
        }
    }
    public void setOnErrorListener(ErrorCallback cb) { this.onError = cb; }
    public void setCompletionListener(CompletionCallback cb) { this.onCompletion = cb; }
}