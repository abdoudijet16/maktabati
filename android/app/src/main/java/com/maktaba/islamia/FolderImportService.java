package com.maktaba.islamia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import com.getcapacitor.JSObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Walks a folder tree the person picked via FolderImporterPlugin.pickFolder
 * (ACTION_OPEN_DOCUMENT_TREE) and imports every .json/.db/.sqlite file inside it,
 * at any depth of subfolders - entirely off the JS bridge, file by file.
 *
 * This runs as a FOREGROUND service (persistent notification), not a plain
 * background thread. A plain thread gets killed by Android's Doze/App Standby or
 * an OEM battery manager (Xiaomi/Huawei/Samsung are especially aggressive about
 * this) within seconds to minutes of the app leaving the foreground or the screen
 * locking - silently aborting the import if the person switches to YouTube,
 * Instagram, etc. or just locks the phone. A foreground service is exempt from
 * those limits for as long as its notification is showing, so the import keeps
 * running in the background exactly like the "download library" option already
 * does.
 *
 * Reads go through the same ContentResolver-based streaming as the rest of this
 * plugin - nothing is ever fully buffered beyond one file at a time.
 */
public class FolderImportService extends android.app.Service {

    private static final String CHANNEL_ID = "folder_import";
    private static final int NOTIF_ID = 9082;
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
        Uri treeUri = intent != null ? intent.getParcelableExtra("treeUri") : null;

        builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("جارٍ استيراد المكتبة")
            .setContentText("جارٍ البدء...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true);

        Notification notif = builder.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notif);
        }

        if (treeUri == null) {
            failAndStop("تعذّر الحصول على مسار المجلد.");
            return START_NOT_STICKY;
        }

        new Thread(() -> runImport(treeUri)).start();
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "استيراد مجلد المكتبة", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("تقدّم استيراد الكتب من مجلد تم اختياره");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text, int done, int total) {
        builder.setContentText(text);
        if (total > 0) builder.setProgress(total, Math.min(done, total), false);
        else builder.setProgress(0, 0, true);
        notificationManager.notify(NOTIF_ID, builder.build());
    }

    private void failAndStop(String message) {
        FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();
        if (plugin != null) plugin.rejectImportCall(message);
        updateNotification("فشل: " + message, 0, 0);
        stopForeground(false);
        stopSelf();
    }

    private void runImport(Uri treeUri) {
        DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
        if (root == null || !root.exists()) {
            failAndStop("تعذّر فتح المجلد المختار.");
            return;
        }

        int[] scanned = {0};
        int[] skipped = {0};
        try {
            walk(root, "", scanned, skipped);
        } catch (Exception e) {
            failAndStop("خطأ أثناء قراءة المجلد: " + e.getMessage());
            return;
        }

        FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();
        if (plugin != null) plugin.resolveImportCall(scanned[0], skipped[0], root.getName());

        updateNotification("اكتمل الاستيراد: " + scanned[0] + " ملف", scanned[0], scanned[0]);
        stopForeground(false);
        stopSelf();
    }

    private void walk(DocumentFile dir, String relPath, int[] scanned, int[] skipped) {
        DocumentFile[] children = dir.listFiles();
        if (children == null) return;

        for (DocumentFile child : children) {
            String childName = child.getName();
            if (childName == null) continue;
            String childRel = relPath.isEmpty() ? childName : relPath + "/" + childName;

            if (child.isDirectory()) {
                walk(child, childRel, scanned, skipped);
                continue;
            }

            String lower = childName.toLowerCase();
            boolean isJson = lower.endsWith(".json");
            boolean isSqlite = lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3");
            if (!isJson && !isSqlite) continue;

            FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();
            long size = child.length(); // 0 or -1 if the provider doesn't report it; treat as unknown, don't skip
            if (size > MAX_SINGLE_FILE_BYTES) {
                skipped[0]++;
                if (plugin != null) {
                    JSObject skip = new JSObject();
                    skip.put("relPath", childRel);
                    skip.put("reason", "large");
                    skip.put("sizeMB", size / 1024.0 / 1024.0);
                    plugin.emitImportEvent("folderImportSkipped", skip);
                }
                continue;
            }

            try {
                byte[] bytes = readAll(child.getUri());
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
                    plugin.emitImportEvent("folderImportFile", fileObj);
                    // Backpressure: wait for JS to finish this file before reading the next.
                    plugin.waitForImportAck(20000);
                }
                if (scanned[0] % 10 == 0) updateNotification(childName, scanned[0], 0); // total unknown ahead of time
            } catch (Exception e) {
                skipped[0]++;
                if (plugin != null) {
                    JSObject skip = new JSObject();
                    skip.put("relPath", childRel);
                    skip.put("reason", "error: " + e.getMessage());
                    plugin.emitImportEvent("folderImportSkipped", skip);
                }
            }
        }
    }

    private byte[] readAll(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot open stream for " + uri);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }
}
