/* ==========================================================================
   Maktaba Islamia - Android app logic
   Fully client-side: no server. NO book text is loaded while indexing - the library
   list is only metadata; a book's text is read when (and only when) the person taps it.
   Library data lives in IndexedDB on the
   device. The library list is built from a CSV catalog (title, author, category)
   inside the picked "maktaba" folder; a book's text is only read from its
   category folder when the person taps the book.

   Feature set mirrors the desktop app (islamic-library): browse by author /
   category, search, favorites ("read later"), per-page bookmarks, table of
   contents, continue-reading row, back / forward history, light + night
   themes, adjustable bundled Arabic fonts and a jump-to-page box.
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

// Native plugins (registered in MainActivity / by `cap sync`).
const Plugins = (window.Capacitor && window.Capacitor.Plugins) || {};
const FolderImporter = Plugins.FolderImporter;
const AppPlugin = Plugins.App;

/* -------------------------------------------------------------------- */
/* Preferences: theme + reading font (kept in localStorage)              */
/* -------------------------------------------------------------------- */
function prefGet(key, def) {
  try { const v = localStorage.getItem(key); return v === null ? def : v; } catch (e) { return def; }
}
function prefSet(key, val) {
  try { localStorage.setItem(key, String(val)); } catch (e) { /* storage unavailable: just don't persist */ }
}

const FONT_STACKS = {
  amiri: '"Amiri", "Noto Naskh Arabic", serif',
  noto: '"Noto Naskh Arabic", "Amiri", serif',
};
const FONT_MIN = 14, FONT_MAX = 44, FONT_DEFAULT = 22;

function currentFontSize() {
  const n = parseInt(prefGet("arabicSize", String(FONT_DEFAULT)), 10);
  return Math.min(FONT_MAX, Math.max(FONT_MIN, isNaN(n) ? FONT_DEFAULT : n));
}
function currentFontFamily() {
  const f = prefGet("arabicFont", "amiri");
  return FONT_STACKS[f] ? f : "amiri";
}
function applyFontPrefs() {
  const size = currentFontSize();
  const family = currentFontFamily();
  document.documentElement.style.setProperty("--arabic-size", size + "px");
  document.documentElement.style.setProperty("--arabic-font", FONT_STACKS[family]);
  const label = document.getElementById("fontSizeValue");
  if (label) label.textContent = String(size);
  document.querySelectorAll("[data-font]").forEach((b) => b.classList.toggle("active", b.dataset.font === family));
}
function changeFontSize(delta) {
  prefSet("arabicSize", Math.min(FONT_MAX, Math.max(FONT_MIN, currentFontSize() + delta)));
  applyFontPrefs();
}

function currentTheme() {
  return document.documentElement.getAttribute("data-theme") === "dark" ? "dark" : "mushaf";
}
function applyTheme(theme) {
  theme = theme === "dark" ? "dark" : "mushaf";
  document.documentElement.setAttribute("data-theme", theme);
  prefSet("theme", theme);
  document.querySelectorAll("[data-theme-value]").forEach((b) => b.classList.toggle("active", b.dataset.themeValue === theme));
}

/* -------------------------------------------------------------------- */
/* Arabic-aware text helpers                                             */
/* -------------------------------------------------------------------- */
// Arabic-aware comparator so titles / author / category names sort the way an
// Arabic reader expects, not by raw code-point order.
const arCollator = new Intl.Collator("ar");

