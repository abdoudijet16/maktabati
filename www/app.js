/* ==========================================================================
   Shamela Reader - Android app logic
   Fully client-side: no server. Library data lives in IndexedDB on the
   device, loaded once from either a .zip (category folders of .json books,
   same shape indexer.py expects) or plain .json files picked directly.
   ========================================================================== */

// ---- On-screen debug log -------------------------------------------------
// Shows every step/error inside the app UI itself (in the settings sheet),
// so problems can be diagnosed from a screenshot with no computer or USB
// debugging needed. Any uncaught error or unhandled promise rejection
// anywhere in the app is captured automatically.
function debugLog(msg) {
  try {
    console.log("[debug]", msg);
    const el = document.getElementById("debugLogText");
    const box = document.getElementById("debugLogBox");
    if (!el || !box) return;
    box.style.display = "block";
    const time = new Date().toLocaleTimeString();
    el.textContent += `[${time}] ${msg}\n`;
    el.scrollTop = el.scrollHeight;
  } catch (e) { /* never let logging itself crash the app */ }
}
window.addEventListener("error", (e) => {
  debugLog(`خطأ غير متوقع: ${e.message || e}` + (e.filename ? ` @ ${e.filename.split("/").pop()}:${e.lineno}` : ""));
});
window.addEventListener("unhandledrejection", (e) => {
  const reason = e.reason && (e.reason.message || e.reason.toString()) || String(e.reason);
  debugLog(`وعد مرفوض دون معالجة: ${reason}`);
});
document.addEventListener("DOMContentLoaded", () => {
  const copyBtn = document.getElementById("copyDebugLogBtn");
  if (copyBtn) copyBtn.onclick = () => {
    const text = document.getElementById("debugLogText").textContent;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => alert("تم نسخ سجل التشخيص."));
    } else {
      alert(text); // fallback: show it in a native alert so it can be screenshotted/read out
    }
  };
});

const KEY_CANDIDATES = {
  title:  ["title", "name", "book_title", "kitab"],
  author: ["author", "author_name", "writer"],
  pages:  ["pages", "content", "text", "body"],
  page_number: ["number", "page", "page_number", "index"],
  page_text:   ["text", "content", "body"],
  chapter:     ["chapter", "chapter_title", "section", "heading"],
};

function pick(obj, candidates, def) {
  for (const k of candidates) if (obj && Object.prototype.hasOwnProperty.call(obj, k)) return obj[k];
  return def;
}

function normalizePages(raw) {
  const out = [];
  if (Array.isArray(raw)) {
    raw.forEach((p, i) => {
      if (typeof p === "string") { out.push([i + 1, p]); return; }
      if (p && typeof p === "object") {
        const num = pick(p, KEY_CANDIDATES.page_number, i + 1);
        const text = pick(p, KEY_CANDIDATES.page_text, "");
        out.push([num, text]);
      }
    });
  } else if (raw && typeof raw === "object") {
    Object.keys(raw).forEach((k) => {
      const num = parseInt(k, 10) || null;
      const v = raw[k];
      const text = typeof v === "string" ? v : pick(v, KEY_CANDIDATES.page_text, "");
      out.push([num, text]);
    });
  }
  return out;
}

function parseBookJson(filename, data, category) {
  if (!data || typeof data !== "object") return null;
  const title = pick(data, KEY_CANDIDATES.title, filename.replace(/\.json$/i, ""));
  const author = pick(data, KEY_CANDIDATES.author, null);
  const rawPages = pick(data, KEY_CANDIDATES.pages, null);
  if (!rawPages) return null;
  const pages = normalizePages(rawPages);
  if (!pages.length) return null;
  return { title, author, category: category || "عام", pages };
}

/* -------------------------------------------------------------------- */
/* IndexedDB storage layer                                               */
/* -------------------------------------------------------------------- */
const DB_NAME = "shamela_reader";
const DB_VERSION = 1;
let dbPromise = null;

