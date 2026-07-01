# ugly launcher

A custom Android home-screen launcher. Kotlin + Jetpack Compose.

## Commands

Run from the repo root:

```
just build      # build debug APK
just install    # build + install to connected device
just test       # run unit tests
```

APK output: `launcher/app/build/outputs/apk/debug/app-debug.apk`

## Layout

- `app/src/main/java/com/uglyos/launcher/MainActivity.kt` — home screen + app drawer
- `app/src/main/AndroidManifest.xml` — registers as HOME, queries launchable apps
- `app/src/main/res/` — icon (adaptive), theme, strings

## Notes

- Package id: `com.uglyos.launcher`
- minSdk 30, compileSdk 35
- Set as default: home button → pick "ugly launcher". Revert: Settings → Apps → Default apps → Home app.
