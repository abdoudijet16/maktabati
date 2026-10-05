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
     * @param source "saf" (a document URI in a folder the person picked)
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
