package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.inputmethodservice.InputMethodService;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class BasicImeService extends InputMethodService
        implements EmojiPaletteView.PanelControl,
        EmojiPaletteView.EmojiPickListener {

    private BasicKeyboardView keyboardView;
    private KeyBubbleOverlay bubbleOverlay;
    private EmojiPaletteView emojiPanel;
    private ClipboardPanelView clipboardPanel;
    private FrameLayout rootContainer;

    // ── Video wallpaper state ────────────────────────────────────
    private TextureView videoView;
    private MediaPlayer mediaPlayer;
    private String currentVideoPath;
    private long   videoSession = 0L;
    private int    videoWidth  = 0;
    private int    videoHeight = 0;

    // ── Wallpaper mode tracking ─────────────────────────────────
    // Tracks whether we're currently in "system wallpaper" mode (transparent
    // keyboard over launcher wallpaper). Used by rebuildWallpaperLayers() to
    // decide when a redraw is actually needed, and to force a re-apply when
    // transitioning into/out of system mode.
    private boolean systemWallpaperMode = false;

    private final Map<String, Integer> videoPositions = new HashMap<>();
    private android.content.SharedPreferences videoPosPrefs;
    private static final String PREF_VIDEO_POSITIONS = "ime_video_positions";
    private static final String KEY_POSITIONS_MAP    = "map";

    private int keyboardHeightPx = 0;
    private int keyboardWidthPx  = 0;

    private static final int PAGE_LETTERS = 0;

    public static final String ACTION_LAYOUT_CHANGED =
            "vaddempudi.gnanesh.syntaxcli.ime.LAYOUT_CHANGED";

    public static final String ACTION_RESTART_IME =
            "vaddempudi.gnanesh.syntaxcli.ime.RESTART_IME";

    public static final String PREFS_RESTART = "ime_restart_signal";
    public static final String KEY_RESTART   = "restart_requested_at";

    private android.content.BroadcastReceiver layoutChangeReceiver;
    private android.app.WallpaperManager.OnColorsChangedListener wallpaperListener = null;

    // ══════════════════════════════════════════════════════════════
    //  Panel controls
    // ══════════════════════════════════════════════════════════════

    @Override
    public void closePanel() {
        showKeyboardPanel();
    }

    private void showKeyboardPanel() {
        if (rootContainer == null || keyboardView == null) return;
        if (keyboardView.getParent() == rootContainer
                && rootContainer.getChildCount() == 1) {
            // Already showing the keyboard. Just make sure the video is running.
            resumeVideoIfPaused();
            return;
        }
        // We're switching from an emoji/clipboard panel back to the keyboard.
        // Tear down the current video layer (it was attached to rootContainer
        // as a sibling of the panel — removeAllViews would detach it anyway).
        teardownVideoLayer();

        rootContainer.removeAllViews();

        FrameLayout.LayoutParams kbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        kbLp.gravity = Gravity.BOTTOM;
        rootContainer.addView(keyboardView, kbLp);

        rebuildWallpaperLayers();
        keyboardView.bringToFront();
        keyboardView.requestLayout();
        keyboardView.invalidate();
    }

    @Override
    public void sendEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_ENTER));
    }

    @Override
    public void sendBackspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_DEL));
    }

    @Override
    public void onEmojiPicked(String emoji) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(emoji, 1);
    }

    // ══════════════════════════════════════════════════════════════
    //  Input view
    // ══════════════════════════════════════════════════════════════

    @Override
    public View onCreateInputView() {
        rootContainer = new FrameLayout(this);

        keyboardView = new BasicKeyboardView(this);
        bubbleOverlay = new KeyBubbleOverlay(this);
        keyboardView.setBubbleOverlay(bubbleOverlay);
        keyboardView.setListener(buildListener());

        FrameLayout.LayoutParams kbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        kbLp.gravity = Gravity.BOTTOM;
        rootContainer.addView(keyboardView, kbLp);

        keyboardView.post(() -> {
            measureKeyboard();
            applyKeyboardFrameLock();
            rebuildWallpaperLayers();
            ensureOverlayPermission();
        });

        applyWallpaperWindowSettings();
        return rootContainer;
    }

    private void measureKeyboard() {
        if (keyboardView == null) return;
        int w = keyboardView.getWidth();
        if (w <= 0) return;
        int h = keyboardView.getMeasuredHeight();
        if (h <= 0) {
            int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            keyboardView.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    spec);
            h = keyboardView.getMeasuredHeight();
        }
        keyboardWidthPx  = w;
        keyboardHeightPx = h;
    }

    private void applyKeyboardFrameLock() {
        if (rootContainer == null || keyboardHeightPx <= 0 || keyboardWidthPx <= 0) return;

        ViewGroup.LayoutParams rootLp = rootContainer.getLayoutParams();
        if (rootLp != null) {
            rootLp.height = keyboardHeightPx;
            rootLp.width  = keyboardWidthPx;
            rootContainer.setLayoutParams(rootLp);
        } else {
            rootContainer.setMinimumHeight(keyboardHeightPx);
            rootContainer.setMinimumWidth(keyboardWidthPx);
        }

        if (keyboardView != null) {
            ViewGroup.LayoutParams lp = keyboardView.getLayoutParams();
            if (lp != null) {
                lp.height = keyboardHeightPx;
                lp.width  = keyboardWidthPx;
                keyboardView.setLayoutParams(lp);
            }
        }

        if (videoView != null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    keyboardWidthPx, keyboardHeightPx);
            lp.gravity = Gravity.BOTTOM;
            videoView.setLayoutParams(lp);
        }

        rootContainer.requestLayout();
    }

    // ══════════════════════════════════════════════════════════════
    //  Video wallpaper — fingerprint + build + teardown
    // ══════════════════════════════════════════════════════════════

    private long videoFingerprint = Long.MIN_VALUE;

    private long computeVideoFingerprint(ImeColorStore colors) {
        long h = 1125899906842597L;
        h = 31 * h + (colors.isWallpaperEnabled() ? 1 : 0);
        h = 31 * h + (colors.hasVideoWallpaper() ? 1 : 0);
        String vid = colors.getWallpaperVideoPath();
        h = 31 * h + (vid == null ? 0 : vid.hashCode());
        if (vid != null && !vid.isEmpty()) {
            try {
                File f = new File(vid);
                if (f.exists()) {
                    h = 31 * h + f.length();
                    h = 31 * h + f.lastModified();
                }
            } catch (Throwable ignored) { }
        }
        return h;
    }

    private void rebuildWallpaperLayers() {
        if (rootContainer == null || keyboardView == null) return;

        // Measure on demand; retry once if the keyboard view isn't laid out.
        if (keyboardWidthPx <= 0 || keyboardHeightPx <= 0) {
            measureKeyboard();
        }
        if (keyboardWidthPx <= 0 || keyboardHeightPx <= 0) {
            keyboardView.postDelayed(() -> {
                if (keyboardView == null) return;
                measureKeyboard();
                applyKeyboardFrameLock();
                rebuildWallpaperLayers();
            }, 16L);
            return;
        }

        ImeColorStore colors = ImeColorStore.get(this);
        long fp = computeVideoFingerprint(colors);

        // ── Resolve which wallpaper mode we want ────────────────────
        final boolean wallpaperOn = colors.isWallpaperEnabled();
        final boolean hasVideo    = wallpaperOn && colors.hasVideoWallpaper();
        final boolean hasImage    = wallpaperOn && !hasVideo && colors.hasCustomImage();
        final boolean wantSystem  = wallpaperOn && !hasVideo && !hasImage;

        final boolean wantVideo = hasVideo;
        final boolean haveVideo = videoView != null && mediaPlayer != null;

        // ── Rebuild guard ───────────────────────────────────────────
        // Include system-mode flag so a transition into/out of system
        // wallpaper actually triggers a redraw of keyboardView's background
        // handling.
        boolean sameMode = (fp == videoFingerprint)
                && (wantVideo == haveVideo)
                && (systemWallpaperMode == wantSystem);
        if (sameMode) {
            return;
        }
        videoFingerprint = fp;
        systemWallpaperMode = wantSystem;

        // Tear down any existing video layer (no-op if none).
        teardownVideoLayer();

        // ── Apply the chosen mode ──────────────────────────────────

        if (wantVideo) {
            // ── VIDEO WALLPAPER ────────────────────────────────────
            // Window stays transparent; TextureView renders the video as the
            // bottom-most child of rootContainer, behind keyboardView.
            rootContainer.setBackground(null);
            keyboardView.setBackground(null);
            keyboardView.setSystemWallpaperMode(false);

            final long mySession = ++videoSession;
            final String targetPath = colors.getWallpaperVideoPath();

            videoView = new TextureView(this);
            videoView.setOpaque(true);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    keyboardWidthPx, keyboardHeightPx);
            lp.gravity = Gravity.BOTTOM;
            rootContainer.addView(videoView, 0, lp);

            videoView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l > 0 && b - t > 0 && videoSession == mySession) {
                    applyVideoTransform();
                }
            });

            videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    if (videoSession != mySession) return;
                    startVideo(targetPath, st, mySession);
                }
                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    if (videoSession != mySession) return;
                    applyVideoTransform();
                }
                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    if (videoSession != mySession) return true;
                    rememberVideoPosition();
                    return true;
                }
                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) { }
            });

            keyboardView.bringToFront();
            keyboardView.invalidate();
            return;
        }

        if (hasImage) {
            // ── CUSTOM IMAGE WALLPAPER ─────────────────────────────
            // keyboardView paints the image itself. Window is transparent so
            // nothing else shows through around the keyboard.
            rootContainer.setBackground(null);
            keyboardView.setBackground(null);
            keyboardView.setSystemWallpaperMode(false);
            keyboardView.refreshWallpaper();
            keyboardView.invalidate();
            return;
        }

        if (wantSystem) {
            // ── SYSTEM WALLPAPER (the "no seam" case) ──────────────
            // Every layer between the launcher and the user must be fully
            // transparent so the home-screen wallpaper shows through with no
            // visible border, shadow, or color band at the top of the
            // keyboard.
            //
            // 1. rootContainer: transparent
            // 2. keyboardView: transparent + tells it not to paint any WP layer
            // 3. IME window: handled in applyWallpaperWindowSettings()
            rootContainer.setBackground(null);
            rootContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            keyboardView.setBackground(null);
            keyboardView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            keyboardView.setSystemWallpaperMode(true);
            keyboardView.refreshWallpaper();
            keyboardView.invalidate();
            return;
        }

        // ── SOLID COLOR (wallpaper disabled entirely) ──────────────
        rootContainer.setBackground(null);
        keyboardView.setBackground(null);
        keyboardView.setSystemWallpaperMode(false);
        keyboardView.refreshWallpaper();
        keyboardView.invalidate();
    }

    private void teardownVideoLayer() {
        videoSession++;
        if (videoView != null) {
            try { rootContainer.removeView(videoView); } catch (Throwable ignored) { }
            videoView = null;
        }
        releaseVideoKeepPosition();
        persistVideoPositions();
    }

    private void rememberVideoPosition() {
        if (mediaPlayer == null || currentVideoPath == null) return;
        try {
            int pos = mediaPlayer.getCurrentPosition();
            if (pos > 0) {
                videoPositions.put(currentVideoPath, pos);
                persistVideoPositions();
            }
        } catch (Throwable ignored) { }
    }

    private void releaseVideoKeepPosition() {
        rememberVideoPosition();
        if (mediaPlayer != null) {
            try { mediaPlayer.stop();    } catch (Throwable ignored) { }
            try { mediaPlayer.release(); } catch (Throwable ignored) { }
            mediaPlayer = null;
        }
        currentVideoPath = null;
        videoWidth  = 0;
        videoHeight = 0;
    }

    private void applyVideoTransform() {
        if (videoView == null) return;
        if (videoWidth <= 0 || videoHeight <= 0) return;

        int vw = videoView.getWidth();
        int vh = videoView.getHeight();
        if (vw <= 0 || vh <= 0) {
            videoView.post(this::applyVideoTransform);
            return;
        }

        float sx = (float) vw / videoWidth;
        float sy = (float) vh / videoHeight;
        float s  = Math.max(sx, sy);

        Matrix m = new Matrix();
        m.setScale(s, s, vw / 2f, vh / 2f);
        videoView.setTransform(m);
    }

    private void startVideo(final String path, final SurfaceTexture st,
                            final long session) {
        if (path == null || path.isEmpty()) return;
        if (session != videoSession) return;

        if (mediaPlayer != null && path.equals(currentVideoPath)) {
            try {
                mediaPlayer.setSurface(new android.view.Surface(st));
                Integer pos = videoPositions.get(path);
                if (pos != null && pos > 0) mediaPlayer.seekTo(pos);
                mediaPlayer.start();
            } catch (Throwable ignored) { }
            return;
        }

        releaseVideoKeepPosition();

        final MediaPlayer mp = new MediaPlayer();
        mediaPlayer        = mp;
        currentVideoPath   = path;
        videoWidth         = 0;
        videoHeight        = 0;

        try {
            mp.setDataSource(path);
            mp.setSurface(new android.view.Surface(st));
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(prepared -> {
                if (session != videoSession) {
                    try { prepared.stop();    } catch (Throwable ignored) { }
                    try { prepared.release(); } catch (Throwable ignored) { }
                    return;
                }
                videoWidth  = prepared.getVideoWidth();
                videoHeight = prepared.getVideoHeight();
                applyVideoTransform();

                Integer pos = videoPositions.get(path);
                if (pos != null && pos > 0) {
                    try { prepared.seekTo(pos); } catch (Throwable ignored) { }
                }
                try { prepared.start(); } catch (Throwable ignored) { }
            });
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                if (session != videoSession) return;
                videoWidth  = w;
                videoHeight = h;
                applyVideoTransform();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (session == videoSession) {
                    XLog.e("IME-VIDEO", "MediaPlayer error what=" + what + " extra=" + extra);
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            XLog.e("IME-VIDEO", "startVideo failed for " + path, t);
            try { mp.release(); } catch (Throwable ignored) { }
            if (mediaPlayer == mp) mediaPlayer = null;
            if (path.equals(currentVideoPath)) currentVideoPath = null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Persistence helpers
    // ══════════════════════════════════════════════════════════════

    private void loadVideoPositions() {
        if (videoPosPrefs == null) return;
        try {
            String raw = videoPosPrefs.getString(KEY_POSITIONS_MAP, "");
            if (raw == null || raw.isEmpty()) return;
            for (String line : raw.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String path = line.substring(0, tab);
                int pos;
                try { pos = Integer.parseInt(line.substring(tab + 1)); }
                catch (Throwable ignored) { continue; }
                if (pos > 0) videoPositions.put(path, pos);
            }
        } catch (Throwable ignored) { }
    }

    private void persistVideoPositions() {
        if (videoPosPrefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : videoPositions.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        videoPosPrefs.edit().putString(KEY_POSITIONS_MAP, sb.toString()).commit();
    }

    // ══════════════════════════════════════════════════════════════
    //  Emoji + clipboard panels
    // ══════════════════════════════════════════════════════════════

    private void showEmojiPanel() {
        if (rootContainer == null) return;
        if (emojiPanel == null) {
            emojiPanel = new EmojiPaletteView(this, this);
        }
        rememberVideoPosition();
        rootContainer.removeAllViews();

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        rootContainer.addView(emojiPanel, lp);
        emojiPanel.requestLayout();
    }

    private void showClipboardPanel() {
        if (rootContainer == null) return;
        ClipboardStore.get(this).capture(this);
        rememberVideoPosition();
        clipboardPanel = new ClipboardPanelView(this,
                new ClipboardPanelView.ClipboardPickListener() {
                    @Override
                    public void onClipPicked(ClipEntry entry) {
                        pasteClip(entry);
                        closeClipboardPanel();
                    }
                    @Override
                    public void onClipClosed() {
                        closeClipboardPanel();
                    }
                });
        rootContainer.removeAllViews();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        rootContainer.addView(clipboardPanel, lp);
        clipboardPanel.requestLayout();
    }

    private void closeClipboardPanel() {
        clipboardPanel = null;
        showKeyboardPanel();
    }

    // ══════════════════════════════════════════════════════════════
    //  Key actions
    // ══════════════════════════════════════════════════════════════

    private void runAction(KeyAction a) {
        if (a == null) return;

        KeyAction fresh = GestureActionStore.get(this).byId(a.id);
        if (fresh != null) a = fresh;

        if (a.type == KeyAction.TYPE_TEXT) {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            ic.commitText(a.payload, 1);
            if (a.autoEnter) {
                rootContainer.postDelayed(() -> {
                    InputConnection ic2 = getCurrentInputConnection();
                    if (ic2 == null) return;
                    ic2.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    ic2.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_ENTER));
                }, 30L);
            }
            return;
        }

        if (a.type == KeyAction.TYPE_APP_LAUNCH) {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(a.payload);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                }
            } catch (Throwable ignored) { }
            return;
        }

        if (a.type == KeyAction.TYPE_LAUNCH_ACTIVITY) {
            try {
                android.content.ComponentName cn =
                        android.content.ComponentName.unflattenFromString(a.payload);
                if (cn != null) {
                    Intent i = new Intent();
                    i.setComponent(cn);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            } catch (Throwable ignored) { }
        }
    }

    private void handleKey(int code, String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        switch (code) {
            case BasicKey.CODE_SHIFT: {
                if (keyboardView == null) return;
                boolean second = keyboardView.registerShiftTap();
                if (keyboardView.isShiftLocked()) {
                    keyboardView.clearShiftLock();
                    return;
                }
                if (second) {
                    keyboardView.setShiftLocked(true);
                    keyboardView.setShifted(false);
                    return;
                }
                keyboardView.setShifted(!keyboardView.isShifted());
                return;
            }
            case BasicKey.CODE_SYMBOLS:
            case BasicKey.CODE_SYM_PAGE_1: setPage(1); return;
            case BasicKey.CODE_SYMBOLS_2:
            case BasicKey.CODE_SYM_PAGE_2: setPage(2); return;
            case BasicKey.CODE_SYM_PAGE_3:
            case BasicKey.CODE_SYM_PAGE_4: setPage(3); return;
            case BasicKey.CODE_NUMERIC:    setPage(4); return;
            case BasicKey.CODE_ABC:        setPage(0); return;
            case BasicKey.CODE_DELETE:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_DEL));
                return;
            case BasicKey.CODE_ENTER:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_ENTER));
                return;
            case BasicKey.CODE_SPACE:
                ic.commitText(" ", 1);
                if (keyboardView != null && keyboardView.isShifted()
                        && !keyboardView.isShiftLocked()) {
                    keyboardView.clearManualShift();
                }
                if (keyboardView != null
                        && (keyboardView.isSymbols()
                        || keyboardView.isSymbols2()
                        || keyboardView.isSymbols3())) {
                    setPage(0);
                }
                return;
            case BasicKey.CODE_LEFT:  moveCursor(ic, -1); return;
            case BasicKey.CODE_RIGHT: moveCursor(ic, +1); return;
        }

        if (text != null && text.length() > 0) {
            ic.commitText(text, 1);
            if (keyboardView != null
                    && !keyboardView.isSymbols()
                    && !keyboardView.isSymbols2()
                    && !keyboardView.isSymbols3()
                    && !keyboardView.isShiftLocked()
                    && keyboardView.isShifted()) {
                keyboardView.clearManualShift();
            }
        }
    }

    private BasicKeyboardView.Listener buildListener() {
        return new BasicKeyboardView.Listener() {
            public void onKey(int code, String text) {
                if (code == BasicKeyboardView.EMOJI_PANEL_TRIGGER) { showEmojiPanel(); return; }
                if (code == BasicKeyboardView.GESTURE_EDITOR_TRIGGER) {
                    GestureActionEditorActivity.open(BasicImeService.this);
                    return;
                }
                if (code == BasicKeyboardView.GESTURE_DOUBLE_TRIGGER) {
                    String id = GestureConfig.get(BasicImeService.this).getSpaceDoubleActionId();
                    KeyAction a = GestureActionStore.get(BasicImeService.this).byId(id);
                    if (a != null) runAction(a);
                    return;
                }
                if (code == BasicKeyboardView.LETTER_LONG_PRESS_TRIGGER) {
                    KeyAction a = GestureActionStore.get(BasicImeService.this).byKeyChar(text);
                    if (a != null) runAction(a);
                    return;
                }
                if (code == BasicKeyboardView.CLIPBOARD_TRIGGER) { showClipboardPanel(); return; }
                handleKey(code, text);
            }
        };
    }

    private void moveCursor(InputConnection ic, int direction) {
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(1024, 0);
        CharSequence after  = ic.getTextAfterCursor(1024, 0);

        int beforeLen = before == null ? 0 : before.length();
        int afterLen  = after  == null ? 0 : after.length();
        int selStart  = beforeLen;
        int selEnd    = beforeLen;

        int target;
        if (direction < 0) {
            target = selStart - 1;
            if (before != null && beforeLen > 0) {
                char c = before.charAt(beforeLen - 1);
                if (Character.isLowSurrogate(c) && beforeLen >= 2) target = selStart - 2;
            }
        } else {
            target = selStart + 1;
            if (after != null && afterLen > 0) {
                char c = after.charAt(0);
                if (Character.isHighSurrogate(c) && afterLen >= 2) target = selStart + 2;
            }
        }
        if (target < 0) target = 0;
        int total = beforeLen + afterLen;
        if (target > total) target = total;
        ic.setSelection(Math.min(selStart, selEnd), Math.max(selStart, selEnd));
        ic.setSelection(target, target);
    }

    private void pasteClip(ClipEntry e) {
        if (e == null) return;
        InputConnection ic = getCurrentInputConnection();
        try {
            if (e.type == ClipEntry.TYPE_TEXT) {
                if (ic != null) ic.commitText(e.text, 1);
                return;
            }

            String mime = e.mimeType != null ? e.mimeType : "image/png";

            android.net.Uri fpUri = ClipboardStore.get(this).fileProviderUriFor(e);
            if (ic != null && fpUri != null) {
                try {
                    android.content.ClipDescription desc =
                            new android.content.ClipDescription("image", new String[]{mime});
                    android.view.inputmethod.InputContentInfo info =
                            new android.view.inputmethod.InputContentInfo(fpUri, desc);
                    boolean ok = ic.commitContent(
                            info,
                            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                            null);
                    if (ok) return;
                } catch (Throwable t) {
                    XLog.w("IME-PASTE", "commitContent failed: " + t);
                }
            }

            android.net.Uri publicUri = ClipboardStore.get(this).publicImageUriFor(e);
            if (publicUri == null) publicUri = fpUri;
            if (publicUri == null) {
                XLog.w("IME-PASTE", "no URI to paste");
                return;
            }

            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                android.content.ClipDescription desc =
                        new android.content.ClipDescription("image", new String[]{mime});
                android.content.ClipData clip =
                        new android.content.ClipData(desc,
                                new android.content.ClipData.Item(publicUri));
                cm.setPrimaryClip(clip);
            }

            if (ic != null) {
                boolean handled = ic.performContextMenuAction(android.R.id.paste);
                if (!handled) {
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE));
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_PASTE));
                }
            }
        } catch (Throwable t) {
            XLog.e("IME-PASTE", "pasteClip failed", t);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Restart marker + rebuild
    // ══════════════════════════════════════════════════════════════

    private void consumeRestartRequestIfAny() {
        try {
            File marker = new File(getFilesDir(), "ime_restart.requested");
            if (!marker.exists()) return;
            long requestedAt = marker.lastModified();
            marker.delete();
            if (System.currentTimeMillis() - requestedAt > 30_000L) return;
            rebuildInputViewNow();
        } catch (Throwable t) {
            XLog.e("IME", "consumeRestartRequestIfAny failed", t);
        }
    }

    private void rebuildInputViewNow() {
        try {
            teardownVideoLayer();
            videoPositions.clear();
            loadVideoPositions();

            emojiPanel = null;
            clipboardPanel = null;

            if (rootContainer != null) rootContainer.removeAllViews();
            keyboardView = null;
            videoView    = null;
            bubbleOverlay = null;
            systemWallpaperMode = false;
            videoFingerprint    = Long.MIN_VALUE;

            View fresh = onCreateInputView();
            if (fresh != null) {
                setInputView(fresh);
                fresh.requestLayout();
            }

            if (keyboardView != null) {
                keyboardView.post(() -> {
                    try {
                        measureKeyboard();
                        applyKeyboardFrameLock();
                        rebuildWallpaperLayers();
                        ensureOverlayPermission();
                    } catch (Throwable t) {
                        XLog.e("IME", "rebuild post failed", t);
                    }
                });
            }

            applyWallpaperWindowSettings();
        } catch (Throwable t) {
            XLog.e("IME", "rebuildInputViewNow failed", t);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        consumeRestartRequestIfAny();
        try { ClipboardStore.get(this).capture(this); } catch (Throwable ignored) { }

        if (rootContainer == null) {
            onCreateInputView();
        } else {
            keyboardView.setListener(buildListener());
            applyWallpaperWindowSettings();
            measureKeyboard();
            applyKeyboardFrameLock();
            rebuildWallpaperLayers();
            resumeVideoIfPaused();
            keyboardView.refreshWallpaper();
            showKeyboardPanel();
            keyboardView.post(this::rebuildWallpaperLayers);
            ensureOverlayPermission();
        }

        clipboardPanel = null;
        emojiPanel     = null;
        applyEditorInputType(info);
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        consumeRestartRequestIfAny();
        if (keyboardView != null) keyboardView.invalidate();
        applyWallpaperWindowSettings();
        measureKeyboard();
        applyKeyboardFrameLock();
        rebuildWallpaperLayers();
        if (keyboardView != null) keyboardView.refreshWallpaper();
        resumeVideoIfPaused();
        applyEditorInputType(attribute);
    }

    private void resumeVideoIfPaused() {
        if (mediaPlayer == null || currentVideoPath == null) return;
        try {
            if (!mediaPlayer.isPlaying()) {
                mediaPlayer.start();
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (keyboardView != null) keyboardView.setPage(PAGE_LETTERS);
        if (bubbleOverlay != null) bubbleOverlay.hide();

        // Persist the video position and pause — do NOT destroy the layer.
        // The TextureView and MediaPlayer must survive across keyboard hides
        // so that reopening the keyboard (or another editor gaining focus)
        // resumes exactly where it was, with no re-init and no flicker.
        rememberVideoPosition();
        if (mediaPlayer != null) {
            try { mediaPlayer.pause(); } catch (Throwable ignored) { }
        }
        persistVideoPositions();

        clipboardPanel = null;
        emojiPanel     = null;
    }

    @Override
    public void onDestroy() {
        try {
            teardownVideoLayer();
            persistVideoPositions();
            if (layoutChangeReceiver != null) {
                try { unregisterReceiver(layoutChangeReceiver); } catch (Throwable ignored) { }
                layoutChangeReceiver = null;
            }
            unregisterWallpaperListener();
            liveLayoutHandler.removeCallbacksAndMessages(null);
            if (keyboardView != null) keyboardView.releaseWallpaperBitmaps();
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    // ══════════════════════════════════════════════════════════════
    //  Live-layout channel
    // ══════════════════════════════════════════════════════════════

    private static volatile BasicImeService INSTANCE = null;
    private long lastLiveLayoutStamp = 0L;
    private final android.os.Handler liveLayoutHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable liveLayoutPoll = new Runnable() {
        @Override public void run() {
            try {
                long st = LiveLayoutBridge.stamp(BasicImeService.this);
                if (st != 0L && st != lastLiveLayoutStamp) {
                    lastLiveLayoutStamp = st;
                    LiveLayoutBridge.LayoutSnapshot snap =
                            LiveLayoutBridge.read(BasicImeService.this);
                    if (snap != null) {
                        ImeColorStore.get(BasicImeService.this).applySnapshot(snap);
                        applyLayoutLiveFromSnapshot(snap);
                    }
                }
            } catch (Throwable ignored) { }
            liveLayoutHandler.postDelayed(this, 90L);
        }
    };

    public static void pushLiveLayout(Context ctx,
                                      LiveLayoutBridge.LayoutSnapshot snap) {
        if (snap == null) return;
        LiveLayoutBridge.write(ctx, snap);
        BasicImeService local = INSTANCE;
        if (local != null) {
            local.liveLayoutHandler.post(() -> {
                ImeColorStore.get(local).applySnapshot(snap);
                local.applyLayoutLiveFromSnapshot(snap);
            });
        }
    }

    private void applyLayoutLiveFromSnapshot(LiveLayoutBridge.LayoutSnapshot snap) {
        if (snap == null) return;

        NumericPadConfig.get(this).applySnapshot(
                snap.numericExtraChar, snap.numericDigitOrder, snap.numericReverseRows);

        if (keyboardView != null) {
            keyboardView.rebuildFromStore();
            keyboardView.refreshWallpaper();
        }
        if (emojiPanel != null)       emojiPanel.refreshFromPrefs();
        if (clipboardPanel != null)   clipboardPanel.requestLayout();
        if (rootContainer != null) {
            rootContainer.requestLayout();
            rootContainer.invalidate();
        }
        applyWallpaperWindowSettings();

        if (keyboardView != null) {
            keyboardView.post(() -> {
                try {
                    measureKeyboard();
                    applyKeyboardFrameLock();
                    rebuildWallpaperLayers();
                } catch (Throwable t) {
                    XLog.e("IME", "applyLayoutLiveFromSnapshot post failed", t);
                }
            });
        }
    }

    private void applyLayoutLive() {
        try {
            LiveLayoutBridge.LayoutSnapshot snap = LiveLayoutBridge.read(this);
            if (snap != null) {
                lastLiveLayoutStamp = LiveLayoutBridge.stamp(this);
                ImeColorStore.get(this).applySnapshot(snap);
                applyLayoutLiveFromSnapshot(snap);
                return;
            }
        } catch (Throwable ignored) { }

        if (keyboardView != null) {
            keyboardView.refreshFromPrefs();
            keyboardView.post(() -> {
                measureKeyboard();
                applyKeyboardFrameLock();
            });
        }
        if (emojiPanel != null) emojiPanel.refreshFromPrefs();
        if (rootContainer != null) {
            rootContainer.requestLayout();
            rootContainer.invalidate();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Wallpaper listener / overlay / window
    // ══════════════════════════════════════════════════════════════

    private void registerWallpaperListener() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O_MR1) return;
        if (wallpaperListener != null) return;
        try {
            final android.app.WallpaperManager wm =
                    android.app.WallpaperManager.getInstance(this);
            wallpaperListener = new android.app.WallpaperManager.OnColorsChangedListener() {
                @Override
                public void onColorsChanged(android.app.WallpaperColors colors, int which) {
                    if ((which & android.app.WallpaperManager.FLAG_SYSTEM) == 0
                            && which != 0) return;
                    if (keyboardView == null) return;
                    keyboardView.post(() -> {
                        if (keyboardView == null) return;
                        keyboardView.reloadSystemWallpaper();
                    });
                }
            };
            wm.addOnColorsChangedListener(wallpaperListener,
                    new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Throwable t) {
            XLog.e("IME-WALL", "registerWallpaperListener failed", t);
            wallpaperListener = null;
        }
    }

    private void unregisterWallpaperListener() {
        if (wallpaperListener == null) return;
        try {
            android.app.WallpaperManager wm =
                    android.app.WallpaperManager.getInstance(this);
            wm.removeOnColorsChangedListener(wallpaperListener);
        } catch (Throwable ignored) { }
        wallpaperListener = null;
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        consumeRestartRequestIfAny();
        applyWallpaperWindowSettings();
        try { ClipboardStore.get(this).capture(this); } catch (Throwable ignored) { }
        ensureOverlayPermission();
    }

    private boolean overlaySettingsPromptShown = false;

    private void ensureOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return;
        if (android.provider.Settings.canDrawOverlays(this)) return;
        if (rootContainer != null) {
            rootContainer.post(this::startOverlaySettingsIntentOnce);
        }
    }

    private void startOverlaySettingsIntentOnce() {
        if (overlaySettingsPromptShown) return;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return;
        if (android.provider.Settings.canDrawOverlays(this)) return;
        overlaySettingsPromptShown = true;
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            XLog.e("IME-OVERLAY", "startActivity failed", t);
        }
    }

    private void applyWallpaperWindowSettings() {
        if (getWindow() == null) return;
        ImeColorStore colors = ImeColorStore.get(this);
        if (colors.isWallpaperEnabled()) {
            // Any wallpaper mode (system / image / video) => fully transparent
            // window so nothing between the launcher wallpaper and the user
            // produces a visible seam at the top of the keyboard.
            getWindow().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            getWindow().getWindow().setDimAmount(0f);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                getWindow().getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
                getWindow().getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            }
        } else {
            getWindow().getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(colors.getBgColor()));
        }
    }

    private void applyEditorInputType(EditorInfo info) {
        if (keyboardView == null) return;
        int type = info == null ? EditorInfo.TYPE_NULL : info.inputType;
        int cls  = type & android.text.InputType.TYPE_MASK_CLASS;
        int vari = type & android.text.InputType.TYPE_MASK_VARIATION;

        boolean num = (cls == android.text.InputType.TYPE_CLASS_NUMBER)
                || (cls == android.text.InputType.TYPE_CLASS_PHONE)
                || (cls == android.text.InputType.TYPE_CLASS_DATETIME);
        boolean url = (cls == android.text.InputType.TYPE_CLASS_TEXT
                && vari == android.text.InputType.TYPE_TEXT_VARIATION_URI);
        boolean email = (cls == android.text.InputType.TYPE_CLASS_TEXT
                && (vari == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || vari == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS));

        keyboardView.setInputMode(num, url, email);
    }

    // ══════════════════════════════════════════════════════════════
    //  onCreate + receiver
    // ══════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;

        videoPosPrefs = getSharedPreferences(PREF_VIDEO_POSITIONS, MODE_PRIVATE);
        loadVideoPositions();

        try {
            lastLiveLayoutStamp = LiveLayoutBridge.stamp(this);
            LiveLayoutBridge.LayoutSnapshot snap = LiveLayoutBridge.read(this);
            if (snap != null) ImeColorStore.get(this).applySnapshot(snap);
        } catch (Throwable ignored) { }

        liveLayoutHandler.post(liveLayoutPoll);
        registerWallpaperListener();
        EmojiGridCache.get(this).setPicker(this);
        GestureActionStore.get(this).all();

        layoutChangeReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null) return;
                String action = i.getAction();
                if (ACTION_RESTART_IME.equals(action)) {
                    try { rebuildInputViewNow(); }
                    catch (Throwable t) { XLog.e("IME-RCV", "rebuild failed", t); }
                } else {
                    applyLayoutLive();
                }
            }
        };
        registerLayoutReceiver();

        new Thread(() -> {
            try { EmojiGridCache.get(this).warm(); } catch (Throwable ignored) { }
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.addPrimaryClipChangedListener(() -> {
                        try {
                            ClipboardStore.get(BasicImeService.this)
                                    .capture(BasicImeService.this);
                        } catch (Throwable ignored) { }
                    });
                }
            } catch (Throwable ignored) { }
        }, "emoji-warm").start();
    }

    @android.annotation.SuppressLint({"UnspecifiedRegisterReceiverFlag", "RegisterReceiverFlag"})
    private void registerLayoutReceiver() {
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(ACTION_LAYOUT_CHANGED);
        filter.addAction(ACTION_RESTART_IME);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(layoutChangeReceiver, filter,
                    android.content.Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(layoutChangeReceiver, filter);
        }
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    private void setPage(int page) {
        if (keyboardView == null) return;
        keyboardView.setPage(page);
    }
}