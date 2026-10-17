package vaddempudi.gnanesh.syntaxcli.musicplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * Receives ACTION_MEDIA_BUTTON from:
 *   - wired headsets (3.5 mm jack)
 *   - Bluetooth A2DP/AVRCP devices (earbuds, car stereos)
 *   - lockscreen / notification shade media controls on some OEMs
 *
 * Translates them into the ACTION_CTRL commands MusicPlayerActivity
 * already understands: CMD_TOGGLE, CMD_NEXT, CMD_PREV, CMD_PLAY, CMD_PAUSE.
 *
 * Single tap    → play / pause (toggle)
 * Double tap    → next track
 * Triple tap    → previous track
 * Long press    → (falls through as KEYCODE_MEDIA_PLAY_PAUSE down+up — treated as toggle)
 */
public class MusicButtonReceiver extends BroadcastReceiver {

    private static long lastTapTime = 0L;
    private static int tapCount = 0;
    private static final long DOUBLE_TAP_WINDOW_MS = 400L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (!Intent.ACTION_MEDIA_BUTTON.equals(action)) return;

        KeyEvent keyEvent = getKeyEvent(intent);
        if (keyEvent == null) return;

        // Only act on the DOWN phase — UP is the pairing event and would double-fire.
        if (keyEvent.getAction() != KeyEvent.ACTION_DOWN) return;

        int code = keyEvent.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                handleMultiTap(context, code);
                break;

            case KeyEvent.KEYCODE_MEDIA_NEXT:
                sendCmd(context, MusicPlayerActivity.CMD_NEXT);
                break;

            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                sendCmd(context, MusicPlayerActivity.CMD_PREV);
                break;

            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                // Treat as prev/next — most players do the same
                sendCmd(context, code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        ? MusicPlayerActivity.CMD_NEXT
                        : MusicPlayerActivity.CMD_PREV);
                break;
        }
    }

    /**
     * Single / double / triple tap on the primary media button.
     * Most earbuds send a single MEDIA_PLAY_PAUSE per tap and the OS
     * doesn't coalesce them — we do the counting ourselves.
     */
    private void handleMultiTap(final Context context, final int keyCode) {
        long now = System.currentTimeMillis();
        if (now - lastTapTime > DOUBLE_TAP_WINDOW_MS) {
            tapCount = 0;
        }
        lastTapTime = now;
        tapCount++;

        final int currentTap = tapCount;

        // Wait out the window, then dispatch based on how many taps arrived.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (currentTap != tapCount) return;  // a later tap superseded this one
            if (currentTap == 1) {
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                    sendCmd(context, MusicPlayerActivity.CMD_PLAY);
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                    sendCmd(context, MusicPlayerActivity.CMD_PAUSE);
                } else {
                    sendCmd(context, MusicPlayerActivity.CMD_TOGGLE);
                }
            } else if (currentTap == 2) {
                sendCmd(context, MusicPlayerActivity.CMD_NEXT);
            } else {
                sendCmd(context, MusicPlayerActivity.CMD_PREV);
            }
            tapCount = 0;
        }, DOUBLE_TAP_WINDOW_MS);
    }

    private void sendCmd(Context context, String cmd) {
        try {
            Intent i = new Intent(MusicPlayerActivity.ACTION_CTRL);
            i.putExtra(MusicPlayerActivity.EXTRA_CMD, cmd);
            LocalBroadcastManager.getInstance(context.getApplicationContext())
                    .sendBroadcast(i);

            // Also forward to the service so it re-broadcasts into the
            // activity if the activity isn't currently alive (rare, but
            // makes resume-after-kill work correctly).
            Intent svc = new Intent(context.getApplicationContext(),
                    MusicPlaybackService.class);
            svc.setAction(MusicPlayerActivity.ACTION_CTRL);
            svc.putExtra(MusicPlayerActivity.EXTRA_CMD, cmd);
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    context.getApplicationContext().startForegroundService(svc);
                } else {
                    context.getApplicationContext().startService(svc);
                }
            } catch (Exception ignored) { }
        } catch (Exception ignored) { }
    }

    private static KeyEvent getKeyEvent(Intent intent) {
        // Modern path: Parcelable extra
        try {
            Object o = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (o instanceof KeyEvent) return (KeyEvent) o;
        } catch (Exception ignored) { }

        // Legacy path: Bundle with "android.intent.extra.KEY_EVENT"
        try {
            Bundle b = intent.getExtras();
            if (b != null) {
                Object o = b.get(Intent.EXTRA_KEY_EVENT);
                if (o instanceof KeyEvent) return (KeyEvent) o;
            }
        } catch (Exception ignored) { }

        return null;
    }
}