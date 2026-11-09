package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.ClipboardManager;
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

public class BasicImeService extends InputMethodService
        implements EmojiPaletteView.PanelControl,
        EmojiPaletteView.EmojiPickListener {

    private BasicKeyboardView keyboardView;
    private KeyBubbleOverlay bubbleOverlay;
    private EmojiPaletteView emojiPanel;
    private ClipboardPanelView clipboardPanel;
    private FrameLayout rootContainer;
    private TextureView videoView;
    private MediaPlayer mediaPlayer;
    private String currentVideoPath;
    private int videoWidth = 0;
    private int videoHeight = 0;
    private int keyboardHeightPx = 0;
    private int keyboardWidthPx = 0;

    private static final int PAGE_LETTERS = 0;

    private int savedVideoPositionMs = 0;
    private String savedVideoPath = null;

    private android.app.AlertDialog overlayPromptDialog = null;

    public static final String ACTION_LAYOUT_CHANGED =
            "vaddempudi.gnanesh.syntaxcli.ime.LAYOUT_CHANGED";

    private android.content.BroadcastReceiver layoutChangeReceiver;


    @Override
    public void closePanel() {
        showKeyboardPanel();
    }

    private void showKeyboardPanel() {
        if (rootContainer == null || keyboardView == null) return;
        if (keyboardView.getParent() == rootContainer
                && rootContainer.getChildCount() == 1) {
            return;
        }
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

    private void rebuildWallpaperLayers() {
        if (rootContainer == null) return;
        if (keyboardHeightPx <= 0 || keyboardWidthPx <= 0) return;

        if (videoView != null) {
            rootContainer.removeView(videoView);
            videoView = null;
        }
        releaseVideoKeepPosition();

        ImeColorStore colors = ImeColorStore.get(this);
        if (!colors.isWallpaperEnabled()) return;
        if (!colors.hasVideoWallpaper()) return;

        videoView = new TextureView(this);
        videoView.setOpaque(true);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                keyboardWidthPx, keyboardHeightPx);
        lp.gravity = Gravity.BOTTOM;
        rootContainer.addView(videoView, 0, lp);

        videoView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l > 0 && b - t > 0) applyVideoTransform();
        });

        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                startVideo(colors.getWallpaperVideoPath(), st);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                applyVideoTransform();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releaseVideoKeepPosition();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });

        if (keyboardView != null) {
            keyboardView.bringToFront();
            keyboardView.invalidate();
        }
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

    private void startVideo(String path, SurfaceTexture st) {
        if (path == null || path.isEmpty()) return;

        if (currentVideoPath != null && currentVideoPath.equals(path) && mediaPlayer != null) {
            try {
                mediaPlayer.setSurface(new android.view.Surface(st));
                mediaPlayer.seekTo(savedVideoPositionMs);
                mediaPlayer.start();
            } catch (Exception ignored) { }
            return;
        }

        releaseVideoKeepPosition();

        if (savedVideoPath != null && !savedVideoPath.equals(path)) {
            savedVideoPositionMs = 0;
        }
        if (savedVideoPath == null) {
            savedVideoPositionMs = 0;
        }
        savedVideoPath = path;

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(path);
            mediaPlayer.setSurface(new android.view.Surface(st));
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(0f, 0f);
            mediaPlayer.setOnPreparedListener(mp -> {
                videoWidth  = mp.getVideoWidth();
                videoHeight = mp.getVideoHeight();
                if (videoWidth <= 0 || videoHeight <= 0) {
                    mp.start();
                    return;
                }
                applyVideoTransform();
                if (savedVideoPositionMs > 0) {
                    mp.seekTo(savedVideoPositionMs);
                }
                mp.start();
            });
            mediaPlayer.setOnVideoSizeChangedListener((mp, w, h) -> {
                videoWidth  = w;
                videoHeight = h;
                applyVideoTransform();
            });
            mediaPlayer.prepareAsync();
            currentVideoPath = path;
        } catch (Exception ignored) {
            releaseVideoKeepPosition();
        }
    }

    private void releaseVideoKeepPosition() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    savedVideoPositionMs = mediaPlayer.getCurrentPosition();
                } else {
                    savedVideoPositionMs = Math.max(savedVideoPositionMs, mediaPlayer.getCurrentPosition());
                }
            } catch (Exception ignored) { }
            try { mediaPlayer.stop(); } catch (Exception ignored) { }
            try { mediaPlayer.release(); } catch (Exception ignored) { }
            mediaPlayer = null;
        }
        currentVideoPath = null;
        videoWidth = 0;
        videoHeight = 0;
    }

    private void showEmojiPanel() {
        if (rootContainer == null) return;
        if (emojiPanel == null) {
            emojiPanel = new EmojiPaletteView(this, this);
        }
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
        clipboardPanel = new ClipboardPanelView(this, new ClipboardPanelView.ClipboardPickListener() {
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
                android.content.Intent launch = getPackageManager()
                        .getLaunchIntentForPackage(a.payload);
                if (launch != null) {
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                }
            } catch (Exception ignored) { }
            return;
        }

        if (a.type == KeyAction.TYPE_LAUNCH_ACTIVITY) {
            try {
                android.content.ComponentName cn =
                        android.content.ComponentName.unflattenFromString(a.payload);
                if (cn != null) {
                    android.content.Intent i = new android.content.Intent();
                    i.setComponent(cn);
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
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
                setPage(1);
                return;
            case BasicKey.CODE_SYMBOLS_2:
                setPage(2);
                return;
            case BasicKey.CODE_SYM_PAGE_1:
                setPage(1);
                return;
            case BasicKey.CODE_SYM_PAGE_2:
                setPage(2);
                return;
            case BasicKey.CODE_SYM_PAGE_3:
                setPage(3);
                return;
            case BasicKey.CODE_SYM_PAGE_4:
                setPage(3);
                return;
            case BasicKey.CODE_NUMERIC:
                setPage(4);
                return;
            case BasicKey.CODE_ABC:
                setPage(0);
                return;
            case BasicKey.CODE_DELETE:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
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
                if (keyboardView != null
                        && (keyboardView.isSymbols()
                        || keyboardView.isSymbols2()
                        || keyboardView.isSymbols3())) {
                    setPage(0);
                }
                return;
            case BasicKey.CODE_LEFT:
                moveCursor(ic, -1);
                return;
            case BasicKey.CODE_RIGHT:
                moveCursor(ic, +1);
                return;
        }

        if (text != null && text.length() > 0) {
            ic.commitText(text, 1);

            if (keyboardView != null
                    && !keyboardView.isSymbols()
                    && !keyboardView.isSymbols2()
                    && !keyboardView.isSymbols3()) {
                if (!keyboardView.isShiftLocked() && keyboardView.isShifted()) {
                    keyboardView.clearManualShift();
                }
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

        // Prefer the true extents (works on every host).
        CharSequence before = ic.getTextBeforeCursor(1024, 0);
        CharSequence after  = ic.getTextAfterCursor(1024, 0);

        int beforeLen = before == null ? 0 : before.length();
        int afterLen  = after  == null ? 0 : after.length();

        // Total length at the cursor. selStart = beforeLen, selEnd = selEndAbs.
        // We only need selStart because there's no selection during typing.
        int selStart = beforeLen;
        int selEnd   = beforeLen;   // assume collapsed

        int target;
        if (direction < 0) {
            // Move left by one codepoint (not one UTF-16 char).
            target = selStart - 1;
            if (before != null && beforeLen > 0) {
                // If the last char is a low surrogate, step back one more.
                char c = before.charAt(beforeLen - 1);
                if (Character.isLowSurrogate(c) && beforeLen >= 2) {
                    target = selStart - 2;
                }
            }
        } else {
            // Move right by one codepoint.
            target = selStart + 1;
            if (after != null && afterLen > 0) {
                char c = after.charAt(0);
                if (Character.isHighSurrogate(c) && afterLen >= 2) {
                    target = selStart + 2;
                }
            }
        }

        if (target < 0) target = 0;
        int total = beforeLen + afterLen;
        if (target > total) target = total;

        // setSelection moves the cursor without dispatching a key event,
        // so the host app cannot interpret it as "close the keyboard".
        ic.setSelection(Math.min(selStart, selEnd), Math.max(selStart, selEnd));
        // Now do the actual move.
        ic.setSelection(target, target);
    }

    private void pasteClip(ClipEntry e) {
        if (e == null) return;
        InputConnection ic = getCurrentInputConnection();

        try {
            // TEXT — unchanged
            if (e.type == ClipEntry.TYPE_TEXT) {
                if (ic != null) ic.commitText(e.text, 1);
                return;
            }

            String mime = e.mimeType != null ? e.mimeType : "image/png";

            // 1) Preferred: commitContent with a real ClipDescription.
            //    This delivers the image directly to the focused EditText and
            //    grants read on the URI to the target app. Works in WhatsApp.
            android.net.Uri fpUri = ClipboardStore.get(this).fileProviderUriFor(e);
            if (ic != null && fpUri != null) {
                try {
                    android.content.ClipDescription desc =
                            new android.content.ClipDescription("image", new String[]{mime});
                    android.view.inputmethod.InputContentInfo info =
                            new android.view.inputmethod.InputContentInfo(
                                    fpUri, desc);
                    boolean ok = ic.commitContent(
                            info,
                            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                            null);
                    if (ok) return;
                } catch (Throwable t) {
                    XLog.w("IME-PASTE", "commitContent failed: " + t);
                }
            }

            // 2) Fallback: MediaStore URI on the clipboard.
            //    FileProvider URI will NOT work for the manual long-press → Paste
            //    path, because WhatsApp's UID has no read grant on it. MediaStore
            //    URIs are readable by any app with READ_EXTERNAL_STORAGE.
            android.net.Uri publicUri = ClipboardStore.get(this).publicImageUriFor(e);
            if (publicUri == null) {
                // last resort: put the file URI on the clipboard anyway
                publicUri = fpUri;
            }
            if (publicUri == null) {
                XLog.w("IME-PASTE", "no URI to paste");
                return;
            }

            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                android.content.ClipDescription desc =
                        new android.content.ClipDescription("image", new String[]{mime});
                android.content.ClipData clip =
                        new android.content.ClipData(desc, new android.content.ClipData.Item(publicUri));
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

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        try {
            ClipboardStore.get(this).capture(this);
        } catch (Throwable ignored) { }

        if (rootContainer == null) {
            onCreateInputView();
        } else {
            keyboardView.setListener(buildListener());
            applyWallpaperWindowSettings();
            measureKeyboard();
            applyKeyboardFrameLock();
            rebuildWallpaperLayers();
            keyboardView.refreshWallpaper();
            showKeyboardPanel();
            ensureOverlayPermission();
        }

        clipboardPanel = null;
        emojiPanel = null;
        applyEditorInputType(info);
    }
    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        if (keyboardView != null) keyboardView.invalidate();
        applyWallpaperWindowSettings();
        measureKeyboard();
        applyKeyboardFrameLock();
        rebuildWallpaperLayers();
        if (keyboardView != null) keyboardView.refreshWallpaper();
        applyEditorInputType(attribute);
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        applyWallpaperWindowSettings();
        try {
            ClipboardStore.get(this).capture(this);
        } catch (Throwable ignored) { }
        ensureOverlayPermission();
    }
    // ── Overlay permission ──────────────────────────────────────────

    private void ensureOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return;
        if (android.provider.Settings.canDrawOverlays(this)) return;

        // Only one prompt at a time; the keyboard may call this from several
        // lifecycle callbacks during the same open.
        if (overlayPromptDialog != null && overlayPromptDialog.isShowing()) return;

        // Post so we never block onCreateInputView / onStartInputView.
        rootContainer.post(() -> {
            if (overlayPromptDialog != null && overlayPromptDialog.isShowing()) return;
            if (!isAddedToService()) return;
            if (android.provider.Settings.canDrawOverlays(this)) return;

            overlayPromptDialog = new android.app.AlertDialog.Builder(this)
                    .setTitle("Enable key popup")
                    .setMessage("To show the key-preview bubble above the keyboard, "
                            + "please allow 'Display over other apps' for this keyboard.")
                    .setPositiveButton("OPEN SETTINGS", (d, w) -> {
                        try {
                            android.content.Intent i = new android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + getPackageName()));
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Throwable ignored) { }
                    })
                    .setNegativeButton("SKIP", null)
                    .setOnDismissListener(d -> overlayPromptDialog = null)
                    .create();
            overlayPromptDialog.setCanceledOnTouchOutside(true);
            overlayPromptDialog.show();
        });
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

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (keyboardView != null) keyboardView.setPage(PAGE_LETTERS);
        if (bubbleOverlay != null) bubbleOverlay.hide();
        releaseVideoKeepPosition();
        clipboardPanel = null;
        emojiPanel = null;
    }


    private void applyLayoutLive() {
        // Refresh the keyboard: re-read all values from ImeColorStore,
        // rebuild hit keys, re-measure, and re-invalidate.
        if (keyboardView != null) {
            keyboardView.refreshFromPrefs();
            keyboardView.post(() -> {
                measureKeyboard();
                applyKeyboardFrameLock();
            });
        }

        // Emoji panel: re-measure so the tab row picks up the new height.
        if (emojiPanel != null) {
            emojiPanel.refreshFromPrefs();
        }

        // Ask the InputMethodManager to re-layout the input view.
        if (rootContainer != null) {
            rootContainer.requestLayout();
            rootContainer.invalidate();
        }
    }

    private boolean isAddedToService() {
        // The IME is "alive" as long as it has a rootContainer and hasn't been
        // destroyed. If onDestroy has run, rootContainer is null.
        return rootContainer != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EmojiGridCache.get(this).setPicker(this);
        GestureActionStore.get(this).all();

        layoutChangeReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, android.content.Intent i) {
                applyLayoutLive();
            }
        };
        registerLayoutReceiver();

        new Thread(() -> {
            try { EmojiGridCache.get(this).warm(); } catch (Throwable ignored) { }
            try {
                ClipboardManager cm = (ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
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
        android.content.IntentFilter filter =
                new android.content.IntentFilter(ACTION_LAYOUT_CHANGED);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(layoutChangeReceiver, filter,
                    android.content.Context.RECEIVER_NOT_EXPORTED);
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