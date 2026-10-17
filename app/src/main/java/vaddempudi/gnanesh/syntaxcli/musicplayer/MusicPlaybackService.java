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

    private BroadcastReceiver noisyReceiver;

    public static final String ACTION_UPDATE_NOTIFICATION =
            "vaddempudi.gnanesh.syntaxcli.MUSIC_UPDATE_NOTIF";

    private BroadcastReceiver updateReceiver;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus = false;

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            hasFocus = false;
                            sendCmd(MusicPlayerActivity.CMD_PAUSE);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            sendCmd(MusicPlayerActivity.CMD_PAUSE);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            // Let the user's other audio (nav, call) play over us
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            hasFocus = true;
                            sendCmd(MusicPlayerActivity.CMD_PLAY);
                            break;
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        initMediaSession();
        requestAudioFocus();

        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshNotification();
                syncMediaSession();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction("vaddempudi.gnanesh.syntaxcli.MUSIC_STATE_CHANGED");
        f.addAction("vaddempudi.gnanesh.syntaxcli.MUSIC_TRACK_CHANGED");
        f.addAction(ACTION_UPDATE_NOTIFICATION);
        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver, f);
        noisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (!AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) return;
                sendCmd(MusicPlayerActivity.CMD_PAUSE);
            }
        };
        IntentFilter noisyFilter = new IntentFilter(
                AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(noisyReceiver, noisyFilter);
        startForegroundInternal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null
                && MusicPlayerActivity.ACTION_CTRL.equals(intent.getAction())) {
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            syncMediaSession();
        }
        refreshNotification();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
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
        try {
            if (noisyReceiver != null) unregisterReceiver(noisyReceiver);
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    // ================================================================
    //  MediaSession — the "owner" of hardware buttons
    // ================================================================

    private void initMediaSession() {
        try {
            mediaSession = new MediaSessionCompat(this,
                    "SyntaxCLIMusicSession");
            mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override
                public void onPlay() {
                    sendCmd(MusicPlayerActivity.CMD_PLAY);
                }
                @Override
                public void onPause() {
                    sendCmd(MusicPlayerActivity.CMD_PAUSE);
                }
                @Override
                public void onSkipToNext() {
                    sendCmd(MusicPlayerActivity.CMD_NEXT);
                }
                @Override
                public void onSkipToPrevious() {
                    sendCmd(MusicPlayerActivity.CMD_PREV);
                }
                @Override
                public void onStop() {
                    sendCmd(MusicPlayerActivity.CMD_PAUSE);
                }
            });

            mediaSession.setActive(true);
            syncMediaSession();
        } catch (Throwable t) {
            // MediaSessionCompat should always work, but if it doesn't,
            // the receiver fallback still handles button events.
        }
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
                    .build();
            mediaSession.setMetadata(md);

            long actions = PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_STOP;

            PlaybackStateCompat ps = new PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(playing
                                    ? PlaybackStateCompat.STATE_PLAYING
                                    : PlaybackStateCompat.STATE_PAUSED,
                            PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build();
            mediaSession.setPlaybackState(ps);
        } catch (Exception ignored) { }
    }

    // ================================================================
    //  Audio focus — pauses when a call / nav starts
    // ================================================================

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
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN)
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

    // ================================================================
    //  Notification
    // ================================================================

    private void startForegroundInternal() {
        Notification n = buildNotification();
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

    private void refreshNotification() {
        try {
            Notification n = buildNotification();
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, n);
        } catch (Exception ignored) { }
    }

    private Notification buildNotification() {
        String title = MusicPlayerActivity.getLastPlayedTitle();
        if (title == null || title.isEmpty()) title = "Music Player";

        boolean playing = MusicPlayerActivity.isCurrentlyPlaying();
        String text = playing ? "Playing" : "Paused";

        Intent openIntent = new Intent(this, MusicPlayerActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 0, openIntent, piFlags);

        PendingIntent prevPi   = ctrlPi(MusicPlayerActivity.CMD_PREV, 1);
        PendingIntent togglePi = ctrlPi(MusicPlayerActivity.CMD_TOGGLE, 2);
        PendingIntent nextPi   = ctrlPi(MusicPlayerActivity.CMD_NEXT, 3);

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
            android.support.v4.media.session.MediaSessionCompat.Token token =
                    mediaSession != null ? mediaSession.getSessionToken() : null;
            androidx.media.app.NotificationCompat.MediaStyle style =
                    new androidx.media.app.NotificationCompat.MediaStyle()
                            .setShowActionsInCompactView(0, 1, 2);
            if (token != null) style.setMediaSession(token);
            b.setStyle(style);
        }

        return b.build();
    }

    private PendingIntent ctrlPi(String cmd, int reqCode) {
        Intent i = new Intent(this, MusicPlaybackService.class);
        i.setAction(MusicPlayerActivity.ACTION_CTRL);
        i.putExtra(MusicPlayerActivity.EXTRA_CMD, cmd);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, reqCode, i, flags);
    }

    private void sendCmd(String cmd) {
        try {
            Intent i = new Intent(MusicPlayerActivity.ACTION_CTRL);
            i.putExtra(MusicPlayerActivity.EXTRA_CMD, cmd);
            LocalBroadcastManager.getInstance(this).sendBroadcast(i);
        } catch (Exception ignored) { }
    }
}