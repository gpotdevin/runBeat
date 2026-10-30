# Architecture — Garmin Companion for RunBeat

**Normative reference for the watch↔phone wire protocol** — referenced from code:
`GarminMessageCodec.kt` and `GarminCommandRouter.kt` must match the tables below
(pinned by unit tests such as `encodeCommandMatchesWatchWireFormat`).
Synced 2026-10-29. Feature rationale and status: [README.md](README.md).

## System overview
```
┌──────────────────┐  Connect IQ messages via Garmin Connect Mobile (BLE)  ┌───────────────────────┐
│  Garmin watch    │  ── commands (play, cadence, ...) ──────────────────▶  │  RunBeat (phone)      │
│  Connect IQ app  │  ◀── state / ack / wake ────────────────────────────  │  GarminCompanionManager│
│  (Monkey C)      │                                                      │         │             │
│  - cadence 1 Hz  │                                                      │  GarminCommandRouter ─┼─▶ PlayerRepository / TrackRepository (RunBeat active)
│  - 60 s ring buf │                                                      │  MediaKeyDispatcher ───┴─▶ media key events + STREAM_MUSIC (external app)
└──────────────────┘                                                      └───────────────────────┘
```
All RunBeat control flows through the existing `PlayerRepository` singleton; the Garmin layer is
a thin command adapter, never touches `ExoPlayer` directly. The exported `MediaSessionService`
stays as fallback transport for basic controls.

## Command routing (phone decides; the watch always sends the same commands)
- RunBeat active → transport/cadence/speed-correction/mark/playlists go to `PlayerRepository` +
  `TrackRepository`; volume → `AudioManager` STREAM_MUSIC.
- RunBeat not active → transport → external media session via `MediaSessionManager.getActiveSessions`
  (requires notification access: `GarminMediaNotificationListener`), driving the playing session's
  `MediaController.TransportControls`; fallback → `AudioManager.dispatchMediaKeyEvent(...)`.
  Volume → `adjustStreamVolume(STREAM_MUSIC)`. `exoPlayer.volume` stays an in-app multiplier, untouched.
- RunBeat-only controls are greyed out on the watch when pushed `source != "RunBeat"`.

## Components

Phone (`audio/garmin/`, SDK-free in `main`; SDK wiring in the `garmin` flavor source set):

| Component | Role |
|-----------|------|
| `GarminCompanionManager` (garmin flavor, Hilt @Singleton) | Connect IQ SDK shell: register IQ app (UUID `06c14376-0c60-4e4c-a977-e93364ace3eb`), decode via `GarminMessageCodec`, delegate to `GarminCommandRouter`, push `state`/`ack`/`wake`; registers a second listener under `IQApp("")` in simulator-ADB mode (SDK 2.4.0 re-broadcasts tethered packets with an empty UUID) |
| `GarminCommandRouter` | RunBeat-vs-external routing; builds `state`/`ack` payloads (`currentState()`) |
| `GarminMessageCodec` | (de)serializes the protocol below; pure functions, unit-tested |
| `MediaKeyDispatcher` | external-session `MediaController` transport (via `GarminMediaNotificationListener` notification access) + media-key fallback; STREAM_MUSIC volume |
| `GarminMediaNotificationListener` | `NotificationListenerService` (manifest `BIND_NOTIFICATION_LISTENER_SERVICE`); enables `getActiveSessions` for other apps. User grants access from Settings |
| `GarminWatchConnection` | process-wide `StateFlow` DISCONNECTED/CONNECTING/CONNECTED for the Settings screen |
| `CadenceAverager` | 60-sample ring buffer; fallback only (the watch computes the mean) |
| `ExternalPlaybackMonitor` | **optional, not shipped** — NotificationListenerService for external now-playing metadata (reserved protocol value `source:"external"`) |

Watch (`garmin-watch/`, Monkey C, SDK 9.3.0, manifest type `audio-content-provider-app`,
permissions `Communications`+`Sensor`+`Background`, 16 product ids):

