package com.maktaba.islamia;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a catalog CSV against the picked maktaba folder and, only when asked, copies it
 * into that folder as catalog.csv.
 *
 * The check does what the reader does when a book is tapped: a row's book counts as present
 * if its file is found at the row's path (category/file.json) or, failing that, anywhere in
 * the folder by file name. Only folder listings are read - no book file is opened.
 */
final class CatalogInstaller {
    private CatalogInstaller() {}

    static final String TARGET_NAME = "catalog.csv";
    private static final int MAX_DEPTH = 6;
    private static final int SAMPLE = 8;

    /** Compares the CSV with the folder. Result: rows, found, missing, missingSample, jsonInFolder, unlisted, hasExisting. */
    static JSObject check(Context ctx, Uri tree, byte[] csv) throws IOException {
        DocumentFile root = DocumentFile.fromTreeUri(ctx, tree);
        if (root == null || !root.exists()) throw new IOException("مجلد المكتبة غير متاح. اختره من جديد.");

        int[] skippedRows = {0};
        List<CatalogReader.Entry> entries = CatalogReader.parseEntries(CatalogReader.decode(csv), skippedRows);

        // One walk over the folder: every .json file with its relative path and its name.
        List<String[]> files = new ArrayList<>(); // {relLower, nameLower}
        collect(root, "", 0, files);
        Set<String> paths = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (String[] f : files) { paths.add(f[0]); names.add(f[1]); }

        int found = 0;
        JSArray sample = new JSArray();
        Set<String> listedPaths = new HashSet<>();
        Set<String> listedNames = new HashSet<>();
        for (CatalogReader.Entry e : entries) {
            String rel = e.rel.toLowerCase();
            String name = rel.substring(rel.lastIndexOf('/') + 1);
            listedPaths.add(rel);
            listedNames.add(name);
            if (paths.contains(rel) || names.contains(name)) {
                found++;
            } else if (sample.length() < SAMPLE) {
                sample.put(e.title + "  ←  " + e.rel);
            }
        }

        int unlisted = 0;
        for (String[] f : files) {
            if (!listedPaths.contains(f[0]) && !listedNames.contains(f[1])) unlisted++;
        }

        JSObject ret = new JSObject();
        ret.put("rows", entries.size());
        ret.put("skippedRows", skippedRows[0]);
        ret.put("found", found);
        ret.put("missing", entries.size() - found);
        ret.put("missingSample", sample);
        ret.put("jsonInFolder", files.size());
        ret.put("unlisted", unlisted);
        DocumentFile existing = root.findFile(TARGET_NAME);
        ret.put("hasExisting", existing != null && existing.isFile());
        ret.put("canWrite", root.canWrite());
        return ret;
    }

    private static void collect(DocumentFile dir, String rel, int depth, List<String[]> out) {
        if (depth > MAX_DEPTH) return;
        DocumentFile[] kids = dir.listFiles();
        if (kids == null) return;
        for (DocumentFile k : kids) {
            String n = k.getName();
            if (n == null) continue;
            String childRel = rel.isEmpty() ? n : rel + "/" + n;
            if (k.isDirectory()) {
                collect(k, childRel, depth + 1, out);
            } else if (k.isFile() && n.toLowerCase().endsWith(".json")) {
                out.add(new String[] { childRel.toLowerCase(), n.toLowerCase() });
            }
        }
    }

    /**
     * Writes the CSV into the folder root as catalog.csv, replacing an existing one. If the new
     * file cannot be written, the previous catalog is put back so nothing is lost.
     */
    static void install(Context ctx, Uri tree, byte[] csv) throws IOException {
        DocumentFile root = DocumentFile.fromTreeUri(ctx, tree);
        if (root == null || !root.exists()) throw new IOException("مجلد المكتبة غير متاح. اختره من جديد.");
        if (!root.canWrite()) throw new SecurityException("no write access");

        byte[] old = null;
        DocumentFile existing = root.findFile(TARGET_NAME);
        if (existing != null && existing.isFile()) {
            try (InputStream in = ctx.getContentResolver().openInputStream(existing.getUri())) {
                if (in != null) old = BookIndexer.readFully(in);
            } catch (IOException ignored) { /* no backup possible; still try to replace */ }
            if (!existing.delete()) throw new IOException("تعذّر استبدال ملف catalog.csv الموجود.");
        }

        try {
            writeNew(ctx, root, csv);
        } catch (IOException | RuntimeException e) {
            if (old != null) { // put the previous catalog back
                try { writeNew(ctx, root, old); } catch (Exception ignored) { /* nothing more to do */ }
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
    }

    private static void writeNew(Context ctx, DocumentFile root, byte[] data) throws IOException {
        DocumentFile target = root.createFile("text/csv", TARGET_NAME);
        if (target == null) throw new IOException("تعذّر إنشاء الملف في المجلد.");
        String made = target.getName();
        if (made != null && !made.equals(TARGET_NAME)) target.renameTo(TARGET_NAME); // some providers add a second ".csv"
        try (OutputStream out = ctx.getContentResolver().openOutputStream(target.getUri(), "wt")) {
            if (out == null) throw new IOException("تعذّر فتح الملف للكتابة.");
            out.write(data);
            out.flush();
        }
    }
}
