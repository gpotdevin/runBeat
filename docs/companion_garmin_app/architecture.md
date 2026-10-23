# Architecture — Garmin Companion for RunBeat

Date: 2026-10-22

## System overview

```
┌────────────────────────┐         Connect IQ messages          ┌──────────────────────────┐
│      Garmin watch      │         (via Garmin Connect          │   RunBeat (phone app)    │
│  ┌──────────────────┐  │          Mobile over BLE)            │                          │
│  │ Connect IQ app   │  │  ─────────────────────────────────▶  │  GarminCompanionManager  │
│  │  (Monkey C)      │  │   commands (play, cadence, ...)      │  (new, Hilt @Singleton)  │
│  │                  │  │                                      │           │              │
│  │ - cadence sampler│  │  ◀─────────────────────────────────  │  PlayerRepository        │
│  │ - 60s ring buffer│  │   state (track, cadence, volume...)  │  (existing, extended)    │
│  │ - control pages  │  │                                      │           │              │
│  └──────────────────┘  │                                      │  TrackRepository / Room  │
│                        │                                      │  (existing, + notForRunning)│
└────────────────────────┘                                      └──────────────────────────┘
                                                                        │
                                              standard media session   │ (unchanged, fallback)
                                                                        ▼
                                                              Garmin "Music" app / BT
                                                              (play/pause/next/prev)
```

Key principle: **all RunBeat control flows through the existing
`PlayerRepository` singleton**
(`app/src/main/java/com/bpmapp/audio/audio/PlayerRepository.kt`). The Garmin
layer is a thin command adapter; it never touches `ExoPlayer` directly. This
keeps one source of truth and means the phone UI, the media notification,
and the watch always agree.

## Command routing (default music app behavior)

The watch sends the same commands regardless of which app is playing. The
**phone** decides where they go:

```
watch command
     │
     ▼
GarminCompanionManager
     │
     ├─ RunBeat active (AudioService foreground / player playing)?
     │     ├─ transport ─▶ PlayerRepository (togglePlayPause/playNext/...)
     │     ├─ cadence / speed-correction / mark-not-for-running ─▶ PlayerRepository + TrackRepository
     │     └─ volume ─▶ AudioManager STREAM_MUSIC (see note below)
     │
     └─ RunBeat not active
           ├─ transport ─▶ AudioManager.dispatchMediaKeyEvent(...)  (routes to the app holding media focus)
           └─ volume ─▶ AudioManager.adjustStreamVolume(STREAM_MUSIC, ...)
```

Notes:

- **Volume uses `AudioManager` STREAM_MUSIC in both branches.** It is the
  global media volume and works for every app. RunBeat's `exoPlayer.volume`
  remains an in-app multiplier (default 1.0) used by the mode-beep ducking;
  the two compose without conflict.
- **Now-playing metadata from external apps** is optional: a
  `NotificationListenerService` reading MediaStyle notifications (requires
  the user to grant notification access). Without it the phone pushes
  `"source": "external"` and the watch shows a generic label. See
  [feasibility.md](feasibility.md).
- The watch disables RunBeat-only commands (cadence, speed correction,
  mark not-for-running) when the pushed state says `source != "RunBeat"`.

## Phone-side components

| Component | Type | Responsibility |
|-----------|------|----------------|
| `GarminCompanionManager` (new, `audio/garmin/`) | Hilt `@Singleton` | Wraps the Connect IQ Mobile SDK: initialize `ConnectIQ`, register the IQ app, receive watch messages, **route** them (RunBeat vs external app, see above), and push state updates to the watch. Exposes a `StateFlow<GarminConnectionState>` (DISCONNECTED / CONNECTING / CONNECTED). |
| `MediaKeyDispatcher` (new, `audio/garmin/`) | plain class | Forwards transport commands to external apps via `AudioManager.dispatchMediaKeyEvent()` and adjusts STREAM_MUSIC volume. No permissions; unit-testable with a mocked `AudioManager`. |
| `ExternalPlaybackMonitor` (new, optional, `audio/garmin/`) | `NotificationListenerService` | Reads MediaStyle notifications to expose external apps' now-playing metadata. Requires user-granted notification access; keep optional and document in PRIVACY.md. |
| `GarminMessageCodec` (new, `audio/garmin/`) | plain object | (De)serializes the JSON message protocol below. Pure functions — unit-testable without the Garmin SDK. |
| `CadenceAverager` (new, `audio/garmin/`) | plain class | 60-sample ring buffer of cadence values with timestamps; `add(sample)`, `meanLast60s(): Float?`. Fallback path if cadence samples are ever streamed to the phone; the watch-side average is the primary path. |
| `PlayerRepository` (extend) | existing `@Singleton` | Expose `speedCorrectionEnabled` (already a flow), `targetCadence`, `playbackSpeed`, `currentTrack` (all exist). Mark-not-for-running orchestration lives in the manager to avoid a dependency cycle. |
| `TrackRepository` / `TrackDao` (extend) | existing | Add `notForRunning` flag: DAO `updateNotForRunning(trackId, value)`, query excluding flagged tracks; repository `markTrackNotForRunning(trackId)` / `unmarkTrackNotForRunning(trackId)`. |
| `LibraryViewModel` (extend) | existing | Filter `notForRunning` tracks when building the play queue **behind a settings toggle (default ON)**; expose toggle to mark/unmark from the phone UI. |
| `SettingsViewModel` / `SettingsScreen` (extend) | existing | New setting: "Exclude tracks marked 'not for running' from playback" (default ON); Garmin connection status section (flavor-gated). |
| `AudioService` (unchanged) | existing | Already an exported `MediaSessionService`; remains the fallback transport for basic controls. No change needed. |

