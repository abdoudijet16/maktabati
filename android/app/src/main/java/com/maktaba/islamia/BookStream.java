package com.maktaba.islamia;

import android.content.Context;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;

import androidx.documentfile.provider.DocumentFile;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the pages of ONE book as a stream, a few at a time.
 *
 * The old approach read the whole book file into memory, turned it into one big
 * string, sent that across the Capacitor bridge and parsed it in JS - which
 * needed a size cap (60MB) to avoid running out of memory. Here the JSON is read
 * token by token with android.util.JsonReader and pages are handed out in small
 * batches, so a book's size no longer matters: only one batch is in memory at a
 * time, on both the native and the JS side.
 *
 * Understands the same page layouts the app has always accepted:
 *   - the pages live under a top-level "pages" (or "content"/"text"/"body") key
 *   - as an array of strings, or an array of objects whose text is under
 *     "text" (or "content"/"body"), or an object map of page number -> text.
 * Pages are numbered 1..N in order; the page number written in the file ("page") is
 * reported alongside each page (see lastNums) so the table of contents, which refers to
 * those numbers, can be mapped to the right place in the book.
 */
final class BookStream {
    private static final int CHAR_BUDGET = 2_000_000; // stop a batch once it holds this many characters

    private enum State { SEEK, ARRAY, MAP, DONE }

    private final JsonReader reader;
    private State state = State.SEEK;
    private List<String> mapPages;
    private List<Integer> mapNums;
    private int mapIndex = 0;
    private final List<Integer> nums = new ArrayList<>(); // page numbers of the batch last returned by next()
    private int objNum = 0;
    private boolean closed = false;