// Search ignores diacritics (tashkeel), tatweel and the common spelling variants
// of alef / yaa / taa marbuta, so typing "الاسلام" finds "الإسلام".
function normalizeAr(s) {
  return String(s || "").toLowerCase()
    .replace(/[\u064B-\u065F\u0670\u0640]/g, "")
    .replace(/[أإآٱ]/g, "ا")
    .replace(/ى/g, "ي")
    .replace(/ة/g, "ه");
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/* -------------------------------------------------------------------- */
/* IndexedDB storage layer                                               */
/* -------------------------------------------------------------------- */
const DB_NAME = "maktaba_islamia";
const DB_VERSION = 2; // v2 adds the "favorites" and "bookmarks" stores
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
      if (!db.objectStoreNames.contains("favorites")) {
        db.createObjectStore("favorites", { keyPath: "bookId" });
      }
      if (!db.objectStoreNames.contains("bookmarks")) {
        const s = db.createObjectStore("bookmarks", { keyPath: "key" }); // key = `${bookId}_${page}` (one per page)
        s.createIndex("bookId", "bookId");
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

function reqResult(r) {
  return new Promise((res, rej) => {
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}
function txDone(t) {
  return new Promise((res, rej) => {
    t.oncomplete = () => res();
    t.onerror = () => rej(t.error);
    t.onabort = () => rej(t.error);
  });
}

async function clearLibrary() {
  const t = await tx(["books", "pages", "progress", "favorites", "bookmarks"], "readwrite");
  ["books", "pages", "progress", "favorites", "bookmarks"].forEach((n) => t.objectStore(n).clear());
  return txDone(t);
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
    source: it.source,   // "catalog" (book listed in the CSV) or "saf" (a .json found in the picked folder)
    ref: it.ref,         // where to find the book file later (folder uri | category/file.json)
    loaded: false,
  }));
  return txDone(t);
}

// Writes one streamed batch of a book's pages (called repeatedly while a book
// loads, instead of holding the whole book in memory - see ensureBookLoaded).
async function storePageBatch(bookId, startIndex, pages) {
  const t = await tx(["pages"], "readwrite");
  const store = t.objectStore("pages");
  pages.forEach((text, i) => {
    store.put({ key: `${bookId}_${startIndex + i + 1}`, bookId, page: startIndex + i + 1, text });
  });
  return txDone(t);
}

// Records how far a book's load has gotten, so an interrupted load (app closed,
// killed in the background, crash) resumes from here instead of starting the
// book over from page 1 next time it's opened.
async function updatePagesLoaded(bookId, n) {
  const t = await tx(["books"], "readwrite");
  const store = t.objectStore("books");
  const req = store.get(bookId);
  req.onsuccess = () => {
    const b = req.result;
    if (b) { b.pagesLoaded = n; store.put(b); }
  };
  return txDone(t);
}

// Marks a book as fully loaded once every page batch has been written.
async function finalizeBook(book, pageCount, pageNums) {
  const updated = { ...book, loaded: true, page_count: pageCount };
  if (pageNums) updated.pageNums = pageNums; else delete updated.pageNums;
  const t = await tx(["books"], "readwrite");
  t.objectStore("books").put(updated);
  await txDone(t);
  return updated;
}

// Saves any change made to a book record (e.g. its table of contents).
async function putBook(book) {
  const t = await tx(["books"], "readwrite");
  t.objectStore("books").put(book);
  return txDone(t);
}

async function getAllBooks() {
  const t = await tx(["books"], "readonly");
  return reqResult(t.objectStore("books").getAll());
}

async function getPage(bookId, page) {
  const t = await tx(["pages"], "readonly");
  const r = await reqResult(t.objectStore("pages").get(`${bookId}_${page}`));
  return r ? r.text : null;
}

/* ---- reading progress ---- */
async function saveProgress(bookId, page) {
  const rec = { bookId, page, updatedAt: Date.now() };
  const t = await tx(["progress"], "readwrite");
  t.objectStore("progress").put(rec);
  await txDone(t);
  return rec;
}
async function getAllProgress() {
  const t = await tx(["progress"], "readonly");
  const rows = await reqResult(t.objectStore("progress").getAll());
  return new Map(rows.map((r) => [r.bookId, r]));
}

/* ---- favorites ("read later") ---- */
async function getFavorites() {
  const t = await tx(["favorites"], "readonly");
  const rows = await reqResult(t.objectStore("favorites").getAll());
  return new Map(rows.map((r) => [r.bookId, r.createdAt]));
}
// Adds the book to the favorites if it isn't there, removes it if it is.
// Resolves to true when the book is now a favorite.
async function toggleFavorite(bookId) {
  const t = await tx(["favorites"], "readwrite");
  const store = t.objectStore("favorites");
  const existing = await reqResult(store.get(bookId));
  let nowFavorite;
  if (existing) { store.delete(bookId); nowFavorite = false; }
  else { store.put({ bookId, createdAt: Date.now() }); nowFavorite = true; }
  await txDone(t);
  return nowFavorite;
}

/* ---- bookmarks (one per exact page, toggled) ---- */
async function getBookmarks(bookId) {
  const t = await tx(["bookmarks"], "readonly");
  const rows = await reqResult(t.objectStore("bookmarks").index("bookId").getAll(bookId));
  return rows.sort((a, b) => a.page - b.page);
}
// Resolves to "added" or "removed".
async function toggleBookmark(bookId, page, snippet) {
  const key = `${bookId}_${page}`;
  const t = await tx(["bookmarks"], "readwrite");
  const store = t.objectStore("bookmarks");
  const existing = await reqResult(store.get(key));
  let action;
  if (existing) { store.delete(key); action = "removed"; }
  else { store.put({ key, bookId, page, note: snippet || "", createdAt: Date.now() }); action = "added"; }
  await txDone(t);
  return action;
}
async function removeBookmark(key) {
  const t = await tx(["bookmarks"], "readwrite");
  t.objectStore("bookmarks").delete(key);
  return txDone(t);
}

/* -------------------------------------------------------------------- */
/* Page numbers <-> position in the book                                  */
/* -------------------------------------------------------------------- */
// The reader works with positions 1..N. A book's table of contents, however,
// refers to the page numbers written in the book file. In nearly every book the
// two are the same; when they are not, the file's numbers are kept in
// book.pageNums (index = position - 1) so the contents can still be mapped.
// Returns null when the numbers are just 1..N (or unknown), the common case.
function buildPageNums(nums) {
  let differs = false;
  for (let i = 0; i < nums.length; i++) {
    if (nums[i] && nums[i] !== i + 1) { differs = true; break; }
  }
  if (!differs) return null;
  const out = new Array(nums.length);
  for (let i = 0; i < nums.length; i++) {
    out[i] = nums[i] || (i > 0 ? out[i - 1] + 1 : 1); // unknown number: continue from the previous page
  }
  return out;
}

function positionOfPageNumber(book, n) {
  const count = book.page_count || 1;
  n = parseInt(n, 10);
  if (!n || n < 1) return 1;
  const nums = book.pageNums;
  if (!nums) return Math.min(n, count);
  let best = -1;
  for (let i = 0; i < nums.length; i++) {
    if (nums[i] === n) return i + 1;
    if (nums[i] > n && (best < 0 || nums[i] < nums[best])) best = i; // nearest page after it
  }
  return best >= 0 ? best + 1 : count;
}

function tocLevels(toc) {
  const byId = new Map();
  toc.forEach((t) => byId.set(t.id, t));
  return toc.map((t) => {
    let depth = 0, cur = t, guard = 0;
    while (cur && cur.parent && byId.has(cur.parent) && guard++ < 6) { cur = byId.get(cur.parent); depth++; }
    return Math.min(depth, 4);
  });
}

/* -------------------------------------------------------------------- */
/* App state + elements                                                  */
/* -------------------------------------------------------------------- */
const state = {
  books: [], byId: new Map(), sorted: [], searchIdx: new Map(),
  progress: new Map(), favs: new Map(),
  currentBook: null, currentPage: 1, bookmarkedPages: new Set(),
  filter: "", filterN: "", viewMode: "all", groupSelection: null,
  searchOpen: false,
};

const $ = (id) => document.getElementById(id);
const els = {
  libraryScreen: $("libraryScreen"),
  readerScreen: $("readerScreen"),
  bookGrid: $("bookGrid"),
  gridSentinel: $("gridSentinel"),
  emptyState: $("emptyState"),
  listMsg: $("listMsg"),
  viewTabs: $("viewTabs"),
  groupHeader: $("groupHeader"),
  groupHeaderTitle: $("groupHeaderTitle"),
  groupBackBtn: $("groupBackBtn"),
  groupList: $("groupList"),
  continueSection: $("continueSection"),
  continueRow: $("continueRow"),
  libraryCount: $("libraryCount"),
  titleBarText: $("titleBarText"),
  backBtn: $("backBtn"),
  forwardBtn: $("forwardBtn"),
  searchBtn: $("searchBtn"),
  searchBar: $("searchBar"),
  searchInput: $("searchInput"),
  settingsBtn: $("settingsBtn"),
  creditsBtn: $("creditsBtn"),
  creditsBanner: $("creditsBanner"),
  settingsOverlay: $("settingsOverlay"),
  tocOverlay: $("tocOverlay"),
  displayOverlay: $("displayOverlay"),
  pageText: $("pageText"),
  pageInput: $("pageInput"),
  pageTotal: $("pageTotal"),
  prevPageBtn: $("prevPageBtn"),
  nextPageBtn: $("nextPageBtn"),
  readerPager: $("readerPager"),
  bookmarkBtn: $("bookmarkBtn"),
  favBtn: $("favBtn"),
  tocBtn: $("tocBtn"),
  displayBtn: $("displayBtn"),
};

// Small message at the bottom of the screen (the top bar is icon-only, so toggles
// like "added to favorites" need some feedback).
let toastTimer = null;
function toast(msg) {
  let t = $("toast");
  if (!t) {
    t = document.createElement("div");
    t.id = "toast";
    t.className = "toast";
    document.body.appendChild(t);
  }
  t.textContent = msg;
  t.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove("show"), 1700);
}

