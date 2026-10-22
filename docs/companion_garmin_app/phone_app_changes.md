# Changes to the Existing RunBeat Codebase

Date: 2026-10-22

Scope: phone app only. Everything below is additive; no existing behavior
is removed. Paths are relative to the repo root. The Garmin SDK integration
is isolated behind a `garmin` product flavor so the F-Droid build is
unaffected (see "Build configuration" at the end).

## 1. Data layer — mark-as-bad flag

### 1a. `app/src/main/java/com/bpmapp/audio/data/Track.kt`
Add one field, mirroring the existing `isFavorite` pattern:

```kotlin
@ColumnInfo(defaultValue = "0")
val isBad: Boolean = false     // Marked "bad for running" (e.g. from the watch)
```

### 1b. `app/src/main/java/com/bpmapp/audio/data/AppDatabase.kt`
Bump `version = 6` to `version = 7`.

### 1c. `app/src/main/java/com/bpmapp/audio/di/AppModule.kt`
Add a migration following the existing `MIGRATION_5_6` pattern, and register
it in `provideDatabase()`:

```kotlin
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN isBad INTEGER NOT NULL DEFAULT 0")
    }
}
// .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
```

### 1d. `app/src/main/java/com/bpmapp/audio/data/TrackDao.kt`
Add:

```kotlin
@Query("UPDATE tracks SET isBad = :isBad WHERE id = :trackId")
suspend fun updateBad(trackId: String, isBad: Boolean): Int

@Query("SELECT * FROM tracks WHERE isBad = 0 ORDER BY fileName COLLATE NOCASE")
fun getPlayableTracks(): Flow<List<Track>>   // excludes bad tracks

@Query("SELECT * FROM tracks WHERE isBad = 1 ORDER BY fileName COLLATE NOCASE")
fun getBadTracks(): Flow<List<Track>>
```

## 2. Repository layer

### 2a. `app/src/main/java/com/bpmapp/audio/audio/TrackRepository.kt`
Add thin wrappers (same style as the existing favorite methods):

```kotlin
suspend fun markTrackBad(trackId: String): Int =
    trackDao.updateBad(trackId, true)

suspend fun unmarkTrackBad(trackId: String): Int =
    trackDao.updateBad(trackId, false)
```

### 2b. `app/src/main/java/com/bpmapp/audio/audio/PlayerRepository.kt`
Add volume control and make the current volume observable. No constructor
change (uses the injected `exoPlayer`):

```kotlin
private val _volume = MutableStateFlow(exoPlayer.volume)
val volume: StateFlow<Float> = _volume.asStateFlow()

fun setVolume(volume: Float) {
    val v = volume.coerceIn(0f, 1f)
    exoPlayer.volume = v
    _volume.value = v
}

fun volumeUp(step: Float = 0.1f) = setVolume(_volume.value + step)
fun volumeDown(step: Float = 0.1f) = setVolume(_volume.value - step)
```

**Mode-beep interaction fix** (small, in `maybePlayModeBeep()`): the beep
ducking captures `volumeBeforeDuck = exoPlayer.volume` and restores it
absolutely. If the user changes volume during the ~1 s beep window, the
restore clobbers the user's change. Change the ducking to store a
*multiplier* (`duckFactor = 0.3f`) and compute
`exoPlayer.volume = (_volume.value * duckFactor).coerceIn(0f, 1f)` /
restore `_volume.value`, keeping `_volume` as the single source of truth.

Everything else the watch needs already exists and is public:
`togglePlayPause()`, `play()`, `pause()`, `playNext()`, `playPrevious()`,
`setTargetCadence(Float)`, `incrementTargetCadence()`,
`decrementTargetCadence()`, `toggleSpeedCorrection()`,
`speedCorrectionEnabled: StateFlow<Boolean>`, `targetCadence: StateFlow<Float>`,
`playbackSpeed: StateFlow<Float>`, `currentTrack: StateFlow<MediaItem?>`,
`removeTrackFromUpcoming(trackId)`.

## 3. New Garmin package (behind the `garmin` flavor)

`app/src/garmin/java/com/bpmapp/audio/audio/garmin/` (flavor-specific
source set — see section 6):

| File | Content |
|------|---------|
| `GarminCompanionManager.kt` | Hilt `@Singleton`. Initializes `ConnectIQ`, registers the IQ app by UUID, implements the message listener, dispatches commands to `PlayerRepository`/`TrackRepository`, collects the repository StateFlows and pushes `state` messages to the watch on change. Exposes `connectionState: StateFlow<GarminConnectionState>`. |
| `GarminMessageCodec.kt` | Pure Kotlin + `org.json` (de)serialization of the protocol in [architecture.md](architecture.md). Unit-testable without the SDK. |
| `CadenceAverager.kt` | 60-sample ring buffer with timestamps (`add(sample)`, `meanLast60s()`). Fallback path if raw cadence samples are ever streamed to the phone. Unit-testable. |
| `GarminCommandReceiver.kt` (optional split) | The `when (command)` dispatch, kept separate from SDK plumbing for testability. |

Mark-bad orchestration lives in `GarminCompanionManager` (it needs both
repositories; putting it in `PlayerRepository` would create a dependency on
`TrackRepository` that does not exist today):

```kotlin
fun markCurrentTrackBad() {
    val trackId = playerRepository.currentTrack.value
        ?.mediaMetadata?.extras?.getString("trackId") ?: return
    scope.launch {
        trackRepository.markTrackBad(trackId)
        playerRepository.removeTrackFromUpcoming(trackId)
        // optional: playerRepository.playNext() — see open questions
        sendToWatch(ack("mark_bad", ok = true))
    }
}
```

