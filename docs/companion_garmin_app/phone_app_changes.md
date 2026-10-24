# Changes to the Existing RunBeat Codebase

Date: 2026-10-22 (updated after decision review)

Scope: phone app only. Everything below is additive; no existing behavior
is removed. Paths are relative to the repo root. The Garmin SDK integration
is isolated behind a `garmin` product flavor so the F-Droid build is
unaffected (see "Build configuration" at the end).

Confirmed decisions reflected here: Connect IQ transport; marking a track
"not for running" skips it immediately; the flag filters future sessions
behind a settings toggle (default ON); the watch also controls external
apps via media key events + STREAM_MUSIC volume.

## 1. Data layer — "not for running" flag

The flag means "this track does not feel right to run on" — not a quality
rating. User-facing label: **"Not for running"**.

### 1a. `app/src/main/java/com/bpmapp/audio/data/Track.kt`
Add one field, mirroring the existing `isFavorite` pattern:

```kotlin
@ColumnInfo(defaultValue = "0")
val notForRunning: Boolean = false   // Marked "not for running" (e.g. from the watch)
```

### 1b. `app/src/main/java/com/bpmapp/audio/data/AppDatabase.kt`
Bump `version = 6` to `version = 7`.

### 1c. `app/src/main/java/com/bpmapp/audio/di/AppModule.kt`
Add a migration following the existing `MIGRATION_5_6` pattern, and register
it in `provideDatabase()`:

```kotlin
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN notForRunning INTEGER NOT NULL DEFAULT 0")
    }
}
// .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
```

### 1d. `app/src/main/java/com/bpmapp/audio/data/TrackDao.kt`
Add:

```kotlin
@Query("UPDATE tracks SET notForRunning = :notForRunning WHERE id = :trackId")
suspend fun updateNotForRunning(trackId: String, notForRunning: Boolean): Int

@Query("SELECT * FROM tracks WHERE notForRunning = 0 ORDER BY fileName COLLATE NOCASE")
fun getTracksExcludingNotForRunning(): Flow<List<Track>>

@Query("SELECT * FROM tracks WHERE notForRunning = 1 ORDER BY fileName COLLATE NOCASE")
fun getNotForRunningTracks(): Flow<List<Track>>
```

## 2. Repository layer

### 2a. `app/src/main/java/com/bpmapp/audio/audio/TrackRepository.kt`
Add thin wrappers (same style as the existing favorite methods):

```kotlin
suspend fun markTrackNotForRunning(trackId: String): Int =
    trackDao.updateNotForRunning(trackId, true)

suspend fun unmarkTrackNotForRunning(trackId: String): Int =
    trackDao.updateNotForRunning(trackId, false)
```

### 2b. `app/src/main/java/com/bpmapp/audio/audio/PlayerRepository.kt`
No volume API needed anymore — watch volume goes through `AudioManager`
STREAM_MUSIC (see §3), which composes with `exoPlayer.volume` (the in-app
multiplier used by mode-beep ducking).

Everything the watch needs already exists and is public:
`togglePlayPause()`, `play()`, `pause()`, `playNext()`, `playPrevious()`,
`setTargetCadence(Float)`, `incrementTargetCadence()`,
`decrementTargetCadence()`, `toggleSpeedCorrection()`,
`speedCorrectionEnabled: StateFlow<Boolean>`, `targetCadence: StateFlow<Float>`,
`playbackSpeed: StateFlow<Float>`, `currentTrack: StateFlow<MediaItem?>`,
`playerState: StateFlow<PlayerState>` (for `isPlaying`).

Optional small fix (mode-beep ducking): `maybePlayModeBeep()` captures
`volumeBeforeDuck = exoPlayer.volume` and restores it absolutely. Since
watch volume now changes the *stream* volume, not `exoPlayer.volume`, this
interaction disappears for watch users; the in-app edge case remains
(volume changed during the ~1 s beep window gets clobbered on restore).
Fix by storing the duck as a multiplier of the current player volume.

