package com.maktaba.islamia;

import android.content.Context;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
 * Page numbers are ignored: pages are numbered 1..N in order.
 */
final class BookStream {
    private static final int CHAR_BUDGET = 2_000_000; // stop a batch once it holds this many characters

    private enum State { SEEK, ARRAY, MAP, DONE }

    private final JsonReader reader;
    private final ZipFile zipFile; // null when reading from a picked folder
    private State state = State.SEEK;
    private List<String> mapPages;
    private int mapIndex = 0;
    private boolean closed = false;

    static BookStream open(Context ctx, String kind, String ref) throws IOException {
        InputStream in;
        ZipFile zf = null;
        if ("zip".equals(kind)) {
            // ref is "<absolute path to the zip file>|<entry name>" - this lets
            // books come from more than one zip (the auto-downloaded library, or
            // a zip the person picked manually) rather than assuming a single
            // fixed file.
            int sep = ref.indexOf('|');
            if (sep < 0) throw new IOException("مرجع كتاب غير صالح.");
            File zip = new File(ref.substring(0, sep));
            String entryName = ref.substring(sep + 1);
            if (!zip.exists()) throw new IOException("ملف المكتبة غير موجود.");
            zf = new ZipFile(zip);
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null) {
                zf.close();
                throw new IOException("لم يتم العثور على الكتاب داخل الملف المضغوط.");
            }
            in = zf.getInputStream(entry);
        } else if ("saf".equals(kind)) {
            in = ctx.getContentResolver().openInputStream(Uri.parse(ref));
            if (in == null) throw new IOException("تعذّر فتح الملف.");
        } else {
            throw new IOException("نوع مصدر غير معروف.");
        }
        try {
            return new BookStream(in, zf);
        } catch (IOException | RuntimeException e) {
            try { in.close(); } catch (Exception ignored) {}
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private BookStream(InputStream in, ZipFile zf) throws IOException {
        this.zipFile = zf;
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 64 * 1024);
        br.mark(1);
        if (br.read() != 0xFEFF) br.reset(); // skip a UTF-8 byte-order mark if present
        this.reader = new JsonReader(br);
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
                    out.add(mapPages.get(mapIndex++));
                    break;
                case DONE:
                    return out;
            }
            for (int i = before; i < out.size(); i++) chars += out.get(i).length();
        }
        return out;
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        try { reader.close(); } catch (Exception ignored) {}
        if (zipFile != null) try { zipFile.close(); } catch (Exception ignored) {}
    }

    private static boolean isPagesKey(String name) {
        return "pages".equals(name) || "content".equals(name) || "text".equals(name) || "body".equals(name);
    }

    private void readArrayElement(List<String> out) throws IOException {
        switch (reader.peek()) {
            case STRING:
                out.add(reader.nextString());
                break;
            case BEGIN_OBJECT:
                out.add(readPageObject());
                break;
            case BEGIN_ARRAY:
                reader.skipValue();
                out.add(""); // an array where a page should be: an empty page, as before
                break;
            default:
                reader.skipValue(); // numbers / booleans / null are not pages
        }
    }

    /** A page given as an object: its text is under "text", else "content", else "body". */
    private String readPageObject() throws IOException {
        String text = null, content = null, body = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String n = reader.nextName();
            if ("text".equals(n)) text = readTextValue();
            else if ("content".equals(n)) content = readTextValue();
            else if ("body".equals(n)) body = readTextValue();
            else reader.skipValue(); // page number and anything else is ignored
        }
        reader.endObject();
        if (text != null) return text;
        if (content != null) return content;
        if (body != null) return body;
        return "";
    }

    private String readTextValue() throws IOException {
        JsonToken t = reader.peek();
        if (t == JsonToken.STRING) return reader.nextString();
        if (t == JsonToken.NULL) { reader.nextNull(); return ""; }
        reader.skipValue();
        return "";
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
        for (String[] e : numeric) mapPages.add(e[1]);
        mapPages.addAll(others);
    }
}
