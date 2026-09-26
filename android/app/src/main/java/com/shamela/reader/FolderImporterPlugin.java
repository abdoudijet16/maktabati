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

        JSArray files = new JSArray();
        int[] scanned = {0};
        int[] skipped = {0};
        try {
            walk(root, "", files, scanned, skipped);
        } catch (Exception e) {
            call.reject("خطأ أثناء قراءة المجلد: " + e.getMessage());
            return;
        }

        JSObject ret = new JSObject();
        ret.put("folderName", root.getName());
        ret.put("scannedCount", scanned[0]);
        ret.put("skippedCount", skipped[0]);
        ret.put("files", files);
        call.resolve(ret);
    }

    private void walk(DocumentFile dir, String relPath, JSArray out, int[] scanned, int[] skipped) {
        DocumentFile[] children = dir.listFiles();
        if (children == null) return;

        for (DocumentFile child : children) {
            String childName = child.getName();
            if (childName == null) continue;
            String childRel = relPath.isEmpty() ? childName : relPath + "/" + childName;

            if (child.isDirectory()) {
                walk(child, childRel, out, scanned, skipped);
                continue;
            }

            String lower = childName.toLowerCase();
            boolean isJson = lower.endsWith(".json");
            boolean isSqlite = lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3");
            if (!isJson && !isSqlite) continue;

            scanned[0]++;
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
                out.put(fileObj);
            } catch (Exception e) {
                skipped[0]++; // unreadable/corrupt file - skip it, keep going
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
