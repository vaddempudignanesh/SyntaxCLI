package vaddempudi.gnanesh.syntaxcli.browser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class DownloadTaskInfo implements Serializable {
    private static final long serialVersionUID = 4L;

    public String gid;
    public String url;
    public String savePath;
    public String userAgent;
    public String referer;
    public String cookies;
    public String extraHeaders;
    public String mimeType;
    public String etag = null;
    public String lastModified = null;
    public long totalSize;
    public int threadCount;
    public long[] startBytes;
    public long[] endBytes;
    public long[] downloadedBytes;
    public long elapsedMs = 0L;
    public boolean hadFailure = false;

    public DownloadTaskInfo(String gid, String url, String savePath,
                            long totalSize, int threadCount) {
        this.gid = gid;
        this.url = url;
        this.savePath = savePath;
        this.totalSize = totalSize;
        this.threadCount = threadCount;
        this.startBytes = new long[threadCount];
        this.endBytes = new long[threadCount];
        this.downloadedBytes = new long[threadCount];
    }

    public long totalDownloaded() {
        long t = 0;
        for (long b : downloadedBytes) t += b;
        return t;
    }

    /**
     * True only when EVERY worker's byte range has been fully written.
     * This is the authoritative completion test — file.length() is
     * useless because pre-allocated files are sparse.
     */
    public boolean isComplete() {
        if (hadFailure) return false;
        if (totalSize <= 0) return false;

        for (int i = 0; i < threadCount; i++) {
            long rangeLen = endBytes[i] - startBytes[i] + 1;
            if (endBytes[i] == Long.MAX_VALUE - 1) {
                return !hadFailure && downloadedBytes[i] > 0;
            }
            if (downloadedBytes[i] < rangeLen) {
                return false;
            }
        }
        return true;
    }

    public void saveState(String stateFilePath) {
        try (ObjectOutputStream oos =
                     new ObjectOutputStream(new FileOutputStream(stateFilePath))) {
            oos.writeObject(this);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static DownloadTaskInfo loadState(String stateFilePath) {
        File file = new File(stateFilePath);
        if (!file.exists()) return null;
        try (ObjectInputStream ois =
                     new ObjectInputStream(new FileInputStream(stateFilePath))) {
            return (DownloadTaskInfo) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }
}