/* -------------------------------------------------------------------- */
/* Library screen                                                        */
/* -------------------------------------------------------------------- */
async function refreshLibrary() {
  const [books, progress, favs] = await Promise.all([getAllBooks(), getAllProgress(), getFavorites()]);
  state.books = books;
  state.progress = progress;
  state.favs = favs;
  state.byId = new Map(books.map((b) => [b.id, b]));
  state.sorted = books.slice().sort((a, b) => arCollator.compare(a.title || "", b.title || ""));
  state.searchIdx = new Map(books.map((b) => [b.id, normalizeAr(`${b.title || ""} ${b.author || ""}`)]));
  els.libraryCount.textContent = books.length
    ? `إجمالي الكتب في هذا التطبيق: ${books.length} كتاب`
    : "";
  renderLibraryView();
}

function matchesFilter(b) {
  if (!state.filterN) return true;
  return (state.searchIdx.get(b.id) || "").includes(state.filterN);
}

function groupKey(b, field) {
  return (b[field] || "").trim() || "غير معروف";
}

function groupCounts(field) {
  const counts = new Map();
  for (const b of state.books) {
    const key = groupKey(b, field);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return Array.from(counts.entries()).sort((a, b) => arCollator.compare(a[0], b[0]));
}

function renderLibraryView(minShown) {
  const mode = state.viewMode;
  els.viewTabs.querySelectorAll(".view-tab").forEach((b) => b.classList.toggle("active", b.dataset.mode === mode));
  els.listMsg.style.display = "none";

  const hasBooks = state.books.length > 0;
  els.emptyState.style.display = hasBooks ? "none" : "flex";
  if (!hasBooks) {
    els.groupHeader.style.display = "none";
    els.groupList.style.display = "none";
    els.continueSection.style.display = "none";
    renderBookGrid([]);
    return;
  }

  if (mode === "all" || mode === "favorites") {
    els.groupHeader.style.display = "none";
    els.groupList.style.display = "none";
    els.bookGrid.style.display = "";
    if (mode === "all") {
      renderContinueRow();
      renderBookGrid(state.sorted.filter(matchesFilter), minShown);
    } else {
      els.continueSection.style.display = "none";
      const favBooks = Array.from(state.favs.entries())
        .sort((a, b) => b[1] - a[1])                       // most recently added first
        .map(([id]) => state.byId.get(id))
        .filter(Boolean)
        .filter(matchesFilter);
      renderBookGrid(favBooks, minShown);
      if (!favBooks.length) showListMsg(state.filterN ? "لا توجد نتائج" : "لم تضف أي كتاب إلى المفضلة بعد — اضغط ★ على أي كتاب");
    }
    return;
  }

  // author / category
  els.continueSection.style.display = "none";
  const field = mode === "author" ? "author" : "category";

  if (!state.groupSelection) {
    // The list of authors/categories themselves, not books yet.
    els.groupHeader.style.display = "none";
    els.bookGrid.style.display = "none";
    renderBookGrid([]);
    els.groupList.style.display = "";

    let entries = groupCounts(field);
    if (state.filterN) entries = entries.filter(([name]) => normalizeAr(name).includes(state.filterN));
    els.groupList.innerHTML = "";
    if (!entries.length) showListMsg("لا توجد نتائج");
    const frag = document.createDocumentFragment();
    entries.forEach(([name, count]) => {
      const item = document.createElement("div");
      item.className = "group-item";
      item.innerHTML = `<span class="group-name">${escapeHtml(name)}</span><span class="group-count">${count} كتاب</span>`;
      item.onclick = () => selectGroup(name);
      frag.appendChild(item);
    });
    els.groupList.appendChild(frag);
    return;
  }

  // Drilled into one author/category: show its books.
  els.groupList.style.display = "none";
  els.bookGrid.style.display = "";
  els.groupHeader.style.display = "flex";
  els.groupHeaderTitle.textContent = state.groupSelection;
  const list = state.sorted.filter((b) => groupKey(b, field) === state.groupSelection).filter(matchesFilter);
  renderBookGrid(list, minShown);
  if (!list.length) showListMsg("لا توجد نتائج");
}

function showListMsg(text) {
  els.listMsg.textContent = text;
  els.listMsg.style.display = "";
}

// The book grid draws a screenful at a time and adds more as the person scrolls
// (with thousands of books, building every card at once would freeze the phone).
const GRID_CHUNK = 40;
let gridToken = 0;
let gridShown = 0;
let gridObserver = null;

function renderBookGrid(list, minShown) {
  const token = ++gridToken;
  if (gridObserver) { gridObserver.disconnect(); gridObserver = null; }
  els.bookGrid.innerHTML = "";
  gridShown = 0;
  if (!list.length) return;

  const more = (count) => {
    if (token !== gridToken) return;
    const frag = document.createDocumentFragment();
    const slice = list.slice(gridShown, gridShown + count);
    slice.forEach((b) => frag.appendChild(makeBookCard(b)));
    gridShown += slice.length;
    els.bookGrid.appendChild(frag);
  };

  more(Math.max(GRID_CHUNK, minShown || 0));

  if (typeof IntersectionObserver === "undefined") {
    more(list.length); // very old WebView: no lazy loading, draw everything
    return;
  }
  gridObserver = new IntersectionObserver((entries) => {
    if (token !== gridToken || !entries.some((e) => e.isIntersecting)) return;
    gridObserver.unobserve(els.gridSentinel);
    more(GRID_CHUNK);
    if (gridShown < list.length) gridObserver.observe(els.gridSentinel); // re-check: still on screen?
  }, { root: els.libraryScreen, rootMargin: "500px" });
  if (gridShown < list.length) gridObserver.observe(els.gridSentinel);
}

function makeBookCard(b) {
  const card = document.createElement("div");
  card.className = "book-card";
  const p = state.progress.get(b.id);
  const isFav = state.favs.has(b.id);
  card.innerHTML = `
    <button class="fav-star${isFav ? " active" : ""}" aria-label="المفضلة">★</button>
    <div class="book-title">${escapeHtml(b.title || "بدون عنوان")}</div>
    <div class="book-author">${escapeHtml(b.author || "")}</div>
    ${b.page_count ? `<div class="book-pages">${b.page_count} صفحة</div>` : ""}
    ${p ? `<div class="book-progress">متابعة القراءة · صفحة ${p.page}</div>` : ""}
  `;
  card.onclick = () => openBook(b, p ? p.page : 1);
  const star = card.querySelector(".fav-star");
  star.onclick = async (ev) => {
    ev.stopPropagation();
    const nowFav = await setFavorite(b.id);
    star.classList.toggle("active", nowFav);
    if (state.viewMode === "favorites" && !nowFav) {
      card.remove();
      if (!els.bookGrid.children.length) showListMsg("لم تضف أي كتاب إلى المفضلة بعد — اضغط ★ على أي كتاب");
    }
  };
  return card;
}

// Toggles a favorite and keeps the in-memory copy in step.
async function setFavorite(bookId) {
  const nowFav = await toggleFavorite(bookId);
  if (nowFav) state.favs.set(bookId, Date.now()); else state.favs.delete(bookId);
  toast(nowFav ? "أُضيف إلى المفضلة" : "أُزيل من المفضلة");
  return nowFav;
}

// "Continue reading" row: the books opened most recently, with their saved page.
function renderContinueRow() {
  const items = Array.from(state.progress.values())
    .filter((p) => state.byId.has(p.bookId))
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .slice(0, 10);
  els.continueRow.innerHTML = "";
  if (!items.length || state.filterN) { els.continueSection.style.display = "none"; return; }
  els.continueSection.style.display = "";
  items.forEach((p) => {
    const b = state.byId.get(p.bookId);
    const card = document.createElement("div");
    card.className = "continue-card";
    card.innerHTML = `
      <div class="book-title">${escapeHtml(b.title || "بدون عنوان")}</div>
      <div class="book-author">${escapeHtml(b.author || "")}</div>
      <div class="book-progress">صفحة ${p.page}${b.page_count ? ` من ${b.page_count}` : ""}</div>`;
    card.onclick = () => openBook(b, p.page);
    els.continueRow.appendChild(card);
  });
}

els.viewTabs.querySelectorAll(".view-tab").forEach((btn) => {
  btn.onclick = () => {
    state.viewMode = btn.dataset.mode;
    state.groupSelection = null;
    snapshotLibrary();
    renderLibraryView();
    els.libraryScreen.scrollTop = 0;
  };
});
els.groupBackBtn.onclick = () => {
  const prev = nav.entries[nav.index - 1];
  if (prev && prev.type === "library" && prev.mode === state.viewMode && !prev.group) {
    navBack(); // the group was opened from that list: go back there like any other "back"
  } else {
    state.groupSelection = null;
    snapshotLibrary();
    renderLibraryView();
  }
};

/* -------------------------------------------------------------------- */
/* Navigation history (back / forward)                                    */
/* -------------------------------------------------------------------- */
// Every screen the person moves through - the library (with its tab, opened
// author/category, search text and scroll position) and each open book (with its
// page) - is an entry. Back/forward (the arrows in the top bar, and the phone's
// own back button) walk through them and restore exactly what was on screen.
const nav = {
  entries: [{ type: "library", mode: "all", group: null, filter: "", scroll: 0, shown: 0 }],
  index: 0,
};

function curEntry() { return nav.entries[nav.index]; }

function snapshotLibrary() {
  const e = curEntry();
  if (!e || e.type !== "library") return;
  e.mode = state.viewMode;
  e.group = state.groupSelection;
  e.filter = state.filter;
  e.scroll = els.libraryScreen.scrollTop;
  e.shown = gridShown;
}

function navPush(entry) {
  snapshotLibrary();
  nav.entries.length = nav.index + 1; // a new visit discards the "forward" entries
  nav.entries.push(entry);
  if (nav.entries.length > 100) nav.entries.shift();
  nav.index = nav.entries.length - 1;
  updateNavButtons();
}

function updateNavButtons() {
  els.backBtn.style.display = nav.index > 0 ? "flex" : "none";
  els.forwardBtn.style.display = nav.index < nav.entries.length - 1 ? "flex" : "none";
}

async function navTo(i) {
  if (i < 0 || i >= nav.entries.length || i === nav.index) return;
  snapshotLibrary();
  nav.index = i;
  updateNavButtons();
  const e = nav.entries[i];
  if (e.type === "book") {
    const book = state.byId.get(e.bookId);
    if (book) { openBook(book, e.page, { restoring: true }); return; }
    // the book no longer exists: fall through to the library
  }
  showLibraryUI();
  const entry = e.type === "library" ? e : { mode: "all", group: null, filter: "", scroll: 0, shown: 0 };
  state.viewMode = entry.mode;
  state.groupSelection = entry.group;
  setFilter(entry.filter || "");
  if (state.filter) openSearchBar(true);
  renderLibraryView(entry.shown);
  els.libraryScreen.scrollTop = entry.scroll || 0;
}
const navBack = () => navTo(nav.index - 1);
const navForward = () => navTo(nav.index + 1);

els.backBtn.onclick = navBack;
els.forwardBtn.onclick = navForward;

function selectGroup(name) {
  snapshotLibrary();
  navPush({ type: "library", mode: state.viewMode, group: name, filter: state.filter, scroll: 0, shown: 0 });
  state.groupSelection = name;
  renderLibraryView();
  els.libraryScreen.scrollTop = 0;
}

/* -------------------------------------------------------------------- */
/* Search                                                                 */
/* -------------------------------------------------------------------- */
function setFilter(text) {
  state.filter = text;
  state.filterN = normalizeAr(text.trim());
  els.searchInput.value = text;
}
function openSearchBar(open) {
  state.searchOpen = open;
  els.searchBar.style.display = open && document.body.dataset.mode === "library" ? "block" : "none";
  if (open) els.searchInput.focus();
}
els.searchBtn.onclick = () => openSearchBar(!state.searchOpen);
els.searchInput.addEventListener("input", (e) => {
  state.filter = e.target.value.trim();
  state.filterN = normalizeAr(state.filter);
  snapshotLibrary();
  renderLibraryView();
});

/* -------------------------------------------------------------------- */
/* Reader screen                                                          */
/* -------------------------------------------------------------------- */
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
  const allNums = []; // the page number the file gives each page, in order
  while (true) {
    if (myToken !== bookLoadToken) {
      try { await FolderImporter.closeBookStream({ id }); } catch (e) { /* ignore */ }
      return null; // cancelled - the person left this book
    }
    const { pages, nums, done } = await FolderImporter.nextBookPages({ id, max: 50 });
    if (pages.length) {
      for (let i = 0; i < pages.length; i++) allNums.push((nums && nums[i]) || 0);
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
      book.pagesLoaded = total;
      if (onProgress) onProgress(total);
    }
    if (done) break;
  }
  if (myToken !== bookLoadToken) return null;
  if (!total) throw new Error("لا توجد صفحات قابلة للقراءة في هذا الكتاب.");
  const updated = await finalizeBook(book, total, buildPageNums(allNums));
  Object.assign(book, updated); // keep the one shared book object in step (library lists point at it)
  if (!updated.pageNums) delete book.pageNums;
  return book;
}

