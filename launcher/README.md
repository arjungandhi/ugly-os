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
- `app/src/main/AndroidManifest.xml` — registers as HOME, queries launchable apps
- `app/src/main/res/` — icon (adaptive, Nord-themed monkey), theme, strings
- `common/src/main/java/com/uglyos/common/theme/` — shared Nord theme, exposed as a library module
- `common/src/main/java/com/uglyos/common/todo/` — todo.txt parsing/editing library

## todo.txt library

`com.uglyos.common.todo` reads and writes [todo.txt](https://github.com/todotxt/todo.txt)
files. `Task` is a parsed line (completion, priority, dates, and inline
`+project`/`@context`/`key:value` tags); `TodoList` is the ordered collection with
add/update/remove/filter/sort; `TodoFile` loads and atomically saves a file.

```kotlin
val todos = TodoFile(File(dir, "todo.txt")).load()
todos.add("(A) call mom @phone due:2026-07-01")
todos.update(0) { it.complete(LocalDate.now()) }
```

## Notes

- Package id: `com.uglyos.launcher`
- minSdk 30, compileSdk 35
- Set as default: home button → pick "ugly launcher". Revert: Settings → Apps → Default apps → Home app.
- Quick-launch shortcuts (phone, messages, email, internet, music, videos, camera,
  wallet). Phone/email/internet/music/camera resolve to the user's default app via
  implicit intents. Messages maps to Beeper (`com.beeper.android`), videos to
  Grayjay (`com.futo.platformplayer`), and wallet to Google Wallet
  (`com.google.android.apps.walletnfcrel`), all launched by package.
