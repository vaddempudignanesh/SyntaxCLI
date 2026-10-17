package vaddempudi.gnanesh.syntaxcli.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class AuraDownloadService extends Service {

    public static final String EXTRA_DOWNLOAD_URL        = "DOWNLOAD_URL";
    public static final String EXTRA_USER_AGENT          = "USER_AGENT";
    public static final String EXTRA_CONTENT_DISPOSITION = "CONTENT_DISPOSITION";
    public static final String EXTRA_MIME_TYPE           = "MIME_TYPE";
    public static final String EXTRA_REFERER             = "REFERER";
    public static final String EXTRA_COOKIES             = "COOKIES";
    public static final String EXTRA_EXTRA_HEADERS       = "EXTRA_HEADERS";

    private static final String CHANNEL_ID           = "AuraDownloadChannel";
    private static final int    BASE_NOTIFICATION_ID = 8810;

    private static final Map<String, NativeEngine>     ENGINES   = new ConcurrentHashMap<>();
    private static final Map<String, DownloadTaskInfo> INFOS     = new ConcurrentHashMap<>();
    private static final Map<String, Integer>          NOTIF_IDS = new ConcurrentHashMap<>();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        createNotificationChannel();

        // ★ Android requires startForeground() within 5s of
        //   startForegroundService(). We MUST call it before anything else,
        //   using the SAME ID that the real per-download notification will
        //   later use, to avoid notification churn / "Cannot find enqueued
        //   record" warnings.
        boolean isControl = AuraDownloadHistory.ACTION_CONTROL.equals(intent.getAction());

        String gid;
        int notifId;
        if (isControl) {
            gid = intent.getStringExtra(AuraDownloadHistory.EXTRA_GID);
            if (gid == null) gid = "control";
            notifId = BASE_NOTIFICATION_ID + Math.abs(gid.hashCode() % 1000);
        } else {
            gid = UUID.randomUUID().toString().substring(0, 8);
            notifId = BASE_NOTIFICATION_ID + Math.abs(gid.hashCode() % 1000);
        }
        NOTIF_IDS.put(gid, notifId);

        // ★ Promote FIRST.
        try {
            Notification placeholder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(isControl ? "Downloads" : "Preparing…")
                    .setContentText("")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build();
            startForeground(notifId, placeholder);
        } catch (Exception ignored) {}

        if (isControl) {
            String action = intent.getStringExtra(AuraDownloadHistory.EXTRA_ACTION);
            handleControl(action, gid);

            // If nothing left, gracefully stop. Otherwise demote but keep
            // the currently posted real notifications visible.
            if (ENGINES.isEmpty()) {
                stopForeground(false);
                stopSelf(startId);
            } else {
                stopForeground(false);
            }
            return START_STICKY;
        }

        String urlString = intent.getStringExtra(EXTRA_DOWNLOAD_URL);
        if (urlString == null) {
            stopForeground(false);
            stopSelf(startId);
            return START_STICKY;
        }

        String userAgent          = intent.getStringExtra(EXTRA_USER_AGENT);
        String contentDisposition = intent.getStringExtra(EXTRA_CONTENT_DISPOSITION);
        String mimeType           = intent.getStringExtra(EXTRA_MIME_TYPE);
        String forcedFilename = intent.getStringExtra("FORCED_FILENAME");
        String referer            = intent.getStringExtra(EXTRA_REFERER);
        String cookies            = intent.getStringExtra(EXTRA_COOKIES);
        String extraHeaders       = intent.getStringExtra(EXTRA_EXTRA_HEADERS);

        if (cookies == null || cookies.isEmpty()) {
            try { cookies = CookieManager.getInstance().getCookie(urlString); }
            catch (Exception ignored) {}
        }


        String fileName = (forcedFilename != null && !forcedFilename.isEmpty())
                ? SafeFileName.sanitizeOnly(forcedFilename)
                : SafeFileName.derive(urlString, contentDisposition, mimeType);
        final String finalGid          = gid;
        final int    finalNotifId      = notifId;
        final String finalUrl          = urlString;
        final String finalUserAgent    = userAgent;
        final String finalCd           = contentDisposition;
        final String finalMime         = mimeType;
        final String finalReferer      = referer;
        final String finalCookies      = cookies;
        final String finalExtraHeaders = extraHeaders;
        final String finalFileName     = fileName;

        // Re-post with the real filename — SAME notifId, no churn.
        startForegroundWithId(finalNotifId, finalFileName);

        new Thread(() -> {
            try {
                initializeDownload(finalGid, finalUrl, finalUserAgent, finalCd,
                        finalMime, finalReferer, finalFileName, finalNotifId,
                        finalCookies, finalExtraHeaders, forcedFilename);
            } catch (Throwable t) {
                Log.e("AuraDL", "Init crashed", t);
                try {
                    JSONObject o = new JSONObject();
                    o.put("gid", finalGid);
                    o.put("name", finalFileName);
                    o.put("status", "error");
                    o.put("errorMessage", t.getMessage() != null ? t.getMessage() : "Init crashed");
                    AuraDownloadHistory.get(this).upsert(o);
                    AuraDownloadHistory.get(this).flush();
                } catch (Exception ignored) {}
            }
        }).start();

        return START_STICKY;
    }

    private void startForegroundWithId(int notifId, String fileName) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(fileName)
                .setContentText("Connecting…")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        try { startForeground(notifId, n); } catch (Exception ignored) {}
    }

    private void handleControl(String action, String gid) {
        if (action == null || gid == null) return;

        AuraDownloadHistory history = AuraDownloadHistory.get(this);

        try {
            switch (action) {
                case "pause": {
                    NativeEngine engine = ENGINES.get(gid);
                    if (engine != null) {
                        engine.pause();
                    } else {
                        JSONObject o = history.get(gid);
                        if (o != null) {
                            o.put("status", "paused");
                            o.put("downloadSpeed", "0");
                            o.put("etaMs", 0);
                            history.upsert(o);
                            history.flush();
                            updateNotification(gid, "paused",
                                    o.optLong("completedLength", 0), 0,
                                    o.optString("name", "file"),
                                    o.optLong("elapsedMs", 0), 0);
                        }
                    }
                    break;
                }
                case "resume": {
                    if (ENGINES.containsKey(gid)) break;

                    DownloadTaskInfo info = INFOS.get(gid);
                    if (info == null) {
                        JSONObject o = history.get(gid);
                        if (o != null) {
                            String savePath = o.optString("savePath", "");
                            if (!savePath.isEmpty()) {
                                info = DownloadTaskInfo.loadState(savePath + ".state");
                                if (info != null) INFOS.put(gid, info);
                            }
                        }
                    }

                    if (info != null) {
                        // ★ FIX: verify the target file still matches the
                        //   recorded downloadedBytes. If not, the file was
                        //   externally modified/deleted — refuse to resume
                        //   (would corrupt), and mark error.
                        if (!verifyFileIntact(info)) {
                            markError(history, gid, info.savePath,
                                    info.totalSize, 0,
                                    "Downloaded file changed — cannot resume");
                            continueOrCleanup(startIdFromGid(gid));
                            break;
                        }
                        try {
                            startEngineFor(info);
                        } catch (Throwable t) {
                            Log.e("AuraDL", "Resume failed", t);
                            markError(history, gid, info.savePath,
                                    info.totalSize, info.totalDownloaded(),
                                    "Resume failed: " + t.getMessage());
                        }
                    } else {
                        JSONObject o = history.get(gid);
                        if (o != null) {
                            o.put("status", "error");
                            o.put("errorMessage", "Cannot resume — state file missing");
                            history.upsert(o);
                            history.flush();
                            updateNotification(gid, "error",
                                    o.optLong("completedLength", 0), 0,
                                    o.optString("name", "file"),
                                    o.optLong("elapsedMs", 0), 0);
                        }
                    }
                    break;
                }
                case "remove": {
                    NativeEngine engine = ENGINES.remove(gid);
                    if (engine != null) engine.pause();
                    DownloadTaskInfo info = INFOS.remove(gid);
                    if (info != null) {
                        try { new File(info.savePath).delete(); } catch (Exception ignored) {}
                        try { new File(info.savePath + ".state").delete(); } catch (Exception ignored) {}
                    } else {
                        JSONObject o = history.get(gid);
                        if (o != null) {
                            String savePath = o.optString("savePath", "");
                            if (!savePath.isEmpty()) {
                                try { new File(savePath).delete(); } catch (Exception ignored) {}
                                try { new File(savePath + ".state").delete(); } catch (Exception ignored) {}
                            }
                        }
                    }
                    history.removeLocal(gid);
                    Integer nid = NOTIF_IDS.remove(gid);
                    if (nid != null) {
                        NotificationManager nm = getSystemService(NotificationManager.class);
                        if (nm != null) nm.cancel(nid);
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e("AuraDL", "handleControl failed", t);
        }
    }

    // stub used by resume error path; keeps code readable
    private int startIdFromGid(String gid) { return 0; }

    private void continueOrCleanup(int startId) {
        if (ENGINES.isEmpty()) {
            stopForeground(false);
            stopSelf();
        }
    }

    /** True if target file exists and its length matches recorded progress. */
    private boolean verifyFileIntact(DownloadTaskInfo info) {
        try {
            File f = new File(info.savePath);
            if (!f.exists()) return false;
            long expected = info.totalDownloaded();
            if (expected <= 0) return true;   // nothing downloaded yet — fine
            return f.length() >= expected;
        } catch (Exception e) {
            return false;
        }
    }

    private void markError(AuraDownloadHistory history, String gid, String savePath,
                           long total, long completed, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("gid", gid);
            o.put("savePath", savePath);
            o.put("status", "error");
            o.put("errorMessage", msg);
            o.put("totalLength", total);
            o.put("completedLength", completed);
            o.put("downloadSpeed", "0");
            o.put("etaMs", 0);
            history.upsert(o);
            history.flush();
            updateNotification(gid, "error", completed, 0,
                    new File(savePath).getName(), 0, 0);
        } catch (Exception ignored) {}
    }

    private void initializeDownload(String gid, String urlString, String userAgent,
                                    String contentDisposition, String mimeType,
                                    String referer, String fileName, int notifId,
                                    String cookies, String extraHeaders,
                                    String forcedFilename) {
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) downloadDir.mkdirs();

            File targetFile = new File(downloadDir, fileName);
            String fullSavePath = targetFile.getAbsolutePath();

            long remoteSize = 0;
            boolean acceptsRanges = false;
            String finalUrl = urlString;
            String finalMime = mimeType;

            // ── Probe ──
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                if (userAgent != null && !userAgent.isEmpty())
                    conn.setRequestProperty("User-Agent", userAgent);
                if (referer != null && !referer.isEmpty())
                    conn.setRequestProperty("Referer", referer);
                if (cookies != null && !cookies.isEmpty())
                    conn.setRequestProperty("Cookie", cookies);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                conn.setRequestProperty("Connection", "keep-alive");

                if (extraHeaders != null) {
                    for (String line : extraHeaders.split("\n")) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            conn.setRequestProperty(
                                    line.substring(0, colon).trim(),
                                    line.substring(colon + 1).trim());
                        }
                    }
                }

                conn.connect();
                int code = conn.getResponseCode();

                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) {
                        if (!loc.startsWith("http")) {
                            loc = new URL(new URL(urlString), loc).toString();
                        }
                        finalUrl = loc;
                        conn.disconnect();
                        conn = (HttpURLConnection) new URL(finalUrl).openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(15000);
                        if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
                        if (referer != null) conn.setRequestProperty("Referer", referer);
                        if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                        conn.connect();
                        code = conn.getResponseCode();
                    }
                }

                remoteSize = conn.getContentLengthLong();
                acceptsRanges = "bytes".equalsIgnoreCase(
                        conn.getHeaderField("Accept-Ranges"));

                // ★ Only re-derive the name if we still had a placeholder name.
                // ★ Only re-derive when the user did not pick a name.
                String cd = conn.getHeaderField("Content-Disposition");
                if (forcedFilename == null && cd != null && !cd.isEmpty()
                        && fileName.startsWith("download_")) {
                    String derived = SafeFileName.derive(finalUrl, cd, finalMime);
                    if (derived != null && !derived.isEmpty()) {
                        fileName = derived;
                        targetFile = new File(downloadDir, fileName);
                        fullSavePath = targetFile.getAbsolutePath();
                    }
                }

                String ct = conn.getHeaderField("Content-Type");
                if (ct != null) {
                    int semi = ct.indexOf(';');
                    if (semi > 0) ct = ct.substring(0, semi).trim();
                    finalMime = ct;
                }

                conn.disconnect();
            } catch (Exception e) {
                Log.w("AuraDL", "probe failed: " + e.getMessage());
            }

            // ── Load or create state ──
            String statePath = fullSavePath + ".state";
            DownloadTaskInfo info = DownloadTaskInfo.loadState(statePath);

            if (info != null && !verifyFileIntact(info)) {
                // State file exists but file was modified/deleted.
                try { new File(statePath).delete(); } catch (Exception ignored) {}
                info = null;
            }

            if (info == null) {
                int threads = (acceptsRanges && remoteSize > 2 * 1024 * 1024) ? 4 : 1;
                info = new DownloadTaskInfo(gid, finalUrl, fullSavePath,
                        remoteSize, threads);
                info.userAgent = userAgent;
                info.referer = referer;
                info.cookies = cookies;
                info.extraHeaders = extraHeaders;
                info.mimeType = finalMime;

                if (threads > 1) {
                    long seg = remoteSize / threads;
                    for (int i = 0; i < threads; i++) {
                        info.startBytes[i] = i * seg;
                        info.endBytes[i] = (i == threads - 1)
                                ? remoteSize - 1
                                : info.startBytes[i] + seg - 1;
                        info.downloadedBytes[i] = 0;
                    }
                } else {
                    info.startBytes[0] = 0;
                    info.endBytes[0] = remoteSize > 0 ? remoteSize - 1 : Long.MAX_VALUE - 1;
                    info.downloadedBytes[0] = 0;
                }

                if (remoteSize > 1024 * 1024) {
                    try {
                        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(targetFile, "rw");
                        raf.setLength(remoteSize);
                        raf.close();
                    } catch (Exception e) {
                        Log.w("AuraDL", "pre-alloc failed: " + e.getMessage());
                    }
                }
                info.saveState(statePath);
            }

            INFOS.put(info.gid, info);
            NOTIF_IDS.put(info.gid, notifId);

            JSONObject job = new JSONObject();
            job.put("gid", info.gid);
            job.put("name", fileName);
            job.put("savePath", info.savePath);
            job.put("url", info.url);
            job.put("status", "active");
            job.put("totalLength", info.totalSize);
            job.put("completedLength", info.totalDownloaded());
            job.put("downloadSpeed", "0");
            AuraDownloadHistory.get(this).upsert(job);
            AuraDownloadHistory.get(this).flush();

            // ★ Update notification title with the final derived name
            startForegroundWithId(notifId, fileName);

            startEngineFor(info);

        } catch (Exception e) {
            Log.e("AuraDL", "initializeDownload failed", e);
            try {
                JSONObject o = new JSONObject();
                o.put("gid", gid);
                o.put("name", fileName);
                o.put("status", "error");
                o.put("errorMessage", e.getMessage() != null ? e.getMessage() : "Init failed");
                AuraDownloadHistory.get(this).upsert(o);
                AuraDownloadHistory.get(this).flush();
            } catch (Exception ignored) {}
        }
    }

    private void startEngineFor(final DownloadTaskInfo info) {
        final String fileName = new File(info.savePath).getName();

        NativeEngine engine = new NativeEngine(info,
                new NativeEngine.DownloadProgressListener() {

                    @Override
                    public void onStarted(long totalSize) {
                        updateNotification(info.gid, "active",
                                info.totalDownloaded(), 0, fileName,
                                info.elapsedMs, 0);
                    }

                    @Override
                    public void onProgress(long totalDownloaded, long totalSize,
                                           long bytesPerSec, long elapsedMs, long etaMs) {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("gid", info.gid);
                            o.put("name", fileName);
                            o.put("savePath", info.savePath);
                            o.put("url", info.url);
                            o.put("status", "active");
                            o.put("totalLength", totalSize);
                            o.put("completedLength", totalDownloaded);
                            o.put("downloadSpeed", String.valueOf(bytesPerSec));
                            o.put("elapsedMs", elapsedMs);
                            o.put("etaMs", etaMs);
                            AuraDownloadHistory.get(AuraDownloadService.this).upsert(o);
                        } catch (Exception ignored) {}
                        updateNotification(info.gid, "active",
                                totalDownloaded, bytesPerSec, fileName,
                                elapsedMs, etaMs);
                    }

                    @Override
                    public void onComplete(long elapsedMs) {
                        ENGINES.remove(info.gid);
                        INFOS.remove(info.gid);

                        try {
                            JSONObject o = new JSONObject();
                            o.put("gid", info.gid);
                            o.put("name", fileName);
                            o.put("savePath", info.savePath);
                            o.put("url", info.url);
                            o.put("status", "complete");
                            o.put("totalLength", info.totalSize > 0 ? info.totalSize : info.totalDownloaded());
                            o.put("completedLength", info.totalDownloaded());
                            o.put("downloadSpeed", "0");
                            o.put("elapsedMs", elapsedMs);
                            o.put("etaMs", 0);
                            AuraDownloadHistory.get(AuraDownloadService.this).upsert(o);
                            AuraDownloadHistory.get(AuraDownloadService.this).flush();
                        } catch (Exception ignored) {}

                        updateNotification(info.gid, "complete",
                                info.totalSize, 0, fileName, elapsedMs, 0);

                        if (ENGINES.isEmpty()) {
                            stopForeground(false);
                            stopSelf();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        // ★ Only the FIRST worker to report an error wins.
                        if (ENGINES.remove(info.gid) == null) return;
                        try {
                            JSONObject o = new JSONObject();
                            o.put("gid", info.gid);
                            o.put("name", fileName);
                            o.put("savePath", info.savePath);
                            o.put("url", info.url);
                            o.put("status", "error");
                            o.put("errorMessage", message);
                            o.put("totalLength", info.totalSize);
                            o.put("completedLength", info.totalDownloaded());
                            o.put("downloadSpeed", "0");
                            o.put("elapsedMs", info.elapsedMs);
                            o.put("etaMs", 0);
                            AuraDownloadHistory.get(AuraDownloadService.this).upsert(o);
                            AuraDownloadHistory.get(AuraDownloadService.this).flush();
                        } catch (Exception ignored) {}

                        updateNotification(info.gid, "error",
                                info.totalDownloaded(), 0, fileName,
                                info.elapsedMs, 0);

                        if (ENGINES.isEmpty()) {
                            stopForeground(false);
                            stopSelf();
                        }
                    }

                    @Override
                    public void onPaused() {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("gid", info.gid);
                            o.put("name", fileName);
                            o.put("savePath", info.savePath);
                            o.put("url", info.url);
                            o.put("status", "paused");
                            o.put("totalLength", info.totalSize);
                            o.put("completedLength", info.totalDownloaded());
                            o.put("downloadSpeed", "0");
                            o.put("elapsedMs", info.elapsedMs);
                            o.put("etaMs", 0);
                            AuraDownloadHistory.get(AuraDownloadService.this).upsert(o);
                            AuraDownloadHistory.get(AuraDownloadService.this).flush();
                        } catch (Exception ignored) {}

                        ENGINES.remove(info.gid);
                        updateNotification(info.gid, "paused",
                                info.totalDownloaded(), 0, fileName,
                                info.elapsedMs, 0);
                    }
                });

        ENGINES.put(info.gid, engine);
        engine.start();
    }

    private int notificationIdFor(String gid) {
        Integer id = NOTIF_IDS.get(gid);
        if (id != null) return id;
        int newId = BASE_NOTIFICATION_ID + Math.abs(gid.hashCode() % 1000);
        NOTIF_IDS.put(gid, newId);
        return newId;
    }

    private void updateNotification(String gid, String status,
                                    long downloaded, long speed, String fileName,
                                    long elapsedMs, long etaMs) {
        try {
            JSONObject o = AuraDownloadHistory.get(this).get(gid);
            long total = o != null ? o.optLong("totalLength", 0) : 0;
            if (total <= 0) total = downloaded;

            int pct = total > 0 ? (int) ((downloaded * 100) / total) : 0;

            int smallIcon;
            String contentText;
            boolean ongoing;

            switch (status) {
                case "active":
                    smallIcon = android.R.drawable.stat_sys_download;
                    contentText = pct + "%  •  " + humanSpeed(speed)
                            + "  •  " + humanBytes(downloaded) + " / " + humanBytes(total)
                            + "  •  " + formatElapsed(elapsedMs)
                            + " left " + formatEta(etaMs);
                    ongoing = true;
                    break;
                case "paused":
                    smallIcon = android.R.drawable.ic_media_pause;
                    contentText = "Paused  •  " + pct + "%  •  "
                            + humanBytes(downloaded) + " / " + humanBytes(total)
                            + "  •  " + formatElapsed(elapsedMs);
                    ongoing = false;
                    break;
                case "complete":
                    smallIcon = android.R.drawable.stat_sys_download_done;
                    contentText = "Completed in " + formatElapsed(elapsedMs)
                            + "  •  " + humanBytes(total);
                    ongoing = false;
                    break;
                default:
                    smallIcon = android.R.drawable.stat_notify_error;
                    String errMsg = o != null ? o.optString("errorMessage", "Network error") : "Error";
                    contentText = "Stopped  •  " + errMsg
                            + "  •  " + formatElapsed(elapsedMs);
                    ongoing = false;
                    break;
            }

            NotificationCompat.Builder b =
                    new NotificationCompat.Builder(this, CHANNEL_ID)
                            .setSmallIcon(smallIcon)
                            .setContentTitle(fileName)
                            .setContentText(contentText)
                            .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setOngoing(ongoing)
                            .setOnlyAlertOnce(true)
                            .setShowWhen(false);

            if (("active".equals(status) || "paused".equals(status)) && total > 0) {
                b.setProgress(100, pct, false);
            }

            switch (status) {
                case "active":
                    b.addAction(action("Pause", "pause", gid));
                    b.addAction(action("Cancel", "remove", gid));
                    break;
                case "paused":
                case "error":
                    b.addAction(action("Resume", "resume", gid));
                    b.addAction(action("Cancel", "remove", gid));
                    break;
                case "complete":
                    NotificationCompat.Action openAct =
                            openAction(o != null ? o.optString("savePath", "") : "");
                    if (openAct != null) b.addAction(openAct);
                    break;
            }

            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(notificationIdFor(gid), b.build());

        } catch (Exception ignored) {}
    }

    private String formatElapsed(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(java.util.Locale.US, "%d:%02d", m, sec);
    }

    private String formatEta(long ms) {
        if (ms <= 0) return "—";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        long h = m / 60;
        return h + "h " + (m % 60) + "m";
    }

    private NotificationCompat.Action action(String label, String cmd, String gid) {
        Intent i = new Intent(this, AuraDownloadService.class);
        i.setAction(AuraDownloadHistory.ACTION_CONTROL);
        i.putExtra(AuraDownloadHistory.EXTRA_ACTION, cmd);
        i.putExtra(AuraDownloadHistory.EXTRA_GID, gid);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pi = PendingIntent.getForegroundService(
                    this, (cmd + gid).hashCode(), i, flags);
        } else {
            pi = PendingIntent.getService(
                    this, (cmd + gid).hashCode(), i, flags);
        }

        int icon;
        switch (cmd) {
            case "pause":  icon = android.R.drawable.ic_media_pause; break;
            case "resume": icon = android.R.drawable.ic_media_play;  break;
            case "remove": icon = android.R.drawable.ic_menu_close_clear_cancel; break;
            default:       icon = android.R.drawable.ic_menu_more; break;
        }
        return new NotificationCompat.Action(icon, label, pi);
    }

    private NotificationCompat.Action openAction(String savePath) {
        try {
            File f = new File(savePath);
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(
                    this, savePath.hashCode(), i, flags);
            return new NotificationCompat.Action(
                    android.R.drawable.ic_menu_view, "Open", pi);
        } catch (Exception e) {
            return null;
        }
    }

    private String humanSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB/s",
                bytesPerSec / (1024.0 * 1024.0));
    }

    private String humanBytes(long b) {
        if (b <= 0) return "0 B";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024L * 1024L)
            return String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
        return String.format(java.util.Locale.US, "%.2f GB",
                b / (1024.0 * 1024.0 * 1024.0));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        for (NativeEngine engine : ENGINES.values()) engine.pause();
        ENGINES.clear();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}