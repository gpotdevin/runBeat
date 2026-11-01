# Architecture — Garmin Companion for RunBeat

**Normative reference for the watch↔phone wire protocol** — referenced from code:
`GarminMessageCodec.kt` and `GarminCommandRouter.kt` must match the tables below
(pinned by unit tests such as `encodeCommandMatchesWatchWireFormat`).
Synced 2026-10-31 (battery: sensor read only while playing, 2-min idle exit). Feature rationale and status: [README.md](README.md).

## System overview
```
┌──────────────────┐  Connect IQ messages via Garmin Connect Mobile (BLE)  ┌───────────────────────┐
│  Garmin watch    │  ── commands (play, cadence, ...) ──────────────────▶  │  RunBeat (phone)      │
│  Connect IQ app  │  ◀── state / ack (only while app open) ────────────  │  GarminCompanionManager│
│  (Monkey C)      │                                                      │         │             │
│  - cadence 1 Hz  │                                                      │  GarminCommandRouter ─┼─▶ PlayerRepository / TrackRepository (RunBeat active)
│  - 60 s ring buf │                                                      │                       │
└──────────────────┘                                                      └───────────────────────┘
```
All RunBeat control flows through the existing `PlayerRepository` singleton; the Garmin layer is
a thin command adapter, never touches `ExoPlayer` directly. The exported `MediaSessionService`
stays as fallback transport for basic controls. The watch controls RunBeat only: the phone never
reads other apps' notifications or media sessions for the companion.

## Command routing (phone decides; the watch always sends the same commands)
- RunBeat active → transport/cadence/speed-correction/mark/playlists go to `PlayerRepository` +
  `TrackRepository`; volume → `AudioManager` STREAM_MUSIC.
- Pause resume grace: "active" includes a REAL pause — player `STATE_READY` with a track loaded,
  for 60 s after the last active poll (`GarminCommandRouter.PAUSE_RESUME_GRACE_MS`). Within the
  grace the pushed `source` stays `"RunBeat"` with `isPlaying:false` and full track metadata, so
  the watch shows the paused track and `toggle`/`play` resume it. After the grace the phone pushes
  `source:"none"` once (grace-expiry push, below) and watch commands are no-ops again. Stopped
  (`STATE_IDLE`) and ended (`STATE_ENDED`) playback gets no grace.
- RunBeat not active → transport and volume commands from the watch are ignored, and `source` is
  pushed as `"none"` (no track metadata is sent). `exoPlayer.volume` stays an in-app multiplier,
  untouched. Exception: `playlist_play` is never gated — picking a playlist explicitly starts
  RunBeat playback.
- RunBeat-only controls are greyed out on the watch when pushed `source != "RunBeat"`.

## Components

Phone (`audio/garmin/`, SDK-free in `main`; SDK wiring in the `garmin` flavor source set):

| Component | Role |
|-----------|------|
| `GarminCompanionManager` (garmin flavor, Hilt @Singleton) | Connect IQ SDK shell: register IQ app (UUID `06c14376-0c60-4e4c-a977-e93364ace3eb`), decode via `GarminMessageCodec`, delegate to `GarminCommandRouter`, push `state`/`ack` only while the watch app is active; registers a second listener under `IQApp("")` in simulator-ADB mode (SDK 2.4.0 re-broadcasts tethered packets with an empty UUID) |
| `GarminCommandRouter` | routes commands to RunBeat only (ignored when RunBeat is not the active player); builds `state`/`ack` payloads (`currentState()`) |
| `GarminMessageCodec` | (de)serializes the protocol below; pure functions, unit-tested |
| `GarminWatchConnection` | process-wide `StateFlow` DISCONNECTED/CONNECTING/CONNECTED for the Settings screen |
| `CadenceAverager` | 60-sample ring buffer; fallback only (the watch computes the mean) |

Watch (`garmin-watch/`, Monkey C, SDK 9.3.0, manifest type `audio-content-provider-app`
(hybrid: also serves a direct app-menu launch via `getInitialView`; see "Launch paths"),
permissions `Communications`+`Sensor` (NO `Background` — the app runs only when started
manually, 2026-10-31), 16 product ids):

