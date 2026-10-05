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
 * running in the background.
 *
 * If the picked folder ("maktaba") has a CSV catalog next to its category folders
 * (title, author, category, file), the library is built from that CSV alone - no book
 * file is opened until the person taps the book. Without a CSV, every .json book is
 * scanned for its title/author/category as before.
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
        BookIndexer.Batch batch = new BookIndexer.Batch();
        try {
            DocumentFile catalog = findCatalog(root);
            if (catalog != null) {
                updateNotification("جارٍ قراءة فهرس الكتب...", 0, 0);
                try (InputStream in = getContentResolver().openInputStream(catalog.getUri())) {
                    if (in == null) throw new IOException("cannot open catalog");
                    scanned[0] = CatalogReader.read(in, treeUri.toString(), batch, skipped);
                }
            } else {
                walk(root, "", scanned, skipped, batch);
            }
            batch.flush();
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

    /** The CSV catalog sits at the top of the maktaba folder: catalog.csv, else any other .csv there. */
    private DocumentFile findCatalog(DocumentFile root) {
        DocumentFile[] children = root.listFiles();
        if (children == null) return null;
        DocumentFile any = null;
        for (DocumentFile c : children) {
            String n = c.getName();
            if (!c.isFile() || n == null || !n.toLowerCase().endsWith(".csv")) continue;
            String l = n.toLowerCase();
            if (l.equals("catalog.csv") || l.equals("books.csv") || l.equals("library.csv")) return c;
            if (any == null) any = c;
        }
        return any;
    }

    private void walk(DocumentFile dir, String relPath, int[] scanned, int[] skipped, BookIndexer.Batch batch) {
        DocumentFile[] children = dir.listFiles();
        if (children == null) return;

        for (DocumentFile child : children) {
            String childName = child.getName();
            if (childName == null) continue;
            String childRel = relPath.isEmpty() ? childName : relPath + "/" + childName;

            if (child.isDirectory()) {
                walk(child, childRel, scanned, skipped, batch);
                continue;
            }

            String lower = childName.toLowerCase();
            boolean isJson = lower.endsWith(".json");
            boolean isSqlite = lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3");
            if (!isJson && !isSqlite) continue;

            FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();

            if (isJson) {
                // Index only: read the head of the file for title/author/category.
                // The full text is read later, the first time the book is opened.
                try (InputStream in = getContentResolver().openInputStream(child.getUri())) {
                    if (in == null) throw new IOException("cannot open stream");
                    batch.add(BookIndexer.readMeta(in, childName, dir.getName(), "saf", child.getUri().toString()));
                    scanned[0]++;
                    if (scanned[0] % 50 == 0) updateNotification("تمت فهرسة " + scanned[0] + " كتاب", scanned[0], 0);
                } catch (Exception e) {
                    skipped[0]++;
                    if (plugin != null) {
                        JSObject skip = new JSObject();
                        skip.put("relPath", childRel);
                        skip.put("reason", "error: " + e.getMessage());
                        plugin.emitImportEvent("folderImportSkipped", skip);
                    }
                }
                continue;
            }

            // A database file holds the TEXT of many books at once, so importing it would load
            // books the person never chose. It is skipped: books are only ever listed (from the
            // catalog or a file's header) and read one at a time when tapped.
            skipped[0]++;
            if (plugin != null) {
                JSObject skip = new JSObject();
                skip.put("relPath", childRel);
                skip.put("reason", "قاعدة بيانات - لا تُحمَّل الكتب إلا عند فتحها");
                plugin.emitImportEvent("folderImportSkipped", skip);
            }
        }
    }
}