## 3. New Garmin package (SDK-free core in main, SDK wiring in `garmin`)

Final placement decision: the SDK-free core lives in the **main** source
set — `app/src/main/java/com/bpmapp/audio/audio/garmin/` — so it compiles
and is unit-tested in every flavor (it has no Garmin SDK imports):

| File | Content |
|------|---------|
| `GarminMessageCodec.kt` | `GarminCommand` sealed class, `GarminWatchState` data class, `GarminAck`, and the pure Kotlin + `org.json` (de)serialization of the protocol in [architecture.md](architecture.md). Unit-testable without the SDK. |
| `CadenceAverager.kt` | 60-sample ring buffer with timestamps (`add(sample)`, `meanLast60s()`). Fallback path if raw cadence samples are ever streamed to the phone. Unit-testable. |
| `MediaKeyDispatcher.kt` | Forwards transport commands to external apps via `AudioManager.dispatchMediaKeyEvent()`; adjusts/reads STREAM_MUSIC volume via `AudioManager`. No permissions required; `AudioManager` is constructor-injected for tests. |
| `CompanionInitializer.kt` | `CompanionInitializer` interface + `NoopCompanionInitializer` (the fdroid flavor has no companion SDK). The interface also has a default no-op `shutdown()` hook, but it is deliberately not called: the companion lives for the whole process and the OS reclaims the SDK receivers when the process dies. |
| `GarminCommandRouter.kt` | **Built (Phase 2b).** SDK-free routing + state builder, unit-tested in every flavor: implements the routing table below (transport → `PlayerRepository` when RunBeat is active, else media keys; volume always STREAM_MUSIC; cadence / speed-correction / mark-not-for-running are RunBeat-only), plus `currentState()` (`source` = "RunBeat" / "none"). Main-thread confined. |
| `GarminConnectionState.kt` | **Built (Phase 2b).** `GarminConnectionState` enum (DISCONNECTED/CONNECTING/CONNECTED) + `GarminWatchConnection` holder that carries the state across the source-set boundary (the manager writes it, the Settings screen reads it). |
| `GarminCompanionStarter.kt` | **Built (Phase 2b).** Marker interface (`GarminCompanionStarter : CompanionInitializer`) bound only by the garmin flavor; lets the main source set's default binding prefer the real initializer without a Hilt binding override. |
| `CompanionModule.kt` | **Built (Phase 2b).** Hilt module: provides `MediaKeyDispatcher` (both flavors) and binds `CompanionInitializer` — to `NoopCompanionInitializer` unless a `GarminCompanionStarter` is bound (garmin flavor). Also declares the `CompanionInitializerEntryPoint` Hilt entry point so `BpmApp` can start the companion at process scope. |

Only the Connect IQ SDK wiring goes in the flavor-specific source set
`app/src/garmin/java/com/bpmapp/audio/audio/garmin/` (see section 7):

| File | Content |
|------|---------|
| `GarminCompanionManager.kt` | **Built (Phase 2b).** Hilt `@Singleton`. Thin Connect IQ SDK shell (the only file importing `com.garmin.*`): initializes `ConnectIQ`, registers the IQ app by UUID, receives watch messages, decodes them via `GarminMessageCodec` and **delegates routing to `GarminCommandRouter`**, pushes acks + `state` messages (repository flows + ~1 Hz volume poll while connected), and exposes `connectionState: StateFlow<GarminConnectionState>`. |
| `GarminCompanionInitializer.kt` + `GarminCompanionModule.kt` | **Built (Phase 2b).** The garmin flavor's real initializer (wraps `GarminCompanionManager`); the `@Binds` module binds it as the `GarminCompanionStarter`, which the main source set's `CompanionModule` prefers over `NoopCompanionInitializer` (Hilt has no binding override, so the real initializer is bound under the marker key rather than re-binding `CompanionInitializer`). |
| `ExternalPlaybackMonitor.kt` (optional) | `NotificationListenerService` reading MediaStyle notifications to expose external apps' now-playing title/source. Requires user-granted notification access (see manifest note, §8). Keep optional; document in PRIVACY.md. **Not implemented (out of scope): `source` is "RunBeat" or "none".** |

