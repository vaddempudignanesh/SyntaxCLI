package vaddempudi.gnanesh.syntaxcli.browser;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class NativeEngine {
    private static final String TAG = "AURA-NATIVE-DL";

    private final DownloadTaskInfo taskInfo;
    private final String stateFilePath;
    private ExecutorService executor;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final DownloadProgressListener listener;

    private final AtomicLong rawBytesThisTick = new AtomicLong(0);
    private static final double ALPHA = 0.25;
    private volatile double smoothedSpeed = 0.0;

    private long startTimeMs = 0L;
    private long accumulatedMs = 0L;

    private Thread tickerThread;

    private long lastStateSaveMs = 0L;
    private static final long STATE_SAVE_INTERVAL_MS = 3000L;

    public interface DownloadProgressListener {
        void onProgress(long totalDownloaded, long totalSize, long bytesPerSec,
                        long elapsedMs, long etaMs);
        void onComplete(long elapsedMs);
        void onError(String message);
        void onPaused();
        void onStarted(long totalSize);
    }

    public NativeEngine(DownloadTaskInfo taskInfo, DownloadProgressListener listener) {
        this.taskInfo = taskInfo;
        this.stateFilePath = taskInfo.savePath + ".state";
        this.listener = listener;
        this.accumulatedMs = taskInfo.elapsedMs;
    }

    public DownloadTaskInfo getTaskInfo() {
        return taskInfo;
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void start() {
        paused.set(false);
        finished.set(false);
        failed.set(false);
        rawBytesThisTick.set(0);
        smoothedSpeed = 0.0;
        startTimeMs = System.currentTimeMillis();
        lastStateSaveMs = startTimeMs;

        executor = Executors.newFixedThreadPool(Math.max(1, taskInfo.threadCount));

        try { listener.onStarted(taskInfo.totalSize); } catch (Exception ignored) {}

        for (int i = 0; i < taskInfo.threadCount; i++) {
            executor.execute(new DownloadWorker(i));
        }

        tickerThread = new Thread(this::tickerLoop, "aura-dl-ticker");
        tickerThread.setDaemon(true);
        tickerThread.start();

        new Thread(() -> {
            try {
                executor.shutdown();
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

                if (tickerThread != null) tickerThread.interrupt();

                long sessionMs = System.currentTimeMillis() - startTimeMs;
                long totalElapsed = accumulatedMs + sessionMs;
                taskInfo.elapsedMs = totalElapsed;

                if (paused.get()) {
                    try { taskInfo.saveState(stateFilePath); } catch (Exception ignored) {}
                    listener.onPaused();
                    return;
                }

                if (failed.get() || taskInfo.hadFailure) {
                    return;
                }

                if (!taskInfo.isComplete()) {
                    listener.onError("Download incomplete: "
                            + taskInfo.totalDownloaded()
                            + " / " + taskInfo.totalSize
                            + " bytes");
                    return;
                }

                long advertised = probeContentLength(taskInfo.url);
                if (advertised > 0 && taskInfo.totalSize > 0
                        && advertised != taskInfo.totalSize) {
                    listener.onError("Remote size changed ("
                            + advertised + " vs " + taskInfo.totalSize + ")");
                    return;
                }

                File stateFile = new File(stateFilePath);
                if (stateFile.exists()) stateFile.delete();

                if (!finished.getAndSet(true)) {
                    listener.onComplete(totalElapsed);
                }
            } catch (InterruptedException ignored) {}
        }, "aura-dl-watchdog").start();
    }

    private long probeContentLength(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.connect();
            long len = c.getContentLengthLong();
            c.disconnect();
            return len;
        } catch (Exception e) {
            return -1L;
        }
    }

    private void tickerLoop() {
        long prevTime = System.currentTimeMillis();

        while (!paused.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                return;
            }
            if (paused.get()) return;

            long now = System.currentTimeMillis();
            long elapsed = now - prevTime;
            prevTime = now;

            long deltaBytes = rawBytesThisTick.getAndSet(0);
            double instantSpeed = elapsed > 0
                    ? (deltaBytes * 1000.0) / elapsed
                    : 0.0;

            smoothedSpeed = ALPHA * instantSpeed + (1.0 - ALPHA) * smoothedSpeed;

            long reported = (long) smoothedSpeed;
            if (reported < 512 && deltaBytes == 0) reported = 0;

            long sessionElapsed = now - startTimeMs;
            long totalElapsed = accumulatedMs + sessionElapsed;

            long downloaded = taskInfo.totalDownloaded();
            long remaining = taskInfo.totalSize > 0
                    ? Math.max(0, taskInfo.totalSize - downloaded) : 0;
            long etaMs = 0L;
            if (reported > 0 && remaining > 0) {
                etaMs = (long) ((remaining * 1000.0) / reported);
            }

            try {
                listener.onProgress(downloaded,
                        taskInfo.totalSize > 0 ? taskInfo.totalSize : downloaded,
                        reported, totalElapsed, etaMs);
            } catch (Exception ignored) {}

            if (now - lastStateSaveMs >= STATE_SAVE_INTERVAL_MS) {
                lastStateSaveMs = now;
                taskInfo.elapsedMs = totalElapsed;
                try { taskInfo.saveState(stateFilePath); } catch (Exception ignored) {}
            }
        }
    }

    public void pause() {
        if (paused.getAndSet(true)) return;
        long sessionMs = System.currentTimeMillis() - startTimeMs;
        taskInfo.elapsedMs = accumulatedMs + sessionMs;
        if (tickerThread != null) tickerThread.interrupt();
        if (executor != null) executor.shutdownNow();
        try { taskInfo.saveState(stateFilePath); } catch (Exception ignored) {}
    }

    private void recordBytes(long bytes) {
        rawBytesThisTick.addAndGet(bytes);
    }

    private class DownloadWorker implements Runnable {
        private final int threadId;

        DownloadWorker(int threadId) {
            this.threadId = threadId;
        }

        @Override
        public void run() {
            HttpURLConnection connection = null;
            RandomAccessFile fileAccessor = null;
            BufferedInputStream in = null;

            try {
                long actualStart = taskInfo.startBytes[threadId]
                        + taskInfo.downloadedBytes[threadId];
                long end = taskInfo.endBytes[threadId];

                if (actualStart > end && end != Long.MAX_VALUE - 1) return;

                URL url = new URL(taskInfo.url);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);

                String rangeHeader;
                if (end == Long.MAX_VALUE - 1) {
                    rangeHeader = "bytes=" + actualStart + "-";
                } else {
                    rangeHeader = "bytes=" + actualStart + "-" + end;
                }
                connection.setRequestProperty("Range", rangeHeader);

                if (actualStart > 0) {
                    if (taskInfo.etag != null && !taskInfo.etag.isEmpty()) {
                        connection.setRequestProperty("If-Range", taskInfo.etag);
                    } else if (taskInfo.lastModified != null
                            && !taskInfo.lastModified.isEmpty()) {
                        connection.setRequestProperty("If-Range", taskInfo.lastModified);
                    }
                }

                if (taskInfo.userAgent != null && !taskInfo.userAgent.isEmpty()) {
                    connection.setRequestProperty("User-Agent", taskInfo.userAgent);
                }
                if (taskInfo.referer != null && !taskInfo.referer.isEmpty()) {
                    connection.setRequestProperty("Referer", taskInfo.referer);
                }
                if (taskInfo.cookies != null && !taskInfo.cookies.isEmpty()) {
                    connection.setRequestProperty("Cookie", taskInfo.cookies);
                }
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

                if (taskInfo.extraHeaders != null && !taskInfo.extraHeaders.isEmpty()) {
                    for (String line : taskInfo.extraHeaders.split("\n")) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            connection.setRequestProperty(
                                    line.substring(0, colon).trim(),
                                    line.substring(colon + 1).trim());
                        }
                    }
                }

                connection.connect();

                int code = connection.getResponseCode();

                int redirects = 0;
                while ((code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == 307 || code == 308) && redirects < 5) {
                    redirects++;
                    String loc = connection.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) break;
                    if (!loc.startsWith("http")) {
                        loc = new URL(url, loc).toString();
                    }
                    connection.disconnect();
                    url = new URL(loc);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(30000);
                    connection.setRequestProperty("Range", rangeHeader);
                    if (taskInfo.userAgent != null)
                        connection.setRequestProperty("User-Agent", taskInfo.userAgent);
                    if (taskInfo.referer != null)
                        connection.setRequestProperty("Referer", taskInfo.referer);
                    if (taskInfo.cookies != null)
                        connection.setRequestProperty("Cookie", taskInfo.cookies);
                    connection.connect();
                    code = connection.getResponseCode();
                }

                if (code != HttpURLConnection.HTTP_PARTIAL
                        && code != HttpURLConnection.HTTP_OK) {
                    if (!paused.get()) {
                        failed.set(true);
                        taskInfo.hadFailure = true;
                        listener.onError("HTTP " + code);
                    }
                    return;
                }

                if (code == HttpURLConnection.HTTP_OK && taskInfo.threadCount > 1
                        && actualStart > 0) {
                    if (!paused.get()) {
                        failed.set(true);
                        taskInfo.hadFailure = true;
                        listener.onError("Server does not support resume");
                    }
                    return;
                }

                in = new BufferedInputStream(connection.getInputStream(), 65536);
                File targetFile = new File(taskInfo.savePath);
                try {
                    fileAccessor = new RandomAccessFile(targetFile, "rw");
                } catch (Exception ioEx) {
                    if (!paused.get()) {
                        failed.set(true);
                        taskInfo.hadFailure = true;
                        listener.onError("Cannot write file: " + ioEx.getMessage());
                    }
                    return;
                }

                if (code == HttpURLConnection.HTTP_OK && taskInfo.threadCount == 1) {
                    if (taskInfo.totalDownloaded() == 0 && taskInfo.elapsedMs == 0L) {
                        fileAccessor.setLength(0);
                        fileAccessor.seek(0);
                    } else {
                        if (!paused.get()) {
                            failed.set(true);
                            taskInfo.hadFailure = true;
                            listener.onError("Server does not support resume");
                        }
                        return;
                    }
                } else {
                    fileAccessor.seek(actualStart);
                }

                byte[] buffer = new byte[65536];
                int bytesRead;
                while (!paused.get() && !Thread.currentThread().isInterrupted()
                        && (bytesRead = in.read(buffer)) != -1) {
                    fileAccessor.write(buffer, 0, bytesRead);
                    taskInfo.downloadedBytes[threadId] += bytesRead;
                    recordBytes(bytesRead);
                }
            } catch (Exception e) {
                if (!paused.get() && !Thread.currentThread().isInterrupted()) {
                    failed.set(true);
                    taskInfo.hadFailure = true;
                    Log.d(TAG, "Worker " + threadId + " error: " + e.getMessage());
                    listener.onError(e.getMessage() == null ? "Network error"
                            : e.getMessage());
                }
            } finally {
                try {
                    long need = taskInfo.endBytes[threadId] - taskInfo.startBytes[threadId] + 1;
                    if (taskInfo.endBytes[threadId] != Long.MAX_VALUE - 1
                            && taskInfo.downloadedBytes[threadId] < need
                            && !paused.get()) {
                        failed.set(true);
                        taskInfo.hadFailure = true;
                    }
                } catch (Exception ignored) {}

                try { if (fileAccessor != null) fileAccessor.close(); } catch (Exception ignored) {}
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (connection != null) connection.disconnect();
            }
        }
    }
}