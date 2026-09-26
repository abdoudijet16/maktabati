# مكتبتي الدينية الشاملة — Android app

Book content credit: [shamela.ws](https://shamela.ws) (المكتبة الشاملة). This app is an independent, unofficial reader for locally-extracted book data — not affiliated with shamela.ws.

A mobile, offline rebuild of ShamelaReader:
- **Screen 1 — Library**: grid of books, category chips, search icon in the top bar.
- **Screen 2 — Reader**: tap a book → swipe **left/right** to turn pages, page counter at the bottom (`12 / 300`).
- **Top bar**: icon-only (back, search, bookmark, settings/gear) — no text buttons.
- **Settings (gear icon)**: lets you load the book database from either
  - a **.zip** of category folders full of `.json` books (same shape `indexer.py` expects), or
  - plain **.json** files picked directly.
  Everything is parsed in-app and stored in the phone's own local database (IndexedDB), so it works fully offline after the first load — no Flask server involved.

This is a [Capacitor](https://capacitorjs.com) project: the `www/` folder is the actual app (plain HTML/CSS/JS, no build step needed to *edit* it), and `android/` is the generated native Android Studio project that wraps it into a real `.apk`.

## Why there's no `.apk` file attached

Building a real Android package requires the Android SDK/Gradle plugin, which Google only serves from its own servers — the sandbox I built this in can't reach them. Everything else is done and tested (the Capacitor project is already generated under `android/`, `www/` is syntax-checked). You just need to run the actual compile step, which takes one of two forms below.

## Option A — Let GitHub build it for you (no Android Studio needed)

1. Create a new GitHub repo and push this whole folder to it.
2. GitHub Actions will run automatically (workflow already included at `.github/workflows/build-apk.yml`), or trigger it manually from the **Actions** tab → *Build Android APK* → *Run workflow*.
3. When it finishes (~3–5 min), open the run → **Artifacts** → download `shamela-reader-debug-apk`. That's your installable `.apk`.
4. Copy it to your phone and install (you'll need to allow "install unknown apps" for whichever app you copy it with).

## Option B — Build locally with Android Studio

1. Install [Android Studio](https://developer.android.com/studio) (it installs the SDK for you).
2. `npm install`
3. `npx cap sync android`
4. `npx cap open android` (opens the project in Android Studio)
5. **Build → Build Bundle(s)/APK(s) → Build APK(s)**. The `.apk` lands in `android/app/build/outputs/apk/debug/`.

## Changing the app icon / name

- Icon source: `www/icon/icon.png` (currently the original Shamela icon). For a properly-shaped launcher icon, use Android Studio's *Image Asset* tool (right-click `android/app/src/main/res` → New → Image Asset) and point it at that PNG.
- App name / package id: edit `capacitor.config.json` (`appName`, `appId`) before running `cap sync`.

## Loading your book library on the phone
database https://archive.org/details/machtaba-islamia
Open the app → tap the gear icon (top bar) → choose:
- **ملف مضغوط (.zip)** — a zip of your `extracted_books/` folder (category subfolders of `.json` files), or
- **ملفات JSON عادية** — select one or more `.json` book files directly, or
- **قاعدة بيانات SQLite (.db)** — import `library.db` directly (the file `indexer.py` already builds). The app reads it in-browser with `sql.js` (https://archive.org/details/machtaba-islamia)), pulling from the `books`/`pages` tables using the exact schema the desktop app already uses — no conversion needed.

Progress is shown live; once done, your books appear on the home screen with reading progress saved automatically as you swipe through pages.
