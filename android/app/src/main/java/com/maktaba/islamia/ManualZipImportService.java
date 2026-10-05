package com.maktaba.islamia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Imports a zip file the person picked manually (already downloaded, no extraction
 * needed) as a FOREGROUND service, same as the auto-download and folder-import
 * paths, so a large zip survives the app being backgrounded.
 *
 * The zip is copied once from its content:// URI into the app's private storage
 * (ContentResolver only gives a byte stream, not always a real file path, so a
 * local copy is needed before random-access reads are possible) and then indexed
 * with the same logic the auto-downloaded library uses (BookIndexer.indexZipFile).
 * Each manually-picked zip gets its own file name (derived from its content hash)
 * so importing more than one doesn't overwrite a previous one, and books already
 * indexed point at their own specific zip file (see BookStream / the "ref" field).
 */
public class ManualZipImportService extends Service {

    private static final String CHANNEL_ID = "manual_zip_import";
    private static final int NOTIF_ID = 9083;
    static final String ZIP_DIR = "manual_zips";

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
        Uri uri = intent != null ? intent.getParcelableExtra("uri") : null;

        builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("جارٍ استيراد الملف المضغوط")
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

        if (uri == null) {
            failAndStop("تعذّر الحصول على الملف المختار.");
            return START_NOT_STICKY;
        }

        new Thread(() -> runImport(uri)).start();
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "استيراد ملف مضغوط", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("تقدّم نسخ وفهرسة ملف مضغوط تم اختياره يدويًا");
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

    private void runImport(Uri uri) {
        File dir = new File(getFilesDir(), ZIP_DIR);
        dir.mkdirs();
        // A stable, unique name for this specific picked file (its content:// URI
        // string is as good a fingerprint as any) so re-importing the same zip
        // reuses the same copy instead of endlessly duplicating it on disk.
        String name = "zip_" + Math.abs(uri.toString().hashCode()) + ".zip";
        File dest = new File(dir, name);
        File part = new File(dir, name + ".part");

        try {
            if (!dest.exists()) {
                copyToFile(uri, part);
                part.renameTo(dest);
            } // else: this exact file was already copied in a previous import - reuse it

            updateNotification("جارٍ فهرسة الكتب...", 0, true);
            int[] scanned = {0};
            int[] skipped = {0};
            BookIndexer.indexZipFile(dest, scanned, skipped, "zipImportFile", "zipImportSkipped");

            FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();
            if (plugin != null) plugin.resolveImportCall(scanned[0], skipped[0]);

            updateNotification("اكتملت الفهرسة: " + scanned[0] + " كتاب", 100, false);
        } catch (Exception e) {
            part.delete();
            failAndStop("فشل استيراد الملف المضغوط: " + e.getMessage());
            return;
        }
        stopForeground(false);
        stopSelf();
    }

    private void copyToFile(Uri uri, File dest) throws IOException {
        long totalBytes = queryFileSize(uri);
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("تعذّر فتح الملف المختار.");
        FileOutputStream out = new FileOutputStream(dest);
        try {
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
                        com.getcapacitor.JSObject progress = new com.getcapacitor.JSObject();
                        progress.put("phase", "copying");
                        progress.put("bytesDone", bytesDone);
                        progress.put("totalBytes", totalBytes);
                        plugin.emitImportEvent("zipImportProgress", progress);
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
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    private long queryFileSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx);
            }
        } catch (Exception ignored) { /* unknown size - progress just shows MB copied */ }
        return -1;
    }
}
