package com.shamela.reader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import androidx.activity.result.ActivityResult;
import androidx.documentfile.provider.DocumentFile;

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
@CapacitorPlugin(name = "FolderImporter")
public class FolderImporterPlugin extends Plugin {

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

    @PluginMethod
    public void pickFolder(PluginCall call) {
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

        DocumentFile root = DocumentFile.fromTreeUri(getContext(), treeUri);
        if (root == null || !root.exists()) {
            call.reject("تعذّر فتح المجلد المختار.");
            return;
        }

        // Do the actual walking/reading OFF the UI thread: a real library folder
        // can hold hundreds of files, and synchronous ContentResolver I/O for all
        // of them on the main thread can freeze the UI long enough to look like a
        // crash (or trigger a real ANR). Each file is sent to JS the moment it's
        // read via notifyListeners, instead of being accumulated into one giant
        // array first - that avoids ever holding the whole library in memory at
        // once, which is what was actually crashing the app (OutOfMemoryError)
        // on a large library.
        new Thread(() -> {
            int[] scanned = {0};
            int[] skipped = {0};
            String folderName = root.getName();
            try {
                walk(root, "", scanned, skipped);
                JSObject ret = new JSObject();
                ret.put("folderName", folderName);
                ret.put("scannedCount", scanned[0]);
                ret.put("skippedCount", skipped[0]);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("خطأ أثناء قراءة المجلد: " + e.getMessage());
            }
        }).start();
    }

    private static final long MAX_SINGLE_FILE_BYTES = 60L * 1024 * 1024; // 60MB safety cap per file

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

            long size = child.length(); // 0 or -1 if the provider doesn't report it; treat as unknown, don't skip
            if (size > MAX_SINGLE_FILE_BYTES) {
                skipped[0]++;
                JSObject skip = new JSObject();
                skip.put("relPath", childRel);
                skip.put("reason", "large");
                skip.put("sizeMB", size / 1024.0 / 1024.0);
                notifyListeners("folderImportSkipped", skip);
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
                notifyListeners("folderImportFile", fileObj);
                // Let bytes/fileObj become eligible for GC before the next file
                // rather than holding a reference to every file for the whole walk.
            } catch (Exception e) {
                skipped[0]++;
                JSObject skip = new JSObject();
                skip.put("relPath", childRel);
                skip.put("reason", "error: " + e.getMessage());
                notifyListeners("folderImportSkipped", skip);
            }
        }
    }

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