| Component | Role |
|-----------|------|
| `CadenceSampler` + `CadenceRingBuffer` | 1 Hz `Sensor.getInfo().cadence` into a 60-slot buffer (step-delta fallback); `mean()` ignores samples older than 60 s and zeros. Battery: the 1 Hz sensor read runs **only while music is playing** (`shouldSampleCadence`); when paused the tick fires but reads no sensor |
| `MessageHandler` | parse/build the JSON protocol; **all string comparisons use `.equals()`** (Monkey C `==` compares references — see simulator notes) |
| `RunBeatCompanionApp` | `AudioContentProviderApp` with lazy foreground init; idle exit after 2 min without interaction when music stopped (goes inert first: stops timers + unregisters phone-message listener + sends `bye`, then `System.exit`); both `getInitialView()` and `getPlaybackConfigurationView()` return the same 4-page ViewLoop remote |
| Views | Main (artist/album/title + play/pause, next, prev, volume + "No good run"), Controls, Cadence, Playlists; fr245 (CIQ 3.3.1) Main-only fallback |

Data layer: `Track.notForRunning` (Room migration 6→7, keyed by `trackId` = file path /
content URI), DAO/repository mark+unmark, queue filtering behind a settings toggle (default ON),
badge + unmark in the library UI.

## Message protocol (NORMATIVE)

Transport: Connect IQ phone messages, JSON UTF-8 strings. Decoding is tolerant: unknown fields
skipped, unknown commands decode to `null` and are logged + ignored.

### Watch → phone (commands)
| Message | Payload | Phone action |
|---------|---------|--------------|
| `play` / `pause` / `toggle` / `next` / `prev` | — | RunBeat active: `PlayerRepository`; ignored otherwise |
| `cadence_set` | `{"cadence": 172}` | `setTargetCadence` (clamped 150–195). RunBeat only. Acked |
| `cadence_delta` | `{"delta": ±1}` | increment/decrement. RunBeat only. Acked |
| `cadence_use_current` | `{"cadence": <60 s mean computed on watch>}` | same as `cadence_set`. RunBeat only. Acked |
| `speed_correction_toggle` | — | `toggleSpeedCorrection()`. RunBeat only. Acked |
| `volume_delta` | `{"delta": ±1}` | `adjustStreamVolume(STREAM_MUSIC)`. RunBeat active only; ignored otherwise. Not acked |
| `seek` | `{"positionMs": N}` | RunBeat active: `seekTo(N)` (0 ≤ N ≤ 86 400 000); else ignored. Not acked — next state push confirms |
| `request_state` | — | phone answers with one `state` push (3 s time-dedup on the phone). Sent on app start, every 20 s while playing (keepalive), and when the link looks stale — staleness threshold is 10 s while playing, 30 s while paused (see below) — with exponential backoff (5→60 s cap). Not acked |
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
- `source`: `"RunBeat"` when RunBeat is the active player (playing, buffering, or paused within
  the 60 s resume grace — with `isPlaying:false` and the paused track's metadata so the watch can
  resume it), else `"none"` — never `"external"`;
  the phone never reads another app's media session for the companion. `title`/`artist`/`album`
  and the RunBeat-only fields (`trackId`, `durationMs`, `positionMs`, `bpm`, `targetCadence`,
  `speedCorrection`, `playbackSpeed`) are sent for `"RunBeat"` only, omitted for `"none"`
  (so no stale track name lingers on the watch).
- `positionMs` rounded down to 5 s buckets (≈1 push/5 s while playing); `volume` = STREAM_MUSIC index.
- `{"type":"playlists","count":N,"id0":"...","name0":"...",...}` answers `playlist_list` (flat
  indexed fields — the watch parser skips nested arrays; cap 50).
- `{"type":"ack","command":"...","ok":true}` confirms the acked commands.
- The phone never sends `wake` anymore (removed 2026-10-31): the watch app is started manually
  only, and its `request_state` handshake is what arms the push window.

Cadence samples are never streamed; only the 60 s mean, on button press.

## Battery & lifecycle
- **Push gating:** `IQDevice` CONNECTED ≠ app open on the watch. Phone pushes only within a 30 s
  window of the last incoming watch message (armed by `request_state`, cleared by `bye`; abnormal
  close expires it in ≤30 s). A closed app costs the watch nothing.
