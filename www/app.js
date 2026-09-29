/* ==========================================================================
   Maktaba Islamia - Android app logic
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
const DB_NAME = "maktaba_islamia";
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

/* Fast import: only the index entry of each book (title/author/category/where its
   file is) is stored up front. The pages are stored the first time the book is opened. */
async function addIndexBatch(items) {
  if (!items.length) return;
  const t = await tx(["books"], "readwrite");
  const store = t.objectStore("books");
  items.forEach((it) => store.add({
    title: it.title,
    author: it.author || null,
    category: it.category || "عام",
    page_count: it.pageCount || 0,
    source: it.source,   // "zip" (downloaded library) or "saf" (a folder the person picked)
    ref: it.ref,         // zip entry name / document URI - used to read the file later
    loaded: false,
  }));
  return new Promise((res, rej) => {
    t.oncomplete = () => res();
    t.onerror = () => rej(t.error);
    t.onabort = () => rej(t.error);
  });
}

// Writes one streamed batch of a book's pages (called repeatedly while a book
// loads, instead of holding the whole book in memory - see ensureBookLoaded).
async function storePageBatch(bookId, startIndex, pages) {
  const t = await tx(["pages"], "readwrite");
  const store = t.objectStore("pages");
  pages.forEach((text, i) => {
    store.put({ key: `${bookId}_${startIndex + i + 1}`, bookId, page: startIndex + i + 1, text });
  });
  return new Promise((res, rej) => {
    t.oncomplete = () => res();
    t.onerror = () => rej(t.error);
    t.onabort = () => rej(t.error);
  });
}

// Records how far a book's load has gotten, so an interrupted load (app closed,
// killed in the background, crash) resumes from here instead of starting the
// book over from page 1 next time it's opened.
async function updatePagesLoaded(bookId, n) {
  const t = await tx(["books"], "readwrite");
  const store = t.objectStore("books");
  const req = store.get(bookId);
  return new Promise((res, rej) => {
    req.onsuccess = () => {
      const b = req.result;
      if (b) { b.pagesLoaded = n; store.put(b); }
      res();
    };
    req.onerror = () => rej(req.error);
    t.onabort = () => rej(t.error);
  });
}