    static BookStream open(Context ctx, String kind, String ref) throws IOException {
        InputStream in = openInput(ctx, kind, ref);
        try {
            return new BookStream(in);
        } catch (IOException | RuntimeException e) {
            try { in.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private static InputStream openInput(Context ctx, String kind, String ref) throws IOException {
        InputStream in;
        if ("catalog".equals(kind)) {
            // ref is "<tree uri of the maktaba folder>|<path relative to it>", e.g.
            // "content://.../tree/...|التفسير/كتاب.json". The file is found by walking
            // down the category folder(s), so nothing has to be indexed up front.
            int sep = ref.indexOf('|');
            if (sep < 0) throw new IOException("مرجع كتاب غير صالح.");
            Uri tree = Uri.parse(ref.substring(0, sep));
            DocumentFile file = locate(ctx, tree, ref.substring(sep + 1));
            if (file == null) throw new IOException("ملف الكتاب غير موجود في مجلد المكتبة. تأكد من فك ضغطه داخل مجلد التصنيف.");
            in = ctx.getContentResolver().openInputStream(file.getUri());
            if (in == null) throw new IOException("تعذّر فتح الملف.");
        } else if ("saf".equals(kind)) {
            in = ctx.getContentResolver().openInputStream(Uri.parse(ref));
            if (in == null) throw new IOException("تعذّر فتح الملف.");
        } else {
            throw new IOException("نوع مصدر غير معروف.");
        }
        return in;
    }

    /**
     * Reads only the table of contents of a book: [{id, title, page, parent}, ...].
     * Book files list "toc" before "pages", so this normally stops long before the
     * page text; if a file has the order the other way round, the pages are skipped
     * without being kept in memory. Returns an empty array when the book has no toc.
     */
    static JSArray readToc(Context ctx, String kind, String ref) throws IOException {
        InputStream in = openInput(ctx, kind, ref);
        JSArray out = new JSArray();
        try (JsonReader r = new JsonReader(newBufferedReader(in))) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) return out;
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if ("toc".equals(name) && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) {
                        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
                        int id = 0, page = 0, parent = 0;
                        String title = "";
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            if ("id".equals(k)) id = readInt(r);
                            else if ("title".equals(k)) title = readText(r);
                            else if ("page".equals(k)) page = readInt(r);
                            else if ("parent".equals(k)) parent = readInt(r);
                            else r.skipValue();
                        }
                        r.endObject();
                        JSObject o = new JSObject();
                        o.put("id", id);
                        o.put("title", title);
                        o.put("page", page);
                        o.put("parent", parent);
                        out.put(o);
                    }
                    r.endArray();
                    return out;
                }
                r.skipValue();
            }
        }
        return out;
    }

    private static BufferedReader newBufferedReader(InputStream in) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 64 * 1024);
        br.mark(1);
        if (br.read() != 0xFEFF) br.reset(); // skip a UTF-8 byte-order mark if present
        return br;
    }

    /**
     * Finds a book file inside the picked maktaba folder: first by its exact relative
     * path (category/file.json), then by file name at the top level, and finally by
     * searching every category folder for that file name (so a book that was put in a
     * different category folder is still found).
     */
    private static DocumentFile locate(Context ctx, Uri tree, String rel) {
        DocumentFile root = DocumentFile.fromTreeUri(ctx, tree);
        if (root == null || !root.exists()) return null;
        String[] parts = rel.split("/");
        DocumentFile cur = root;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            cur = cur.findFile(part);
            if (cur == null) break;
        }
        if (cur != null && cur.isFile()) return cur;
        return search(root, parts[parts.length - 1], 0);
    }

    private static DocumentFile search(DocumentFile dir, String name, int depth) {
        if (depth > 6) return null;
        DocumentFile[] kids = dir.listFiles();
        if (kids == null) return null;
        for (DocumentFile k : kids) {
            if (k.isFile() && name.equalsIgnoreCase(k.getName())) return k;
        }
        for (DocumentFile k : kids) {
            if (k.isDirectory()) {
                DocumentFile hit = search(k, name, depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private BookStream(InputStream in) throws IOException {
        this.reader = new JsonReader(newBufferedReader(in));
        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
            reader.beginObject();
        } else {
            state = State.DONE; // not a book object: no pages
        }
    }

    boolean isDone() { return state == State.DONE; }

    /** Returns the next batch of page texts (at most maxPages, and roughly CHAR_BUDGET characters). */
    synchronized List<String> next(int maxPages) throws IOException {
        if (closed) throw new IOException("تم إغلاق قراءة الكتاب.");
        List<String> out = new ArrayList<>();
        nums.clear();
        int chars = 0;
        while (out.size() < maxPages && chars < CHAR_BUDGET) {
            int before = out.size();
            switch (state) {
                case SEEK:
                    if (!reader.hasNext()) { state = State.DONE; break; }
                    String name = reader.nextName();
                    if (isPagesKey(name)) {
                        JsonToken t = reader.peek();
                        if (t == JsonToken.BEGIN_ARRAY) { reader.beginArray(); state = State.ARRAY; break; }
                        if (t == JsonToken.BEGIN_OBJECT) { loadMap(); state = State.MAP; break; }
                    }
                    reader.skipValue();
                    break;
                case ARRAY:
                    if (!reader.hasNext()) { reader.endArray(); state = State.DONE; break; }
                    readArrayElement(out);
                    break;
                case MAP:
                    if (mapIndex >= mapPages.size()) { state = State.DONE; break; }
                    out.add(mapPages.get(mapIndex));
                    nums.add(mapNums.get(mapIndex));
                    mapIndex++;
                    break;
                case DONE:
                    return out;
            }
            for (int i = before; i < out.size(); i++) chars += out.get(i).length();
        }
        return out;
    }

    /** Page numbers (as written in the file, 0 when unknown) of the pages returned by the last next() call. */
    synchronized List<Integer> lastNums() { return new ArrayList<>(nums); }

    synchronized void close() {
        if (closed) return;
        closed = true;
        try { reader.close(); } catch (Exception ignored) {}
    }

    private static boolean isPagesKey(String name) {
        return "pages".equals(name) || "content".equals(name) || "text".equals(name) || "body".equals(name);
    }

    private void readArrayElement(List<String> out) throws IOException {
        switch (reader.peek()) {
            case STRING:
                out.add(reader.nextString());
                nums.add(0);
                break;
            case BEGIN_OBJECT:
                out.add(readPageObject());
                nums.add(objNum);
                break;
            case BEGIN_ARRAY:
                reader.skipValue();
                out.add(""); // an array where a page should be: an empty page, as before
                nums.add(0);
                break;
            default:
                reader.skipValue(); // numbers / booleans / null are not pages
        }
    }

    /**
     * A page given as an object: its text is under "text", else "content", else "body".
     * Its number ("page", "number" or "page_number") is left in objNum (0 if absent).
     */
    private String readPageObject() throws IOException {
        String text = null, content = null, body = null;
        objNum = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            String n = reader.nextName();
            if ("text".equals(n)) text = readTextValue();
            else if ("content".equals(n)) content = readTextValue();
            else if ("body".equals(n)) body = readTextValue();
            else if ("page".equals(n) || "number".equals(n) || "page_number".equals(n)) {
                int v = readInt(reader);
                if (objNum == 0 && v > 0) objNum = v;
            }
            else reader.skipValue(); // anything else is ignored
        }
        reader.endObject();
        if (text != null) return text;
        if (content != null) return content;
        if (body != null) return body;
        return "";
    }

    private String readTextValue() throws IOException {
        return readText(reader);
    }

    private static String readText(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.STRING) return r.nextString();
        if (t == JsonToken.NULL) { r.nextNull(); return ""; }
        r.skipValue();
        return "";
    }

    /** A number written as a JSON number or a numeric string; 0 for anything else. */
    private static int readInt(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NUMBER || t == JsonToken.STRING) {
            String s = r.nextString().trim();
            try { return (int) Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
        }
        r.skipValue();
        return 0;
    }

    /**
     * Pages given as an object map (page number -> text). Rare, so it is read in one go.
     * Ordered like a JavaScript object: whole-number keys ascending first, then the rest
     * in file order - exactly what the app did before.
     */
    private void loadMap() throws IOException {
        List<String[]> numeric = new ArrayList<>();
        List<String> others = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            JsonToken t = reader.peek();
            String text;
            if (t == JsonToken.STRING) text = reader.nextString();
            else if (t == JsonToken.BEGIN_OBJECT) text = readPageObject();
            else { reader.skipValue(); text = ""; }
            if (key.matches("0|[1-9][0-9]{0,8}")) numeric.add(new String[]{key, text});
            else others.add(text);
        }
        reader.endObject();
        java.util.Collections.sort(numeric, (a, b) -> Long.compare(Long.parseLong(a[0]), Long.parseLong(b[0])));
        mapPages = new ArrayList<>();
        mapNums = new ArrayList<>();
        for (String[] e : numeric) {
            mapPages.add(e[1]);
            int num = 0;
            try { num = Integer.parseInt(e[0]); } catch (NumberFormatException ignored) {}
            mapNums.add(num);
        }
        for (String o : others) { mapPages.add(o); mapNums.add(0); }
    }
}