// The table of contents is read from the book file on first use, then kept with
// the book (so it also works for books opened before this feature existed).
async function ensureToc(book) {
  if (Array.isArray(book.toc)) return book.toc;
  if (!book.source || !book.ref || !FolderImporter) return [];
  try {
    const res = await FolderImporter.readBookToc({ kind: book.source, ref: book.ref });
    book.toc = Array.isArray(res.toc) ? res.toc : [];
    await putBook(book);
    return book.toc;
  } catch (err) {
    debugLog(`تعذّر قراءة فهرس "${book.title}": ${err && err.message || err}`);
    delete book.toc;
    return [];
  }
}

function showReaderUI(book) {
  document.body.dataset.mode = "reader";
  els.libraryScreen.style.display = "none";
  els.readerScreen.style.display = "flex";
  els.searchBar.style.display = "none";
  els.creditsBanner.style.display = "none";
  els.titleBarText.textContent = book.title;
}

function showLibraryUI() {
  bookLoadToken++; // cancel any book that is still loading
  state.currentBook = null;
  document.body.dataset.mode = "library";
  els.readerScreen.style.display = "none";
  els.libraryScreen.style.display = "block";
  els.titleBarText.textContent = "مكتبة إسلامية";
  els.searchBar.style.display = state.searchOpen ? "block" : "none";
  closeSheet(els.tocOverlay);
  closeSheet(els.displayOverlay);
}

