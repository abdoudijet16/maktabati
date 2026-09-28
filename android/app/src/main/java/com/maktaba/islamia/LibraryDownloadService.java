package com.maktaba.islamia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.getcapacitor.JSObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the (large - the real library is 3.1GB) library zip, extracts it, and
 * imports every .json/.db/.sqlite file inside, entirely off the JS bridge.
 *
 * This runs as a FOREGROUND service (with a visible, ongoing notification), not a
 * plain background thread. A plain thread gets killed by Android's Doze/App Standby
 * or an OEM battery manager (Xiaomi/Huawei/Samsung are especially aggressive about
 * this) within seconds to minutes of the app leaving the foreground or the screen
 * locking - which would silently abort a download that can take several minutes to
 * hours. A foreground service is specifically exempt from those background
 * execution limits for as long as its notification is showing, so the download
 * keeps running if the person switches to another app or locks the screen.
 *
 * Every read/write here streams through a small fixed buffer - nothing is ever
 * held fully in memory - and everything is written to Context.getFilesDir(), the
 * app's private internal storage: invisible to file managers, not part of the
 * regular Downloads/Documents folders, and not reachable by any other app.
 *
 * Note (Android 14 / API 34+): a "dataSync" foreground service type is capped by
 * the system at ~6 hours of cumulative runtime per rolling 24h window. That's far
 * more than this download should ever need on a normal connection, but is worth
 * knowing if this is ever reused for something slower.
 */
public class LibraryDownloadService extends Service {

    private static final String CHANNEL_ID = "library_download";
    private static final int NOTIF_ID = 9081;
    private static final long MAX_SINGLE_FILE_BYTES = 60L * 1024 * 1024; // 60MB safety cap per file

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
        // Don't ask the system to auto-restart us if killed - a half-downloaded
        // file with no in-progress PluginCall to resolve isn't useful; the
        // person can just tap the download button again.
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
            channel.setDescription("تقدّم تنزيل مكتبة الكتب واستيرادها");
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
        File privateRoot = new File(getFilesDir(), "library_download");
        File zipFile = new File(privateRoot, "library.zip");
        File extractDir = new File(privateRoot, "extracted");
        try {
            privateRoot.mkdirs();
            deleteRecursive(extractDir); // clear any previous partial extraction first
            extractDir.mkdirs();

            downloadToFile(url, zipFile);
            unzipStreamed(zipFile, extractDir);
            zipFile.delete(); // reclaim space - extracted files are what we need from here

            updateNotification("جارٍ الاستيراد...", 0, true);
            int[] scanned = {0};
            int[] skipped = {0};
            walkPlainFolder(extractDir, "", scanned, skipped);

            FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();
            if (plugin != null) plugin.resolveImportCall(scanned[0], skipped[0]);

            updateNotification("اكتمل الاستيراد: " + scanned[0] + " كتاب", 100, false);
        } catch (Exception e) {
            failAndStop("فشل التنزيل أو الاستيراد: " + e.getMessage());
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
            // Follow redirects manually: archive.org often redirects to a
            // specific ia<NNN>.us.archive.org mirror host, and
            // HttpURLConnection does not follow cross-host redirects on its own.
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

    private void unzipStreamed(File zipFile, File destDir) throws IOException {
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
        try {
            ZipEntry entry;
            int filesExtracted = 0;
            String destCanonical = destDir.getCanonicalPath();
            while ((entry = zin.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                // Zip-slip guard: refuse any entry that would extract outside destDir.
                if (!outFile.getCanonicalPath().startsWith(destCanonical + File.separator)
                        && !outFile.getCanonicalPath().equals(destCanonical)) {
                    zin.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    FileOutputStream fos = new FileOutputStream(outFile);
                    try {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = zin.read(buf)) != -1) fos.write(buf, 0, n);
                    } finally {
                        fos.close();
                    }
                    filesExtracted++;
                    if (filesExtracted % 25 == 0) { // throttle: every 25 files
                        FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();
                        if (plugin != null) {
                            JSObject progress = new JSObject();
                            progress.put("phase", "extracting");
                            progress.put("filesExtracted", filesExtracted);
                            plugin.emitImportEvent("downloadProgress", progress);
                        }
                        updateNotification("جارٍ فك الضغط... " + filesExtracted + " ملف", 0, true);
                    }
                }
                zin.closeEntry();
            }
        } finally {
            zin.close();
        }
    }

    private void walkPlainFolder(File dir, String relPath, int[] scanned, int[] skipped) {
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            String childName = child.getName();
            String childRel = relPath.isEmpty() ? childName : relPath + "/" + childName;

            if (child.isDirectory()) {
                walkPlainFolder(child, childRel, scanned, skipped);
                continue;
            }

            String lower = childName.toLowerCase();
            boolean isJson = lower.endsWith(".json");
            boolean isSqlite = lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3");
            if (!isJson && !isSqlite) continue;

            FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();
            long size = child.length();
            if (size > MAX_SINGLE_FILE_BYTES) {
                skipped[0]++;
                if (plugin != null) {
                    JSObject skip = new JSObject();
                    skip.put("relPath", childRel);
                    skip.put("reason", "large");
                    skip.put("sizeMB", size / 1024.0 / 1024.0);
                    plugin.emitImportEvent("downloadImportSkipped", skip);
                }
                continue;
            }

            try {
                byte[] bytes = readFileBytes(child);
                JSObject fileObj = new JSObject();
                fileObj.put("name", childName);
                fileObj.put("relPath", childRel);
                if (isJson) {
                    fileObj.put("type", "json");
                    fileObj.put("text", new String(bytes, StandardCharsets.UTF_8));
                } else {
                    fileObj.put("type", "sqlite");
                    fileObj.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
                }
                scanned[0]++;
                if (plugin != null) {
                    plugin.emitImportEvent("downloadImportFile", fileObj);
                    // Backpressure: wait for JS to finish this file before reading the next.
                    plugin.waitForImportAck(20000);
                }
            } catch (Exception e) {
                skipped[0]++;
                if (plugin != null) {
                    JSObject skip = new JSObject();
                    skip.put("relPath", childRel);
                    skip.put("reason", "error: " + e.getMessage());
                    plugin.emitImportEvent("downloadImportSkipped", skip);
                }
            }
        }
    }

    private byte[] readFileBytes(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
            return buffer.toByteArray();
        }
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }
}
