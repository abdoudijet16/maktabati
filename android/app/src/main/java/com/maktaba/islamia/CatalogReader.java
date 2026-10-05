package com.maktaba.islamia;

import com.getcapacitor.JSObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the library catalog (a CSV file sitting inside the "maktaba" folder, next to
 * the category folders) and turns every row into an index entry for the library
 * screen. Nothing is read from the book files themselves: the books only show up in
 * the library (title / author / category) and their text is loaded when the person
 * taps a book.
 *
 * Expected columns (header row, any order, English or Arabic names):
 *   title    | name | book | الكتاب | العنوان | اسم الكتاب     (required)
 *   author   | writer | المؤلف
 *   category | التصنيف | القسم
 *   file     | filename | path | ملف | اسم الملف               (optional)
 *
 * "file" is the book's file name (or a path relative to the maktaba folder). When it
 * is empty the file is assumed to be  <category>/<title>.json .
 * Without a header row the column order is: title, author, category, file.
 * Comma, semicolon and tab separators are all accepted (Excel exports vary).
 */
final class CatalogReader {
    private CatalogReader() {}

    private static final String[] H_TITLE = {"title", "name", "book", "book_title", "الكتاب", "العنوان", "اسم الكتاب", "عنوان الكتاب"};
    private static final String[] H_AUTHOR = {"author", "writer", "author_name", "المؤلف", "المؤلّف", "المؤلف/ة"};
    private static final String[] H_CATEGORY = {"category", "cat", "categorie", "التصنيف", "القسم", "الفئة"};
    private static final String[] H_FILE = {"file", "filename", "file_name", "path", "relpath", "ملف", "اسم الملف", "المسار"};
    private static final String[] H_PAGES = {"pages", "page_count", "عدد الصفحات"};

    /** One catalog row: the book's display data and where its file should be (relative to the maktaba folder). */
    static final class Entry {
        String title, author, category, rel;
        int pages;
    }

    /** Returns the number of books indexed; rows without a title are counted in skipped[0]. */
    static int read(InputStream in, String treeUri, BookIndexer.Batch batch, int[] skipped) throws IOException {
        String text = decode(BookIndexer.readFully(in));
        int count = 0;
        for (Entry e : parseEntries(text, skipped)) {
            JSObject o = new JSObject();
            o.put("title", e.title);
            if (!e.author.isEmpty()) o.put("author", e.author);
            if (!e.category.isEmpty()) o.put("category", e.category);
            o.put("pageCount", e.pages);
            o.put("source", "catalog");
            o.put("ref", treeUri + "|" + e.rel);
            batch.add(o);
            count++;
        }
        return count;
    }

    static String decode(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1); // BOM from Excel
        return text;
    }

    /** Parses the CSV text into entries (the single place that knows the column rules). */
    static List<Entry> parseEntries(String text, int[] skipped) {
        List<Entry> out = new ArrayList<>();
        List<String[]> rows = parse(text);
        if (rows.isEmpty()) return out;

        String[] header = rows.get(0);
        int iTitle = find(header, H_TITLE);
        int iAuthor = find(header, H_AUTHOR);
        int iCategory = find(header, H_CATEGORY);
        int iFile = find(header, H_FILE);
        int iPages = find(header, H_PAGES);
        int start = 1;
        if (iTitle < 0) { // no recognizable header: positional columns, first row is data
            iTitle = 0; iAuthor = 1; iCategory = 2; iFile = 3; iPages = -1;
            start = 0;
        }

        for (int r = start; r < rows.size(); r++) {
            String[] row = rows.get(r);
            String title = cell(row, iTitle);
            if (title.isEmpty()) { skipped[0]++; continue; }
            Entry e = new Entry();
            e.title = title;
            e.author = cell(row, iAuthor);
            e.category = cell(row, iCategory);
            String file = cell(row, iFile).replace('\\', '/');
            if (file.isEmpty()) file = title;
            if (!file.toLowerCase().endsWith(".json")) file = file + ".json";
            e.rel = (file.contains("/") || e.category.isEmpty()) ? file : e.category + "/" + file;
            try { e.pages = Integer.parseInt(cell(row, iPages)); } catch (NumberFormatException ignored) {}
            out.add(e);
        }
        return out;
    }

    private static String cell(String[] row, int i) {
        if (i < 0 || i >= row.length) return "";
        return row[i].trim();
    }

    private static int find(String[] header, String[] names) {
        for (int i = 0; i < header.length; i++) {
            String h = header[i].trim().toLowerCase();
            for (String n : names) if (h.equals(n)) return i;
        }
        return -1;
    }

    /** Small RFC-4180 CSV parser (quoted fields, doubled quotes, newlines inside quotes). */
    static List<String[]> parse(String text) {
        char delim = detectDelimiter(text);
        List<String[]> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else quoted = false;
                } else sb.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == delim) {
                cur.add(sb.toString()); sb.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                cur.add(sb.toString()); sb.setLength(0);
                addRow(rows, cur);
                cur = new ArrayList<>();
            } else sb.append(c);
        }
        if (sb.length() > 0 || !cur.isEmpty()) { cur.add(sb.toString()); addRow(rows, cur); }
        return rows;
    }

    private static void addRow(List<String[]> rows, List<String> cur) {
        boolean blank = true;
        for (String s : cur) if (!s.trim().isEmpty()) { blank = false; break; }
        if (!blank) rows.add(cur.toArray(new String[0]));
    }

    private static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        int[] counts = new int[3];
        char[] cands = {',', ';', '\t'};
        boolean q = false;
        for (char c : first.toCharArray()) {
            if (c == '"') q = !q;
            else if (!q) for (int k = 0; k < 3; k++) if (c == cands[k]) counts[k]++;
        }
        int best = 0;
        for (int k = 1; k < 3; k++) if (counts[k] > counts[best]) best = k;
        return cands[best];
    }
}
