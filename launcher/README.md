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
- `app/src/main/java/com/uglyos/launcher/TodoPage.kt` — interactive todo.txt page with mode switcher
- `app/src/main/java/com/uglyos/launcher/TodoModeStore.kt` — user-defined todo modes (filters) + selected-mode persistence
- `app/src/main/java/com/uglyos/launcher/Search.kt` — global spotlight-style search (left of home)
- `app/src/main/java/com/uglyos/launcher/DateTimeWidget.kt` — home clock, calendar card, now-playing bar, next-event line
- `app/src/main/java/com/uglyos/launcher/NextEvent.kt` — reads the next calendar event via the calendar provider
- `app/src/main/java/com/uglyos/launcher/MediaControl.kt` — reads/controls the active media session (notification-listener service + helpers)
- `app/src/main/java/com/uglyos/launcher/Frecency.kt` — per-app launch history feeding search ranking
- `app/src/main/AndroidManifest.xml` — registers as HOME, queries launchable apps
- `app/src/main/res/` — icon (adaptive, Nord-themed monkey), theme, strings
- `common/` — shared library module (Nord theme, todo.txt library); see `common/README.md`

## Basics

- Package id: `com.uglyos.launcher`. minSdk 30, compileSdk 35.
- Set as default: home button → pick "ugly launcher". Revert: Settings → Apps → Default apps → Home app.
- Pages, left to right: search, home, todo, settings.
- The "monkey dir" (set in settings) is the directory the launcher reads data
  from. Reading arbitrary paths needs all-files access (`MANAGE_EXTERNAL_STORAGE`).
  Settings persist in the `ugly_launcher` prefs.

## Features

- **Home** — dot-matrix clock, calendar card, and a next-event stack: up to three
  of the next hour's events with live countdowns, hidden when the hour is clear.
  Which calendars feed it is user-controlled in settings.
- **Now playing** — a control that surfaces between the calendar card and the
  next-event stack only when a media session is live: title, artist, and prev /
  play-pause / next. Hand-drawn glyphs, hidden when nothing is playing. Needs
  notification-listener access (Android's only route to other apps' media
  sessions), granted from settings → permissions → media controls.
- **Quick launch** — a dock of user-pinned apps as monochrome glyphs on a fixed
  grid (default 2 × 5). Tap launches, long-press removes, `+` pins. Seeded on
  first run from default shortcuts, then it's whatever you pin. Persists in
  `quick_launch` prefs.
- **Search** — fans the query out to independent providers (apps, settings,
  contacts, web fallback) and ranks all hits on one scale; the top hit is what
  Enter opens. Graded fuzzy scoring plus a frecency boost (`frecency` prefs,
  ~3-day half-life). Add a source with another provider in `Search.kt`.
- **Todo** — one page over `monkey_dir/atp/todo/todo.txt`. The footer names the
  active mode (bottom-right); tapping it opens a menu to switch, add, edit, or
  delete modes. A fresh install is seeded with an "all" mode that shows everything,
  editable like any other; you define modes that filter on a `+project`, an
  `@context`, or both — or the inverse (match vs exclude). A scoped mode hides its
  own tag on each row and auto-appends it to tasks
  added there, so a task lands in the mode you made it in. The chosen mode persists
  across restarts. Add/edit/complete tasks (done archived to done.txt); live-reload
  via `FileObserver` so Syncthing edits show up. Modes persist in `todo_modes` prefs.
- **Settings** — grouped by signpost (data, quick launch, permissions, next
  event). Permissions are requested inline, routing to system settings once
  permanently denied.