Initialization: start the manager lazily from `MainActivity` (or `BpmApp`)
when the `garmin` flavor is present, e.g. via a small
`CompanionInitializer` interface with a no-op implementation in the
fdroid/default source set. No manifest service is required by the Connect
IQ Mobile SDK; it binds to Garmin Connect Mobile.

## 4. Queue building — filter bad tracks

`app/src/main/java/com/bpmapp/audio/viewmodel/LibraryViewModel.kt`

- Wherever the play queue / `MediaItem` list is built for playback
  (functions building on `trackRepository.allTracks` /
  `trackDao.getAllTracks()` and `getMediaItemForTrack()`), filter out
  `track.isBad` so bad tracks are skipped in the next session.
- Expose a `toggleBad(trackId)` function (mirroring the existing
  `toggleFavorite` at ~line 750) and surface the flag in
  `ui/LibraryScreen.kt` / `ui/components/TrackCard.kt` (badge + unmark
  action), so a track marked from the watch can also be unmarked from the
  phone.

## 5. UI / strings

- `app/src/main/res/values/strings.xml` (+ `values-fr`, `values-es`):
  strings for the "bad track" badge, confirmation messages, and the Garmin
  connection status (e.g. shown in `SettingsScreen.kt` under the `garmin`
  flavor: "Garmin watch: connected / not connected").
- `SettingsScreen.kt` / `SettingsViewModel.kt`: a Garmin section (flavor-
  gated) showing connection state and a "mark-as-bad also skips current
  track" preference if the open question is resolved that way.

## 6. Build configuration

`app/build.gradle`:

```groovy
// Garmin Maven repository for the Connect IQ Mobile SDK (proprietary).
// Only the 'garmin' flavor resolves it, so F-Droid builds never fetch it.

android {
    flavorDimensions += 'companion'
    productFlavors {
        fdroid { dimension 'companion' }   // default, no Garmin SDK
        garmin {
            dimension 'companion'
            // applicationIdSuffix '.garmin' (optional)
        }
    }
}

dependencies {
    garminImplementation 'com.garmin.connectiq:ciq-companion-app-sdk:<latest>'
}
```

plus the Garmin Maven repo in the top-level `build.gradle`
(`pluginManagement`/`dependencyResolutionManagement` or the module
`repositories` block — the module already declares its own `repositories`).

Source sets: put the Garmin classes in `app/src/garmin/java/...` so the
`fdroid` flavor compiles without the SDK, and add a no-op
`CompanionInitializer` in `app/src/main/java/...` (or `app/src/fdroid/`).

`AndroidManifest.xml`: no new permissions are required (the Connect IQ
Mobile SDK delegates communication to Garmin Connect Mobile; the app keeps
its no-`INTERNET` stance). Optionally declare nothing at all — connection
status is surfaced in-app.

## 7. Docs to update

- `docs/PRIVACY.md`: add a paragraph about the optional Garmin Connect IQ
  integration (messages transit Garmin Connect Mobile; content limited to
  playback state and cadence; no new phone permissions).
- `CHANGELOG.md`: feature entry once implemented.
- `docs/fdroid-metadata.yml`: no change (F-Droid keeps building the
  `fdroid` flavor with the current dependency set).

## 8. Tests

- Unit tests (existing JUnit4 + Mockito setup) for `GarminMessageCodec`
  and `CadenceAverager` — both are pure Kotlin, no SDK needed.
- Unit test for the Room migration 6->7 (Robolectric or
  `MigrationTestHelper` if instrumented tests are preferred).
- Unit test for `PlayerRepository` volume clamping (Robolectric, mocking
  `ExoPlayer` as done in existing tests — check `app/src/test` for the
  current pattern).
- Existing `CadenceMatcher` tests are unaffected (no change to the matcher).

## Summary of touched files

```
app/src/main/java/com/bpmapp/audio/data/Track.kt                 (add isBad)
app/src/main/java/com/bpmapp/audio/data/AppDatabase.kt           (version 7)
app/src/main/java/com/bpmapp/audio/data/TrackDao.kt              (3 new methods)
app/src/main/java/com/bpmapp/audio/di/AppModule.kt               (MIGRATION_6_7)
app/src/main/java/com/bpmapp/audio/audio/TrackRepository.kt      (2 wrappers)
app/src/main/java/com/bpmapp/audio/audio/PlayerRepository.kt     (volume API + duck fix)
app/src/main/java/com/bpmapp/audio/viewmodel/LibraryViewModel.kt (filter + toggleBad)
app/src/main/java/com/bpmapp/audio/ui/LibraryScreen.kt           (badge/unmark)
app/src/main/java/com/bpmapp/audio/ui/components/TrackCard.kt     (badge)
app/src/main/java/com/bpmapp/audio/ui/screens/SettingsScreen.kt   (garmin section, flavor-gated)
app/src/main/java/com/bpmapp/audio/viewmodel/SettingsViewModel.kt(flavor-gated state)
app/src/main/res/values/strings.xml (+ fr, es)                   (new strings)
app/src/garmin/java/com/bpmapp/audio/audio/garmin/               (NEW: manager, codec, averager)
app/build.gradle                                                 (flavors + garmin dep)
build.gradle / settings.gradle                                   (Garmin maven repo)
docs/PRIVACY.md, CHANGELOG.md                                    (doc updates)
```