- **No unsolicited contact:** the phone NEVER sends first (no `wake` message — removed 2026-10-31,
  it woke the watch's BLE radio on every playback start). The watch app connects only when the
  user starts it manually; its `request_state` handshake arms the push window. The watch app
  carries no `Background` permission and no background service, so nothing listens for phone
  messages while it is closed.
- **Listener unregistered on exit (2026-10-31):** `Communications.registerForPhoneAppMessages(null)` is called in `onStop` and `exitApp`. `System.exit()` is a no-op in the audio-content-provider context, so the process can linger after the view pops; per the long-standing CIQ bug reported on forums.garmin.com (connect-iq discussion/872 and discussion/304686, "Battery drain when ConnectIQ app is not running"), a callback that is not unregistered keeps the app process alive and wakeable by BLE traffic after the app is closed. Passing `null` unregisters it — this is what makes a closed app cost the watch nothing.
- **Adaptive tick (2026-10-31):** the cadence-sampler Timer runs at 1 Hz while music is playing
  and 5 s while paused (`reconcileTickPeriod`, reconciled from both periodic ticks so a
  play/pause transition is picked up within ≤5 s). A repeating 1 s Timer wakes the CPU 60×/min
  even when the tick has nothing to do; while paused the tick only drives idle-exit / repaint /
  adjust-screen auto-pop checks (the auto-pop may then lag up to ~5 s past its 3 s mark).
- **Pause resume grace (one-shot expiry push):** a paused RunBeat stays resumable from the watch
  for 60 s (`GarminCommandRouter.PAUSE_RESUME_GRACE_MS`); the state flow emits nothing while
  paused, so the manager schedules one delayed push at the grace boundary to flip the watch to
  `source:"none"`. The push goes through the normal `pushState()` gates (aggregate connection +
  per-device 30 s watch-app window), so a closed watch is never woken for it; the watch's own
  ~30 s stale probe is the fallback if the delayed push misses its window.
- **Watch repaint policy:** the tick repaints only when visible content changed (position,
  header freshness, ack line, cadence number). Paused and still = no repaint.
- **Sensor policy:** the cadence sensor read runs only while music is playing; when paused
  the slower tick still fires (idle-exit/repaint checks) but performs no sensor read — an open
  but idle app costs almost nothing.
- **Paused link probing (2026-10-31):** while paused the phone pushes nothing, so the watch
  only hears back when it probes (`request_state`); each probe/answer round trip is a BLE
  wakeup. The staleness threshold is therefore 10 s while playing but 30 s while paused
  (`WatchState.isStale`), cutting the paused ping-pong to ~1 round trip/30 s. Trade-off:
  dead-phone detection (and the "Phone not connected" header) is slower while paused.
  Phone-side content dedup was rejected on purpose: skipping an unchanged answer would
  leave the watch permanently stale (no message ever arrives), and a minimal "alive" answer
  costs the same BLE wake as a full state push.
- **Draw-path policy (2026-10-31):** `WatchUi.loadResource()` hits the file system (per the CIQ
  forum guidance it must not run in `onUpdate`), so every view caches its `Rez.Strings` in
  members at `initialize()`; the draw path only consumes the cached strings.
- **Idle exit:** after 2 min without interaction once music stopped or link stale, the app first
  goes inert (stops its timers, unregisters the phone-message listener, sends `bye` — so even if
  the exit below is a no-op no further BLE traffic happens) then calls `System.exit()`; `onBack`
  attempts it but always returns false (returning true trapped the user back when `System.exit`
  was a no-op in the old provider context) — back falls through to the system pop, which as a
  plain watch-app exits to the watch face in one press.

## Launch paths (hybrid, 2026-10-31, Venu 2 firmware)
The app is an `audio-content-provider-app` **and** serves a direct app-menu launch — the two
Spotify-like entry points:

| | App-menu launch | Provider-flow launch (Music → Music Providers / music shortcut) |
|---|---|---|
| Entry | `getInitialView()` | `getPlaybackConfigurationView()` (same remote UI) |
| Host | none — opens directly | stock Music app is the host: brief flash on launch, back pops to it (one extra press) — firmware behavior, no API can remove it (forums.garmin.com connect-iq discussion/413352) |
| `System.exit` | expected to work | no-op on device (guarded; cleanup runs first) |

Provider branding: `getProviderIconInfo()` returns the launcher icon on green so RunBeat
shows its own icon in the provider list like Spotify. `getSyncDelegate()` returns null on
purpose (a real one would make the system open its own empty content browser instead of the
remote UI — observed 2026-10-28). Control from the stock player also works via the phone's
"Phone" media source (MediaSession metadata). A plain `watch-app` was tried 2026-10-31 and
reverted: that type can never appear in the Music app / music shortcut at all.

## Feature flows
1. **Transport:** command → router → `PlayerRepository` → state push back. Ignored when RunBeat
   is not the active player.
2. **Cadence:** watch ± → `cadence_delta`; "use current" → watch computes 60 s mean →
   `cadence_use_current` → `setTargetCadence(mean)`; speed correction re-applies in the repository.
3. **Speed correction:** toggle → `toggleSpeedCorrection()` → state push.
4. **Volume:** `volume_delta` → STREAM_MUSIC ±1 (ignored when RunBeat is not active); current
   volume pushed in `state`.
5. **Mark not-for-running:** `mark_not_for_running` → Room flag + immediate `playNext()` + ack;
   future queues exclude flagged tracks behind the settings toggle (default ON).

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
