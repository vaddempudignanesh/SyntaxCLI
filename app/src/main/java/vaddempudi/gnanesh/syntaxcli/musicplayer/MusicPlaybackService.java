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
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import vaddempudi.gnanesh.syntaxcli.R;

public class MusicPlaybackService extends Service {

    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int    NOTIF_ID   = 0xA11CE;

    public static final String ACTION_UPDATE_NOTIFICATION =
            "vaddempudi.gnanesh.syntaxcli.MUSIC_UPDATE_NOTIF";

    private BroadcastReceiver updateReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshNotification();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction("vaddempudi.gnanesh.syntaxcli.MUSIC_STATE_CHANGED");
        f.addAction("vaddempudi.gnanesh.syntaxcli.MUSIC_TRACK_CHANGED");
        f.addAction(ACTION_UPDATE_NOTIFICATION);
        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver, f);

        startForegroundInternal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null
                && MusicPlayerActivity.ACTION_CTRL.equals(intent.getAction())) {
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
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
        super.onDestroy();
    }

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
            b.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2));
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
}