Dependency direction: `GarminCompanionManager` depends on
`PlayerRepository` + `TrackRepository` + `MediaKeyDispatcher`. Neither
existing class depends on the Garmin layer (the Garmin code lives behind
the `garmin` product flavor — see
[phone_app_changes.md](phone_app_changes.md)), so the core app stays
SDK-free.

## Watch-side components (Monkey C, separate project)

| Component | Responsibility |
|-----------|----------------|
| `CadenceSampler` | Once per second, read cadence (primary source: `Toybox.Sensor` `Sensor.getInfo().cadence`, accelerometer-based, `Sensor` permission; last-resort fallback: step-delta from `ActivityMonitor.getInfo().steps`), push into ring buffer. |
| `CadenceRingBuffer` | Fixed-size (60) circular buffer; `mean()` over samples not older than 60 s. |
| `MessageHandler` | Parse phone->watch state messages; send watch->phone commands. |
| Views (pages) | Main control page, cadence page, settings/toggles page — see [watch_app_plan.md](watch_app_plan.md). |

## Message protocol

Transport: Connect IQ phone messages (small payloads, JSON-encoded UTF-8
strings via `org.json`, already on Android — no new dependency).

### Watch -> phone (commands)

| Message | Payload | Phone action |
|---------|---------|--------------|
| `play` / `pause` / `toggle` | — | RunBeat active: `PlayerRepository.play()/pause()/togglePlayPause()`; otherwise `MediaKeyDispatcher` (media key event) |
| `next` / `prev` | — | RunBeat active: `PlayerRepository.playNext()/playPrevious()`; otherwise media key event |
| `cadence_set` | `{"cadence": 172}` | `PlayerRepository.setTargetCadence(value)` (clamped 150-195). RunBeat only. |
| `cadence_delta` | `{"delta": 1}` or `{"delta": -1}` | `incrementTargetCadence()` / `decrementTargetCadence()`. RunBeat only. |
| `cadence_use_current` | `{"cadence": <60s mean computed on watch>}` | same as `cadence_set` — the watch computes the average and sends the result; the phone just applies it. RunBeat only. |
| `speed_correction_toggle` | — | `PlayerRepository.toggleSpeedCorrection()`. RunBeat only. |
| `volume_delta` | `{"delta": 1}` or `{"delta": -1}` | `AudioManager.adjustStreamVolume(STREAM_MUSIC, ...)` — works for RunBeat and external apps alike |
| `mark_not_for_running` | — | flag current `trackId` + skip immediately (see below). RunBeat only. |

### Phone -> watch (state, pushed on change)

```json
{
  "type": "state",
  "source": "RunBeat",
  "isPlaying": true,
  "trackId": "content://media/...",
  "title": "Song name",
  "bpm": 140.0,
  "targetCadence": 172,
  "speedCorrection": true,
  "playbackSpeed": 1.03,
  "volume": 12
}
```

- `source`: `"RunBeat"` when RunBeat is the active playback app,
  `"external"` (or the external app's name when the optional notification
  listener is granted), or `"none"`.
- RunBeat-only fields (`trackId`, `title`, `bpm`, `targetCadence`,
  `speedCorrection`, `playbackSpeed`) are omitted or null when
  `source != "RunBeat"`; the watch greys out the corresponding controls.