// Drop a book that failed to open from the history and go back to where we were.
async function abortBookOpen() {
  if (curEntry().type === "book" && nav.index > 0) {
    await navTo(nav.index - 1);
    nav.entries.length = nav.index + 1;
    updateNavButtons();
  } else {
    showLibraryUI();
  }
}

async function openBook(book, startPage, opts) {
  opts = opts || {};
  const myToken = ++bookLoadToken; // supersedes any still-loading previous book
  showReaderUI(book);
  if (!opts.restoring) navPush({ type: "book", bookId: book.id, page: startPage || 1 });
  state.currentBook = null;
  els.pageText.textContent = "";
  els.prevPageBtn.disabled = true;
  els.nextPageBtn.disabled = true;
  els.pageInput.value = String(startPage || 1);
  els.pageTotal.textContent = book.page_count ? `/ ${book.page_count}` : "/ …";
  updateFavIcon(book);
  els.bookmarkBtn.classList.remove("on");

  if (book.loaded === false) {
    els.pageText.textContent = "جارٍ تحميل الكتاب...";
    try {
      const loaded = await ensureBookLoaded(book, myToken, (n) => {
        if (myToken === bookLoadToken) els.pageText.textContent = `جارٍ تحميل الكتاب... (${n} صفحة)`;
      });
      if (!loaded) return; // the person left before it finished loading
    } catch (err) {
      debugLog(`تعذّر فتح "${book.title}": ${err && err.message || err}`);
      if (myToken === bookLoadToken) {
        alert("تعذّر فتح الكتاب: " + (err && err.message || err));
        await abortBookOpen();
      }
      return;
    }
  }

  if (myToken !== bookLoadToken) return; // superseded while the check above ran
  const marks = await getBookmarks(book.id);
  if (myToken !== bookLoadToken) return;
  state.bookmarkedPages = new Set(marks.map((m) => m.page));
  state.currentBook = book;
  state.currentPage = Math.min(Math.max(1, startPage || 1), book.page_count || 1);
  els.pageInput.max = String(book.page_count || 1);
  await renderPage(0);
}