Routing + mark orchestration in `GarminCompanionManager` (it needs
`PlayerRepository`, `TrackRepository` and `MediaKeyDispatcher`; putting the
orchestration in `PlayerRepository` would create a dependency on
`TrackRepository` that does not exist today):

```kotlin
fun onCommand(cmd: GarminCommand) {
    val runbeatActive = playerRepository.playerState.value.isPlaying ||
        audioServiceRunning()
    when (cmd) {
        is GarminCommand.Toggle ->
            if (runbeatActive) playerRepository.togglePlayPause()
            else mediaKeyDispatcher.toggle()
        is GarminCommand.Next ->
            if (runbeatActive) playerRepository.playNext()
            else mediaKeyDispatcher.next()
        is GarminCommand.Prev ->
            if (runbeatActive) playerRepository.playPrevious()
            else mediaKeyDispatcher.previous()
        is GarminCommand.VolumeDelta ->
            mediaKeyDispatcher.adjustStreamVolume(cmd.delta)   // both branches
        is GarminCommand.CadenceSet ->
            if (runbeatActive) playerRepository.setTargetCadence(cmd.cadence)
        is GarminCommand.CadenceDelta ->
            if (runbeatActive) { /* increment/decrement */ }
        is GarminCommand.SpeedCorrectionToggle ->
            if (runbeatActive) playerRepository.toggleSpeedCorrection()
        is GarminCommand.MarkNotForRunning ->
            if (runbeatActive) markCurrentTrackNotForRunning()
    }
    pushState()
}

private fun markCurrentTrackNotForRunning() {
    val trackId = playerRepository.currentTrack.value
        ?.mediaMetadata?.extras?.getString("trackId") ?: return
    scope.launch {
        trackRepository.markTrackNotForRunning(trackId)
        playerRepository.playNext()   // confirmed: skip immediately
        sendToWatch(ack("mark_not_for_running", ok = true))
    }
}
```

**Built (Phase 2b):** the routing above lives in `GarminCommandRouter` (main
source set — SDK-free, unit-tested in every flavor); `GarminCompanionManager`
(garmin source set) is the thin Connect IQ SDK shell that decodes watch
messages, delegates to the router, and pushes acks + state. RunBeat is treated
as active when `playerState.isPlaying || playbackState == STATE_BUFFERING`
(the simplest robust signal from the discussion below); paused RunBeat falls
through to media keys, as documented. Marking a track reads the current
`trackId` from `PlayerRepository.currentTrack` extras, flags it in Room and
skips immediately. `ExternalPlaybackMonitor` is not implemented, so `source`
is `"RunBeat"` or `"none"`.

`audioServiceRunning()`: check whether `AudioService` is foreground via
`ActivityManager.getRunningServices()` (deprecated but functional) or
`UsageStatsManager`, or simpler: track it from `PlayerRepository.playerState`
(`isPlaying || playbackState == STATE_BUFFERING`). Simplest robust signal:
treat RunBeat as active when `playerState.isPlaying` is true; document that
paused-RunBeat falls through to media keys (acceptable: a paused RunBeat
would forward keys to the next app with focus, which is standard remote
behavior).

Initialization (built, Phase 2b): the companion is started at process
scope — `BpmApp.onCreate` obtains the Hilt-bound `CompanionInitializer`
through the `CompanionInitializerEntryPoint` Hilt entry point (declared in
`CompanionModule.kt`; Hilt does not inject into the `@HiltAndroidApp`
Application itself) and calls `initialize`. `AudioService` also injects
`CompanionInitializer` and calls `initialize` in its own `onCreate`, so
service-started processes bring the companion up too (`initialize` is
idempotent — a no-op when the Application already initialized it).
`MainActivity` has no companion wiring. `shutdown()` is retained on the
interface as a no-op default but is deliberately never called: the
companion lives for the whole process and the OS reclaims the SDK
receivers when the process dies. The main source set's `CompanionModule`
binds `CompanionInitializer` to `NoopCompanionInitializer` unless the
garmin flavor binds a `GarminCompanionStarter` — Hilt has no binding
override, so the garmin flavor's `GarminCompanionModule` binds the real
initializer under that marker key via `@Binds` instead of re-binding
`CompanionInitializer`.