// Marks a book as fully loaded once every page batch has been written.
async function finalizeBook(book, pageCount) {
  const updated = { ...book, loaded: true, page_count: pageCount };
  const t = await tx(["books"], "readwrite");
  t.objectStore("books").put(updated);
  return new Promise((res, rej) => {
    t.oncomplete = () => res(updated);
    t.onerror = () => rej(t.error);
    t.onabort = () => rej(t.error);
  });
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
const state = { books: [], currentBook: null, currentPage: 1, filter: "", viewMode: "all", groupSelection: null };

const els = {
  libraryScreen: document.getElementById("libraryScreen"),
  readerScreen: document.getElementById("readerScreen"),
  bookGrid: document.getElementById("bookGrid"),
  emptyState: document.getElementById("emptyState"),
  viewTabs: document.getElementById("viewTabs"),
  groupHeader: document.getElementById("groupHeader"),
  groupHeaderTitle: document.getElementById("groupHeaderTitle"),
  groupBackBtn: document.getElementById("groupBackBtn"),
  groupList: document.getElementById("groupList"),
  libraryCount: document.getElementById("libraryCount"),
  prefetchStatus: document.getElementById("prefetchStatus"),
  titleBar: document.getElementById("titleBar"),
  titleBarText: document.getElementById("titleBarText"),
  backBtn: document.getElementById("backBtn"),
  searchBtn: document.getElementById("searchBtn"),
  searchBar: document.getElementById("searchBar"),
  searchInput: document.getElementById("searchInput"),
  settingsBtn: document.getElementById("settingsBtn"),
  creditsBtn: document.getElementById("creditsBtn"),
  creditsBanner: document.getElementById("creditsBanner"),
  settingsOverlay: document.getElementById("settingsOverlay"),
  pageText: document.getElementById("pageText"),
  pageCounter: document.getElementById("pageCounter"),
  prevPageBtn: document.getElementById("prevPageBtn"),
  nextPageBtn: document.getElementById("nextPageBtn"),
  readerPager: document.getElementById("readerPager"),
  bookmarkBtn: document.getElementById("bookmarkBtn"),
};

els.viewTabs.querySelectorAll(".view-tab").forEach((btn) => {
  btn.onclick = () => {
    state.viewMode = btn.dataset.mode;
    state.groupSelection = null;
    els.viewTabs.querySelectorAll(".view-tab").forEach((b) => b.classList.toggle("active", b === btn));
    renderLibraryView();
  };
});
els.groupBackBtn.onclick = () => {
  state.groupSelection = null;
  renderLibraryView();
};

async function refreshLibrary() {
  state.books = await getAllBooks();
  els.libraryCount.textContent = state.books.length
    ? `إجمالي الكتب في هذا التطبيق: ${state.books.length} كتاب`
    : "";
  renderLibraryView();
}

// Arabic-aware comparator so author/category names sort the way an Arabic
// reader expects, not by raw code-point order.
const arCollator = new Intl.Collator("ar");

function groupCounts(field) {
  const counts = new Map();
  for (const b of state.books) {
    const key = (b[field] || "").trim() || "غير معروف";
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return Array.from(counts.entries()).sort((a, b) => arCollator.compare(a[0], b[0]));
}

function renderLibraryView() {
  if (state.viewMode === "all") {
    els.groupHeader.style.display = "none";
    els.groupList.style.display = "none";
    els.bookGrid.style.display = "";
    renderBookGrid(state.books);
    return;
  }

  const field = state.viewMode === "author" ? "author" : "category";

  if (!state.groupSelection) {
    // Show the list of authors/categories themselves, not books yet.
    els.groupHeader.style.display = "none";
    els.bookGrid.style.display = "none";
    els.groupList.style.display = "";
    els.emptyState.style.display = "none";

    let entries = groupCounts(field);
    if (state.filter) {
      const q = state.filter.toLowerCase();
      entries = entries.filter(([name]) => name.toLowerCase().includes(q));
    }
    els.groupList.innerHTML = "";
    if (!entries.length && state.books.length) {
      els.groupList.innerHTML = `<div class="empty-state" style="height:auto; padding:30px 0;"><p>لا توجد نتائج</p></div>`;
    }
    entries.forEach(([name, count]) => {
      const item = document.createElement("div");
      item.className = "group-item";
      item.innerHTML = `<span class="group-name">${escapeHtml(name)}</span><span class="group-count">${count} كتاب</span>`;
      item.onclick = () => {
        state.groupSelection = name;
        renderLibraryView();
      };
      els.groupList.appendChild(item);
    });
    return;
  }

  // Drilled into one author/category: show its books.
  els.groupList.style.display = "none";
  els.bookGrid.style.display = "";
  els.groupHeader.style.display = "flex";
  els.groupHeaderTitle.textContent = state.groupSelection;
  const filtered = state.books.filter((b) => (b[field] || "غير معروف") === state.groupSelection);
  renderBookGrid(filtered);
}

async function renderBookGrid(baseList) {
  let list = baseList;
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
// A book's pages are streamed in from native a batch at a time (rather than the
// whole file being read into memory at once) - see openBookStream/nextBookPages
// in FolderImporterPlugin.java and BookStream.java. This means there is no size
// limit on a book: only one small batch of pages is ever held in memory here,
// and each batch is written to IndexedDB as it arrives.
//
// bookLoadToken makes an in-progress load cancellable: if the person leaves the
// book (or opens a different one) before loading finishes, the stale load
// notices on its next batch and stops instead of racing the new one.
let bookLoadToken = 0;

async function ensureBookLoaded(book, myToken, onProgress) {
  if (book.loaded !== false) return book; // already loaded (or imported from a database)

  // If a previous load of this book was interrupted (app closed, killed in the
  // background, crash), resume from where it left off: pages up to
  // pagesLoaded are already saved, so the stream is fast-forwarded past them
  // (cheap - just parsing, no database writes) and only the new tail is saved.
  const alreadyLoaded = book.pagesLoaded || 0;

  const { id } = await FolderImporter.openBookStream({ kind: book.source, ref: book.ref });
  let total = 0;
  while (true) {
    if (myToken !== bookLoadToken) {
      try { await FolderImporter.closeBookStream({ id }); } catch (e) { /* ignore */ }
      return null; // cancelled - the person left this book
    }
    const { pages, done } = await FolderImporter.nextBookPages({ id, max: 50 });
    if (pages.length) {
      if (total >= alreadyLoaded) {
        await storePageBatch(book.id, total, pages);
        total += pages.length;
        await updatePagesLoaded(book.id, total);
      } else if (total + pages.length <= alreadyLoaded) {
        total += pages.length; // entirely already saved - just advance past it
      } else {
        const newPart = pages.slice(alreadyLoaded - total); // batch straddles the resume point
        await storePageBatch(book.id, alreadyLoaded, newPart);
        total += pages.length;
        await updatePagesLoaded(book.id, total);
      }
      if (onProgress) onProgress(total);
    }
    if (done) break;
  }
  if (myToken !== bookLoadToken) return null;
  if (!total) throw new Error("لا توجد صفحات قابلة للقراءة في هذا الكتاب.");
  const updated = await finalizeBook(book, total);
  const i = state.books.findIndex((b) => b.id === updated.id);
  if (i >= 0) state.books[i] = updated;
  return updated;
}

async function openBook(book, startPage) {
  const myToken = ++bookLoadToken; // supersedes any still-loading previous book

  els.libraryScreen.style.display = "none";
  els.readerScreen.style.display = "block";
  els.backBtn.style.display = "flex";
  els.searchBtn.style.display = "none";
  els.bookmarkBtn.style.display = "flex";
  els.titleBarText.textContent = book.title;

  if (book.loaded === false) {
    els.pageText.textContent = "جارٍ تحميل الكتاب...";
    try {
      const loaded = await ensureBookLoaded(book, myToken, (n) => {
        if (myToken === bookLoadToken) els.pageText.textContent = `جارٍ تحميل الكتاب... (${n} صفحة)`;
      });
      if (!loaded) return; // the person left before it finished loading
      book = loaded;
    } catch (err) {
      debugLog(`تعذّر فتح "${book.title}": ${err && err.message || err}`);
      if (myToken === bookLoadToken) {
        alert("تعذّر فتح الكتاب: " + (err && err.message || err));
        closeReader();
      }
      return;
    }
  }

  if (myToken !== bookLoadToken) return; // superseded while the check above ran
  state.currentBook = book;
  state.currentPage = Math.min(Math.max(1, startPage || 1), book.page_count);
  await renderPage(0);
}

function closeReader() {
  bookLoadToken++; // cancel any book that is still loading
  state.currentBook = null;
  els.readerScreen.style.display = "none";
  els.libraryScreen.style.display = "block";
  els.backBtn.style.display = "none";
  els.searchBtn.style.display = "flex";
  els.bookmarkBtn.style.display = "none";
  els.titleBarText.textContent = "مكتبة إسلامية";
  renderLibraryView();
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
  renderLibraryView();
});

/* ---------------- Settings sheet: choose database source ---------------- */
const settingsEls = {
  overlay: els.settingsOverlay,
  downloadOption: document.getElementById("pickDownloadOption"),
  zipOption: document.getElementById("pickZipOption"),
  folderOption: document.getElementById("pickFolderOption"),
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
  // On an empty library there's nothing to delete - only show the two
  // import options (auto-download / manual folder). The delete button
  // reappears as soon as a library exists.
  settingsEls.clearBtn.style.display = books.length ? "" : "none";
}

els.settingsBtn.onclick = openSettings;
els.creditsBtn.onclick = () => {
  const showing = els.creditsBanner.style.display !== "none";
  els.creditsBanner.style.display = showing ? "none" : "";
};
document.getElementById("emptyOpenSettings").onclick = openSettings;
settingsEls.closeBtn.onclick = closeSettings;
settingsEls.clearBtn.onclick = async () => {
  const understood = confirm(
    "تحذير: حذف المكتبة\n\n" +
    "سيؤدي هذا إلى حذف جميع الكتب وتقدّم القراءة المحفوظ نهائيًا من داخل هذا التطبيق، وسيُحذف أيضًا ملف المكتبة الذي تم تنزيله، ولا يمكن التراجع عن ذلك بعد الحذف.\n\n" +
    "ملاحظة مهمة: هذا الإجراء لا يقوم بإلغاء تثبيت التطبيق نفسه من الهاتف - فقط يمسح الكتب المستوردة بداخله، ويمكنك بعدها استيراد المكتبة من جديد في أي وقت.\n" +
    "إذا كنت تريد حذف التطبيق بالكامل من الهاتف بدلاً من ذلك، أغلق هذه الرسالة وقم بإلغاء تثبيت التطبيق من إعدادات الهاتف.\n\n" +
    "هل تريد المتابعة وحذف مكتبة الكتب من داخل التطبيق؟"
  );
  if (!understood) return;

  const finalConfirm = confirm(
    "تأكيد نهائي: سيتم حذف جميع الكتب وتقدّم القراءة الآن ولن يمكن استرجاعها.\n\nهل أنت متأكد؟"
  );
  if (!finalConfirm) return;

  prefetchStop = true; // stop preparing books that are about to be deleted
  await clearLibrary();
  // Also delete the downloaded library file (several GB) so the space is freed.
  try { if (FolderImporter) await FolderImporter.clearDownloadedFiles(); } catch (e) { /* nothing to delete */ }
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

// One import routine shared by both buttons. Native code sends the books as small
// index entries in batches ("libraryIndexBatch"); each batch is saved in one quick
// database write. SQLite files (a whole database) still arrive as a file event.
async function runNativeImport({ fileEvent, skipEvent, extraListeners, startCall }) {
  const existing = new Set((await getAllBooks()).map((b) => b.ref).filter(Boolean));
  const out = { indexed: 0, duplicates: 0, sqliteAdded: 0, sqliteFiles: 0, skippedCount: 0, result: null };
  let writeChain = Promise.resolve();
  const listeners = [];

  listeners.push(await FolderImporter.addListener("libraryIndexBatch", (batch) => {
    const all = batch.items || [];
    const items = all.filter((it) => it.ref && !existing.has(it.ref));
    out.duplicates += all.length - items.length;
    items.forEach((it) => existing.add(it.ref));
    writeChain = writeChain
      .then(() => addIndexBatch(items))
      .then(() => {
        out.indexed += items.length;
        setProgress(out.indexed, out.indexed, `جارٍ فهرسة الكتب... (${out.indexed})`);
      })
      .catch((err) => debugLog("فشل حفظ دفعة من الفهرس: " + (err && err.message || err)));
  }));

  listeners.push(await FolderImporter.addListener(fileEvent, async (file) => {
    out.sqliteFiles++;
    try {
      if (file.type === "sqlite") {
        const buf = await base64ToUint8Array(file.base64);
        out.sqliteAdded += await importFromSqlite(buf, () => {});
      }
    } catch (err) {
      debugLog(`تجاوز ملف تالف (${file.relPath || file.name}): ${err.message}`);
    } finally {
      // Tell native we're done with this file so it sends the next (keeps memory flat).
      try { await FolderImporter.ackImportFile(); } catch (e) { /* native times out and continues */ }
    }
  }));

  listeners.push(await FolderImporter.addListener(skipEvent, (info) => {
    out.skippedCount++;
    const sizeNote = info.sizeMB ? `, ${info.sizeMB.toFixed(1)}MB` : "";
    debugLog(`تم تجاوز ${info.relPath} (${info.reason}${sizeNote})`);
  }));

  for (const make of extraListeners || []) listeners.push(await make());

  let failure = null;
  try { out.result = await startCall(); } catch (e) { failure = e; }
  for (const l of listeners) { try { await l.remove(); } catch (e) { /* ignore */ } }
  await writeChain; // make sure every batch has been saved before reporting
  if (failure) throw failure;
  return out;
}

// After import, or on app start if some books were left unloaded from a
// previous session, quietly reads every book's pages in the background - one
// book at a time - so most books are already instant to open by the time
// they're actually tapped, instead of everyone paying the "جارٍ تحميل
// الكتاب..." step individually the first time. Uses the exact same streaming
// loader as opening a book manually (ensureBookLoaded), so it's memory-safe
// for a book of any size and automatically resumes any book that was only
// partly loaded before.
//
// This yields to a manually opened book automatically: ensureBookLoaded is
// cancelled the same way (bookLoadToken) when the person taps a book, so
// prefetch never competes with something the person is actively waiting on -
// it just picks up the next book once the token frees up again.
let prefetchRunning = false;
let prefetchStop = false;
const prefetchFailedIds = new Set();

async function startBackgroundPrefetch() {
  if (prefetchRunning) return;
  prefetchRunning = true;
  prefetchStop = false;
  try {
    while (!prefetchStop) {
      const todo = state.books.filter((b) =>
        b.loaded === false && !prefetchFailedIds.has(b.id) && (!state.currentBook || b.id !== state.currentBook.id)
      );
      if (!todo.length) break;
      const book = todo[0];
      const myToken = bookLoadToken; // don't start a new "generation" - just ride the current one
      updatePrefetchStatus(todo.length);
      try {
        const updated = await ensureBookLoaded(book, myToken, null);
        if (updated) {
          const i = state.books.findIndex((b) => b.id === updated.id);
          if (i >= 0) state.books[i] = updated;
        }
        // If it came back null, the person opened something and cancelled this
        // book's load - just loop around and try the next candidate.
      } catch (err) {
        debugLog(`تعذّر تجهيز "${book.title}" في الخلفية: ${err && err.message || err}`);
        prefetchFailedIds.add(book.id); // don't retry a permanently-broken book this session
      }
      await new Promise((r) => setTimeout(r, 20)); // brief yield so the UI stays responsive
    }
  } finally {
    prefetchRunning = false;
    updatePrefetchStatus(0);
  }
}

function updatePrefetchStatus(remaining) {
  if (!els.prefetchStatus) return;
  if (remaining > 0) {
    els.prefetchStatus.style.display = "";
    els.prefetchStatus.textContent = `جارٍ تجهيز الكتب للقراءة الفورية في الخلفية... (${remaining} متبقٍ)`;
  } else {
    els.prefetchStatus.style.display = "none";
  }
}

async function finishImport(out) {
  const total = out.indexed + out.sqliteAdded;
  const r = out.result || {};
  debugLog(`أرسل النظام ${r.scannedCount ?? "?"} ملف. فُهرس ${out.indexed} كتاب، ${out.sqliteAdded} من قواعد SQLite، ${out.duplicates} موجود مسبقًا، تم تجاوز ${r.skippedCount ?? out.skippedCount}.`);
  if (r.scannedCount != null && out.indexed + out.duplicates + out.sqliteFiles < r.scannedCount) {
    debugLog(`تحذير: فُقد ${r.scannedCount - (out.indexed + out.duplicates + out.sqliteFiles)} ملف أثناء النقل.`);
  }
  setProgress(total, total, `تم! أُضيف ${total} كتاب.`);
  await updateStats();
  if (!total && !out.duplicates) {
    alert("لم يتم العثور على أي كتب (ملفات JSON أو SQLite) في هذا المصدر.");
  } else if (!total) {
    alert(`كل الكتب (${out.duplicates}) موجودة مسبقًا في المكتبة.`);
  } else {
    alert(`اكتملت الفهرسة.\nإجمالي الكتب المضافة: ${total} كتاب.\nيتم تحميل نص كل كتاب عند فتحه لأول مرة.`);
  }
  startBackgroundPrefetch(); // quietly get books ready to open instantly, in the background
}

// Pick one top-level folder; every .json/.db file inside it (at any depth of
// subfolders) is indexed. The folder must stay on the phone: each book is read
// from it the first time it is opened.
// Imports a zip the person already downloaded themselves (browser, another app,
// etc.) directly - no extraction needed. The zip is copied once into the app's
// private storage (needed for random-access reads later) and indexed exactly
// like the auto-downloaded library. See ManualZipImportService.java.
settingsEls.zipOption.onclick = async () => {
  debugLog("تم الضغط على خيار استيراد ملف مضغوط.");
  if (!FolderImporter) {
    alert("هذه الميزة غير متوفرة في هذا الإصدار من التطبيق.");
    return;
  }
  showProgress(true);
  setProgress(0, 100, "في انتظار اختيار الملف...");

  let out;
  try {
    out = await runNativeImport({
      fileEvent: "zipImportFile",
      skipEvent: "zipImportSkipped",
      extraListeners: [
        () => FolderImporter.addListener("zipImportProgress", (info) => {
          if (info.phase === "copying") {
            const pct = info.totalBytes > 0 ? Math.round((info.bytesDone / info.totalBytes) * 100) : 0;
            setProgress(pct, 100, `جارٍ نسخ الملف... ${pct}% (${(info.bytesDone / 1024 / 1024).toFixed(0)}MB)`);
          }
        }),
      ],
      startCall: () => FolderImporter.pickAndImportZip(),
    });
  } catch (err) {
    debugLog("فشل استيراد الملف المضغوط: " + (err && err.message || err));
    showProgress(false);
    if (!/cancel|إلغاء/i.test(err && err.message || "")) alert("فشل استيراد الملف: " + (err && err.message || err));
    return;
  }
  await finishImport(out);
};

settingsEls.folderOption.onclick = async () => {
  debugLog("تم الضغط على خيار المجلد الكامل.");
  if (!FolderImporter) {
    alert("ميزة اختيار المجلد غير متوفرة في هذا الإصدار من التطبيق.");
    return;
  }
  showProgress(true);
  setProgress(0, 0, "جارٍ فتح المجلد...");

  let out;
  try {
    out = await runNativeImport({
      fileEvent: "folderImportFile",
      skipEvent: "folderImportSkipped",
      startCall: () => FolderImporter.pickFolder(),
    });
  } catch (err) {
    debugLog("فشل اختيار المجلد: " + (err && err.message || err));
    showProgress(false);
    if (!/cancel|إلغاء/i.test(err && err.message || "")) alert("فشل اختيار المجلد: " + (err && err.message || err));
    return;
  }
  await finishImport(out);
};

// Downloads the full library zip (about 3.1GB, from archive.org), keeps it as-is in
// the app's private storage and indexes it - all natively (see
// FolderImporterPlugin/LibraryDownloadService). This can't be done in JS: a file this
// size can't be held in memory or passed across the Capacitor bridge on a phone.
const LIBRARY_ZIP_URL = "https://archive.org/download/maktaba-islamia/maktaba-islamia.zip";

settingsEls.downloadOption.onclick = async () => {
  debugLog("تم الضغط على خيار التنزيل التلقائي.");
  if (!FolderImporter) {
    alert("ميزة التنزيل التلقائي غير متوفرة في هذا الإصدار من التطبيق.");
    return;
  }
  if (!confirm("سيتم تنزيل المكتبة كاملة (حوالي 3.1 جيجابايت) وحفظها داخل التطبيق. يُفضّل استخدام واي فاي وتوفر مساحة كافية على الهاتف. بعد التنزيل تظهر أسماء الكتب فورًا، ويُفتح كل كتاب عند الضغط عليه. المتابعة؟")) {
    return;
  }

  showProgress(true);
  setProgress(0, 100, "جارٍ التنزيل... 0%");

  let out;
  try {
    out = await runNativeImport({
      fileEvent: "downloadImportFile",
      skipEvent: "downloadImportSkipped",
      extraListeners: [
        () => FolderImporter.addListener("downloadProgress", (info) => {
          if (info.phase === "downloading") {
            const pct = info.totalBytes > 0 ? Math.round((info.bytesDone / info.totalBytes) * 100) : 0;
            setProgress(pct, 100, `جارٍ التنزيل... ${pct}% (${(info.bytesDone / 1024 / 1024).toFixed(0)}MB)`);
          }
        }),
      ],
      startCall: () => FolderImporter.downloadLibrary({ url: LIBRARY_ZIP_URL }),
    });
  } catch (err) {
    debugLog("فشل التنزيل: " + (err && err.message || err));
    alert("فشل تنزيل المكتبة: " + (err && err.message || err));
    await updateStats();
    return;
  }
  await finishImport(out);
};

/* ---------------- Boot ---------------- */
(async function boot() {
  await refreshLibrary();
  if (!state.books.length) openSettings();
  startBackgroundPrefetch(); // pick up any books left unloaded from a previous session
})();
