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
- Swipe left past the home screen to reach the settings page (4th page). Settings
  persist in SharedPreferences (`ugly_launcher`). The "monkey dir" is the directory
  the launcher reads its data from; it starts unset and is chosen via the system
  folder picker (Storage Access Framework), so the grant survives reboots.