## 4. Queue building — filter flagged tracks (toggle, default ON)

`app/src/main/java/com/bpmapp/audio/viewmodel/LibraryViewModel.kt`

- Add a persisted setting `excludeNotForRunningTracks: StateFlow<Boolean>`
  (SharedPreferences, key in the existing `AppSettings` prefs or a new
  `RunBeatSettings` prefs file — follow the existing pattern used for
  `selected_rhythm_patterns` in `PlayerRepository`).
- Wherever the play queue / `MediaItem` list is built for playback
  (functions building on `trackRepository.allTracks` /
  `trackDao.getAllTracks()` and `getMediaItemForTrack()`), filter out
  `track.notForRunning` **when the setting is on** (default).
- Expose `toggleNotForRunning(trackId)` (mirroring the existing
  `toggleFavorite` at ~line 750) and surface the flag in
  `ui/LibraryScreen.kt` / `ui/components/TrackCard.kt` (badge
  "Not for running" + unmark action), so a track flagged from the watch can
  also be unmarked from the phone.

## 5. Settings UI

`app/src/main/java/com/bpmapp/audio/ui/screens/SettingsScreen.kt` +
`viewmodel/SettingsViewModel.kt`:

- New toggle: **"Exclude tracks marked 'not for running' from playback"**
  (default ON).
- Garmin section (flavor-gated): connection state
  ("Garmin watch: connected / not connected") and, if
  `ExternalPlaybackMonitor` is implemented, a shortcut to the system
  notification-access settings.

## 6. Strings

`app/src/main/res/values/strings.xml` (+ `values-fr`, `values-es`):

