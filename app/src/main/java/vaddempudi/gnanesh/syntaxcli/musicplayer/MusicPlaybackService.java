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
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import vaddempudi.gnanesh.syntaxcli.R;

public class MusicPlaybackService extends Service {

    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int    NOTIF_ID   = 0xA11CE;

    public static final String ACTION_UPDATE_NOTIFICATION =
            "vaddempudi.gnanesh.syntaxcli.MUSIC_UPDATE_NOTIF";
    public static final String EXTRA_POS_MS  = "pos_ms";
    public static final String EXTRA_DUR_MS  = "dur_ms";


    private boolean resumeOnFocusGain = false;   // was paused *by us due to focus loss*

    public static final String EXTRA_PLAYING = "playing";
    public static final String EXTRA_TITLE   = "title";

    // In-process handle so the activity can update us directly (no broadcast).
    private static volatile MusicPlaybackService sInstance = null;
    public static void updateState(boolean isPlaying, String title, long posMs, long durMs) {
        MusicPlaybackService s = sInstance;
        if (s == null) return;
        s.playing  = isPlaying;
        s.curTitle = (title == null) ? "" : title;
        s.curPosMs = posMs;
        s.curDurMs = durMs;
        if (isPlaying) {
            s.requestAudioFocus();
        }
        s.refreshNotification(null);
        s.syncMediaSession();
    }

    // authoritative mirror owned by THIS (the :music) process
    private volatile boolean playing  = false;
    private volatile String  curTitle = "";
    private volatile long    curPosMs = 0L;
    private volatile long    curDurMs = 0L;

    public static volatile boolean userStoppedPlayback = false;

    private static final int RC_PREV   = 0x1001;
    private static final int RC_TOGGLE = 0x1002;
    private static final int RC_NEXT   = 0x1003;

    private BroadcastReceiver updateReceiver;
    private BroadcastReceiver noisyReceiver;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus = false;
    private boolean pausedForTransientLoss = false;

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        createChannel();
        initMediaSession();

        // Pause when the user unplugs headphones / disconnects bluetooth.
        noisyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                    // Pause playback and stop auto-resume.
                    resumeOnFocusGain = false;
                    sendCmd(MusicPlayerActivity.CMD_PAUSE);
                    abandonAudioFocus();
                }
            }
        };
        IntentFilter nf = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, nf, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, nf);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = (intent != null)
                ? intent.getStringExtra(MusicPlayerActivity.EXTRA_CMD) : null;

        startForegroundInternal();

        if (cmd != null) {
            dispatchCmd(cmd);
        }
        return START_NOT_STICKY;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        resumeOnFocusGain = false;
        try { sendCmd(MusicPlayerActivity.CMD_PAUSE); } catch (Exception ignored) { }
        try { if (mediaSession != null) mediaSession.setActive(false); }
        catch (Exception ignored) { }
        abandonAudioFocus();
        stopForeground(true);
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception ignored) { }
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        sInstance = null;
        try { if (noisyReceiver != null) unregisterReceiver(noisyReceiver); }
        catch (Exception ignored) { }
        try {
            if (mediaSession != null) {
                mediaSession.setActive(false);
                mediaSession.release();
                mediaSession = null;
            }
        } catch (Exception ignored) { }
        abandonAudioFocus();
        try { stopForeground(true); } catch (Exception ignored) { }
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void dispatchCmd(String cmd) {
        if (cmd == null) return;

        if (MusicPlayerActivity.alive) {
            sendCmd(cmd);
            // Do NOT stopSelf — the notification lives here.
        } else {
            try {
                Intent open = new Intent(this, MusicPlayerActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                open.putExtra(MusicPlayerActivity.EXTRA_CMD, cmd);
                startActivity(open);
            } catch (Exception ignored) { }
        }
    }

    private void initMediaSession() {
        try {
            mediaSession = new MediaSessionCompat(this, "SyntaxCLIMusicSession");

            // Do NOT set FLAG_HANDLES_MEDIA_BUTTONS and do NOT call
            // setMediaButtonReceiver(). We route physical media buttons
            // exclusively through the manifest MusicButtonReceiver so its
            // multi-tap debounce isn't short-circuited by the session.
            mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override public void onPlay()   { sendCmd(MusicPlayerActivity.CMD_PLAY); }
                @Override public void onPause()  { sendCmd(MusicPlayerActivity.CMD_PAUSE); }
                @Override public void onSkipToNext() { sendCmd(MusicPlayerActivity.CMD_NEXT); }
                @Override public void onSkipToPrevious() { sendCmd(MusicPlayerActivity.CMD_PREV); }
                @Override public void onStop()   { sendCmd(MusicPlayerActivity.CMD_PAUSE); }
                @Override public void onSeekTo(long pos) {
                    try { MusicPlayerActivity.requestSeek(pos); } catch (Exception ignored) { }
                }
            });

            mediaSession.setActive(true);
            syncMediaSession();
        } catch (Throwable ignored) { }
    }
    private PendingIntent buildMediaButtonPendingIntent() {
        Intent mbIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mbIntent.setClass(this, MusicButtonReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(this, 0, mbIntent, flags);
    }

    private void syncMediaSession() {
        if (mediaSession == null) return;
        try {
            String title = curTitle == null ? "" : curTitle;

            MediaMetadataCompat md = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, curDurMs)
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
                            curPosMs,
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

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int focusChange) {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            // Permanent loss — another app took over.
                            hasFocus = false;
                            resumeOnFocusGain = false;
                            sendCmd(MusicPlayerActivity.CMD_PAUSE);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            // Phone call, navigation prompt, etc. — pause.
                            hasFocus = false;
                            // Remember whether we were playing so we can resume
                            // ONLY if the user didn't explicitly pause.
                            resumeOnFocusGain = playing;
                            if (playing) sendCmd(MusicPlayerActivity.CMD_PAUSE);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            hasFocus = true;
                            if (resumeOnFocusGain && !userStoppedPlayback) {
                                resumeOnFocusGain = false;
                                sendCmd(MusicPlayerActivity.CMD_PLAY);
                            } else {
                                resumeOnFocusGain = false;
                            }
                            break;
                    }
                }
            };

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
        try {
            Notification n = buildNotification(null);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // If this throws on Android 12+, it means the FGS type isn't granted
            // in Settings → Special app access → Media playback.
            // Nothing we can do programmatically; just bail without ANR.
            try { stopSelf(); } catch (Exception ignored) { }
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
        String title = curTitle;
        if (title == null || title.isEmpty()) title = "Music Player";

        boolean isPlaying = playing;
        String text = isPlaying ? "Playing" : "Paused";

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
                .addAction(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_circle,
                        isPlaying ? "Pause" : "Play", togglePi)
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
        Intent i = new Intent(this, MusicPlayerActivity.class);
        i.setAction(action);
        i.putExtra(MusicPlayerActivity.EXTRA_CMD, cmd);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, reqCode, i, flags);
    }

    public static void requestFocusNow(MusicPlaybackService svc) {
        if (svc != null) svc.requestAudioFocus();
    }

    private void sendCmd(String cmd) {
        if (cmd == null) return;
        try {
            Intent i = new Intent(MusicPlayerActivity.ACTION_CTRL);
            i.setPackage(getPackageName());
            i.putExtra(MusicPlayerActivity.EXTRA_CMD, cmd);
            sendBroadcast(i);
        } catch (Exception ignored) { }
    }
}