| Component | Role |
|-----------|------|
| `CadenceSampler` + `CadenceRingBuffer` | 1 Hz `Sensor.getInfo().cadence` into a 60-slot buffer (step-delta fallback); `mean()` ignores samples older than 60 s and zeros |
| `MessageHandler` | parse/build the JSON protocol; **all string comparisons use `.equals()`** (Monkey C `==` compares references — see simulator notes) |
| `RunBeatCompanionApp` | lifecycle; `(:background)` class with lazy foreground init; `getInitialView()` and `getPlaybackConfigurationView()` both return the same 3-page ViewLoop remote |
| Views | Main (artist/album/title + play/pause, next, prev, volume), Controls, Cadence; fr245 (CIQ 3.3.1) Main-only fallback |
| `RunBeatBackgroundService` | turns the phone's `wake` message into a launch-confirmation dialog while the app is closed |

Data layer: `Track.notForRunning` (Room migration 6→7, keyed by `trackId` = file path /
content URI), DAO/repository mark+unmark, queue filtering behind a settings toggle (default ON),
badge + unmark in the library UI.

## Message protocol (NORMATIVE)

Transport: Connect IQ phone messages, JSON UTF-8 strings. Decoding is tolerant: unknown fields
skipped, unknown commands decode to `null` and are logged + ignored.

### Watch → phone (commands)
| Message | Payload | Phone action |
|---------|---------|--------------|
| `play` / `pause` / `toggle` / `next` / `prev` | — | RunBeat active: `PlayerRepository`; else media key event |
| `cadence_set` | `{"cadence": 172}` | `setTargetCadence` (clamped 150–195). RunBeat only. Acked |
| `cadence_delta` | `{"delta": ±1}` | increment/decrement. RunBeat only. Acked |
| `cadence_use_current` | `{"cadence": <60 s mean computed on watch>}` | same as `cadence_set`. RunBeat only. Acked |
| `speed_correction_toggle` | — | `toggleSpeedCorrection()`. RunBeat only. Acked |
| `volume_delta` | `{"delta": ±1}` | `adjustStreamVolume(STREAM_MUSIC)`. Works for all apps. Not acked |
| `seek` | `{"positionMs": N}` | RunBeat active: `seekTo(N)` (0 ≤ N ≤ 86 400 000); else ignored. Not acked — next state push confirms |
| `request_state` | — | phone answers with one `state` push. Sent on app start, every 5 s while playing (keepalive), exponential backoff (5→60 s cap) when silent. Not acked |
| `bye` | — | sent in `onStop`; phone drops the active window immediately. Never routed, no answer |
| `mark_not_for_running` | — | flag current `trackId` in Room **and** `playNext()` (immediate skip). RunBeat only. Acked |
| `playlist_list` | — | link-layer: phone answers with one `playlists` push. Not acked |
| `playlist_play` | `{"playlistId": "..."}` | load playlist (not-for-running filter applied) and play. RunBeat side. Acked |

### Phone → watch
`state` (pushed on change, on connect, and in answer to `request_state`):
```json
{
  "type": "state", "source": "RunBeat", "isPlaying": true,
  "trackId": "content://media/...", "title": "Song name", "artist": "Artist name",
  "album": "Album name", "durationMs": 213000, "positionMs": 42000,
  "bpm": 140.0, "targetCadence": 172, "speedCorrection": true,
  "playbackSpeed": 1.03, "volume": 12
}
```
- `source`: `"RunBeat"` when RunBeat is the active player, else `"none"` (`"external"` reserved
  for the optional monitor, never emitted by the shipped build). RunBeat-only fields are
  null/omitted when `source != "RunBeat"`; `artist`/`album` required when `source == "RunBeat"`.
- `positionMs` rounded down to whole seconds (≈1 push/s while playing); `volume` = STREAM_MUSIC index.
- `{"type":"playlists","count":N,"id0":"...","name0":"...",...}` answers `playlist_list` (flat
  indexed fields — the watch parser skips nested arrays; cap 50).
