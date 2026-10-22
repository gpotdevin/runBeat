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
│                        │                                      │  (existing, + isBad)     │
└────────────────────────┘                                      └──────────────────────────┘
                                                                        │
                                              standard media session   │ (unchanged, fallback)
                                                                        ▼
                                                              Garmin "Music" app / BT
                                                              (play/pause/next/prev)
```

Key principle: **all control flows through the existing `PlayerRepository`
singleton** (`app/src/main/java/com/bpmapp/audio/audio/PlayerRepository.kt`).
The Garmin layer is a thin command adapter; it never touches `ExoPlayer`
directly. This keeps one source of truth and means the phone UI, the media
notification, and the watch always agree.

## Phone-side components

| Component | Type | Responsibility |
|-----------|------|----------------|
| `GarminCompanionManager` (new, `audio/garmin/`) | Hilt `@Singleton` | Wraps the Connect IQ Mobile SDK: initialize `ConnectIQ`, register the IQ app, receive watch messages, dispatch them to `PlayerRepository` / `TrackRepository`, and push state updates to the watch. Exposes a `StateFlow<GarminConnectionState>` (DISCONNECTED / CONNECTING / CONNECTED). |
| `GarminMessageCodec` (new, `audio/garmin/`) | plain object | (De)serializes the JSON message protocol below. Pure functions — unit-testable without the Garmin SDK. |
| `CadenceAverager` (new, `audio/garmin/`) | plain class | 60-sample ring buffer of cadence values with timestamps; `add(sample)`, `meanLast60s(): Float?`. Used if cadence samples are streamed to the phone; the watch-side average is the primary path (see below). |
| `PlayerRepository` (extend) | existing `@Singleton` | Add: `volume: StateFlow<Float>`, `setVolume(v)`, `volumeUp()/volumeDown()` (step 0.1, clamp [0,1]); expose `speedCorrectionEnabled` (already a flow); mark-bad orchestration helper `markCurrentTrackBad()` may live in the manager instead to avoid a dependency cycle. |
| `TrackRepository` / `TrackDao` (extend) | existing | Add `isBad` flag: DAO `updateBad(trackId, isBad)`, query `getTracksExcludingBad()`; repository `markTrackBad(trackId)` / `unmarkTrackBad(trackId)`. |
| `LibraryViewModel` (extend) | existing | Filter `isBad` tracks when building the play queue; expose toggle to mark/unmark from the phone UI. |
| `AudioService` (unchanged) | existing | Already an exported `MediaSessionService`; remains the fallback transport for basic controls. No change needed. |

Dependency direction: `GarminCompanionManager` depends on
`PlayerRepository` + `TrackRepository`. Neither existing class depends on
the Garmin layer (the Garmin code lives behind the `garmin` product flavor
— see [phone_app_changes.md](phone_app_changes.md)), so the core app stays
SDK-free.

## Watch-side components (Monkey C, separate project)

| Component | Responsibility |
|-----------|----------------|
| `CadenceSampler` | Once per second, read cadence (`Toybox.ActivityMonitor.getLiveCadence()`, fallback: step-delta from `ActivityMonitor.getInfo()`), push into ring buffer. |
| `CadenceRingBuffer` | Fixed-size (60) circular buffer; `mean()` over samples not older than 60 s. |
| `MessageHandler` | Parse phone->watch state messages; send watch->phone commands. |
| Views (pages) | Main control page, cadence page, settings/toggles page — see [watch_app_plan.md](watch_app_plan.md). |

## Message protocol

Transport: Connect IQ phone messages (small payloads, JSON-encoded UTF-8
strings via `org.json`, already on Android — no new dependency).

### Watch -> phone (commands)

| Message | Payload | Phone action |
|---------|---------|--------------|
| `play` / `pause` / `toggle` | — | `PlayerRepository.play()/pause()/togglePlayPause()` |
| `next` / `prev` | — | `PlayerRepository.playNext()/playPrevious()` |
| `cadence_set` | `{"cadence": 172}` | `PlayerRepository.setTargetCadence(value)` (clamped 150-195) |
| `cadence_delta` | `{"delta": 1}` or `{"delta": -1}` | `incrementTargetCadence()` / `decrementTargetCadence()` |
| `cadence_use_current` | `{"cadence": <60s mean computed on watch>}` | same as `cadence_set` — the watch computes the average and sends the result; the phone just applies it |
| `speed_correction_toggle` | — | `PlayerRepository.toggleSpeedCorrection()` |
| `volume_delta` | `{"delta": 1}` or `{"delta": -1}` | `PlayerRepository.volumeUp()/volumeDown()` |
| `mark_bad` | — | mark `currentTrack` trackId as bad (see below) |

### Phone -> watch (state, pushed on change)

```json
{
  "type": "state",
  "isPlaying": true,
  "trackId": "content://media/...",
  "title": "Song name",
  "bpm": 140.0,
  "targetCadence": 172,
  "speedCorrection": true,
  "playbackSpeed": 1.03,
  "volume": 0.8
}
```

Plus `{"type":"ack","command":"mark_bad","ok":true}` style confirmations
for commands that need feedback (e.g. mark-bad, cadence applied).

Cadence samples are **not** streamed continuously; only the 60-second mean
is sent, on button press. This keeps BLE traffic near zero and battery
impact minimal.

## Feature flows

### 1. Play control
Watch button -> `toggle`/`next`/`prev` message -> `GarminCompanionManager`
-> `PlayerRepository` -> ExoPlayer. Phone pushes updated `state` back;
watch icon refreshes.

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
Watch +/- -> `volume_delta` -> `PlayerRepository.volumeUp()/volumeDown()`
-> `exoPlayer.volume = (current +/- 0.1).coerceIn(0f, 1f)` -> state push.

Edge case: the mode-beep feature ducks `exoPlayer.volume` to 30% and later
restores `volumeBeforeDuck` (`PlayerRepository.maybePlayModeBeep()`). If the
user changes volume during the ~1 s beep window, the restore would override
it. Mitigation (small fix in `PlayerRepository`): store the duck as a
*multiplier* of the current volume instead of an absolute value, or update
`volumeBeforeDuck` when the user volume changes. See
[phone_app_changes.md](phone_app_changes.md).

### 5. Mark current track as bad
1. Watch button -> `mark_bad` message.
2. `GarminCompanionManager` reads the current `trackId` from
   `PlayerRepository.currentTrack` (extras key `"trackId"`, already set by
   `LibraryViewModel.getMediaItemForTrack()`).
3. `TrackRepository.markTrackBad(trackId)` — new `isBad = true` in Room.
4. `PlayerRepository.removeTrackFromUpcoming(trackId)` (existing) removes it
   from the current session's queue; the user can optionally also trigger
   `playNext()` to skip it immediately — **open question**, see
   [implementation_plan.md](implementation_plan.md).
5. Phone pushes state; watch shows a brief confirmation.
6. **Next session filtering:** when the play queue is built in
   `LibraryViewModel`, tracks with `isBad = true` are excluded (and badged
   in the library list so the user can unmark them).

Note: marking is keyed by `trackId` (the Room primary key = file path or
content URI string), so it survives across sessions and playlists.
