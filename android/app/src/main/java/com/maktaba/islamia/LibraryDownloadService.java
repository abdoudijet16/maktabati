package com.maktaba.islamia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.getcapacitor.JSObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads the (large - about 3.1GB) library zip and indexes it, as a FOREGROUND
 * service so it keeps running when the person switches apps or locks the screen.
 *
 * The zip is KEPT as-is in the app's private storage (Context.getFilesDir()) and
 * is NOT extracted. Indexing walks the zip's entry list and reads only the first
 * few KB of each book to get its title/author/category, so the whole library is
 * listed quickly. The full text of a book is read straight out of the zip (random
 * access via ZipFile) the first time it is opened. This avoids writing many GB of
 * extracted files to the phone and avoids parsing/storing every book up front.
 *
 * Nothing is buffered beyond a small chunk: the download streams to disk and each
 * entry is read one at a time.
 *
 * Note (Android 14 / API 34+): a "dataSync" foreground service is capped by the
 * system at about 6 hours of cumulative runtime per rolling 24h window.
 */
public class LibraryDownloadService extends Service {

    private static final String CHANNEL_ID = "library_download";
    private static final int NOTIF_ID = 9081;

    static final String ZIP_DIR = "library_download";
    static final String ZIP_NAME = "library.zip";

    private NotificationManager notificationManager;
    private NotificationCompat.Builder builder;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannelIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String url = intent != null ? intent.getStringExtra("url") : null;

        builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("جارٍ تنزيل المكتبة")
            .setContentText("جارٍ البدء...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, true);

        Notification notif = builder.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notif);
        }

        if (url == null || url.isEmpty()) {
            failAndStop("لم يتم تحديد رابط التنزيل.");
            return START_NOT_STICKY;
        }

        new Thread(() -> runDownload(url)).start();
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "تنزيل المكتبة", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("تقدّم تنزيل مكتبة الكتب وفهرستها");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text, int progress, boolean indeterminate) {
        builder.setContentText(text);
        builder.setProgress(100, progress, indeterminate);
        notificationManager.notify(NOTIF_ID, builder.build());
    }

    private void failAndStop(String message) {
        FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();
        if (plugin != null) plugin.rejectImportCall(message);
        updateNotification("فشل: " + message, 0, false);
        stopForeground(false);
        stopSelf();
    }

    private void runDownload(String url) {
        File privateRoot = new File(getFilesDir(), ZIP_DIR);
        File zipFile = new File(privateRoot, ZIP_NAME);
        File partFile = new File(privateRoot, ZIP_NAME + ".part");
        try {
            privateRoot.mkdirs();
            // Older versions extracted the zip into this folder - reclaim that space.
            BookIndexer.deleteRecursive(new File(privateRoot, "extracted"));

            // Download to a .part file first so a half-finished download is never
            // mistaken for a complete library zip.
            downloadToFile(url, partFile);
            zipFile.delete();
            if (!partFile.renameTo(zipFile)) throw new IOException("تعذّر حفظ الملف بعد التنزيل.");

            updateNotification("جارٍ فهرسة الكتب...", 0, true);
            int[] scanned = {0};
            int[] skipped = {0};
            indexZip(zipFile, scanned, skipped);

            FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();
            if (plugin != null) plugin.resolveImportCall(scanned[0], skipped[0]);

            updateNotification("اكتملت الفهرسة: " + scanned[0] + " كتاب", 100, false);
        } catch (Exception e) {
            partFile.delete();
            failAndStop("فشل التنزيل أو الفهرسة: " + e.getMessage());
            return;
        }
        stopForeground(false);
        stopSelf();
    }

    private void downloadToFile(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            URL url = new URL(urlStr);
            // Follow redirects manually: archive.org often redirects to a specific
            // ia<NNN>.us.archive.org host, and HttpURLConnection does not follow
            // cross-host redirects on its own.
            for (int i = 0; i < 5; i++) {
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MaktabaIslamia");
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null) throw new IOException("إعادة توجيه بدون رابط جديد.");
                    url = new URL(url, loc);
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("فشل الاتصال بالخادم (HTTP " + code + ").");
                }
                break;
            }
            if (conn == null) throw new IOException("تعذّر فتح الاتصال.");

            long totalBytes = conn.getContentLengthLong();
            in = conn.getInputStream();
            out = new FileOutputStream(dest);

            byte[] buffer = new byte[65536];
            long bytesDone = 0;
            long lastReportedMB = -1;
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                bytesDone += n;
                long mb = bytesDone / (1024 * 1024);
                if (mb != lastReportedMB) { // throttle: at most once per MB
                    lastReportedMB = mb;
                    FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();
                    if (plugin != null) {
                        JSObject progress = new JSObject();
                        progress.put("phase", "downloading");
                        progress.put("bytesDone", bytesDone);
                        progress.put("totalBytes", totalBytes);
                        plugin.emitImportEvent("downloadProgress", progress);
                    }
                    if (totalBytes > 0) {
                        int pct = (int) Math.min(100, (bytesDone * 100) / totalBytes);
                        updateNotification(pct + "% (" + mb + "MB)", pct, false);
                    } else {
                        updateNotification(mb + "MB...", 0, true);
                    }
                }
            }
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Walks the zip's entry list (a cheap lookup in its central directory) and
     * reads only the head of each book JSON to build its index entry.
     */
    private void indexZip(File zipFile, int[] scanned, int[] skipped) throws IOException {
        BookIndexer.indexZipFile(zipFile, scanned, skipped, "downloadImportFile", "downloadImportSkipped");
    }
}
