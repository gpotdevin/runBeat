# Build Instructions

Gradle wrapper only — no global Gradle install needed.

## Prerequisites
- JDK 17, Android SDK platform `android-36`, build tools `34.0.0` (pinned in `app/build.gradle`)
- Android NDK `26.1.10909125` + CMake (for the vendored `aubio` native module)
- `cp local.properties.template local.properties` and fill in `sdk.dir`, `ndk.dir`, `java.home`

## Commands
```bash
./gradlew :app:assembleDebug        # debug APK → app/build/outputs/apk/debug/
./gradlew :app:bundleRelease        # release AAB → app/build/outputs/bundle/release/
./gradlew :app:installDebug         # install on device
./gradlew testDebugUnitTest         # unit tests
./gradlew lintDebug                 # lint
./gradlew connectedAndroidTest      # instrumented tests (device/emulator)
./gradlew :aubio:assembleRelease :soundtouch-android:assembleRelease   # native module changes
```
Flavors: `fdroid` (default, F-Droid build: `:app:assembleFdroidRelease`) and `garmin`
(`:app:assembleGarminDebug`).

## Signing a release
Release builds sign from environment variables (no secrets in the repo):
`BPM_RELEASE_STORE_FILE`, `BPM_RELEASE_STORE_PASSWORD`, `BPM_RELEASE_KEY_ALIAS`,
`BPM_RELEASE_KEY_PASSWORD` → `./gradlew :app:bundleRelease`. Never commit a keystore.

## Native modules
`aubio/` and `soundtouch-android/` are vendored copies (not submodules); C++ changes need a full
rebuild of those modules (command above).

## Versioning
`versionCode` = `build-counter.txt` (repo root, manually incremented once per release — single
source of truth, also read by F-Droid's `UpdateCheckData`); `versionName` in `app/build.gradle`.