function updateFavIcon(book) {
  const on = !!book && state.favs.has(book.id);
  els.favBtn.classList.toggle("on", on);
  els.favBtn.title = on ? "إزالة من المفضلة" : "أضف إلى المفضلة";
}
function updateBookmarkIcon() {
  const on = state.bookmarkedPages.has(state.currentPage);
  els.bookmarkBtn.classList.toggle("on", on);
  els.bookmarkBtn.title = on ? "إزالة الإشارة المرجعية" : "إشارة مرجعية لهذه الصفحة";
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function renderPage(direction) {
  const book = state.currentBook;
  if (!book) return;
  const page = state.currentPage;
  const text = await getPage(book.id, page);
  if (book !== state.currentBook || page !== state.currentPage) return; // a faster page turn took over
  if (direction) {
    els.pageText.classList.add(direction > 0 ? "turn-next" : "turn-prev");
    await sleep(120);
    if (book !== state.currentBook || page !== state.currentPage) return;
  }
  els.pageText.textContent = text || "(هذه الصفحة غير متوفرة)";
  els.pageText.scrollTop = 0;
  els.pageText.classList.remove("turn-next", "turn-prev");

  const count = book.page_count || 1;
  els.pageInput.value = String(page);
  els.pageTotal.textContent = `/ ${count}`;
  els.prevPageBtn.disabled = page <= 1;
  els.nextPageBtn.disabled = page >= count;
  updateBookmarkIcon();

  const e = curEntry();
  if (e && e.type === "book" && e.bookId === book.id) e.page = page; // so Back/Forward return to this page

  const rec = await saveProgress(book.id, page);
  state.progress.set(book.id, rec);
}

function goToPage(n) {
  const book = state.currentBook;
  if (!book) return;
  const count = book.page_count || 1;
  n = parseInt(n, 10);
  if (isNaN(n)) { els.pageInput.value = String(state.currentPage); return; }
  n = Math.min(Math.max(1, n), count);
  if (n === state.currentPage) { els.pageInput.value = String(n); return; }
  const diff = n - state.currentPage;
  state.currentPage = n;
  renderPage(Math.abs(diff) === 1 ? diff : 0); // animate single-page turns only; jumps just switch
}
const nextPage = () => goToPage(state.currentPage + 1);
const prevPage = () => goToPage(state.currentPage - 1);

els.prevPageBtn.onclick = prevPage;
els.nextPageBtn.onclick = nextPage;
els.pageInput.addEventListener("focus", () => els.pageInput.select());
els.pageInput.addEventListener("change", () => goToPage(els.pageInput.value));
els.pageInput.addEventListener("keydown", (e) => { if (e.key === "Enter") els.pageInput.blur(); });

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
    if (window.getSelection && String(window.getSelection()).length) return; // dragging a text selection, not turning a page
    if (dx < 0) nextPage(); else prevPage();   // RTL: swipe left = forward
  }, { passive: true });
})();

/* ---- favorite + bookmark buttons in the reader ---- */
els.favBtn.onclick = async () => {
  const book = state.currentBook;
  if (!book) return;
  await setFavorite(book.id);
  updateFavIcon(book);
};

els.bookmarkBtn.onclick = async () => {
  const book = state.currentBook;
  if (!book) return;
  const page = state.currentPage;
  const snippet = (els.pageText.textContent || "").replace(/\s+/g, " ").trim().slice(0, 70);
  const action = await toggleBookmark(book.id, page, snippet);
  if (action === "added") state.bookmarkedPages.add(page); else state.bookmarkedPages.delete(page);
  updateBookmarkIcon();
  toast(action === "added" ? `أُضيفت علامة في الصفحة ${page}` : `أُزيلت العلامة من الصفحة ${page}`);
};

/* ---- table of contents + bookmarks sheet ---- */
const tocEls = {
  list: $("tocList"), marks: $("marksList"), tabToc: $("tabToc"), tabMarks: $("tabMarks"),
};

function switchTocTab(which) {
  const toc = which === "toc";
  tocEls.tabToc.classList.toggle("active", toc);
  tocEls.tabMarks.classList.toggle("active", !toc);
  tocEls.list.style.display = toc ? "" : "none";
  tocEls.marks.style.display = toc ? "none" : "";
  if (toc) renderTocList(); else renderMarksList();
}

async function renderTocList() {
  const book = state.currentBook;
  if (!book) return;
  tocEls.list.innerHTML = `<div class="toc-empty">جارٍ تحميل الفهرس...</div>`;
  const toc = await ensureToc(book);
  if (book !== state.currentBook) return;
  tocEls.list.innerHTML = "";
  if (!toc.length) {
    tocEls.list.innerHTML = `<div class="toc-empty">لا يوجد فهرس لهذا الكتاب</div>`;
    return;
  }
  const levels = tocLevels(toc);
  const frag = document.createDocumentFragment();
  toc.forEach((t, i) => {
    const row = document.createElement("div");
    row.className = "toc-item lvl" + levels[i];
    row.style.setProperty("--lvl", levels[i]);
    row.textContent = t.title || "";
    row.onclick = () => {
      closeSheet(els.tocOverlay);
      goToPage(positionOfPageNumber(book, t.page));
    };
    frag.appendChild(row);
  });
  tocEls.list.appendChild(frag);
}

async function renderMarksList() {
  const book = state.currentBook;
  if (!book) return;
  const marks = await getBookmarks(book.id);
  if (book !== state.currentBook) return;
  tocEls.marks.innerHTML = "";
  if (!marks.length) {
    tocEls.marks.innerHTML = `<div class="toc-empty">لا توجد علامات بعد — اضغط أيقونة العلامة أثناء القراءة</div>`;
    return;
  }
  marks.forEach((m) => {
    const row = document.createElement("div");
    row.className = "bm-item";
    row.innerHTML = `
      <div class="bm-main"><div class="bm-page">صفحة ${m.page}</div>${m.note ? `<div class="bm-snippet">${escapeHtml(m.note)}</div>` : ""}</div>
      <button class="bm-remove" aria-label="حذف العلامة">✕</button>`;
    row.onclick = () => { closeSheet(els.tocOverlay); goToPage(m.page); };
    row.querySelector(".bm-remove").onclick = async (ev) => {
      ev.stopPropagation();
      await removeBookmark(m.key);
      state.bookmarkedPages.delete(m.page);
      updateBookmarkIcon();
      renderMarksList();
    };
    tocEls.marks.appendChild(row);
  });
}

els.tocBtn.onclick = () => { if (state.currentBook) { openSheet(els.tocOverlay); switchTocTab("toc"); } };
tocEls.tabToc.onclick = () => switchTocTab("toc");
tocEls.tabMarks.onclick = () => switchTocTab("marks");
$("closeTocBtn").onclick = () => closeSheet(els.tocOverlay);

/* ---- font + theme sheet ---- */
els.displayBtn.onclick = () => { applyFontPrefs(); openSheet(els.displayOverlay); };
$("closeDisplayBtn").onclick = () => closeSheet(els.displayOverlay);
$("fontMinus").onclick = () => changeFontSize(-2);
$("fontPlus").onclick = () => changeFontSize(2);
document.querySelectorAll("[data-font]").forEach((b) => {
  b.onclick = () => { prefSet("arabicFont", b.dataset.font); applyFontPrefs(); };
});
document.querySelectorAll("[data-theme-value]").forEach((b) => {
  b.onclick = () => applyTheme(b.dataset.themeValue);
});

/* -------------------------------------------------------------------- */
/* Sheets + the phone's back button                                       */
/* -------------------------------------------------------------------- */
const allSheets = [els.settingsOverlay, els.tocOverlay, els.displayOverlay];

