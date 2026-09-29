package com.maktaba.islamia;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Lets the user pick ONE top-level folder via Android's native "Open document tree"
 * dialog, then recursively walks every subfolder inside it (no depth limit) looking
 * for .json and .db/.sqlite/.sqlite3 files, reads each one, and returns everything
 * to JS in a single call.
 *
 * This exists because:
 *  - The system's single/multi FILE picker (used by @capawesome/capacitor-file-picker's
 *    pickFiles) can only browse one folder view at a time and can't recurse into
 *    subfolders in one selection - fine for a flat file, useless for a library that's
 *    organized into per-category subfolders.
 *  - The FOLDER picker (ACTION_OPEN_DOCUMENT_TREE) grants access to the whole tree at
 *    once, but nothing in the web layer can walk that tree - only native code can,
 *    via DocumentFile - so this small native plugin does exactly that and nothing more.
 */
@CapacitorPlugin(
    name = "FolderImporter",
    permissions = {
        @com.getcapacitor.annotation.Permission(
            strings = { android.Manifest.permission.POST_NOTIFICATIONS },
            alias = "notifications"
        )
    }
)
public class FolderImporterPlugin extends Plugin {

    /**
     * Lets the person pick a .zip file they downloaded manually (e.g. straight from
     * a browser, with no extraction needed) and imports it in place: the zip is
     * copied once into the app's private storage, then indexed exactly like the
     * auto-downloaded library. Runs as a foreground service (ManualZipImportService)
     * so a large zip survives the app being backgrounded, same as the other
     * long-running imports.
     */
    @PluginMethod
    public void pickAndImportZip(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(call, intent, "handlePickZipResult");
    }

