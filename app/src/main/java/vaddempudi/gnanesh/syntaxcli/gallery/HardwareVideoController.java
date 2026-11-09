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
import java.util.ArrayList;
import java.util.List;

/**
 * SINGLE ExoPlayer for the entire gallery session.
 *
 * Battery rules enforced by this class:
 *  1. The player is created ONCE (ensurePlayer) and never released until the
 *     gallery activity itself is destroyed.
 *  2. Surface binding uses TextureView + setVideoSurface(Surface) so the
 *     underlying Surface is stable for the view's lifetime — no per-bind
 *     VPU firmware re-init on MediaTek.
 *  3. The audio renderer is NOT filtered to hardware-only. MediaTek's
 *     c2.android.aac.decoder is a software decoder; filtering it out with
 *     setEnableDecoderFallback(false) produces silent video. We keep the
 *     DEFAULT selector for all tracks and set enableDecoderFallback(true).
 *  4. handleAudioFocus = true — ExoPlayer requests/abandons focus itself
 *     exactly once per play/pause transition. The activity NEVER calls
 *     AudioManager.requestAudioFocus() / abandonAudioFocus() manually.
 *  5. DefaultLoadControl is capped at 15 s so the VPU idles at half clock
 *     after the first couple of seconds.
 *  6. ProgressiveMediaSource + reusable FileDataSource.Factory avoids
 *     re-parsing the MP4/MKV atom table on every setMediaItem().
 *  7. stopAndDetach() only calls pause() — it never calls stop() or
 *     clearMediaItems() or clearVideoSurface(), which would flush the
 *     pipeline and force a codec re-create on the next bind.
 */
@OptIn(markerClass = UnstableApi.class)
public class HardwareVideoController {

    private static final String TAG = "video-cli";
    private static final String AUDIO_TAG = "audio-check";

    /** 2 s min buffer, 15 s max — same as Fossify Gallery. */
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

    // Current TextureView binding (never unbound until release()).
    private TextureView boundTextureView = null;
    private Surface boundSurface = null;
    private final TextureView.SurfaceTextureListener boundSurfaceListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture st, int w, int h) {
            if (released) return;
            attachSurface(st);
        }
        @Override public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture st, int w, int h) {}
        @Override public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture st) {
            // Returning false means we keep the SurfaceTexture alive (Fossify does the same).
            return false;
        }
        @Override public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture st) {}
    };

    /**
     * DEFAULT selector — no hardware-only filter. Filtering audio tracks out
     * with a hardware-only selector makes MediaTek's software AAC decoder
     * disappear from the list, and with setEnableDecoderFallback(false) the
     * player builds zero audio renderers -> silent playback.
     */
    private final MediaCodecSelector selector = new MediaCodecSelector() {
        @NonNull
        @Override
        public List<MediaCodecInfo> getDecoderInfos(
                @NonNull String mimeType,
                boolean requiresSecureDecoder,
                boolean requiresTunnelingDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            Log.d(TAG, me() + " decoderQuery mime=" + mimeType
                    + " count=" + (all != null ? all.size() : 0));
            return all;
        }
    };

    public HardwareVideoController(Context context) {
        this.context = context.getApplicationContext();
        Log.d(TAG, me() + " constructed");
    }

    // ─── Public API ────────────────────────────────────────────────

    /** Bind to the given TextureView and (re)start playback of `path`. */
    public void showPath(@NonNull String path, @NonNull TextureView textureView, boolean startWhenReady) {
        if (path.isEmpty() || released) return;

        this.pendingPath = path;
        this.pendingStartWhenReady = startWhenReady;
        if (startWhenReady) userPaused = false;

        ensurePlayer();

        // Swap the target view WITHOUT tearing the codec pipeline down.
        if (boundTextureView != textureView) {
            if (boundTextureView != null) {
                boundTextureView.setSurfaceTextureListener(null);
            }
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

    /** Pause and keep the player alive. Called on close/back. */
    public void pauseOnly() {
        if (player != null) {
            try { player.pause(); } catch (Exception ignored) {}
        }
        userPaused = true;
        pendingStartWhenReady = false;
    }

    /** Full teardown — only when the gallery activity is being destroyed. */
    public void release() {
        if (released) return;
        released = true;
        Log.d(TAG, me() + " release()");

        if (boundTextureView != null) {
            try { boundTextureView.setSurfaceTextureListener(null); } catch (Exception ignored) {}
            boundTextureView = null;
        }
        if (boundSurface != null) {
            try { boundSurface.release(); } catch (Exception ignored) {}
            boundSurface = null;
        }
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

        // enableDecoderFallback(true) is REQUIRED for audio: MediaTek's AAC
        // decoder reports hardwareAccelerated=false on many builds, and with
        // fallback off it would be filtered out by a hardware-only selector.
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

        // handleAudioFocus = true -> ExoPlayer owns focus. The activity must
        // NOT also request focus, or MIUI/One UI will thrash it per bind.
        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                /* handleAudioFocus = */ true);

        player.setRepeatMode(Player.REPEAT_MODE_ONE);

        dataSourceFactory = () -> new FileDataSource();

        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onAudioEnabled(@NonNull EventTime e, @NonNull DecoderCounters c) {
                Log.d(AUDIO_TAG, me() + " ★ audio ENABLED");
            }
            @Override public void onAudioDisabled(@NonNull EventTime e, @NonNull DecoderCounters c) {
                Log.d(AUDIO_TAG, me() + " ★ audio disabled after " + c.renderedOutputBufferCount + " buffers");
            }
            @Override public void onAudioInputFormatChanged(@NonNull EventTime e, @NonNull Format f, @Nullable androidx.media3.exoplayer.DecoderReuseEvaluation r) {
                Log.d(AUDIO_TAG, me() + " ★ audio input format=" + f.sampleMimeType
                        + " sr=" + f.sampleRate + " ch=" + f.channelCount);
            }
            @Override public void onAudioDecoderInitialized(@NonNull EventTime e, @NonNull String n, long i, long d) {
                Log.d(AUDIO_TAG, me() + " ★ audio decoder=" + n);
            }
            @Override public void onAudioSinkError(@NonNull EventTime e, @NonNull Exception x) {
                Log.e(AUDIO_TAG, me() + " ✖ audio sink error", x);
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
            Log.d(TAG, me() + " applyMediaItem " + path + " play=" + (startWhenReady && !userPaused));
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

    // ─── Playback helpers ─────────────────────────────────────────

    public void stopPlayback() {
        // Kept for API compatibility. Only pauses — never stops or clears,
        // because that would flush the pipeline and force a codec re-create.
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