function openSheet(el) { el.style.display = "flex"; }
function closeSheet(el) {
  if (el.style.display === "none") return;
  el.style.display = "none";
  if (el === els.settingsOverlay) refreshLibrary();
}
// Tapping the dimmed area outside a sheet closes it.
allSheets.forEach((s) => s.addEventListener("click", (e) => { if (e.target === s) closeSheet(s); }));

// The phone's back button: close an open sheet first, then step back through the
// history, and only leave the app from the very first screen.
function handleBackButton() {
  const open = allSheets.find((s) => s.style.display !== "none");
  if (open) { closeSheet(open); return; }
  if (state.searchOpen && document.body.dataset.mode === "library" && nav.index === 0 && !state.filter) {
    openSearchBar(false);
    return;
  }
  if (nav.index > 0) { navBack(); return; }
  if (AppPlugin && AppPlugin.exitApp) AppPlugin.exitApp();
}
if (AppPlugin && AppPlugin.addListener) {
  AppPlugin.addListener("backButton", handleBackButton);
}

/* -------------------------------------------------------------------- */
/* Settings sheet: choose the library folder                              */
/* -------------------------------------------------------------------- */
const settingsEls = {
  overlay: els.settingsOverlay,
  folderOption: $("pickFolderOption"),
  rescanOption: $("rescanOption"),
  catalogOption: $("catalogOption"),
  progress: $("sheetProgress"),
  progressFill: $("progressFill"),
  progressLabel: $("progressLabel"),
  stats: $("sheetStats"),
  closeBtn: $("closeSheetBtn"),
  clearBtn: $("clearLibraryBtn"),
};

function openSettings() {
  applyTheme(currentTheme()); // refresh the highlighted theme chip
  openSheet(settingsEls.overlay);
  updateStats();
}
function closeSettings() { closeSheet(settingsEls.overlay); }

async function updateStats() {
  const books = await getAllBooks();
  settingsEls.stats.textContent = books.length
    ? `المكتبة الحالية: ${books.length} كتاب`
    : "لا توجد مكتبة محمّلة بعد.";
  // On an empty library there's nothing to delete - only show the
  // import option. The delete button reappears as soon as a library exists.
  settingsEls.clearBtn.style.display = books.length ? "" : "none";
  // Re-indexing needs a folder that was picked before.
  const hasFolder = !!prefGet("maktaba_tree_uri", "");
  settingsEls.rescanOption.style.display = hasFolder ? "flex" : "none";
  settingsEls.catalogOption.style.display = hasFolder ? "flex" : "none";
}

els.settingsBtn.onclick = openSettings;
els.creditsBtn.onclick = () => {
  const showing = els.creditsBanner.style.display !== "none";
  els.creditsBanner.style.display = showing ? "none" : "";
};
$("emptyOpenSettings").onclick = openSettings;
settingsEls.closeBtn.onclick = closeSettings;
settingsEls.clearBtn.onclick = async () => {
  const understood = confirm(
    "تحذير: حذف المكتبة\n\n" +
    "سيؤدي هذا إلى حذف جميع الكتب وتقدّم القراءة والمفضلة والعلامات المرجعية المحفوظة نهائيًا من داخل هذا التطبيق، ولا يمكن التراجع عن ذلك بعد الحذف.\n\n" +
    "ملاحظة مهمة: هذا الإجراء لا يقوم بإلغاء تثبيت التطبيق نفسه من الهاتف - فقط يمسح الكتب المستوردة بداخله، ويمكنك بعدها استيراد المكتبة من جديد في أي وقت.\n" +
    "إذا كنت تريد حذف التطبيق بالكامل من الهاتف بدلاً من ذلك، أغلق هذه الرسالة وقم بإلغاء تثبيت التطبيق من إعدادات الهاتف.\n\n" +
    "هل تريد المتابعة وحذف مكتبة الكتب من داخل التطبيق؟"
  );
  if (!understood) return;

  const finalConfirm = confirm(
    "تأكيد نهائي: سيتم حذف جميع الكتب وتقدّم القراءة والمفضلة والعلامات الآن ولن يمكن استرجاعها.\n\nهل أنت متأكد؟"
  );
  if (!finalConfirm) return;

  await clearLibrary();
  await updateStats();
  await refreshLibrary();
};

function showProgress(show) {
  settingsEls.progress.style.display = show ? "block" : "none";
}
function setProgress(done, total, label) {
  const pct = total ? Math.round((done / total) * 100) : 0;
  settingsEls.progressFill.style.width = pct + "%";
  settingsEls.progressLabel.textContent = label || `${done} / ${total}`;
}