- Badge/action label: **"Not for running"** — with a clarifying description
  ("This track will be skipped during runs. It is not deleted and can be
  unmarked anytime.").
- Watch confirmation: "Marked as not for running".
- Settings toggle label + summary (filter, default on).
- Garmin connection status strings.

## 7. Build configuration

`app/build.gradle`:

```groovy
android {
    flavorDimensions += 'companion'
    productFlavors {
        fdroid { dimension 'companion' }   // default, no Garmin SDK
        garmin { dimension 'companion' }   // adds the Connect IQ Mobile SDK
    }
}

dependencies {
    garminImplementation 'com.garmin.connectiq:ciq-companion-app-sdk:<latest>'
}
```

Both flavors share the applicationId `com.RunBeat.run.audio` (no
`applicationIdSuffix`): the garmin flavor is the same app WITH the Connect
IQ integration under the main name, and the fdroid flavor is the same app
WITHOUT it (what F-Droid ships). The two flavors are therefore not
co-installable side by side — a garmin build replaces the fdroid build
(and if the signatures differ, Android requires uninstalling the other
build first).

The SDK (`com.garmin.connectiq:ciq-companion-app-sdk`) is published on Maven
Central, which the module already declares — no additional repository is
needed.

Source sets: the SDK-free classes (codec, averager, dispatcher,
`CompanionInitializer` + no-op) live in `app/src/main/java/...` and compile
in every flavor; only the Connect IQ wiring (`GarminCompanionManager`, the
garmin `CompanionInitializer` implementation, the optional
`ExternalPlaybackMonitor`) goes in `app/src/garmin/java/...`, so the
`fdroid` flavor compiles without the SDK.

F-Droid consequence: with product flavors there is no plain `assembleRelease`
task anymore, so `docs/fdroid-metadata.yml` builds the flavor-specific task
`assembleFdroidRelease` instead of `yes` (see section 9).

## 8. Manifest

`app/src/main/AndroidManifest.xml`:

- No new permissions for the core feature set. The Connect IQ Mobile SDK
  delegates communication to Garmin Connect Mobile; the app keeps its
  no-`INTERNET` stance. Watch transport/volume for external apps uses
  `AudioManager` media keys — no permission.
- If `ExternalPlaybackMonitor` is implemented: declare the service with
  `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` and instruct the
  user to grant notification access in system settings (runtime opt-in).

## 9. Docs to update

- `docs/PRIVACY.md`: add a paragraph about the optional Garmin Connect IQ
  integration (messages transit Garmin Connect Mobile; content limited to
  playback state and cadence; no new phone permissions) and, if implemented,
  the optional notification-access for external now-playing metadata.
- `CHANGELOG.md`: feature entries once implemented.
- `docs/fdroid-metadata.yml`: the gradle build command changes from `yes` to
  the flavor-specific task `assembleFdroidRelease` (with product flavors there
  is no plain `assembleRelease` task anymore). F-Droid keeps building the
  `fdroid` flavor with the current dependency set.

## 10. Tests

- Unit tests (existing JUnit4 + Mockito setup) for `GarminMessageCodec`,
  `CadenceAverager` and `MediaKeyDispatcher` (mock `AudioManager`) — all
  pure/injectable, no SDK needed.
- Unit test for the Room migration 6->7 (Robolectric or
  `MigrationTestHelper`).
- Unit test for the queue filtering honoring the settings toggle
  (LibraryViewModel, Robolectric pattern as in existing tests).
- Existing `CadenceMatcher` tests are unaffected (no change to the matcher).

## Summary of touched files

```
app/src/main/java/com/bpmapp/audio/data/Track.kt                 (add notForRunning)
app/src/main/java/com/bpmapp/audio/data/AppDatabase.kt           (version 7)
app/src/main/java/com/bpmapp/audio/data/TrackDao.kt              (3 new methods)
app/src/main/java/com/bpmapp/audio/di/AppModule.kt               (MIGRATION_6_7)
app/src/main/java/com/bpmapp/audio/audio/TrackRepository.kt      (2 wrappers)
app/src/main/java/com/bpmapp/audio/audio/PlayerRepository.kt     (optional duck fix only)
app/src/main/java/com/bpmapp/audio/viewmodel/LibraryViewModel.kt (filter + setting + toggleNotForRunning)
app/src/main/java/com/bpmapp/audio/viewmodel/SettingsViewModel.kt(filter setting, garmin connection state)
app/src/main/java/com/bpmapp/audio/ui/LibraryScreen.kt           (badge/unmark)
app/src/main/java/com/bpmapp/audio/ui/components/TrackCard.kt     (badge)
app/src/main/java/com/bpmapp/audio/ui/screens/SettingsScreen.kt   (filter toggle, garmin section)
app/src/main/java/com/bpmapp/audio/BpmApp.kt                      (companion init at process scope)
app/src/main/java/com/bpmapp/audio/audio/AudioService.kt          (companion init in onCreate)
app/src/main/res/values/strings.xml (+ fr, es)                   (new strings)
app/src/main/java/com/bpmapp/audio/audio/garmin/               (NEW: codec, averager, dispatcher, router, connection state, starter marker, CompanionInitializer + no-op, CompanionModule)
app/src/garmin/java/com/bpmapp/audio/audio/garmin/               (NEW: manager, garmin CompanionInitializer + @Binds module; optional monitor not implemented)
app/src/test/java/com/bpmapp/audio/audio/garmin/GarminCommandRouterTest.kt (NEW)
garmin-watch/manifest.xml                                        (IQ app UUID)
app/src/main/AndroidManifest.xml                                (only if ExternalPlaybackMonitor: listener service)
app/build.gradle                                                 (flavors + garmin dep)
docs/PRIVACY.md, CHANGELOG.md, docs/fdroid-metadata.yml          (doc updates)
```
