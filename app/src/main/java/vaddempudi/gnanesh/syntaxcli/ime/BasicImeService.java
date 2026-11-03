package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
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

public class BasicImeService extends InputMethodService
        implements EmojiPaletteView.PanelControl,
        EmojiPaletteView.EmojiPickListener {

    private BasicKeyboardView keyboardView;
    private KeyBubbleOverlay bubbleOverlay;
    private EmojiPaletteView emojiPanel;

    private FrameLayout rootContainer;
    private TextureView videoView;
    private MediaPlayer mediaPlayer;
    private String currentVideoPath;

    @Override
    public void closePanel() {
        if (keyboardView != null) setInputView(keyboardView);
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

    @Override
    public void onCreate() {
        super.onCreate();
        EmojiGridCache.get(this).setPicker(this);
        new Thread(() -> {
            try {
                EmojiGridCache.get(this).warm();
            } catch (Throwable t) {
                android.util.Log.e("EmojiGridCache", "warm failed", t);
            }
        }, "emoji-warm").start();
    }

    @Override
    public View onCreateInputView() {
        rootContainer = new FrameLayout(this);

        keyboardView = new BasicKeyboardView(this);
        bubbleOverlay = new KeyBubbleOverlay(this);
        keyboardView.setBubbleOverlay(bubbleOverlay);
        keyboardView.setListener(buildListener());

        rootContainer.addView(keyboardView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        applyWallpaperWindowSettings();
        rebuildWallpaperLayers();

        return rootContainer;
    }

    private void rebuildWallpaperLayers() {
        if (rootContainer == null) return;

        if (videoView != null) {
            rootContainer.removeView(videoView);
            videoView = null;
        }
        stopVideo();

        ImeColorStore colors = ImeColorStore.get(this);

        if (!colors.isWallpaperEnabled()) return;

        if (colors.hasVideoWallpaper()) {
            videoView = new TextureView(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.BOTTOM;
            rootContainer.addView(videoView, 0, lp);

            videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    startVideo(colors.getWallpaperVideoPath(), st);
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    stopVideo();
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
            });
        }

        if (keyboardView != null) {
            keyboardView.bringToFront();
            keyboardView.invalidate();
        }
    }

    private void startVideo(String path, SurfaceTexture st) {
        if (path == null || path.isEmpty()) return;
        if (currentVideoPath != null && currentVideoPath.equals(path) && mediaPlayer != null) {
            try { mediaPlayer.setSurface(new android.view.Surface(st)); mediaPlayer.start(); } catch (Exception ignored) { }
            return;
        }
        stopVideo();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(path);
            mediaPlayer.setSurface(new android.view.Surface(st));
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(0f, 0f);
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.prepareAsync();
            currentVideoPath = path;
        } catch (Exception e) {
            android.util.Log.e("BasicImeService", "video start failed", e);
            stopVideo();
        }
    }

    private void stopVideo() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) { }
            try { mediaPlayer.release(); } catch (Exception ignored) { }
            mediaPlayer = null;
        }
        currentVideoPath = null;
    }

    private void showEmojiPanel() {
        if (emojiPanel == null) {
            emojiPanel = new EmojiPaletteView(this, this);
        }
        setInputView(emojiPanel);
    }

    private void runAction(KeyAction a) {
        if (a == null) return;
        if (a.type == KeyAction.TYPE_TEXT) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(a.payload, 1);
        } else if (a.type == KeyAction.TYPE_APP_LAUNCH) {
            try {
                android.content.Intent launch = getPackageManager()
                        .getLaunchIntentForPackage(a.payload);
                if (launch != null) {
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                }
            } catch (Exception ignored) { }
        }
    }

    private void handleKey(int code, String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        switch (code) {
            case BasicKey.CODE_SHIFT: {
                if (keyboardView == null) return;
                boolean second = keyboardView.registerShiftTap();
                if (keyboardView.isShiftLocked()) { keyboardView.clearShiftLock(); return; }
                if (second) { keyboardView.setShiftLocked(true); keyboardView.setShifted(false); return; }
                keyboardView.setShifted(!keyboardView.isShifted());
                return;
            }
            case BasicKey.CODE_SYMBOLS:      setPage(1); return;
            case BasicKey.CODE_SYMBOLS_2:    setPage(2); return;
            case BasicKey.CODE_SYM_PAGE_1:   setPage(1); return;
            case BasicKey.CODE_SYM_PAGE_2:   setPage(2); return;
            case BasicKey.CODE_SYM_PAGE_3:   setPage(3); return;
            case BasicKey.CODE_SYM_PAGE_4:   setPage(3); return;
            case BasicKey.CODE_ABC:          setPage(0); return;
            case BasicKey.CODE_DELETE:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_DEL));
                return;
            case BasicKey.CODE_ENTER:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                return;
            case BasicKey.CODE_SPACE:
                ic.commitText(" ", 1);
                if (keyboardView != null && keyboardView.isShifted() && !keyboardView.isShiftLocked()) {
                    keyboardView.clearManualShift();
                }
                return;
            case BasicKey.CODE_LEFT:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
                return;
            case BasicKey.CODE_RIGHT:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
                return;
        }

        if (text != null && text.length() > 0) {
            ic.commitText(text, 1);
            if (keyboardView != null
                    && !keyboardView.isSymbols()
                    && !keyboardView.isSymbols2()
                    && !keyboardView.isSymbols3()) {
                if (keyboardView.isShiftLocked()) keyboardView.setShifted(true);
                else if (keyboardView.isShifted()) keyboardView.clearManualShift();
            }
        }
    }

    private BasicKeyboardView.Listener buildListener() {
        return new BasicKeyboardView.Listener() {
            @Override
            public void onKey(int code, String text) {
                if (code == BasicKeyboardView.EMOJI_PANEL_TRIGGER) {
                    showEmojiPanel();
                    return;
                }
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
                handleKey(code, text);
            }
        };
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        setPage(0);

        if (rootContainer == null) {
            onCreateInputView();
        } else {
            keyboardView.setListener(buildListener());
            applyWallpaperWindowSettings();
            rebuildWallpaperLayers();
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        applyWallpaperWindowSettings();
    }

    private void applyWallpaperWindowSettings() {
        if (getWindow() == null) return;
        ImeColorStore colors = ImeColorStore.get(this);
        if (colors.isWallpaperEnabled()) {
            getWindow().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        } else {
            getWindow().getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(colors.getBgColor()));
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        if (keyboardView != null) {
            keyboardView.invalidate();
        }
        applyWallpaperWindowSettings();
        rebuildWallpaperLayers();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        setPage(0);
        if (bubbleOverlay != null) bubbleOverlay.hide();
        stopVideo();
    }

    @Override
    public void onDestroy() {
        if (bubbleOverlay != null) {
            bubbleOverlay.hide();
            bubbleOverlay = null;
        }
        stopVideo();
        super.onDestroy();
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