// One import routine shared by the "pick folder" and "re-index" buttons. Native
// code sends the books as small index entries in batches ("libraryIndexBatch");
// each batch is saved in one quick database write. Nothing else is read.
async function runNativeImport({ fileEvent, skipEvent, extraListeners, startCall }) {
  const existing = new Set((await getAllBooks()).map((b) => b.ref).filter(Boolean));
  const out = { indexed: 0, duplicates: 0, skippedCount: 0, result: null };
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

  // Native no longer sends whole files (no book text is loaded during indexing); if an
  // older native build does, just acknowledge it so the import is never held up.
  listeners.push(await FolderImporter.addListener(fileEvent, async () => {
    try { await FolderImporter.ackImportFile(); } catch (e) { /* native times out and continues */ }
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

async function finishImport(out, rescan) {
  const total = out.indexed;
  const r = out.result || {};
  debugLog(`أرسل النظام ${r.scannedCount ?? "?"} ملف. فُهرس ${out.indexed} كتاب، ${out.duplicates} موجود مسبقًا، تم تجاوز ${r.skippedCount ?? out.skippedCount}.`);
  if (r.scannedCount != null && out.indexed + out.duplicates < r.scannedCount) {
    debugLog(`تحذير: فُقد ${r.scannedCount - (out.indexed + out.duplicates)} ملف أثناء النقل.`);
  }
  setProgress(total, total, rescan ? `تمت إضافة ${total} كتاب جديد.` : `تم! أُضيف ${total} كتاب.`);
  await updateStats();
  await refreshLibrary();
  if (!total && !out.duplicates) {
    alert("لم يتم العثور على أي كتب (لا يوجد ملف فهرس CSV ولا ملفات JSON) في هذا المجلد.");
  } else if (!total) {
    alert(rescan
      ? `لا توجد كتب جديدة. كل الكتب (${out.duplicates}) موجودة مسبقًا في المكتبة.`
      : `كل الكتب (${out.duplicates}) موجودة مسبقًا في المكتبة.`);
  } else {
    alert(`اكتملت الفهرسة.\nإجمالي الكتب المضافة: ${total} كتاب.\nيتم تحميل نص كل كتاب عند الضغط عليه فقط.`);
  }
}

// Pick the "maktaba" folder. If it holds a CSV catalog the library list is built from
// that; otherwise every .json/.db inside it is indexed. The folder must stay on the
// phone: each book is read from its category folder the first time it is tapped.
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
  if (out.result && out.result.treeUri) prefSet("maktaba_tree_uri", out.result.treeUri); // enables "re-index" later
  await finishImport(out, false);
};

// Re-index: scan the already-picked folder again for new books (no folder picker).
// Books already in the library are skipped, so ids, favorites, bookmarks and
// reading progress are untouched.
async function runRescan() {
  const treeUri = prefGet("maktaba_tree_uri", "");
  if (!treeUri || !FolderImporter) return;
  debugLog("إعادة الفهرسة للمجلد المختار سابقًا.");
  showProgress(true);
  setProgress(0, 0, "جارٍ البحث عن كتب جديدة...");

  let out;
  try {
    out = await runNativeImport({
      fileEvent: "folderImportFile",
      skipEvent: "folderImportSkipped",
      startCall: () => FolderImporter.rescanFolder({ treeUri }),
    });
  } catch (err) {
    debugLog("فشلت إعادة الفهرسة: " + (err && err.message || err));
    showProgress(false);
    alert("تعذّرت إعادة الفهرسة: " + (err && err.message || err) + "\nاختر مجلد المكتبة من جديد من الخيار أعلاه.");
    return;
  }
  await finishImport(out, true);
}
settingsEls.rescanOption.onclick = runRescan;

// Copy a catalog CSV into the library folder - but only after checking that the books it
// lists are really in that folder. Nothing is copied when none of them are.
const NO_WRITE_MSG = "لا توجد صلاحية الكتابة في مجلد المكتبة.\nاضغط \"مجلد المكتبة (maktaba)\" واختر المجلد من جديد (وامنح التطبيق صلاحية التعديل)، ثم أعد المحاولة.";

settingsEls.catalogOption.onclick = async () => {
  const treeUri = prefGet("maktaba_tree_uri", "");
  if (!FolderImporter) { alert("هذه الميزة غير متوفرة في هذا الإصدار من التطبيق."); return; }
  if (!treeUri) { alert("اختر مجلد المكتبة أولًا."); return; }

  let file;
  try {
    file = await FolderImporter.pickCatalogFile();
  } catch (err) {
    const msg = (err && err.message) || "";
    if (!/cancel|إلغاء/i.test(msg)) alert(msg || "تعذّر اختيار الملف.");
    return;
  }
  debugLog(`تم اختيار ملف الفهرس: ${file.name}`);
  if (!/\.(csv|tsv|txt)$/i.test(file.name || "")) {
    alert(`الملف "${file.name}" ليس ملف CSV. اختر ملفًا بامتداد .csv`);
    return;
  }

  showProgress(true);
  setProgress(0, 0, "جارٍ فحص ملف الفهرس والمجلد...");
  let r;
  try {
    r = await FolderImporter.checkCatalog({ treeUri, base64: file.base64 });
  } catch (err) {
    showProgress(false);
    debugLog("فشل فحص الفهرس: " + (err && err.message || err));
    alert(err && err.message || "تعذّر فحص ملف الفهرس.");
    return;
  }
  showProgress(false);
  debugLog(`فحص الفهرس: ${r.rows} صف، ${r.found} موجود، ${r.missing} غير موجود، ${r.unlisted} كتاب في المجلد غير مذكور.`);

  if (!r.rows) {
    alert("لا يحتوي الملف على أي كتب.\nتأكد أن الصف الأول عناوين الأعمدة: title,author,category,file");
    return;
  }
  const sample = (r.missingSample || []).map((x) => "• " + x).join("\n");
  if (!r.found) {
    alert(`لم يتم النسخ: لم يُعثر على أي كتاب من الفهرس (${r.rows} كتاب) داخل مجلد المكتبة.\n\n` +
      "تأكد أن ملفات الكتب (.json) داخل المجلد الذي اخترته، وأن عمود file يطابق أسماءها.\n\n" +
      (sample ? "أمثلة على الكتب غير الموجودة:\n" + sample : ""));
    return;
  }
  if (!r.canWrite) { alert(NO_WRITE_MSG); return; }

  let msg = `نتيجة الفحص:\n✔ ${r.found} من ${r.rows} كتاب موجودة في المجلد.`;
  if (r.missing) msg += `\n✖ ${r.missing} كتاب غير موجودة (ستظهر في المكتبة لكن لن تُفتح):\n${sample}${r.missing > (r.missingSample || []).length ? "\n…" : ""}`;
  if (r.unlisted) msg += `\nℹ ${r.unlisted} كتاب داخل المجلد غير مذكورة في هذا الفهرس (لن تظهر).`;
  if (r.hasExisting) msg += "\n\nسيُستبدل ملف catalog.csv الموجود في المجلد.";
  msg += "\n\nهل تريد نسخ الملف إلى مجلد المكتبة؟";
  if (!confirm(msg)) return;

  showProgress(true);
  setProgress(0, 0, "جارٍ نسخ الملف إلى المجلد...");
  try {
    await FolderImporter.installCatalog({ treeUri, base64: file.base64 });
  } catch (err) {
    showProgress(false);
    const m = (err && err.message) || "";
    debugLog("فشل نسخ الفهرس: " + m);
    alert(/NO_WRITE/.test(m) ? NO_WRITE_MSG : (m || "تعذّر نسخ الملف."));
    return;
  }
  debugLog("تم نسخ catalog.csv إلى المجلد.");
  toast("تم نسخ catalog.csv إلى المجلد");
  await runRescan(); // index the books it lists
};

/* ---------------- Boot ---------------- */
(async function boot() {
  applyTheme(currentTheme());
  applyFontPrefs();
  updateNavButtons();
  await refreshLibrary();
  if (!state.books.length) openSettings();
})();
