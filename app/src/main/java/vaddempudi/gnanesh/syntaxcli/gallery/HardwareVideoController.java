package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import java.io.File;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class HardwareVideoController {

    private static final String TAG = "video-cli";
    private static final String AUDIO_TAG = "audio-check";

    private static final int MIN_BUFFER_MS = 2000;
    private static final int MAX_BUFFER_MS = 15000;

    public interface PreparedCallback { void onPrepared(long durationMs); }
    public interface ErrorCallback { void onError(Throwable error); }
    public interface CompletionCallback { void onCompletion(); }

    private final Context context;
    private final String id = Integer.toHexString(System.identityHashCode(this));
    private String me() { return "ctrl[" + id + "]"; }

    private ExoPlayer player;
    private DataSource.Factory dataSourceFactory;
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

    // ── Single source of truth for the bound TextureView ─────────
    // INVARIANT: boundTextureView is non-null iff player != null and we have
    // a live Surface attached. Everywhere it is written goes through
    // showPath() / detachSurface() / release(). No other method touches it.
    private TextureView boundTextureView = null;
    private Surface boundSurface = null;

    private final TextureView.SurfaceTextureListener boundSurfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture st, int w, int h) {
                    if (released) return;
                    attachSurface(st);
                }
                @Override public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture st, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture st) {
                    // Returning false keeps the SurfaceTexture alive across
                    // visibility changes (Fossify does the same). The Surface
                    // itself is released by detachSurface() when the view is
                    // recycled.
                    return false;
                }
                @Override public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture st) {}
            };

    private final MediaCodecSelector selector = new MediaCodecSelector() {
        @NonNull
        @Override
        public List<MediaCodecInfo> getDecoderInfos(
                @NonNull String mimeType,
                boolean requiresSecureDecoder,
                boolean requiresTunnelingDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            return MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
        }
    };

    public HardwareVideoController(Context context) {
        this.context = context.getApplicationContext();
    }

    // ─── Public API ────────────────────────────────────────────────

    public void showPath(@NonNull String path, @NonNull TextureView textureView, boolean startWhenReady) {
        if (path.isEmpty() || released) return;

        this.pendingPath = path;
        this.pendingStartWhenReady = startWhenReady;
        if (startWhenReady) userPaused = false;

        ensurePlayer();

        // Swap the target view WITHOUT tearing the codec pipeline down.
        if (boundTextureView != textureView) {
            detachSurface(boundTextureView);   // release the old one first
            boundTextureView = textureView;
            textureView.setSurfaceTextureListener(boundSurfaceListener);

            android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {
                attachSurface(st);
            }
            // else: onSurfaceTextureAvailable will call attachSurface.
        }

        applyMediaItem(path, startWhenReady);
    }

    /**
     * Detaches the given TextureView from this controller:
     *  - removes our SurfaceTextureListener
     *  - releases our Surface
     *  - clears boundTextureView if it was the one
     *
     * Called from FullscreenAdapter.onViewRecycled() and from
     * FullscreenAdapter.onBindViewHolder() before reusing a recycled view.
     * This is the fix for "BufferQueue has been abandoned".
     */
    public void detachSurface(@Nullable TextureView textureView) {
        if (textureView == null) return;

        // Remove the listener so onSurfaceTextureAvailable cannot fire again.
        try { textureView.setSurfaceTextureListener(null); } catch (Exception ignored) {}

        // Only drop our Surface if this is the one we attached.
        if (boundTextureView == textureView) {
            if (boundSurface != null) {
                try { boundSurface.release(); } catch (Exception ignored) {}
                boundSurface = null;
            }
            if (player != null) {
                try { player.clearVideoSurface(); } catch (Exception ignored) {}
            }
            boundTextureView = null;
        }
    }

    public void pauseOnly() {
        if (player != null) {
            try { player.pause(); } catch (Exception ignored) {}
        }
        userPaused = true;
        pendingStartWhenReady = false;
    }

    public void release() {
        if (released) return;
        released = true;

        // Detach first, then release player.
        detachSurface(boundTextureView);

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

    // ─── Internals ────────────────────────────────────────────────

    private void attachSurface(@NonNull android.graphics.SurfaceTexture st) {
        if (released || player == null) return;
        if (boundSurface != null) {
            try { boundSurface.release(); } catch (Exception ignored) {}
        }
        boundSurface = new Surface(st);
        try { player.setVideoSurface(boundSurface); } catch (Exception ignored) {}
    }

    private void ensurePlayer() {
        if (released || player != null) return;
        Log.d(TAG, me() + " ensurePlayer (one-time)");

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(selector);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, MIN_BUFFER_MS, MIN_BUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        player = new ExoPlayer.Builder(context, renderers)
                .setLoadControl(loadControl)
                .build();

        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                /* handleAudioFocus = */ true);

        player.setRepeatMode(Player.REPEAT_MODE_ONE);

        dataSourceFactory = () -> new FileDataSource();

        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onAudioEnabled(@NonNull EventTime e, @NonNull DecoderCounters c) {}
            @Override public void onAudioDisabled(@NonNull EventTime e, @NonNull DecoderCounters c) {}
            @Override public void onAudioInputFormatChanged(@NonNull EventTime e, @NonNull Format f, @Nullable androidx.media3.exoplayer.DecoderReuseEvaluation r) {}
            @Override public void onAudioDecoderInitialized(@NonNull EventTime e, @NonNull String n, long i, long d) {}
            @Override public void onAudioSinkError(@NonNull EventTime e, @NonNull Exception x) {
                Log.e(AUDIO_TAG, me() + " audio sink error", x);
            }
        });

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && !isPrepared) {
                    isPrepared = true;
                    long dur = Math.max(0L, player != null ? player.getDuration() : 0L);
                    lastKnownDurationMs = dur;
                    mainHandler.post(() -> { if (onPrepared != null) onPrepared.onPrepared(dur); });
                } else if (state == Player.STATE_ENDED) {
                    mainHandler.post(() -> { if (onCompletion != null) onCompletion.onCompletion(); });
                }
            }
            @Override public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, me() + " onPlayerError code=" + error.errorCode, error);
                mainHandler.post(() -> { if (onError != null) onError.onError(error); });
            }
        });
    }

    private void applyMediaItem(String path, boolean startWhenReady) {
        if (player == null) return;
        try {
            // ★ KILL any residual audio from the previous media item
            player.setVolume(1f);

            MediaItem item = path.startsWith("content://")
                    ? MediaItem.fromUri(Uri.parse(path))
                    : MediaItem.fromUri(Uri.fromFile(new File(path)));
            MediaSource source = new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(item);
            player.setMediaSource(source);
            isPrepared = false;
            player.prepare();
            player.setPlayWhenReady(startWhenReady && !userPaused);
        } catch (Exception e) {
            Log.e(TAG, me() + " applyMediaItem threw", e);
        }
    }

    public void pauseForLifecycle() {
        if (player != null) {
            try { player.pause(); } catch (Exception ignored) {}
        }
    }

    public void requestLayout() {
        if (boundTextureView != null) {
            try { boundTextureView.requestLayout(); } catch (Exception ignored) {}
        }
    }

    public void stopPlayback() {
        pause();
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
        PlaybackParameters p = player.getPlaybackParameters();
        return p != null ? p.speed : 1.0f;
    }

    public boolean setPlaybackSpeed(float speed) {
        if (player == null) return false;
        try {
            player.setPlaybackParameters(new PlaybackParameters(speed <= 0f ? 1.0f : speed));
            return true;
        } catch (Exception e) { return false; }
    }

    public String getVideoPath() { return pendingPath; }

    // HardwareVideoController.java — add this method
    /** Pause playback immediately, drop audio, and keep the player alive
     *  so the next showPath() can reuse it without a codec restart. */
    public void pauseForPageChange() {
        if (player != null) {
            try { player.pause(); } catch (Exception ignored) {}
            // Belt-and-braces: also mute the audio track immediately so any
            // in-flight decoder flush cannot leak a fraction of a second of audio.
            try { player.setVolume(0f); } catch (Exception ignored) {}
        }
        userPaused = true;
        pendingStartWhenReady = false;
    }

    /** Restore the normal volume when the next video is actually about to play. */
    public void resetVolumeForPlay() {
        if (player != null) {
            try { player.setVolume(1f); } catch (Exception ignored) {}
        }
    }

    public void setOnPreparedListener(PreparedCallback cb) {
        this.onPrepared = cb;
        if (isPrepared && cb != null) {
            long dur = lastKnownDurationMs;
            mainHandler.post(() -> cb.onPrepared(dur));
        }
    }
    public void setOnErrorListener(ErrorCallback cb) { this.onError = cb; }
    public void setCompletionListener(CompletionCallback cb) { this.onCompletion = cb; }
}