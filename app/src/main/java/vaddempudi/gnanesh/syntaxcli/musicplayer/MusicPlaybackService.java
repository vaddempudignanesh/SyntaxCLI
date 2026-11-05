package vaddempudi.gnanesh.syntaxcli.musicplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import vaddempudi.gnanesh.syntaxcli.R;

public class MusicPlaybackService extends Service {

    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int    NOTIF_ID   = 0xA11CE;

    public static final String ACTION_UPDATE_NOTIFICATION =
            "vaddempudi.gnanesh.syntaxcli.MUSIC_UPDATE_NOTIF";
    public static final String EXTRA_POS_MS = "pos_ms";
    public static final String EXTRA_DUR_MS = "dur_ms";

    private static final int RC_PREV   = 0x1001;
    private static final int RC_TOGGLE = 0x1002;
    private static final int RC_NEXT   = 0x1003;

    private BroadcastReceiver updateReceiver;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus = false;

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int focusChange) {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            hasFocus = false;
                            sendCmd(MusicPlayerActivity.CMD_PAUSE);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            hasFocus = true;
                            sendCmd(MusicPlayerActivity.CMD_PLAY);
                            break;
                    }
                }
            };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        initMediaSession();
        requestAudioFocus();

        updateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                refreshNotification(intent);
                syncMediaSession();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction("vaddempudi.gnanesh.syntaxcli.MUSIC_STATE_CHANGED");
        f.addAction("vaddempudi.gnanesh.syntaxcli.MUSIC_TRACK_CHANGED");
        f.addAction(ACTION_UPDATE_NOTIFICATION);
        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver, f);

        startForegroundInternal();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String cmd = intent.getStringExtra(MusicPlayerActivity.EXTRA_CMD);
            if (cmd != null) dispatchCmd(cmd);
        }
        return START_STICKY;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(updateReceiver);
        } catch (Exception ignored) { }
        try {
            if (mediaSession != null) {
                mediaSession.setActive(false);
                mediaSession.release();
                mediaSession = null;
            }
        } catch (Exception ignored) { }
        abandonAudioFocus();
        super.onDestroy();
    }

    private void dispatchCmd(String cmd) {
        if (cmd == null) return;
        sendCmd(cmd);
        if (!MusicPlayerActivity.alive) {
            try {
                Intent open = new Intent(this, MusicPlayerActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                open.putExtra("start_hidden", true);
                open.putExtra(MusicPlayerActivity.EXTRA_CMD, cmd);
                startActivity(open);
            } catch (Exception ignored) { }
        }
    }

    private void initMediaSession() {
        try {
            mediaSession = new MediaSessionCompat(this, "SyntaxCLIMusicSession");
            mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override public void onPlay()   { sendCmd(MusicPlayerActivity.CMD_PLAY); }
                @Override public void onPause()  { sendCmd(MusicPlayerActivity.CMD_PAUSE); }
                @Override public void onSkipToNext() { sendCmd(MusicPlayerActivity.CMD_NEXT); }
                @Override public void onSkipToPrevious() { sendCmd(MusicPlayerActivity.CMD_PREV); }
                @Override public void onStop()   { sendCmd(MusicPlayerActivity.CMD_PAUSE); }
                @Override public void onSeekTo(long pos) {
                    try {
                        MusicPlayerActivity.requestSeek(pos);
                    } catch (Exception ignored) { }
                }
            });

            mediaSession.setActive(true);
            syncMediaSession();
        } catch (Throwable ignored) { }
    }

    private void syncMediaSession() {
        if (mediaSession == null) return;
        try {
            String title = MusicPlayerActivity.getLastPlayedTitle();
            if (title == null) title = "";
            boolean playing = MusicPlayerActivity.isCurrentlyPlaying();

            MediaMetadataCompat md = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,
                            MusicPlayerActivity.lastKnownDurationMs)
                    .build();
            mediaSession.setMetadata(md);

            long actions = PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_SEEK_TO
                    | PlaybackStateCompat.ACTION_STOP;

            PlaybackStateCompat.Builder psb = new PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(playing
                                    ? PlaybackStateCompat.STATE_PLAYING
                                    : PlaybackStateCompat.STATE_PAUSED,
                            MusicPlayerActivity.lastKnownPositionMs,
                            playing ? 1f : 0f);
            mediaSession.setPlaybackState(psb.build());
        } catch (Exception ignored) { }
    }

    private void requestAudioFocus() {
        if (hasFocus) return;
        try {
            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(focusListener)
                        .setWillPauseWhenDucked(false)
                        .build();
                hasFocus = audioManager.requestAudioFocus(focusRequest)
                        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            } else {
                hasFocus = audioManager.requestAudioFocus(focusListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
        } catch (Exception ignored) { }
    }

    private void abandonAudioFocus() {
        try {
            if (audioManager == null) return;
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Exception ignored) { }
        hasFocus = false;
    }

    private void startForegroundInternal() {
        Notification n = buildNotification(null);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    private void refreshNotification(Intent state) {
        try {
            Notification n = buildNotification(state);
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, n);
        } catch (Exception ignored) { }
    }

    private Notification buildNotification(@Nullable Intent state) {
        String title = MusicPlayerActivity.getLastPlayedTitle();
        if (title == null || title.isEmpty()) title = "Music Player";

        boolean playing = MusicPlayerActivity.isCurrentlyPlaying();
        String text = playing ? "Playing" : "Paused";

        long pos = state != null
                ? state.getLongExtra(EXTRA_POS_MS, MusicPlayerActivity.lastKnownPositionMs)
                : MusicPlayerActivity.lastKnownPositionMs;
        long dur = state != null
                ? state.getLongExtra(EXTRA_DUR_MS, MusicPlayerActivity.lastKnownDurationMs)
                : MusicPlayerActivity.lastKnownDurationMs;
        if (dur > 0) {
            text += "  \u2022  " + fmt(pos) + " / " + fmt(dur);
        }

        Intent openIntent = new Intent(this, MusicPlayerActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        PendingIntent prevPi   = ctrlPi("ACTION_PREV",   MusicPlayerActivity.CMD_PREV,   RC_PREV);
        PendingIntent togglePi = ctrlPi("ACTION_TOGGLE", MusicPlayerActivity.CMD_TOGGLE, RC_TOGGLE);
        PendingIntent nextPi   = ctrlPi("ACTION_NEXT",   MusicPlayerActivity.CMD_NEXT,   RC_NEXT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play_circle)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setSilent(true)
                .setColor(0xFF00FF00)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(R.drawable.ic_skip_back, "Previous", prevPi)
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play_circle,
                        playing ? "Pause" : "Play", togglePi)
                .addAction(R.drawable.ic_skip_forward, "Next", nextPi);

        if (Build.VERSION.SDK_INT >= 21) {
            MediaSessionCompat.Token token =
                    mediaSession != null ? mediaSession.getSessionToken() : null;
            androidx.media.app.NotificationCompat.MediaStyle style =
                    new androidx.media.app.NotificationCompat.MediaStyle()
                            .setShowActionsInCompactView(0, 1, 2);
            if (token != null) style.setMediaSession(token);
            b.setStyle(style);
        }
        return b.build();
    }

    private PendingIntent ctrlPi(String action, String cmd, int reqCode) {
        Intent i = new Intent(this, MusicPlaybackService.class);
        i.setAction(action);
        i.putExtra(MusicPlayerActivity.EXTRA_CMD, cmd);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, reqCode, i, flags);
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "0:00";
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void sendCmd(String cmd) {
        try {
            Intent i = new Intent(MusicPlayerActivity.ACTION_CTRL);
            i.putExtra(MusicPlayerActivity.EXTRA_CMD, cmd);
            LocalBroadcastManager.getInstance(this).sendBroadcast(i);
        } catch (Exception ignored) { }
    }
}