function openDb() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains("books")) {
        const s = db.createObjectStore("books", { keyPath: "id", autoIncrement: true });
        s.createIndex("category", "category");
      }
      if (!db.objectStoreNames.contains("pages")) {
        db.createObjectStore("pages", { keyPath: "key" }); // key = `${bookId}_${page}`
      }
      if (!db.objectStoreNames.contains("progress")) {
        db.createObjectStore("progress", { keyPath: "bookId" });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

async function tx(storeNames, mode) {
  const db = await openDb();
  return db.transaction(storeNames, mode);
}

async function clearLibrary() {
  const t = await tx(["books", "pages", "progress"], "readwrite");
  t.objectStore("books").clear();
  t.objectStore("pages").clear();
  t.objectStore("progress").clear();
  return new Promise((res) => (t.oncomplete = res));
}

async function addParsedBook(book) {
  const t = await tx(["books", "pages"], "readwrite");
  const booksStore = t.objectStore("books");
  const id = await new Promise((res, rej) => {
    const r = booksStore.add({
      title: book.title, author: book.author, category: book.category,
      page_count: book.pages.length,
    });
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
  const pagesStore = t.objectStore("pages");
  book.pages.forEach(([num, text]) => {
    pagesStore.put({ key: `${id}_${num}`, bookId: id, page: num, text });
  });
  return new Promise((res) => (t.oncomplete = () => res(id)));
}

async function getAllBooks() {
  const t = await tx(["books"], "readonly");
  return new Promise((res, rej) => {
    const r = t.objectStore("books").getAll();
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}

async function getPage(bookId, page) {
  const t = await tx(["pages"], "readonly");
  return new Promise((res, rej) => {
    const r = t.objectStore("pages").get(`${bookId}_${page}`);
    r.onsuccess = () => res(r.result ? r.result.text : null);
    r.onerror = () => rej(r.error);
  });
}

async function saveProgress(bookId, page) {
  const t = await tx(["progress"], "readwrite");
  t.objectStore("progress").put({ bookId, page, updatedAt: Date.now() });
}

async function getProgress(bookId) {
  const t = await tx(["progress"], "readonly");
  return new Promise((res) => {
    const r = t.objectStore("progress").get(bookId);
    r.onsuccess = () => res(r.result ? r.result.page : null);
    r.onerror = () => res(null);
  });
}

async function addSqliteBook(row) {
  const t = await tx(["books", "pages"], "readwrite");
  const booksStore = t.objectStore("books");
  const id = await new Promise((res, rej) => {
    const r = booksStore.add({
      title: row.title, author: row.author, category: row.category,
      page_count: row.page_count,
    });
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
  const pagesStore = t.objectStore("pages");
  row.pages.forEach(([num, text]) => {
    pagesStore.put({ key: `${id}_${num}`, bookId: id, page: num, text });
  });
  return new Promise((res) => (t.oncomplete = () => res(id)));
}

let sqlJsPromise = null;
function loadSqlJs() {
  if (!sqlJsPromise) {
    sqlJsPromise = initSqlJs({ locateFile: (f) => `vendor/${f}` });
  }
  return sqlJsPromise;
}

/* Reads a library.db built by indexer.py directly (books/pages/toc tables,
   same schema the desktop app uses) and imports every book into IndexedDB. */
async function importFromSqlite(buf, progressCb) {
  const SQL = await loadSqlJs();
  const db = new SQL.Database(buf);

  let books;
  try {
    books = db.exec(
      "SELECT book_id, title, author, category, page_count FROM books ORDER BY book_id"
    );
  } catch (err) {
    db.close();
    throw new Error("لم يتم العثور على جدول books المتوقع في هذا الملف: " + err.message);
  }
  if (!books.length) { db.close(); return 0; }

  const rows = books[0].values; // [[book_id, title, author, category, page_count], ...]
  let added = 0;
  for (let i = 0; i < rows.length; i++) {
    const [bookId, title, author, category, pageCount] = rows[i];
    progressCb && progressCb(i + 1, rows.length, `جارٍ الاستيراد... (${i + 1}/${rows.length})`);

    const pageRes = db.exec("SELECT page, text FROM pages WHERE book_id = ? ORDER BY page", [bookId]);
    const pages = pageRes.length ? pageRes[0].values.map(([p, t]) => [p, t]) : [];
    if (!pages.length) continue;

    await addSqliteBook({
      title: title || `كتاب ${bookId}`,
      author: author || null,
      category: category || "عام",
      page_count: pageCount || pages.length,
      pages,
    });
    added++;
  }
  db.close();
  return added;
}

/* -------------------------------------------------------------------- */
/* App state + screens                                                   */
/* -------------------------------------------------------------------- */
const state = { books: [], currentBook: null, currentPage: 1, filter: "", category: "" };

const els = {
  libraryScreen: document.getElementById("libraryScreen"),
  readerScreen: document.getElementById("readerScreen"),
  bookGrid: document.getElementById("bookGrid"),
  emptyState: document.getElementById("emptyState"),
  categoryChips: document.getElementById("categoryChips"),
  titleBar: document.getElementById("titleBar"),
  backBtn: document.getElementById("backBtn"),
  searchBtn: document.getElementById("searchBtn"),
  searchBar: document.getElementById("searchBar"),
  searchInput: document.getElementById("searchInput"),
  settingsBtn: document.getElementById("settingsBtn"),
  settingsOverlay: document.getElementById("settingsOverlay"),
  pageText: document.getElementById("pageText"),
  pageCounter: document.getElementById("pageCounter"),
  prevPageBtn: document.getElementById("prevPageBtn"),
  nextPageBtn: document.getElementById("nextPageBtn"),
  readerPager: document.getElementById("readerPager"),
  bookmarkBtn: document.getElementById("bookmarkBtn"),
};

async function refreshLibrary() {
  state.books = await getAllBooks();
  renderCategoryChips();
  renderBookGrid();
}

function renderCategoryChips() {
  const cats = Array.from(new Set(state.books.map((b) => b.category))).sort();
  els.categoryChips.innerHTML = "";
  if (!cats.length) return;
  const all = document.createElement("div");
  all.className = "chip" + (state.category === "" ? " active" : "");
  all.textContent = "الكل";
  all.onclick = () => { state.category = ""; renderCategoryChips(); renderBookGrid(); };
  els.categoryChips.appendChild(all);
  cats.forEach((c) => {
    const chip = document.createElement("div");
    chip.className = "chip" + (state.category === c ? " active" : "");
    chip.textContent = c;
    chip.onclick = () => { state.category = c; renderCategoryChips(); renderBookGrid(); };
    els.categoryChips.appendChild(chip);
  });
}

async function renderBookGrid() {
  let list = state.books;
  if (state.category) list = list.filter((b) => b.category === state.category);
  if (state.filter) {
    const q = state.filter.toLowerCase();
    list = list.filter((b) => (b.title || "").toLowerCase().includes(q) || (b.author || "").toLowerCase().includes(q));
  }
  els.bookGrid.innerHTML = "";
  els.emptyState.style.display = list.length === 0 && state.books.length === 0 ? "flex" : "none";
  for (const b of list) {
    const card = document.createElement("div");
    card.className = "book-card";
    const progress = await getProgress(b.id);
    card.innerHTML = `
      <div class="book-cover">📕</div>
      <div class="book-title">${escapeHtml(b.title || "بدون عنوان")}</div>
      <div class="book-author">${escapeHtml(b.author || "")}</div>
      ${progress ? `<div class="book-progress">متابعة القراءة · صفحة ${progress}</div>` : ""}
    `;
    card.onclick = () => openBook(b, progress || 1);
    els.bookGrid.appendChild(card);
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/* ---------------- Reader screen ---------------- */
async function openBook(book, startPage) {
  state.currentBook = book;
  state.currentPage = Math.min(Math.max(1, startPage || 1), book.page_count);
  els.libraryScreen.style.display = "none";
  els.readerScreen.style.display = "block";
  els.backBtn.style.display = "flex";
  els.searchBtn.style.display = "none";
  els.bookmarkBtn.style.display = "flex";
  els.titleBar.textContent = book.title;
  await renderPage(0);
}

function closeReader() {
  state.currentBook = null;
  els.readerScreen.style.display = "none";
  els.libraryScreen.style.display = "block";
  els.backBtn.style.display = "none";
  els.searchBtn.style.display = "flex";
  els.bookmarkBtn.style.display = "none";
  els.titleBar.textContent = "📚 مكتبتي الدينية الشاملة";
  renderBookGrid();
}

async function renderPage(direction) {
  const book = state.currentBook;
  if (!book) return;
  const text = await getPage(book.id, state.currentPage);
  if (direction) {
    els.pageText.classList.add(direction > 0 ? "turn-next" : "turn-prev");
    await new Promise((r) => setTimeout(r, 120));
  }
  els.pageText.textContent = text || "(هذه الصفحة غير متوفرة)";
  els.pageText.scrollTop = 0;
  els.pageText.classList.remove("turn-next", "turn-prev");
  els.pageCounter.textContent = `${state.currentPage} / ${book.page_count}`;
  saveProgress(book.id, state.currentPage);
}

function nextPage() {
  const book = state.currentBook;
  if (!book || state.currentPage >= book.page_count) return;
  state.currentPage += 1;
  renderPage(1);
}
function prevPage() {
  if (!state.currentBook || state.currentPage <= 1) return;
  state.currentPage -= 1;
  renderPage(-1);
}

/* Swipe gesture: swipe left -> next page, swipe right -> previous page
   (natural reading direction for RTL Arabic books). */
(function setupSwipe() {
  let startX = 0, startY = 0, tracking = false;
  els.readerPager.addEventListener("touchstart", (e) => {
    startX = e.touches[0].clientX; startY = e.touches[0].clientY; tracking = true;
  }, { passive: true });
  els.readerPager.addEventListener("touchend", (e) => {
    if (!tracking) return;
    tracking = false;
    const dx = e.changedTouches[0].clientX - startX;
    const dy = e.changedTouches[0].clientY - startY;
    if (Math.abs(dx) < 50 || Math.abs(dx) < Math.abs(dy) * 1.3) return;
    if (dx < 0) nextPage(); else prevPage();   // RTL: swipe left = forward
  }, { passive: true });
})();

els.prevPageBtn.onclick = prevPage;
els.nextPageBtn.onclick = nextPage;
els.backBtn.onclick = closeReader;

/* ---------------- Search ---------------- */
els.searchBtn.onclick = () => {
  const showing = els.searchBar.style.display !== "none";
  els.searchBar.style.display = showing ? "none" : "block";
  if (!showing) els.searchInput.focus();
};
els.searchInput.addEventListener("input", (e) => {
  state.filter = e.target.value.trim();
  renderBookGrid();
});

/* ---------------- Settings sheet: choose database source ---------------- */
const settingsEls = {
  overlay: els.settingsOverlay,
  folderOption: document.getElementById("pickFolderOption"),
  zipOption: document.getElementById("pickZipOption"),
  jsonOption: document.getElementById("pickJsonOption"),
  sqliteOption: document.getElementById("pickSqliteOption"),
  progress: document.getElementById("sheetProgress"),
  progressFill: document.getElementById("progressFill"),
  progressLabel: document.getElementById("progressLabel"),
  stats: document.getElementById("sheetStats"),
  closeBtn: document.getElementById("closeSheetBtn"),
  clearBtn: document.getElementById("clearLibraryBtn"),
};

function openSettings() {
  settingsEls.overlay.style.display = "flex";
  updateStats();
}
function closeSettings() {
  settingsEls.overlay.style.display = "none";
  refreshLibrary();
}
async function updateStats() {
  const books = await getAllBooks();
  settingsEls.stats.textContent = books.length
    ? `المكتبة الحالية: ${books.length} كتاب`
    : "لا توجد مكتبة محمّلة بعد.";
}

els.settingsBtn.onclick = openSettings;
document.getElementById("emptyOpenSettings").onclick = openSettings;
settingsEls.closeBtn.onclick = closeSettings;
settingsEls.clearBtn.onclick = async () => {
  if (!confirm("مسح كل الكتب المحمّلة من هذا الجهاز؟")) return;
  await clearLibrary();
  await updateStats();
  refreshLibrary();
};

function showProgress(show) {
  settingsEls.progress.style.display = show ? "block" : "none";
}
function setProgress(done, total, label) {
  const pct = total ? Math.round((done / total) * 100) : 0;
  settingsEls.progressFill.style.width = pct + "%";
  settingsEls.progressLabel.textContent = label || `${done} / ${total}`;
}

// Reads a picked file's bytes as a Uint8Array from the plugin's base64
// "data" field. Decodes in chunks with periodic yields (setTimeout 0) so
// a large file doesn't freeze the UI thread for a long stretch - a tight,
// unbroken synchronous loop over many MB would look exactly like "nothing
// happens" even while it's still working. Chunked atob (rather than a
// fetch() on a data: URL) also avoids any WebView-specific data-URI size
// limits on some older Android System WebView builds.
async function base64ToUint8Array(base64) {
  debugLog(`فك ترميز base64: ${(base64.length / 1024 / 1024).toFixed(2)} ميجابايت (نص)...`);
  const binary = atob(base64);
  const len = binary.length;
  const bytes = new Uint8Array(len);
  const CHUNK = 1 << 20; // yield every ~1M chars
  for (let i = 0; i < len; i++) {
    bytes[i] = binary.charCodeAt(i);
    if (i > 0 && i % CHUNK === 0) await new Promise((r) => setTimeout(r, 0));
  }
  debugLog(`تم فك الترميز: ${(len / 1024 / 1024).toFixed(2)} ميجابايت.`);
  return bytes;
}
async function base64ToUtf8Text(base64) {
  return new TextDecoder("utf-8").decode(await base64ToUint8Array(base64));
}

// All file/folder picking now goes through our own native FolderImporter
// plugin (see FolderImporterPlugin.java), using ACTION_OPEN_DOCUMENT /
// ACTION_OPEN_DOCUMENT_TREE rather than @capawesome/capacitor-file-picker's
// ACTION_GET_CONTENT. GET_CONTENT is handled inconsistently by many Android
// file managers for container-like files such as .zip (some browse into it
// instead of selecting it, then report a plain cancel even when nothing was
// actually cancelled). OPEN_DOCUMENT means "hand back this exact file/tree"
// and every tested file manager honors that the same way.
const { FolderImporter } = Capacitor.Plugins;

// Pick one top-level folder and import everything inside it (any depth of
// subfolders) in a single native call - see FolderImporterPlugin.java.
// This sidesteps both problems the other pickers have: no MIME-type
// filtering (native code reads by file extension, not by what a content
// provider claims the MIME type is), and no per-file/per-visible-folder
// selection limit (it recurses on the native side).
//
// Files stream in one at a time via the "folderImportFile" event (rather
// than being handed back all at once) and are parsed/imported immediately
// as each one arrives - this caps memory use to roughly one file at a
// time instead of holding an entire large library in memory simultaneously,
// which is what was crashing the app on a real-sized library folder.
settingsEls.folderOption.onclick = async () => {
  debugLog("تم الضغط على خيار المجلد الكامل.");
  if (!FolderImporter) {
    alert("ميزة اختيار المجلد غير متوفرة في هذا الإصدار من التطبيق.");
    return;
  }

  showProgress(true);
  setProgress(0, 0, "جارٍ فتح المجلد...");

  let done = 0, added = 0, skippedCount = 0;

  const fileListener = await FolderImporter.addListener("folderImportFile", async (file) => {
    done++;
    setProgress(done, done, `جارٍ الفهرسة... (${done}) ${file.name}`);
    try {
      if (file.type === "json") {
        const data = JSON.parse(file.text);
        const parts = (file.relPath || file.name).split("/").filter(Boolean);
        const category = parts.length > 1 ? parts[0] : null;
        const book = parseBookJson(file.name, data, category);
        if (book) { await addParsedBook(book); added++; }
      } else if (file.type === "sqlite") {
        const buf = await base64ToUint8Array(file.base64);
        const n = await importFromSqlite(buf, () => {});
        added += n;
      }
    } catch (err) {
      debugLog(`تجاوز ملف تالف (${file.relPath || file.name}): ${err.message}`);
    }
  });

  const skipListener = await FolderImporter.addListener("folderImportSkipped", (info) => {
    skippedCount++;
    const sizeNote = info.sizeMB ? `, ${info.sizeMB.toFixed(1)}MB` : "";
    debugLog(`تم تجاوز ${info.relPath} (${info.reason}${sizeNote})`);
  });

  let result;
  try {
    result = await FolderImporter.pickFolder();
  } catch (err) {
    debugLog("فشل FolderImporter.pickFolder: " + (err && err.message || err));
    if (!/cancel|إلغاء/i.test(err && err.message || "")) alert("فشل اختيار المجلد: " + (err && err.message || err));
    await fileListener.remove();
    await skipListener.remove();
    return;
  }
  await fileListener.remove();
  await skipListener.remove();

  debugLog(`تم فتح المجلد "${result.folderName || "?"}". أُضيف ${added} كتاب من ${done} ملف تمت معالجته. تم تجاوز: ${result.skippedCount ?? skippedCount}.`);
  if (!done) {
    alert("لم يتم العثور على أي ملفات JSON أو SQLite صالحة داخل هذا المجلد أو مجلداته الفرعية.");
    await updateStats();
    return;
  }
  setProgress(done, done, `تم! أُضيف ${added} كتاب.`);
  await updateStats();
};

settingsEls.zipOption.onclick = async () => {
  debugLog("تم الضغط على خيار الملف المضغوط.");
  let picked;
  try {
    // Using our own native pickFile (ACTION_OPEN_DOCUMENT), not
    // FilePicker.pickFiles (ACTION_GET_CONTENT): many file managers treat
    // ACTION_GET_CONTENT on a .zip as "browse into it" rather than "select
    // it", and finish with a plain cancel if no selection is finalized -
    // even when nothing was actually cancelled. OPEN_DOCUMENT means
    // "hand back this exact file" and is handled far more consistently.
    picked = await FolderImporter.pickFile();
  } catch (err) {
    debugLog("فشل اختيار الملف: " + (err && err.message || err));
    if (!/cancel|إلغاء/i.test(err && err.message || "")) alert("فشل اختيار الملف: " + (err && err.message || err));
    return;
  }
  debugLog(`تم الاختيار: ${picked ? (picked.name || "(بدون اسم)") : "لا شيء"}, حجم البيانات: ${picked && picked.base64 ? (picked.base64.length / 1024 / 1024).toFixed(2) + "MB (base64)" : "لا يوجد"}`);
  if (!picked || !picked.base64) { alert("تعذّر قراءة الملف: لم يتم استلام بيانات الملف."); return; }
  if (picked.name && !picked.name.toLowerCase().endsWith(".zip")) {
    if (!confirm(`الملف المختار "${picked.name}" لا يبدو ملفًا مضغوطًا (.zip). المتابعة على أي حال؟`)) return;
  }
  showProgress(true);
  setProgress(0, 1, "جارٍ فتح الملف المضغوط...");
  try {
    const buffer = await base64ToUint8Array(picked.base64);
    debugLog("جارٍ فتح الأرشيف عبر JSZip...");
    const zip = await JSZip.loadAsync(buffer);
    const entries = Object.values(zip.files).filter((f) => !f.dir && f.name.toLowerCase().endsWith(".json"));
    debugLog(`تم فتح الأرشيف. عدد ملفات JSON الموجودة: ${entries.length}`);
    let done = 0, added = 0;
    for (const entry of entries) {
      done++;
      setProgress(done, entries.length, `جارٍ الفهرسة... (${done}/${entries.length})`);
      try {
        const raw = await entry.async("string");
        const data = JSON.parse(raw);
        // category = first path segment, if the json sits inside a folder
        const parts = entry.name.split("/").filter(Boolean);
        const category = parts.length > 1 ? parts[0] : null;
        const filename = parts[parts.length - 1];
        const book = parseBookJson(filename, data, category);
        if (book) { await addParsedBook(book); added++; }
      } catch (err) { debugLog(`تجاوز ملف تالف (${entry.name}): ${err.message}`); }
    }
    debugLog(`اكتملت الفهرسة: أُضيف ${added} من أصل ${entries.length}.`);
    setProgress(entries.length, entries.length, `تم! أُضيف ${added} كتاب.`);
  } catch (err) {
    debugLog("خطأ أثناء معالجة الملف المضغوط: " + (err && err.message || err));
    setProgress(0, 1, "تعذّر قراءة الملف المضغوط: " + err.message);
  } finally {
    await updateStats();
  }
};

settingsEls.jsonOption.onclick = async () => {
  debugLog("تم الضغط على خيار ملفات JSON.");
  let result;
  try {
    result = await FolderImporter.pickFilesMulti();
  } catch (err) {
    debugLog("فشل اختيار الملفات: " + (err && err.message || err));
    if (!/cancel|إلغاء/i.test(err && err.message || "")) alert("فشل اختيار الملفات: " + (err && err.message || err));
    return;
  }
  const files = (result.files || []).filter((f) => f.base64);
  debugLog(`تم اختيار ${files.length} ملف: ${files.map((f) => f.name).join(", ")}`);
  if (!files.length) { alert("تعذّر قراءة الملفات: لم يتم استلام بيانات."); return; }
  showProgress(true);
  let done = 0, added = 0;
  for (const file of files) {
    done++;
    setProgress(done, files.length, `جارٍ الفهرسة... (${done}/${files.length})`);
    try {
      const text = await base64ToUtf8Text(file.base64);
      const data = JSON.parse(text);
      const book = parseBookJson(file.name, data, null);
      if (book) { await addParsedBook(book); added++; }
    } catch (err) { debugLog(`تجاوز ملف تالف (${file.name}): ${err.message}`); }
  }
  debugLog(`اكتملت الفهرسة: أُضيف ${added} من أصل ${files.length}.`);
  setProgress(files.length, files.length, `تم! أُضيف ${added} كتاب.`);
  await updateStats();
};

settingsEls.sqliteOption.onclick = async () => {
  debugLog("تم الضغط على خيار SQLite.");
  let picked;
  try {
    picked = await FolderImporter.pickFile();
  } catch (err) {
    debugLog("فشل اختيار الملف: " + (err && err.message || err));
    if (!/cancel|إلغاء/i.test(err && err.message || "")) alert("فشل اختيار الملف: " + (err && err.message || err));
    return;
  }
  debugLog(`تم الاختيار: ${picked ? (picked.name || "(بدون اسم)") : "لا شيء"}`);
  if (!picked || !picked.base64) { alert("تعذّر قراءة الملف: لم يتم استلام بيانات الملف."); return; }
  showProgress(true);
  setProgress(0, 1, "جارٍ فتح قاعدة البيانات...");
  try {
    const buf = await base64ToUint8Array(picked.base64);
    const added = await importFromSqlite(buf, (done, total, label) => setProgress(done, total, label));
    debugLog(`تم استيراد قاعدة البيانات: أُضيف ${added} كتاب.`);
    setProgress(1, 1, `تم! أُضيف ${added} كتاب.`);
  } catch (err) {
    debugLog("خطأ أثناء معالجة قاعدة البيانات: " + (err && err.message || err));
    setProgress(0, 1, "تعذّر قراءة قاعدة البيانات: " + err.message);
  } finally {
    await updateStats();
  }
};

/* ---------------- Boot ---------------- */
(async function boot() {
  await refreshLibrary();
  if (!state.books.length) openSettings();
})();