- `{"type":"ack","command":"...","ok":true}` confirms the acked commands.
- `{"type":"wake"}` sent once when playback starts with no watch app active.

Cadence samples are never streamed; only the 60 s mean, on button press.

## Battery & lifecycle
- **Push gating:** `IQDevice` CONNECTED ≠ app open on the watch. Phone pushes only within a 30 s
  window of the last incoming watch message (armed by `request_state`, cleared by `bye`; abnormal
  close expires it in ≤30 s). A closed app costs the watch nothing.
- **Watch repaint policy:** the 1 Hz tick repaints only when visible content changed (position,
  header freshness, ack line, cadence number). Paused and still = no repaint.
- **Idle exit:** `System.exit()` after 3 min without interaction once music stopped or link stale;
  `onBack` attempts it but always returns false (in the provider-flow context `System.exit` is a
  no-op and returning true trapped the user) — back falls through to the system pop.

## Launch paths (decided 2026-10-29, Venu 2 firmware)
An `audio-content-provider-app` appears BOTH in Music → Music Providers AND in the app menu
(same as Spotify). Both entry points return the same remote UI; only the host context differs:

| | Provider-flow launch | Direct (app-menu) launch |
|---|---|---|
| Entry | Music → Music Providers → RunBeat (`getPlaybackConfigurationView`) | App menu (`getInitialView`) |
| Back from remote | lands on the stock Music app (one extra press) | watch face, no Music app (verify) |
| `System.exit` | no-op on device (guarded) | expected to work (verify) |

## Feature flows
1. **Transport:** command → router → `PlayerRepository` or media key → state push back.
2. **Cadence:** watch ± → `cadence_delta`; "use current" → watch computes 60 s mean →
   `cadence_use_current` → `setTargetCadence(mean)`; speed correction re-applies in the repository.
3. **Speed correction:** toggle → `toggleSpeedCorrection()` → state push.
4. **Volume:** `volume_delta` → STREAM_MUSIC ±1; current volume pushed in `state`.
5. **Mark not-for-running:** `mark_not_for_running` → Room flag + immediate `playNext()` + ack;
   future queues exclude flagged tracks behind the settings toggle (default ON).
6. **External apps:** same watch commands; phone routes to media keys / STREAM_MUSIC;
   RunBeat-only controls disabled; watch shows "Nothing playing" (no monitor shipped).

## Connect IQ simulator (ADB tether) — gotchas (verified 2026-10-25/26/29)
Staging procedure and division of labor: [simulator_runbook_2026-10-29.md](simulator_runbook_2026-10-29.md).
Enable Settings > GARMIN > "Use Connect IQ simulator (ADB)" (debug builds only); tether is
`adb forward tcp:7381 tcp:7381` + simulator Connection > Start; no Garmin Connect Mobile needed.

1. **Watch → phone in tethered mode** needs the phone-side `IQApp("")` listener (SDK 2.4.0
   re-broadcasts tethered packets with an empty app UUID). WIRELESS (real watch) unaffected.
2. **Phone → watch** works in the simulator and on real BLE alike. It was silently broken until
   2026-10-26 because the watch parser compared strings with `==`/`!=` (reference comparison in
   Monkey C) — every message failed at the first character check, looking identical to "not
   received" (the `request_state` re-ask stream never pauses). **Use `.equals()` for all string
   comparisons.**
3. **Input model:** `M` opens the per-screen menu (all commands reachable), vertical swipe cycles
   ViewLoop pages, horizontal swipe on page 1 seeks ±15 s, click/Enter = SELECT (the page's
   primary action). Open bug: positional taps on drawn buttons fire `onSelect` instead of the
   buttons' `onTap` zones, on simulator and device.
4. Environment quirks (adb not on PATH → modal "no data connection" dialog, window drift,
   `ss` segfaults → use `/proc/net/tcp(6)` port `1CD5`, Git Bash path mangling): runbook §4.