    @ActivityCallback
    private void handlePickZipResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("تم إلغاء اختيار الملف.");
            return;
        }
        Uri uri = result.getData().getData();
        if (uri == null) { call.reject("تعذّر الحصول على مسار الملف."); return; }

        activeInstance = this;
        pendingImportCall = call;
        importAck.drainPermits(); // clear any stale acks from a previous run
        Intent serviceIntent = new Intent(getContext(), ManualZipImportService.class);
        serviceIntent.putExtra("uri", uri);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getContext().startForegroundService(serviceIntent);
        } else {
            getContext().startService(serviceIntent);
        }
    }

    /**
     * Picks ONE file of any type via ACTION_OPEN_DOCUMENT (not ACTION_GET_CONTENT).
     * OPEN_DOCUMENT explicitly means "hand back this exact document" and is handled
     * far more consistently across file managers than GET_CONTENT, which many apps
     * instead treat as "open/browse into this" for container-like files such as
     * .zip - silently finishing with RESULT_CANCELED if the app never finalizes a
     * selection, even though the person didn't mean to cancel anything.
     */
    @PluginMethod
    public void pickFile(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(call, intent, "handlePickFileResult");
    }

    @ActivityCallback
    private void handlePickFileResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("تم إلغاء اختيار الملف.");
            return;
        }
        Uri uri = result.getData().getData();
        if (uri == null) { call.reject("تعذّر الحصول على مسار الملف."); return; }
        try {
            byte[] bytes = readAll(uri);
            JSObject ret = new JSObject();
            ret.put("name", queryDisplayName(uri));
            ret.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("خطأ أثناء قراءة الملف: " + e.getMessage());
        }
    }

    /**
     * Picks MULTIPLE files of any type via ACTION_OPEN_DOCUMENT with
     * EXTRA_ALLOW_MULTIPLE, for the same reliability reasons as pickFile above.
     */
    @PluginMethod
    public void pickFilesMulti(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(call, intent, "handlePickFilesMultiResult");
    }

    @ActivityCallback
    private void handlePickFilesMultiResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("تم إلغاء اختيار الملفات.");
            return;
        }
        Intent data = result.getData();
        JSArray files = new JSArray();
        try {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    addFileToArray(uri, files);
                }
            } else if (data.getData() != null) {
                addFileToArray(data.getData(), files);
            }
            JSObject ret = new JSObject();
            ret.put("files", files);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("خطأ أثناء قراءة الملفات: " + e.getMessage());
        }
    }

    private void addFileToArray(Uri uri, JSArray files) {
        try {
            byte[] bytes = readAll(uri);
            JSObject fileObj = new JSObject();
            fileObj.put("name", queryDisplayName(uri));
            fileObj.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            files.put(fileObj);
        } catch (Exception e) {
            // skip unreadable file, keep going
        }
    }

    private String queryDisplayName(Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) { /* fall through to path-based guess */ }
        if (name == null) {
            String path = uri.getPath();
            if (path != null && path.contains("/")) name = path.substring(path.lastIndexOf('/') + 1);
        }
        return name != null ? name : "file";
    }

    /**
     * Same reliability logic as downloadLibrary below: requesting the
     * notification permission first (Android 13+) so FolderImportService's
     * progress notification can actually show once the folder is picked.
     */
    @PluginMethod
    public void pickFolder(PluginCall call) {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && getPermissionState("notifications") != com.getcapacitor.PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "pickFolderPermCallback");
            return;
        }
        launchFolderPicker(call);
    }

    @com.getcapacitor.annotation.PermissionCallback
    private void pickFolderPermCallback(PluginCall call) {
        launchFolderPicker(call); // proceed regardless of grant/deny, same reasoning as downloads
    }

    private void launchFolderPicker(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(call, intent, "handlePickFolderResult");
    }

    @ActivityCallback
    private void handlePickFolderResult(PluginCall call, ActivityResult result) {
        if (call == null) return;

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("تم إلغاء اختيار المجلد."); // user cancelled
            return;
        }

        Uri treeUri = result.getData().getData();
        if (treeUri == null) {
            call.reject("تعذّر الحصول على مسار المجلد.");
            return;
        }

        try {
            getContext().getContentResolver().takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (Exception ignored) {
            // Not fatal if this fails - we still have read access for this session.
        }

        // Hand off to FolderImportService - a FOREGROUND service with a
        // persistent notification, not a plain background thread. A plain
        // thread (the previous implementation) gets killed by Android's
        // Doze/App Standby or an OEM battery manager within seconds to
        // minutes of the app leaving the foreground or the screen locking,
        // which would silently abort an import mid-way. A foreground
        // service is exempt from those limits for as long as its
        // notification is showing, so importing a large folder survives
        // switching to another app (YouTube, Instagram, etc.) or locking
        // the screen - exactly like the auto-download option already does.
        activeInstance = this;
        pendingImportCall = call;
        importAck.drainPermits(); // clear any stale acks from a previous run
        Intent serviceIntent = new Intent(getContext(), FolderImportService.class);
        serviceIntent.putExtra("treeUri", treeUri);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getContext().startForegroundService(serviceIntent);
        } else {
            getContext().startService(serviceIntent);
        }
    }

    /**
     * Downloads a (potentially very large - the real library is 3.1GB) zip file
     * from `url`, extracts it, and imports every .json/.db/.sqlite file inside -
     * all natively, all streamed straight to/from disk. This can't be done from
     * JS: a file this size can't be fetched into a JS ArrayBuffer, base64-encoded
     * across the Capacitor bridge, or held in memory by a JS zip library on a
     * phone. Everything here is written to and read from an app-private folder
     * (Context.getFilesDir()) - not part of the regular Downloads/Documents
     * folders a file manager shows, and not reachable by other apps.
     *
     * The actual work runs in LibraryDownloadService, a foreground service with
     * a persistent notification - not a plain background thread. A plain thread
     * gets killed by Android (Doze/App Standby/OEM battery managers) as soon as
     * the app is backgrounded or the screen locks, which would silently abort a
     * download that can take several minutes. A foreground service is exempt
     * from those background execution limits for as long as its notification is
     * showing, so the download survives the user switching away or locking the
     * screen.
     */
    @PluginMethod
    public void downloadLibrary(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("لم يتم تحديد رابط التنزيل."); return; }

        // Android 13+ requires this runtime permission just to SHOW the
        // notification - it does not affect whether the foreground service
        // itself is allowed to run and stay alive. If denied, the download
        // still proceeds and still survives backgrounding; the person just
        // won't see a progress notification for it.
        if (android.os.Build.VERSION.SDK_INT >= 33
                && getPermissionState("notifications") != com.getcapacitor.PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "downloadPermCallback");
            return;
        }
        startDownload(call, url);
    }

    @com.getcapacitor.annotation.PermissionCallback
    private void downloadPermCallback(PluginCall call) {
        String url = call.getString("url");
        startDownload(call, url); // proceed regardless of grant/deny - see comment above
    }

    private void startDownload(PluginCall call, String url) {
        activeInstance = this;
        pendingImportCall = call;
        importAck.drainPermits(); // clear any stale acks from a previous run
        Intent serviceIntent = new Intent(getContext(), LibraryDownloadService.class);
        serviceIntent.putExtra("url", url);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getContext().startForegroundService(serviceIntent);
        } else {
            getContext().startService(serviceIntent);
        }
    }

    private static FolderImporterPlugin activeInstance;
    private PluginCall pendingImportCall;

    /** Called by LibraryDownloadService/FolderImportService (different classes, same process) to emit progress/file events to JS. */
    public void emitImportEvent(String name, JSObject data) {
        notifyListeners(name, data);
    }

    /** Called by LibraryDownloadService/FolderImportService when an import finishes successfully. */
    public void resolveImportCall(int scanned, int skipped) {
        resolveImportCall(scanned, skipped, null);
    }

    public void resolveImportCall(int scanned, int skipped, String folderName) {
        if (pendingImportCall != null) {
            JSObject ret = new JSObject();
            ret.put("scannedCount", scanned);
            ret.put("skippedCount", skipped);
            if (folderName != null) ret.put("folderName", folderName);
            pendingImportCall.resolve(ret);
            pendingImportCall = null;
        }
    }

    /** Called by LibraryDownloadService/FolderImportService if anything fails. */
    public void rejectImportCall(String message) {
        if (pendingImportCall != null) {
            pendingImportCall.reject(message);
            pendingImportCall = null;
        }
    }

    public static FolderImporterPlugin getActiveInstance() { return activeInstance; }

    // ---- Reading one book on demand, as a stream of page batches ----
    // The book is never read into memory as a whole: JS opens a stream, then asks
    // for batches of pages one call at a time (natural backpressure), so a book of
    // any size uses only a small, constant amount of memory. See BookStream.
    private final java.util.Map<String, BookStream> bookStreams = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger bookStreamCounter = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Opens a book for reading.
     * kind "zip": `ref` is the entry name inside the downloaded library zip (random access, no extraction).
     * kind "saf": `ref` is the document URI of a file in a folder the person picked.
     */
    @PluginMethod
    public void openBookStream(PluginCall call) {
        final String kind = call.getString("kind");
        final String ref = call.getString("ref");
        if (kind == null || ref == null) { call.reject("بيانات الكتاب ناقصة."); return; }

        new Thread(() -> {
            try {
                closeAllBookStreams(); // only one book is loaded at a time
                BookStream stream = BookStream.open(getContext(), kind, ref);
                String id = String.valueOf(bookStreamCounter.incrementAndGet());
                bookStreams.put(id, stream);
                JSObject ret = new JSObject();
                ret.put("id", id);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("تعذّر فتح الكتاب: " + e.getMessage());
            }
        }).start();
    }

    /** Returns the next batch of pages: { pages: [text, ...], done: boolean }. */
    @PluginMethod
    public void nextBookPages(PluginCall call) {
        final String id = call.getString("id");
        final int max = call.getInt("max", 50);
        final BookStream stream = id == null ? null : bookStreams.get(id);
        if (stream == null) { call.reject("انتهت جلسة قراءة الكتاب."); return; }

        new Thread(() -> {
            try {
                java.util.List<String> pages = stream.next(max);
                JSArray arr = new JSArray();
                for (String p : pages) arr.put(p);
                boolean done = stream.isDone();
                JSObject ret = new JSObject();
                ret.put("pages", arr);
                ret.put("done", done);
                if (done) {
                    stream.close();
                    bookStreams.remove(id);
                }
                call.resolve(ret);
            } catch (Exception e) {
                stream.close();
                bookStreams.remove(id);
                call.reject("تعذّر قراءة الكتاب: " + e.getMessage());
            }
        }).start();
    }

    /** Closes a book stream early (e.g. the person left the book while it was loading). */
    @PluginMethod
    public void closeBookStream(PluginCall call) {
        String id = call.getString("id");
        BookStream stream = id == null ? null : bookStreams.remove(id);
        if (stream != null) stream.close();
        call.resolve();
    }

    private void closeAllBookStreams() {
        for (BookStream s : bookStreams.values()) s.close();
        bookStreams.clear();
    }

    /** Deletes the downloaded library zip (used when the library is cleared, to free the space). */
    @PluginMethod
    public void clearDownloadedFiles(PluginCall call) {
        new Thread(() -> {
            BookIndexer.deleteRecursive(new java.io.File(getContext().getFilesDir(), LibraryDownloadService.ZIP_DIR));
            BookIndexer.deleteRecursive(new java.io.File(getContext().getFilesDir(), ManualZipImportService.ZIP_DIR));
            call.resolve();
        }).start();
    }

    // ---- Backpressure between native file emission and JS processing ----
    // The old design had native read and emit every matching file as fast as
    // disk I/O allowed, with no regard for how fast JS could actually consume
    // them. For a 7683-book library, native could race far ahead and end up
    // with thousands of full book payloads sitting in memory simultaneously,
    // each waiting its turn for an IndexedDB write on the JS side - more than
    // enough to exhaust the WebView's JS heap and silently crash/reload the
    // renderer partway through, which stops the import with no visible error
    // and leaves only whatever had already been committed. This semaphore
    // makes native wait for an explicit acknowledgment from JS after each
    // file before reading the next one, so at most one file's payload is
    // ever in flight at a time.
    private final java.util.concurrent.Semaphore importAck = new java.util.concurrent.Semaphore(0);

    @PluginMethod
    public void ackImportFile(PluginCall call) {
        importAck.release();
        call.resolve();
    }

    /** Called by LibraryDownloadService/FolderImportService right after emitting a
     *  file event, to block the native reading thread until JS finishes processing
     *  that file (or up to timeoutMs, as a safety net against ever hanging forever
     *  if JS fails to ack for some reason). */
    public boolean waitForImportAck(long timeoutMs) {
        try {
            return importAck.tryAcquire(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static final long MAX_SINGLE_FILE_BYTES = 60L * 1024 * 1024; // 60MB safety cap per file

    private byte[] readAll(Uri uri) throws IOException {
        InputStream in = getContext().getContentResolver().openInputStream(uri);
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