- `volume`: current STREAM_MUSIC media volume (AudioManager index, not 0..1).

Plus `{"type":"ack","command":"mark_not_for_running","ok":true}` style
confirmations for commands that need feedback (e.g. mark, cadence applied).

Cadence samples are **not** streamed continuously; only the 60-second mean
is sent, on button press. This keeps BLE traffic near zero and battery
impact minimal.

## Feature flows

### 1. Play control
Watch button -> `toggle`/`next`/`prev` message -> `GarminCompanionManager`
-> routed per the routing table: `PlayerRepository` when RunBeat is active,
media key event otherwise. Phone pushes updated `state` back; watch icon
and source label refresh.

### 2a. Manual target cadence (watch UI)
Watch +/- -> `cadence_delta` -> `PlayerRepository.increment/decrementTargetCadence()`
-> `reapplySpeedCorrection()` runs inside the repository (existing
behavior) -> new `targetCadence` pushed to watch.

### 2b. Target cadence = average of last minute
1. Watch samples cadence every second into the 60-sample ring buffer.
2. User presses the dedicated "use current cadence" button.
3. Watch computes `mean()` of the last 60 s (ignoring stale/zero samples,
   e.g. when the arm was still) and sends `cadence_use_current` with the
   value.
4. Phone applies `setTargetCadence(mean)`; speed correction re-applies
   automatically for the current track.

Rationale for computing the average on the watch: the sensor is there, it
avoids 60x message traffic, and the phone app needs no new permissions.
A phone-side `CadenceAverager` is included only as a fallback if a future
variant streams raw samples.

### 3. Toggle speed correction
Watch toggle -> `speed_correction_toggle` ->
`PlayerRepository.toggleSpeedCorrection()` (existing: enable re-applies the
cadence-matched speed factor, disable restores 1.0x) -> state push.

### 4. Volume up/down
Watch +/- -> `volume_delta` -> `AudioManager.adjustStreamVolume(
STREAM_MUSIC, +/-1, ...)` via `MediaKeyDispatcher`. This is the global media
volume: it works identically for RunBeat and for external apps. The phone
reads back the current volume (`AudioManager.getStreamVolume`) and pushes it
in the `state` message.

This deliberately does **not** touch `exoPlayer.volume`: RunBeat's player
volume stays an in-app multiplier (default 1.0) used by the mode-beep
ducking, and composes with the stream volume without conflict. (The
`PlayerRepository` volume API from the earlier draft is therefore dropped.)

### 5. Mark current track "not for running"

User-facing wording: **"Not for running"** — the track may sound perfectly
fine, it just does not feel right to run on. The flag is stored in the
library and filtered from future sessions; it is not a quality rating.

1. Watch button -> `mark_not_for_running` message (enabled only when
   `source == "RunBeat"`).
2. `GarminCompanionManager` reads the current `trackId` from
   `PlayerRepository.currentTrack` (extras key `"trackId"`, already set by
   `LibraryViewModel.getMediaItemForTrack()`).
3. `TrackRepository.markTrackNotForRunning(trackId)` — new
   `notForRunning = true` in Room (migration 6->7).
4. **Skip immediately (confirmed decision):** `PlayerRepository.playNext()`.
   (`removeTrackFromUpcoming()` becomes unnecessary for the current track
   since we skip; it is still useful for symmetry but not required here.)
5. Phone pushes state + ack; watch shows a brief "Marked: not for running"
   confirmation.
6. **Future sessions:** when the play queue is built in `LibraryViewModel`,
   tracks with `notForRunning = true` are excluded **behind a settings
   toggle, on by default** ("Exclude tracks marked 'not for running'").
   Flagged tracks stay visible in the library with a badge and can be
   unmarked from the phone UI.

Note: marking is keyed by `trackId` (the Room primary key = file path or
content URI string), so it survives across sessions and playlists.

### 6. Control external apps (default music app)
1. Phone pushes `source` in every `state` message: `"RunBeat"` while
   RunBeat's playback is active, otherwise `"external"` (or the external
   app's name if the optional `ExternalPlaybackMonitor` notification
   listener is granted), or `"none"`.
2. Watch transport buttons always send the same commands; the phone routes
   them per the routing table above (media key events when RunBeat is not
   active).
3. Watch volume buttons always adjust STREAM_MUSIC.
4. RunBeat-only controls (cadence page actions, speed-correction toggle,
   mark not-for-running) are shown disabled when `source != "RunBeat"`.
5. Main watch screen shows the current source label and, when known, the
   external track title (via `ExternalPlaybackMonitor`).
