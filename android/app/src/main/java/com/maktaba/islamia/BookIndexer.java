package com.maktaba.islamia;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the lightweight "index" entry for a book (title/author/category/page
 * count + where to find the file later) by reading only the first few KB of
 * its JSON, instead of parsing and storing the whole book.
 *
 * Book files start with their metadata (book_id, title, author, category,
 * page_count, toc, pages), so the head of the file is enough. This is what
 * makes importing thousands of books fast: the full text of a book is only
 * read the first time the person actually opens it.
 */
final class BookIndexer {
    private BookIndexer() {}

    private static final int HEAD_BYTES = 8192;
    private static final int BATCH_SIZE = 300;

    private static final Pattern P_TITLE = Pattern.compile("\"title\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern P_AUTHOR = Pattern.compile("\"author\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern P_CATEGORY = Pattern.compile("\"category\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern P_PAGE_COUNT = Pattern.compile("\"page_count\"\\s*:\\s*(\\d+)");

    /**
     * @param in     stream positioned at the start of the book's JSON
     * @param fileName file name (used as the title if the JSON has none)
     * @param folder name of the folder the file sits in (used as category if the JSON has none)
     * @param source "saf" (a document URI in a folder the person picked) or "zip" (an entry in the downloaded zip)
     * @param ref    the URI / zip entry name needed to read the full file later
     */
    static JSObject readMeta(InputStream in, String fileName, String folder, String source, String ref) throws IOException {
        byte[] buf = new byte[HEAD_BYTES];
        int total = 0, n;
        while (total < buf.length && (n = in.read(buf, total, buf.length - total)) != -1) total += n;
        String head = new String(buf, 0, total, StandardCharsets.UTF_8);

        // Only look at the part before the big "toc"/"pages" arrays, so a chapter
        // title inside the table of contents is never mistaken for the book title.
        int cut = head.length();
        int iToc = head.indexOf("\"toc\"");
        int iPages = head.indexOf("\"pages\"");
        if (iToc >= 0) cut = Math.min(cut, iToc);
        if (iPages >= 0) cut = Math.min(cut, iPages);
        String region = head.substring(0, cut);

        String title = firstString(P_TITLE, region);
        String author = firstString(P_AUTHOR, region);
        String category = firstString(P_CATEGORY, region);
        int pageCount = 0;
        Matcher m = P_PAGE_COUNT.matcher(region);
        if (m.find()) {
            try { pageCount = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }

        if (title == null || title.trim().isEmpty()) {
            title = fileName.replaceAll("(?i)\\.json$", "").replace('_', ' ');
        }
        if (category == null || category.trim().isEmpty()) {
            category = (folder == null || folder.isEmpty()) ? null : folder;
        }

        JSObject o = new JSObject();
        o.put("title", title.trim());
        if (author != null && !author.trim().isEmpty()) o.put("author", author.trim());
        if (category != null) o.put("category", category.trim());
        o.put("pageCount", pageCount);
        o.put("source", source);
        o.put("ref", ref);
        return o;
    }

    private static String firstString(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        try {
            // Let a real JSON parser decode the string literal (handles unicode and backslash escapes).
            Object v = new JSONTokener(m.group(1)).nextValue();
            return v instanceof String ? (String) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Collects index entries and sends them to JS in batches (a few hundred at a time). */
    static final class Batch {
        private JSArray items = new JSArray();

        void add(JSObject entry) {
            items.put(entry);
            if (items.length() >= BATCH_SIZE) flush();
        }

        void flush() {
            if (items.length() == 0) return;
            FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();
            if (plugin != null) {
                JSObject ev = new JSObject();
                ev.put("items", items);
                plugin.emitImportEvent("libraryIndexBatch", ev);
            }
            items = new JSArray();
        }
    }

    /**
     * Walks a zip file's entry list (a cheap lookup in its central directory) and
     * indexes every book inside it - shared by the auto-downloaded library and by
     * a zip file the person picks manually, so both paths use the same logic.
     * JSON books are indexed by reading only their head (see readMeta); a SQLite
     * database inside the zip is read in full and emitted as `fileEvent`, exactly
     * like the folder-picker's SQLite path.
     *
     * @param zipFile      the zip file, already fully on disk
     * @param fileEvent    event name for a whole SQLite file (e.g. "downloadImportFile")
     * @param skipEvent    event name for a skipped/corrupt entry
     */
    static void indexZipFile(File zipFile, int[] scanned, int[] skipped, String fileEvent, String skipEvent) throws IOException {
        String zipPath = zipFile.getAbsolutePath();
        Batch batch = new Batch();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.contains("__MACOSX/")) continue;
                int slash = name.lastIndexOf('/');
                String base = name.substring(slash + 1);
                if (base.startsWith("._")) continue;
                String folder = "";
                if (slash > 0) {
                    String parent = name.substring(0, slash);
                    folder = parent.substring(parent.lastIndexOf('/') + 1);
                }

                String lower = base.toLowerCase();
                boolean isJson = lower.endsWith(".json");
                boolean isSqlite = lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3");
                if (!isJson && !isSqlite) continue;

                FolderImporterPlugin plugin = FolderImporterPlugin.getActiveInstance();

                if (isJson) {
                    try (InputStream in = zf.getInputStream(entry)) {
                        batch.add(readMeta(in, base, folder, "zip", zipPath + "|" + name));
                        scanned[0]++;
                    } catch (Exception e) {
                        skipped[0]++;
                        if (plugin != null) {
                            JSObject skip = new JSObject();
                            skip.put("relPath", name);
                            skip.put("reason", "error: " + e.getMessage());
                            plugin.emitImportEvent(skipEvent, skip);
                        }
                    }
                    continue;
                }

                long size = entry.getSize();
                if (size > MAX_SQLITE_BYTES) {
                    skipped[0]++;
                    if (plugin != null) {
                        JSObject skip = new JSObject();
                        skip.put("relPath", name);
                        skip.put("reason", "large");
                        skip.put("sizeMB", size / 1024.0 / 1024.0);
                        plugin.emitImportEvent(skipEvent, skip);
                    }
                    continue;
                }
                try {
                    byte[] bytes = readFully(zf.getInputStream(entry));
                    JSObject fileObj = new JSObject();
                    fileObj.put("name", base);
                    fileObj.put("relPath", name);
                    fileObj.put("type", "sqlite");
                    fileObj.put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
                    scanned[0]++;
                    if (plugin != null) {
                        plugin.emitImportEvent(fileEvent, fileObj);
                        plugin.waitForImportAck(20000); // backpressure: wait for JS to finish this file
                    }
                } catch (Exception e) {
                    skipped[0]++;
                    if (plugin != null) {
                        JSObject skip = new JSObject();
                        skip.put("relPath", name);
                        skip.put("reason", "error: " + e.getMessage());
                        plugin.emitImportEvent(skipEvent, skip);
                    }
                }
            }
        }
        batch.flush();
    }

    private static final long MAX_SQLITE_BYTES = 60L * 1024 * 1024;

    static byte[] readFully(InputStream in) throws IOException {
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

    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        file.delete();
    }
}
