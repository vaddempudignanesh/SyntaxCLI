package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.graphics.PixelFormat;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

public class CustomVideoView extends SurfaceView implements SurfaceHolder.Callback {

    public static final String LOG_TAG = "gallery-tui";

    public enum Fit { NONE, SMALLER, LARGER, WIDTH, HEIGHT; }

    public interface OnTapListener { void onTap(); }

    private MediaPlayer mp;
    private String path;
    private boolean prepared;
    private boolean playWhenReady;
    private boolean surfaceReady;
    private int videoW, videoH;
    private Fit fit = Fit.SMALLER;

    private OnTapListener tapListener;
    private MediaPlayer.OnPreparedListener preparedListener;
    private MediaPlayer.OnErrorListener errorListener;
    private MediaPlayer.OnCompletionListener completionListener;

    public CustomVideoView(Context c) { super(c); init(); }
    public CustomVideoView(Context c, AttributeSet a) { super(c, a); init(); }
    public CustomVideoView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        setFocusable(true);
        setClickable(true);
    }

    public void setFit(Fit mode) {
        if (fit != mode) { fit = mode; requestLayout(); }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && tapListener != null) tapListener.onTap();
        return true;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int aw = MeasureSpec.getSize(wSpec);
        int ah = MeasureSpec.getSize(hSpec);

        if (videoW <= 0 || videoH <= 0 || aw <= 0 || ah <= 0) {
            setMeasuredDimension(aw, ah);
            return;
        }

        double sw = (double) aw / videoW;
        double sh = (double) ah / videoH;
        double s;
        switch (fit) {
            case NONE:    s = 1.0; break;
            case LARGER:  s = Math.max(sw, sh); break;
            case WIDTH:   s = sw; break;
            case HEIGHT:  s = sh; break;
            default:      s = Math.min(sw, sh); break;
        }

        int w = (int) Math.round(videoW * s);
        int h = (int) Math.round(videoH * s);

        if (fit == Fit.SMALLER || fit == Fit.NONE) {
            if (w > aw) w = aw;
            if (h > ah) h = ah;
        }

        if (w < 1) w = 1;
        if (h < 1) h = 1;
        setMeasuredDimension(w, h);
    }

    private void applySize(int w, int h) {
        if (w > 0 && h > 0 && (w != videoW || h != videoH)) {
            videoW = w; videoH = h;
            requestLayout();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        open();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        release();
    }

    public String getVideoPath() { return path; }

    public void setVideoPath(String p) {
        if (p != null && p.equals(path) && mp != null) return;
        path = p;
        playWhenReady = false;
        release();
        open();
    }

    public void start() {
        if (mp != null && prepared) {
            try { if (!mp.isPlaying()) mp.start(); }
            catch (IllegalStateException e) { playWhenReady = true; }
        } else {
            playWhenReady = true;
        }
    }

    public void pause() {
        playWhenReady = false;
        if (mp != null && prepared) {
            try { if (mp.isPlaying()) mp.pause(); } catch (IllegalStateException ignored) {}
        }
    }

    public void stopPlayback() {
        playWhenReady = false;
        release();
    }

    public void seekTo(int ms) {
        if (mp != null && prepared) {
            try { mp.seekTo(ms < 0 ? 0 : ms); } catch (IllegalStateException ignored) {}
        }
    }

    public boolean seekToSafe(int ms) {
        if (mp == null || !prepared) return false;
        try { mp.seekTo(ms < 0 ? 0 : ms); return true; }
        catch (Exception ignored) { return false; }
    }

    public int getCurrentPosition() {
        if (mp == null || !prepared) return 0;
        try { return mp.getCurrentPosition(); } catch (Exception ignored) { return 0; }
    }

    public int getDuration() {
        if (mp == null || !prepared) return 0;
        try { return mp.getDuration(); } catch (Exception ignored) { return 0; }
    }

    public boolean isPlaying() {
        if (mp == null || !prepared) return false;
        try { return mp.isPlaying(); } catch (Exception ignored) { return false; }
    }

    public int getSafeDuration() {
        if (mp == null || !prepared) return -1;
        try { int d = mp.getDuration(); return d > 0 ? d : -1; }
        catch (Exception ignored) { return -1; }
    }

    public int getSafePosition() {
        if (mp == null || !prepared) return -1;
        try { int p = mp.getCurrentPosition(); return p >= 0 ? p : -1; }
        catch (Exception ignored) { return -1; }
    }

    public android.media.PlaybackParams getPlaybackParams() {
        if (android.os.Build.VERSION.SDK_INT < 23) return null;
        if (mp != null && prepared) {
            try { return mp.getPlaybackParams(); } catch (Exception ignored) {}
        }
        return null;
    }

    public boolean setPlaybackParams(android.media.PlaybackParams params) {
        if (android.os.Build.VERSION.SDK_INT < 23) return false;
        if (mp == null || !prepared) return false;
        try {
            if (params == null) params = new android.media.PlaybackParams();
            if (params.getSpeed() == 0f) params.setSpeed(1.0f);
            mp.setPlaybackParams(params);
            return true;
        } catch (Exception ignored) { return false; }
    }

    public void setOnTapListener(OnTapListener l) { tapListener = l; }

    public void setOnPreparedListener(MediaPlayer.OnPreparedListener l) {
        preparedListener = l;
        if (prepared && mp != null && l != null) {
            try { l.onPrepared(mp); } catch (Exception ignored) {}
        }
    }

    public void setOnErrorListener(MediaPlayer.OnErrorListener l) { errorListener = l; }
    public void setOnCompletionListener(MediaPlayer.OnCompletionListener l) { completionListener = l; }

    private void open() {
        if (!surfaceReady || path == null || path.isEmpty()) return;
        release();

        try {
            final MediaPlayer p = new MediaPlayer();
            mp = p;
            prepared = false;
            p.setDataSource(path);
            p.setDisplay(getHolder());

            p.setOnVideoSizeChangedListener((m, w, h) -> applySize(w, h));

            p.setOnPreparedListener(m -> {
                if (mp != m) { try { m.reset(); m.release(); } catch (Exception ignored) {} return; }
                prepared = true;
                int w = m.getVideoWidth(), h = m.getVideoHeight();
                if (w > 0 && h > 0) applySize(w, h);
                if (preparedListener != null) {
                    try { preparedListener.onPrepared(m); } catch (Exception ignored) {}
                }
                if (playWhenReady && mp == m) {
                    try { m.start(); playWhenReady = false; } catch (IllegalStateException ignored) {}
                }
            });

            p.setOnCompletionListener(m -> {
                if (mp != m) return;
                playWhenReady = false;
                if (completionListener != null) {
                    try { completionListener.onCompletion(m); } catch (Exception ignored) {}
                }
            });

            p.setOnErrorListener((m, what, extra) -> {
                if (mp != m) return true;
                prepared = false;
                playWhenReady = false;
                if (errorListener != null) {
                    try { return errorListener.onError(m, what, extra); }
                    catch (Exception ignored) { return true; }
                }
                return true;
            });

            p.prepareAsync();
        } catch (IOException | RuntimeException ignored) {
            prepared = false;
            release();
        }
    }

    private void release() {
        MediaPlayer p = mp;
        mp = null;
        prepared = false;
        if (p == null) return;
        try { p.setOnPreparedListener(null); } catch (Exception ignored) {}
        try { p.setOnCompletionListener(null); } catch (Exception ignored) {}
        try { p.setOnErrorListener(null); } catch (Exception ignored) {}
        try { p.setDisplay(null); } catch (Exception ignored) {}
        try { p.stop(); } catch (Exception ignored) {}
        try { p.reset(); } catch (Exception ignored) {}
        try { p.release(); } catch (Exception ignored) {}
    }

    @Override
    protected void onAttachedToWindow() { super.onAttachedToWindow(); }
    @Override
    protected void onDetachedFromWindow() { super.onDetachedFromWindow(); }
}