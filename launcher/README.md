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
- `app/src/main/java/com/uglyos/launcher/Shortcuts.kt` — home-screen quick-launch grid
- `app/src/main/java/com/uglyos/launcher/Settings.kt` — settings page + persisted config
- `app/src/main/java/com/uglyos/launcher/TodoPage.kt` — read-only todo.txt list pages
- `app/src/main/java/com/uglyos/launcher/Search.kt` — global spotlight-style search (left of home)
- `app/src/main/java/com/uglyos/launcher/Frecency.kt` — per-app launch history feeding search ranking
- `app/src/main/AndroidManifest.xml` — registers as HOME, queries launchable apps
- `app/src/main/res/` — icon (adaptive, Nord-themed monkey), theme, strings
- `common/` — shared library module (Nord theme, todo.txt library); see `common/README.md`

## Notes

- Package id: `com.uglyos.launcher`
- minSdk 30, compileSdk 35
- Set as default: home button → pick "ugly launcher". Revert: Settings → Apps → Default apps → Home app.
- Quick-launch shortcuts (phone, messages, email, internet, music, videos, camera,
  wallet). Phone/email/internet/music/camera resolve to the user's default app via
  implicit intents. Messages maps to Beeper (`com.beeper.android`), videos to
  Grayjay (`com.futo.platformplayer`), and wallet to Google Wallet
  (`com.google.android.apps.walletnfcrel`), all launched by package.
- Pages, left to right: search, home, todo, work, settings.
- Search fans the query out to independent providers (apps, settings, contacts,
  web fallback) and ranks all hits on one scale, so the best match across every
  source leads as the "top hit" — the one Enter opens. Ranking uses graded fuzzy
  scoring (exact > prefix > word-start > substring > loose subsequence, with
  boundary/acronym bonuses) plus a frecency boost: apps launched more often and
  more recently rank higher. Launch history lives in the `frecency` prefs and
  decays with a ~3-day half-life. Add a source by writing another provider in
  `Search.kt` and dropping it into `runSearch`.
- The two todo pages read `monkey_dir/atp/todo/todo.txt` (read-only for now). The
  "todo" page shows every task *except* those tagged `@pattern`; the "work" page
  shows only `@pattern` tasks. Both are backed by the same `TodoPage` component.
- Todo reads use direct file I/O and a `FileObserver` on the todo dir, so edits
  synced in by Syncthing show up live; pages also reload on resume.
- Settings persist in SharedPreferences (`ugly_launcher`). The "monkey dir" is the
  directory the launcher reads its data from, stored as a plain path. It starts
  unset; the folder is picked once via the system picker (converted to a real path).
  Reading arbitrary paths needs all-files access (`MANAGE_EXTERNAL_STORAGE`), granted
  once from the settings page.
