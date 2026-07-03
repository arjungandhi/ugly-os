# ugly launcher

A custom Android home-screen launcher. Kotlin + Jetpack Compose.

## Commands

Run from the repo root:

```
just build      # build debug APK
just install    # build + install to connected device
just dev        # watch sources, reinstall + relaunch on change
just test       # run unit tests
```

`just dev` requires `watchexec`. It is not true hot reload — Compose Live Edit
is Android Studio only — but it reinstalls and relaunches on every `.kt`/`.xml`
change (incremental, usually a few seconds).

APK output: `launcher/app/build/outputs/apk/debug/app-debug.apk`

## Layout

- `app/src/main/java/com/uglyos/launcher/MainActivity.kt` — home screen + app drawer
- `app/src/main/java/com/uglyos/launcher/Shortcuts.kt` — home-screen quick-launch dock (pinned apps)
- `app/src/main/java/com/uglyos/launcher/QuickLaunchStore.kt` — dock pins + grid-size persistence
- `app/src/main/java/com/uglyos/launcher/AppGlyph.kt` — monochrome app-icon glyph, shared by drawer + dock
- `app/src/main/java/com/uglyos/launcher/Settings.kt` — settings page + persisted config
- `app/src/main/java/com/uglyos/launcher/TodoPage.kt` — read-only todo.txt list pages
- `app/src/main/java/com/uglyos/launcher/Search.kt` — global spotlight-style search (left of home)
- `app/src/main/java/com/uglyos/launcher/DateTimeWidget.kt` — home clock, calendar card, next-event line
- `app/src/main/java/com/uglyos/launcher/NextEvent.kt` — reads the next calendar event via the calendar provider
- `app/src/main/java/com/uglyos/launcher/Frecency.kt` — per-app launch history feeding search ranking
- `app/src/main/AndroidManifest.xml` — registers as HOME, queries launchable apps
- `app/src/main/res/` — icon (adaptive, Nord-themed monkey), theme, strings
- `common/` — shared library module (Nord theme, todo.txt library); see `common/README.md`

## Basics

- Package id: `com.uglyos.launcher`. minSdk 30, compileSdk 35.
- Set as default: home button → pick "ugly launcher". Revert: Settings → Apps → Default apps → Home app.
- Pages, left to right: search, home, todo, work, settings.
- The "monkey dir" (set in settings) is the directory the launcher reads data
  from. Reading arbitrary paths needs all-files access (`MANAGE_EXTERNAL_STORAGE`).
  Settings persist in the `ugly_launcher` prefs.

## Features

- **Home** — dot-matrix clock, calendar card, and a next-event stack: up to three
  of the next hour's events with live countdowns, hidden when the hour is clear.
  Which calendars feed it is user-controlled in settings.
- **Quick launch** — a dock of user-pinned apps as monochrome glyphs on a fixed
  grid (default 2 × 5). Tap launches, long-press removes, `+` pins. Seeded on
  first run from default shortcuts, then it's whatever you pin. Persists in
  `quick_launch` prefs.
- **Search** — fans the query out to independent providers (apps, settings,
  contacts, web fallback) and ranks all hits on one scale; the top hit is what
  Enter opens. Graded fuzzy scoring plus a frecency boost (`frecency` prefs,
  ~3-day half-life). Add a source with another provider in `Search.kt`.
- **Todo / work** — read `monkey_dir/atp/todo/todo.txt` (read-only). "todo" shows
  every task except `@pattern`; "work" shows only `@pattern`. Live-reload via
  `FileObserver` so Syncthing edits show up.
- **Settings** — grouped by signpost (data, quick launch, permissions, next
  event). Permissions are requested inline, routing to system settings once
  permanently denied.
