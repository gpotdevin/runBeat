# Build Instructions

This project builds with the Gradle wrapper; no global Gradle install is required.

## Prerequisites

- **JDK 17** (the project targets Java/Kotlin `JVM 17`)
- **Android SDK** with platforms `android-34`
- **Android NDK** (required for the `aubio` native module)
- **CMake** (used by the `aubio` native build)

## Environment setup

1. Clone the repository:

   ```bash
   git clone https://github.com/gpotdevin/runBeat.git
   cd bpm-app
   ```

2. Create `local.properties` pointing at your SDK/NDK. A template is provided:

   ```bash
   cp local.properties.template local.properties
   ```

   then fill in `sdk.dir`, `ndk.dir`, and `java.home` as needed.

3. (Optional) Set up CI-like signing environment variables — see
   [Signing a release](#signing-a-release).

## Build the app

Debug APK:

```bash
./gradlew :app:assembleDebug
```

Release Android App Bundle (AAB):

```bash
./gradlew :app:bundleRelease
```

Output locations:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release bundle: `app/build/outputs/bundle/release/app-release.aab`

## Install on a device

```bash
./gradlew :app:installDebug
```

## Tests and lint

```bash
./gradlew testDebugUnitTest        # JUnit unit tests
./gradlew lintDebug                # Android Lint
./gradlew connectedAndroidTest     # Instrumented tests (requires device/emulator)
```

## Signing a release

Release builds are signed from environment variables (so no secrets live in the
repo). The signing configuration in `app/build.gradle` reads:

| Variable | Description |
|----------|-------------|
| `BPM_RELEASE_STORE_FILE` | Path to the release keystore |
| `BPM_RELEASE_STORE_PASSWORD` | Keystore password |
| `BPM_RELEASE_KEY_ALIAS` | Key alias |
| `BPM_RELEASE_KEY_PASSWORD` | Key password |

```bash
export BPM_RELEASE_STORE_FILE=/path/to/bpm-release.keystore
export BPM_RELEASE_STORE_PASSWORD=your_store_password
export BPM_RELEASE_KEY_ALIAS=bpm_app
export BPM_RELEASE_KEY_PASSWORD=your_key_password
./gradlew :app:bundleRelease
```

Never commit a keystore or signing secrets.

## Native modules

`aubio` and `soundtouch-android` are vendored copies (not git submodules) of
upstream projects — see `aubio/README.md` and `soundtouch-android/README.md`.
Changes to their C++/native sources require a full rebuild of those modules:

```bash
./gradlew :aubio:assembleRelease :soundtouch-android:assembleRelease
```

## Versioning

`versionCode` is read from (and auto-incremented in) `build-counter.txt` at the
repository root. `versionName` is set in `app/build.gradle`.