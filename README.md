# مكتبة إسلامية — Android app

Book content credit: [shamela.ws](https://shamela.ws) (المكتبة الشاملة). This app is an independent, unofficial reader for locally-extracted book data — not affiliated with shamela.ws.

The Android edition of the **islamic-library** desktop app (v2.0 brings it up to the same feature set):

- **Library**: grid of books with tabs **الكل / المؤلف / التصنيف / ⭐ المفضلة**, a "continue reading" row, and search (ignores diacritics and alef/yaa/taa-marbuta spelling variants). Long lists load as you scroll, so thousands of books stay smooth.
- **Reader**: swipe **left/right** (or use ‹ ›) to turn pages; type a number in the page box to jump straight to a page.
- **Back / Forward history** like the desktop app: opened the wrong book? Back returns to the same search / author / category list at the same scroll position; Forward returns to the book on the page you were reading. The phone's own back button does the same (it only closes the app from the first screen).
- **Favorites ("read later")**: tap ★ on any book card, or the star icon inside the reader.
- **Bookmarks per exact page**, toggled (tap again to remove). The bookmark icon lights up when the current page is bookmarked.
- **Table of contents** (read from the book's `toc`, opened on demand) and a bookmarks list, from the list icon in the reader.
- **Aa sheet**: font size, Amiri / Noto Naskh Arabic (bundled, fully offline) and **light (Mushaf) / dark (Night)** themes - same palette as the desktop app. Theme can also be switched in settings.
- **Re-index** (settings): scans the folder you picked earlier for newly added books without picking it again. Existing books, favorites, bookmarks and progress are never touched.
- Reading position is saved automatically on every page.

**Nothing is loaded until you choose a book.** Importing only builds the list (from `catalog.csv`, or the first 8 KB header of each book file if there is no catalog). A book's text is read from the `maktaba` folder only when you tap it (streamed, so size doesn't matter), and its table of contents only when you open the contents list. Whole database files (`.db` / `.sqlite`) are no longer imported, because they would load many books at once.

This is a [Capacitor](https://capacitorjs.com) project: the `www/` folder is the actual app (plain HTML/CSS/JS, no build step needed to *edit* it), and `android/` is the generated native Android Studio project that wraps it into a real `.apk`.

## Why there's no `.apk` file attached

Building a real Android package requires the Android SDK/Gradle plugin, which Google only serves from its own servers, so the APK has to be compiled by GitHub (Option A) or on your PC (Option B). The web layer (`www/`) was tested end-to-end against a simulated device; the Java changes were compile-checked but the finished APK has not been run on a physical phone yet.

## Updating from v1.x

- Your library, reading progress and the folder permission are kept **if you install over the old app**. The database upgrades itself on first launch (favorites and bookmarks are added).
- Caveat: debug APKs built by GitHub Actions are signed with a throw-away key, so Android may refuse to install over an APK that came from a *different* build machine ("package conflicts"). In that case uninstall the old app first, then re-pick your `maktaba` folder (books re-index in seconds). To avoid this in future, sign releases with your own keystore.
- Books opened before this update get their table of contents the first time you open the list icon.

## Option A — Let GitHub build it for you (no Android Studio needed)

1. Create a new GitHub repo and push this whole folder to it.
2. GitHub Actions will run automatically (workflow already included at `.github/workflows/build-apk.yml`), or trigger it manually from the **Actions** tab → *Build Android APK* → *Run workflow*.
3. When it finishes (~3–5 min), open the run → **Artifacts** → download `maktaba-islamia-debug-apk` (contains `maktaba-islamia.apk`).
   Tip: push a tag such as `v2.0.0` (`git tag v2.0.0 && git push --tags`) and the APK is also attached to a GitHub Release, giving you a permanent download link.
4. Copy it to your phone and install (you'll need to allow "install unknown apps" for whichever app you copy it with).

## Option B — Build locally with Android Studio

1. Install [Android Studio](https://developer.android.com/studio) (it installs the SDK for you).
2. `npm install`
3. `npx cap sync android`
4. `npx cap open android` (opens the project in Android Studio)
5. **Build → Build Bundle(s)/APK(s) → Build APK(s)**. The `.apk` lands in `android/app/build/outputs/apk/debug/`.

## Changing the app icon / name

- Icon source: `www/icon/icon.png`. For a properly-shaped launcher icon, use Android Studio's *Image Asset* tool (right-click `android/app/src/main/res` → New → Image Asset) and point it at that PNG.
- App name / package id: edit `capacitor.config.json` (`appName`, `appId`) before running `cap sync`.

## Loading your book library on the phone

There is no zip import or auto-download any more: you extract the books yourself.

```
maktaba/
├── catalog.csv          <- the catalog (see below)
├── التفسير/             <- one folder per category
│   └── tafsir_ibn_kathir.json
└── الحديث/
    └── riyad_alsalihin.json
```

1. Extract your books into `maktaba/<category>/` and copy the `maktaba` folder to the phone.
2. Put `catalog.csv` in `maktaba/`, next to the category folders. Columns (header row required, any order; comma, semicolon or tab separated, UTF-8):
   `title, author, category, file` — `file` is optional; if empty, the book is looked up as `<category>/<title>.json`. Optional `pages` column shows the page count on each card.
   Arabic headers also work: `الكتاب, المؤلف, التصنيف, ملف`. See `maktaba-example/`.
3. App → gear icon → **مجلد المكتبة (maktaba)** → pick that folder.

All books in the CSV appear in the library immediately (title / author / category). Nothing is read from the book files until you tap a book; if a book's file is missing the app tells you. If there is no CSV, the app falls back to scanning every `.json` in the folder as before. To add more books later: extract them, add rows to the CSV, and pick the folder again (already-listed books are skipped).
