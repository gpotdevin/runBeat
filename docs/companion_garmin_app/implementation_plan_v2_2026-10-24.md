# Implementation Plan v2 — 2026-10-24: music-source control & first-screen transport

**Status: implementation complete (agents A+B); verification done by agent C on 2026-10-25 — results in section 9 (Verification results).**
**Repo:** `/c/Users/guill/dev/runBeat-wt/integration` (git worktree, branch `feature/garmin-companion`)
**Inputs:** [requirements_update_2026-10-24.md](requirements_update_2026-10-24.md) (authoritative), [architecture.md](architecture.md), [README.md](README.md), [device_test_plan.md](device_test_plan.md), full read of the watch app (`garmin-watch/`) and the phone companion (`app/src/main/java/com/bpmapp/audio/audio/garmin/`, `app/src/garmin/`, tests), `PlayerRepository.kt`, `AudioService.kt`, `LibraryViewModel.getMediaItemForTrack()`.
**Supersedes:** the implementation steps of [implementation_plan.md](implementation_plan.md) for the 2026-10-24 requirements delta (first-screen transport, artist/album, volume, recognition fix). The Room migration / mark-not-for-running / cadence work described there is already built and stays unchanged.

---

## 1. Music-source approach — constraint re-confirmed, design chosen

### 1.1 Re-confirmation of the SDK 9.3.0 constraint (done 2026-10-24 by the planning agent)

Checked against the locally installed **Connect IQ SDK 9.3.0** (`connectiq-sdk-win-9.3.0-2026-10-20-95888f262`, present at both `/c/Users/guill/dev/tools/Garmin/ConnectIQ/Sdks/` and `/c/Users/guill/AppData/Roaming/Garmin/ConnectIQ/Sdks/`):

- **SDK-level API dump** (`<SDK>/bin/api.debug.xml`): **0** occurrences of `MediaPlayer`, `registerMediaPlayer`, `PlaybackInfo`, `TrackInfo`. Present: `AudioContentProviderApp` (35), `ContentDelegate` (27), `ContentMetadata` (8), `PlaybackProfile` (14).
- **Every device API dump** (`/c/Users/guill/AppData/Roaming/Garmin/ConnectIQ/Devices/*/*.api.debug.xml`, ~180 devices incl. `venu2`): **0 files** contain `registerMediaPlayer` / `MediaPlayer` / `PlaybackInfo` / `TrackInfo`. `venu2.api.debug.xml` contains the audio-content-provider machinery: `AudioContentProviderApp` (37), `ContentDelegate` (29), `PlaybackProfile` (16), `Content`, `ContentRef`, `ContentIterator`, `ContentMetadata`, `PlaybackControl`, `SystemButton`, `ActiveContent`, `SyncDelegate`.
- **SDK FAQ** (`<SDK>/doc/docs/Connect_IQ_FAQ/How_Do_I_Create_an_Audio_Content_Provider.html`): audio content providers exist so users can "listen to their favorite content from your service **while leaving their phone behind**" — i.e. **watch-side playback** driven by the native watch media player; they act as a plug-in to the native media player (confirmed by Garmin's own blog: Connect IQ 3 audio content providers "download content from a CDN via Wi-Fi directly to the watch, and act as a plug-in to the native media player").
- **Web check (Garmin owner's manuals):** the native watch Music app's phone control is **Music Providers > Control Phone** — a built-in "Phone" source driven by the **phone's standard media session** (MediaSession over Bluetooth/GCM), not by any Connect IQ registration. There is no Connect IQ API that registers a phone app as a music source for phone playback.

**Finding: there is NO phone-playback music-source mechanism in Connect IQ SDK 9.3.0.** The only "appear as a source in the native Music app" mechanism (audio-content-provider-app / `Toybox.Application.AudioContentProviderApp` / `Toybox.Media`) forces watch-side playback, which is the standalone app the user explicitly rejects and would bypass RunBeat's phone-side tempo/cadence engine.

### 1.2 Chosen design (unchanged from the requirements doc, now confirmed)

- The watch app **stays a `watch-app` remote** (`garmin-watch/manifest.xml` keeps `type="watch-app"`, permissions `Communications` + `Sensor`, **no Media permission** — it never plays audio). Music always plays on the phone.
- "The watch app is the phone's music-control source" is delivered as: **first screen = full transport** (current track artist / album / title + on-screen play/pause, next, previous, volume up/down + current volume), tightly linked to the RunBeat phone app over Connect IQ.
- The watch's **native Music app surfaces RunBeat through its built-in "Phone" source**, which reflects the phone's active MediaSession. RunBeat's `AudioService` already builds a media3 `MediaSession` over the ExoPlayer, and media3 publishes the current `MediaItem.mediaMetadata` (title / artist / albumTitle — already set in `LibraryViewModel.getMediaItemForTrack()`) plus playback state to every controller, including Garmin Connect Mobile → the watch's "Phone" source. So requirement 5 needs **verification + a small notification enrichment**, not a new mechanism (see section 5, `AudioService.kt`).

---

## 2. Root-cause diagnosis: why the watch does not recognize the phone app

Full read of `GarminCompanionManager.kt` (449 lines), `garmin-watch/source/MessageHandler.mc` (412 lines), `SimulatorAdbMode.kt`, `GarminCompanionInitializer.kt`, `device_test_plan.md`, plus the initialization chain (`BpmApp.kt` → `CompanionInitializerEntryPoint` → `GarminCompanionInitializer` → `GarminCompanionManager`; `AudioService.onCreate` → same initializer).

### 2.1 Ranked findings

**RC-1 (primary, watch side): there is no watch→phone state request — the watch can only passively wait for a push it may have missed.**
`MessageHandler.mc` has no `request_state` command; `WatchState.phoneReachable` starts `false` and only an *incoming* message flips it (`markMessageReceived()`). The phone pushes state only (a) on connect (`setConnected()` → `startStatePush()` + `pushState()`) and (b) on flow changes. The normal case is the user **launching the watch app after the phone link is already up** — the connect push was sent before the watch app was listening, and while paused with nothing changing, `distinctUntilChanged()` in `watchStateFlow()` suppresses every subsequent emission (the 1 Hz `volumeTicker()` produces an identical `GarminWatchState`). Result: the watch sits on "Phone not connected" with empty track info **indefinitely** — exactly the reported simulator symptom ("no live track info"). Commands appear not to land for the same underlying reason when the phone link is actually down (see RC-3); when the link is up, commands *do* land but the watch gives no feedback because it never learns the state.

**RC-2 (phone side, aggravating): the state push has no heartbeat.**
`watchStateFlow()` = `combine(playerState, currentTrack, targetCadence, speedCorrectionEnabled, playbackSpeed)` + 1 Hz volume ticker, then `distinctUntilChanged()`. `playerState` emits ~10x/s with the position, but `GarminWatchState` has no position field, so those emissions dedupe to nothing. While paused, zero pushes. Adding `positionMs` (rounded to whole seconds, see section 3) makes playing-state pushes ≈1/s; the paused case is covered by the RC-1 fix (watch re-asks).

**RC-3 (simulator/TETHERED preconditions — suspects (a) and (b) from the task): the phone only reaches the simulator when four conditions all hold.**
In `TETHERED` mode the Connect IQ Mobile SDK talks BLE-simulation over ADB to local port 7381. All of these must be true, or the phone never sees the simulated device and nothing is pushed / no command is received:
1. the **garmin-flavor debug APK** is installed and the process has started (the fdroid flavor has no Connect IQ SDK and cannot talk to the simulator at all);
2. the debug toggle **Settings > GARMIN > "Use Connect IQ simulator (ADB)"** is ON — default is OFF, i.e. `WIRELESS` (real watch via Garmin Connect Mobile); with it off, the simulator sees nothing. The pref is read at process start (`GarminCompanionInitializer.initialize` → `manager.setSimulatorAdbMode(SimulatorAdbMode.isEnabled(context))`) and applied live via `SettingsViewModel.setSimulatorAdbMode` → `onSimulatorAdbModeChanged`;
3. **`adb forward tcp:7381 tcp:7381`** is set;
4. the simulator's **Connection > Start (CTRL-F1)** is active, so `getKnownDevices()` returns the simulated device. `refreshDevices()` retries 5x with exponential backoff (2/4/8/16/32 s) and is re-armed by `onSdkReady`; if the connection starts after the retries are exhausted and no further SDK event arrives, registration can stay empty until the next SDK event.
Any single miss produces exactly the reported symptom. The Settings screen already shows CONNECTING / CONNECTED / DISCONNECTED distinctly (`SettingsScreen.kt:79-81`) — use it as the preflight indicator.

**RC-4 (not a bug): volume via the watch's *native* music controls.**
The native Music app's volume on the "Phone" source is device-dependent and is not a reliable path to the phone `STREAM_MUSIC`. The supported volume path is the companion app's volume row → `AudioManager.adjustStreamVolume(STREAM_MUSIC)` (`MediaKeyDispatcher.adjustStreamVolume`), which works for RunBeat and external apps alike. No code change; documented here and in the verify steps.

**Ruled out:**
- **(e) UUID pairing:** `GarminCompanionManager.IQ_APP_ID = "06c14376-0c60-4e4c-a977-e93364ace3eb"` matches `garmin-watch/manifest.xml`; `registerForEvents(device, this, iqApp, this)` uses it; the watch sends via `Communications.transmit(json, null, listener)` — the standard GCM-routed phone-message pattern. Correct.
- **(d) "phone does not push on connect":** it does (`setConnected()` → `startStatePush()` + `pushState()`). The gap is the watch missing that push (RC-1) and the dedup starvation (RC-2).
- **Watch→phone transmit path:** standard, correct.

### 2.2 The fix (what changes)

1. **Watch→phone `{"type":"request_state"}`** (new command, section 3): the watch sends it in `onStart` right after `registerForPhoneAppMessages`, and re-sends it every **5 s** while it has not received any message in the last ~10 s (a `Toybox.Timer`, stopped in `onStop`). The phone answers the **sender** with one state push (`pushStateTo(device)`), independent of the aggregate connection-state gate. This makes the link self-healing on both simulator and real hardware, on app start and on reconnect.
2. **Phone pushes on connect** (already present, keep) **and answers `request_state`** — see `GarminCompanionManager.kt` changes in section 5.
3. **Position in the state** (section 3) gives a ~1 Hz heartbeat while playing.
4. **TETHERED preflight logging:** in `refreshDevices()` / `onInitializeError`, when `simulatorAdbMode` is on, log actionable hints ("TETHERED: no known devices — in the simulator use Connection > Start (CTRL-F1); on the phone run `adb forward tcp:7381 tcp:7381`"). Settings already surfaces CONNECTING/CONNECTED/DISCONNECTED — the verify steps (section 6) make it a preflight check.
5. **Watch connection-status UX:** three explicit states on screen (section 4, `BaseView.mc`): "Connecting…" (no message ever), normal header (message within 10 s), "Phone not connected" (transmit error or stale).

---

## 3. Extended wire format (NORMATIVE — the shared contract between Agent A and Agent B)

Both agents implement **exactly** this. All existing fields and commands are kept. Transport: Connect IQ phone messages, JSON-encoded UTF-8 strings. The watch parser is a tolerant flat-object parser (unknown fields ignored); the phone codec (org.json) is tolerant the same way. Old/new peers are mutually tolerant: unknown fields are skipped by the watch, unknown commands decode to `null` and are logged + ignored by the phone.

### 3.1 Phone → watch: `state` (pushed on change, on connect, and in answer to `request_state`)

```json
{
  "type": "state",
  "source": "RunBeat",
  "isPlaying": true,
  "trackId": "content://media/...",
  "title": "Song name",
  "artist": "Artist name",
  "album": "Album name",
  "durationMs": 213000,
  "positionMs": 42000,
  "bpm": 140.0,
  "targetCadence": 172,
  "speedCorrection": true,
  "playbackSpeed": 1.03,
  "volume": 12
}
```

| Field | Type | Rule |
|---|---|---|
| `type` | `"state"` | unchanged |
| `source` | string | unchanged: `"RunBeat"` when RunBeat is the active playback app, else `"none"` (`"external"` reserved, not emitted) |
| `isPlaying` | bool | unchanged |
| `trackId` | string | unchanged (RunBeat only) |
| `title` | string | unchanged (RunBeat only) |
| **`artist`** | string | **NEW, required when `source == "RunBeat"`** — from `MediaMetadata.artist` (RunBeat already defaults it to `"Unknown"` in `getMediaItemForTrack`); null/omitted otherwise |
| **`album`** | string | **NEW, required when `source == "RunBeat"`** — from `MediaMetadata.albumTitle`; null/omitted otherwise |
| **`durationMs`** | long | **NEW, recommended** — track duration in ms: `extras.durationMs` (already put by `getMediaItemForTrack`), fallback `PlayerState.duration`; null/omitted when unknown or not RunBeat |
| **`positionMs`** | long | **NEW, recommended** — current playback position in ms, **rounded down to whole seconds by the phone** (`currentPosition / 1000 * 1000`) to keep BLE traffic ≈1 msg/s while playing; null/omitted when not RunBeat |
| `bpm` | number | unchanged (RunBeat only) |
| `targetCadence` | number | unchanged (RunBeat only) |
| `speedCorrection` | bool | unchanged (RunBeat only) |
| `playbackSpeed` | number | unchanged (RunBeat only) |
| `volume` | int | unchanged — current `STREAM_MUSIC` AudioManager index |

Ack messages (`{"type":"ack","command":"...","ok":true}`) are unchanged.

### 3.2 Watch → phone: commands (all existing kept, two new)

| Message | Payload | Phone action |
|---|---|---|
| `play` / `pause` / `toggle` | — | unchanged |
| `next` / `prev` | — | unchanged |
| `cadence_set` | `{"cadence": 172}` | unchanged |
| `cadence_delta` | `{"delta": 1}` / `-1` | unchanged |
| `cadence_use_current` | `{"cadence": 172}` | unchanged |
| `speed_correction_toggle` | — | unchanged |
| `volume_delta` | `{"delta": 1}` / `-1` | unchanged |
| `mark_not_for_running` | — | unchanged |
| **`request_state`** | — | **NEW** — no payload, **no ack**; the phone answers the sender with one `state` push (see `pushStateTo(device)`). Sent by the watch on app start and every 5 s while no message has been received for ~10 s |
| **`seek`** | `{"positionMs": 42000}` | **NEW, optional** — RunBeat active: `PlayerRepository.seekTo(positionMs)`; otherwise ignored. No ack (transport-like: the following state push confirms). Sanity bound on the phone: 0 ≤ N ≤ 86 400 000 |

**Traffic budget:** while playing, state pushes ≈1/s (the position second changes); while paused, pushes only on real changes plus the watch's 5 s `request_state` re-ask (each answered with one push). Every message is a small flat JSON string — well within Connect IQ limits.

---

## 4. Watch-side changes (Agent A — owns `garmin-watch/**` only)

### 4.1 `garmin-watch/source/WatchState.mc`
- Add fields: `var artist as String = ""`, `var album as String = ""`, `var durationMs as Number? = null`, `var positionMs as Number? = null`.
- In `MessageHandler.applyState`'s `source != "RunBeat"` branch, also clear `artist`, `album`, `durationMs`, `positionMs` (no stale RunBeat metadata on screen).
- Add formatted accessors: `positionText()` and `durationText()` (m:ss, empty string when null — small local `formatMs()` helper), `progressText()` → `"1:23 / 3:45"` (or `""` when duration unknown).
- Add a staleness helper for the connection UX: `function isStale(now as Number) as Boolean` → `lastMessageAt == 0 || now - lastMessageAt > 10000` (10 s), and `function hasConnected() as Boolean` → `lastMessageAt > 0`.
- Keep `volume as Float?` + `volumeText()` (AudioManager index, shown as-is).

### 4.2 `garmin-watch/source/MessageHandler.mc`
- `applyState`: parse `artist` / `album` (String), `durationMs` / `positionMs` (the existing flat parser already yields `Number` for numeric literals — store as `Number`, format with `%.0f`; ms values fit Float precision for any realistic track length).
- Add `function sendRequestState() as Void { sendJson("{\"type\":\"request_state\"}"); }`.
- Add `function sendSeek(positionMs as Number) as Void { sendJson("{\"type\":\"seek\",\"positionMs\":" + positionMs.format("%d") + "}"); }`.
- Update the header comment to the extended wire format (section 3).

### 4.3 `garmin-watch/source/RunBeatCompanionApp.mc`
- `onStart`: after `Communications.registerForPhoneAppMessages(...)`, immediately call `_messages.sendRequestState()`.
- Start a repeating `Toybox.Timer` (5000 ms) that re-sends `request_state` while `_state.isStale(System.getTimer())` (covers "no message ever" and "link dropped"); stop it in `onStop`. (The existing 1 Hz `CadenceSampler` timer already drives `WatchUi.requestUpdate()`, so the progress display and status refresh for free.)
- Pass an optional tap/swipe handler into the page-0 delegate (see 4.5); pages 1/2 unchanged.

### 4.4 `garmin-watch/source/view/MainView.mc` — first-screen redesign
All content is drawn in `onUpdate` relative to `w`/`h` (adapts to 218–454 px, round and square); `layouts.xml` keeps only `headerLabel` (no new layout ids).

Screen layout (top → bottom):
1. Header (existing `headerLabel` via `BaseView.updateHeader`): source label / connection status (see 4.6).
2. Track block: **ARTIST** (FONT_SMALL, white, truncated), **ALBUM** (FONT_TINY, LT_GRAY, truncated), **TITLE** (FONT_MEDIUM, white, truncated) — stacked around y ≈ 0.16 / 0.24 / 0.33. Falls back to `NoValue` ("--") per line when absent; when `source != "RunBeat"` the block shows the source label only (as today).
3. Progress (only when `durationMs > 0`): thin bar (`fillRectangle`, fraction = positionMs/durationMs) + `"m:ss / m:ss"` caption (FONT_TINY, LT_GRAY) around y ≈ 0.42.
4. **Transport row** (y ≈ 0.58): three on-screen buttons — **[PREV] [PLAY/PAUSE] [NEXT]** — drawn as filled rounded rects with glyphs: reuse `drawPlayIcon`/`drawPauseIcon` for the center; draw prev/next as double-triangle glyphs with the same scanline `fillRectangle` technique already used for the play icon.
5. **Volume row** (y ≈ 0.76): **[VOL−] `VOL 12` [VOL+]** — drawn buttons left/right, current volume (from `state.volume`, `"--"` when unknown) centered between them.
6. Ack line + page indicator (unchanged, `BaseView`).

`onSelectPressed()` stays **play/pause** (single button / ENTER). Add:
- `function onTapZone(x as Number, y as Number) as Symbol?` — hit-tests the five drawn button rects, returns `:zone_prev` / `:zone_play` / `:zone_next` / `:zone_vol_down` / `:zone_vol_up` or `null`; the delegate maps the zone to `sendPrev()/sendToggle()/sendNext()/sendVolumeDelta(-1/+1)`.
- `function onHorizontalSwipe(direction as Symbol) as Void` — seek gesture (touch only): `:swipe_left` → `sendSeek(max(0, positionMs - 15000))`, `:swipe_right` → `sendSeek(positionMs + 15000)` (clamped to `durationMs` when known); no-op when `positionMs`/`durationMs` unknown.

### 4.5 Input mapping — both device classes (the core design decision)

The screen is identical on all devices; only activation differs.

| Action | Touch devices (venu2/venu3/venu3s; epix2 touch) | 5-button devices (fr245/255/265/955/965, fenix7/8, epix2 buttons) |
|---|---|---|
| play/pause | tap the center button; single physical button (select) also = play/pause | ENTER (onSelect) = play/pause |
| next / previous | tap the right / left transport button | MENU (onMenu) → `MainViewMenu`, flat items **Next / Previous** |
| volume up / down | tap VOL+ / VOL− | MENU (onMenu) → `MainViewMenu`, flat items **Volume + / Volume −** |
| page navigation | vertical swipe up/down (ViewLoop, unchanged) | UP/DOWN buttons (ViewLoop, unchanged) |
| seek (optional) | horizontal swipe on page 0 (±15 s) | not mapped (protocol + `sendSeek` still implemented) |

Rationale for the button-device mapping: a 5-button device has 5 inputs (UP, DOWN, ENTER, BACK, menu behavior) for 6 needed actions (5 transport + page navigation). Stealing UP/DOWN for transport would make the cadence/controls pages unreachable on every button device — rejected. Putting the four transport actions as **flat top-level items** in the main menu is a single press with no sub-menus ("no digging"), keeps the most-used actions (play/pause, page nav) on buttons, and matches Garmin's native music app pattern on button devices. The five controls remain **drawn on-screen** so the user always sees the full transport and the current volume.

Implementation: `RunBeatPageDelegate` (in `RunBeatCompanionApp.mc`) gets an optional handler (a `Method` or null). Override `onTap(clickEvent)` → if a handler is set, invoke it with the tap coordinates and return `true`; else return `false` (default). Override `onSwipe(swipeEvent)` → if a handler is set and the direction is horizontal, invoke it and return `true`; else `false` (vertical swipes keep cycling pages via `RunBeatViewLoopDelegate`). Only page 0 passes a handler. `WatchUi.InputDelegate.onTap`/`onSwipe` are available at minSdk 3.2.

- **fr245 MainView-only fallback** (`RunBeatFallbackDelegate`, CIQ 3.3.1 < 3.4.0): `onSelect` = play/pause (unchanged); `onMenu` opens the same extended `MainViewMenu` (4 items). No tap zones (no touch screen).
- `MainViewMenuDelegate` (in `MainView.mc`): handle the two new item ids.

### 4.6 `garmin-watch/source/view/BaseView.mc` — connection-status UX
`updateHeader` picks one of three states from `WatchState`:
- `!hasConnected()` (no message ever) → **"Connecting…"** (yellow);
- `isStale(now)` (transmit error or no message for >10 s) → **"Phone not connected"** (yellow, existing string);
- otherwise → the normal label/color.
All three pages inherit this. (A failed `transmit` already calls `markPhoneUnreachable()` via `RunBeatConnectionListener.onError`; the staleness timer makes recovery automatic once the phone answers the next `request_state`.)

### 4.7 Resources and other watch files
- `garmin-watch/resources/strings.xml`: add `Connecting` ("Connecting…") and `VolumeShort` ("VOL"); reuse `NoValue`, `PhoneNotConnected`, `MenuNext`, `MenuPrev`, `MenuVolumeUp`, `MenuVolumeDown`. No other new strings (drawn glyphs need none).
- `garmin-watch/resources/menus/MainViewMenu.xml`: extend to 4 flat items — `menu_next`, `menu_prev`, `menu_volume_up`, `menu_volume_down` (existing string ids). `CadenceViewMenu.xml` / `ControlViewMenu.xml` unchanged (ControlView's menu keeps its redundant volume items).
- `garmin-watch/resources/layouts.xml`: **unchanged** (headerLabel only; all new content is drawn in `onUpdate`).
- `garmin-watch/manifest.xml`: **unchanged** — still `type="watch-app"`, `Communications` + `Sensor`, no Media permission.
- `CadenceView.mc`, `ControlView.mc`, `CadenceSampler.mc`, `CadenceRingBuffer.mc`: **unchanged** (cadence / speed-correction / mark-not-for-running stay on their screens).

---

## 5. Phone-side changes (Agent B — owns `app/src/**` only)

### 5.1 `app/src/main/java/com/bpmapp/audio/audio/garmin/GarminMessageCodec.kt`
- `GarminWatchState`: add `artist: String? = null`, `album: String? = null`, `durationMs: Long? = null`, `positionMs: Long? = null`.
- `encodeState`: emit `artist`/`album` when non-null; `durationMs`/`positionMs` when non-null (`JSONObject.put(Long)`).
- `decodeState`: read them back (`optStringOrNull`; new `optLongOrNull` with the same has/isNull guard style).
- `GarminCommand`: add `data object RequestState : GarminCommand()` and `data class Seek(val positionMs: Long) : GarminCommand()`.
- `decodeCommand`: `"request_state"` → `RequestState`; `"seek"` → `positionMs` must be present, finite, integral, within `0..86_400_000` (24 h sanity bound) → `Seek`, else `null` (same tolerant style as `optDeltaOrNull`).
- `encodeCommand`: wire strings `{"type":"request_state"}` and `{"type":"seek","positionMs":N}` (for round-trip and watch-format tests).
- Update the KDoc wire-format comment (points at `architecture.md`).

### 5.2 `app/src/main/java/com/bpmapp/audio/audio/garmin/GarminCommandRouter.kt`
- `currentState()`: add
  - `artist = if (active) track?.mediaMetadata?.artist?.toString() else null`
  - `album = if (active) track?.mediaMetadata?.albumTitle?.toString() else null`
  - `durationMs = if (active) (extras?.getLong("durationMs")?.takeIf { it > 0 } ?: playerRepository.playerState.value.duration.takeIf { it > 0 }) else null`
  - `positionMs = if (active) playerRepository.playerState.value.currentPosition else null` — **rounded down to whole seconds here** (`/ 1000 * 1000`) so `distinctUntilChanged` in the manager emits ≈1/s while playing.
  (`PlayerRepository.PlayerState` already has `currentPosition: Long` and `duration: Long`; `getMediaItemForTrack` already puts `extras.durationMs`.)
- `handleCommand()`: add `is GarminCommand.Seek ->` — RunBeat active: `playerRepository.seekTo(cmd.positionMs)`; otherwise ignored (no media-key equivalent for an absolute seek). Returns `GarminAck("seek", ok = true/false)`; the manager does **not** ack it (transport-like — the following state push confirms). Add `"seek"` to `wireType()`.
- `RequestState` is **deliberately NOT handled here** — it is a link-layer request intercepted by the manager (5.3), not a playback command.

### 5.3 `app/src/garmin/java/com/bpmapp/audio/audio/garmin/GarminCompanionManager.kt` (the recognition fix)
- `onMessageReceived`: right after `decodeCommand`, intercept:
  ```kotlin
  if (command is GarminCommand.RequestState) {
      scope.launch { recomputeConnection(); pushStateTo(device) }
      continue
  }
  ```
  No ack, no mutex (it only reads flows and sends).
- New `private fun pushStateTo(device: IQDevice)`: sends `GarminMessageCodec.encodeState(router.currentState())` to that device when `deviceStatuses[device.deviceIdentifier] == CONNECTED` (log otherwise). Independent of the aggregate `connectionState` gate used by `pushState()`.
- `watchStateFlow()`: round the position before combining so the heartbeat is ≈1/s while playing — make the first combine source `playerRepository.playerState.map { it.copy(currentPosition = it.currentPosition / 1000L) }.distinctUntilChanged()` (`PlayerState` is a data class; `copy` works). Keep the 1 Hz `volumeTicker()`.
- `refreshDevices()` / `onInitializeError`: when `simulatorAdbMode` is on, log actionable TETHERED hints ("no known devices — in the simulator use Connection > Start (CTRL-F1); on the phone run `adb forward tcp:7381 tcp:7381`"). No behavior change otherwise.
- `needsAck()`: unchanged (RequestState is intercepted before the router; Seek is not acked).
- `setConnected()` → `startStatePush()` + `pushState()` stays (push on connect, RC-1's counterpart).

### 5.4 `app/src/main/java/com/bpmapp/audio/audio/AudioService.kt` (native Music app "Phone" source)
The media3 `MediaSession` already publishes the player's `MediaItem.mediaMetadata` (title / artist / albumTitle — set in `LibraryViewModel.getMediaItemForTrack()`) plus playback state to every controller, which is what Garmin Connect Mobile forwards to the watch's native Music app **Phone** source. Change: include the album in the media notification for consistency — read `currentTrack?.mediaMetadata?.albumTitle` next to the existing `trackTitle`/`trackArtist` (line ~260) and use it as the notification `contentText` (currently `R.string.app_name`). Minimal diff; verify with `adb shell dumpsys media_session` that RunBeat's session shows title/artist/album + play state.

### 5.5 No changes needed (verified by the planning agent — Agent B must NOT touch these)
- `PlayerRepository.kt`: `seekTo(positionMs: Long)` exists (line ~320); `PlayerState` has `currentPosition`/`duration`; `currentTrack` exposes `mediaMetadata` with `extras{trackId, bpm, durationMs}`.
- `LibraryViewModel.getMediaItemForTrack()` already sets `title`, `artist`, `albumTitle`, `extras{trackId, bpm, fileName, durationMs}`.
- `SettingsScreen.kt` / `SettingsViewModel.kt`: CONNECTING/CONNECTED/DISCONNECTED already displayed; the debug simulator toggle exists. Optional (small, Agent B): extend the simulator-toggle description string (`app/src/main/res/values/strings.xml`, `settings_garmin_simulator_adb_desc`) to mention `adb forward tcp:7381 tcp:7381` + CTRL-F1.
- `MediaKeyDispatcher.kt`: unchanged (no seek-by-key; absolute seek is RunBeat-only).

### 5.6 Unit tests to add/update (Agent B)
- `app/src/test/java/com/bpmapp/audio/audio/garmin/GarminMessageCodecTest.kt`:
  - state round-trip **with artist/album/durationMs/positionMs** (decode(encode(state)) == state);
  - UTF-8 / special characters in `artist`/`album` round-trip;
  - `encodeState` omits the four new fields when null (wire-format assertion);
  - `decodeCommand`: `request_state`; `seek` valid / missing `positionMs` / negative / non-integral / absurd (>24 h) → `null`;
  - `encodeCommand` matches the watch's exact wire strings for both new commands (extend the existing `encodeCommandMatchesWatchWireFormat` pattern);
  - tolerance: `{"type":"state",...}` still decodes to `null` as a command.
- `app/src/test/java/com/bpmapp/audio/audio/garmin/GarminCommandRouterTest.kt`:
  - extend the `track()` helper with `artist`/`album` (+ `durationMs` extra);
  - `currentState_includesArtistAlbumDurationPositionWhenActive` (asserts artist/album from `MediaMetadata`, durationMs from extras, positionMs from `PlayerState.currentPosition`, rounded to seconds);
  - `currentState_omitsRunBeatOnlyFieldsWhenInactive` (artist/album/duration/position null);
  - `seek_routesToPlayerRepositorySeekToWhenActive` (`verify(playerRepository).seekTo(N)`);
  - `seek_ignoredWhenInactive` (no `seekTo`, no media-key dispatch).
- `app/src/testGarmin/java/com/bpmapp/audio/audio/garmin/GarminCompanionManagerCommandSerializationTest.kt`:
  - add a test that a `request_state` message from a device triggers `sendMessage` with a `state` payload **to that device** (following the file's existing ConnectIQ-mocking pattern).

---

## 6. Build + verify steps (exact commands)

### 6.1 Watch `.prg` — all 16 devices, signed (RSA-4096 developer key, signing is mandatory)

Device list (must match `garmin-watch/manifest.xml` `<iq:products>` — **16 devices; note the existing `compile_repo_all.sh` loops only 15 and omits `venu2` — run venu2 separately or extend the list**):

```
fr245 fr255 fr265 fr955 fr965 fenix7 fenix7s fenix7x fenix8solar47mm fenix8solar51mm fenix843mm fenix847mm epix2 venu2 venu3 venu3s
```

```bash
SDK=/c/Users/guill/AppData/Roaming/Garmin/ConnectIQ/Sdks/connectiq-sdk-win-9.3.0-2026-10-20-95888f262
JAVA=/c/Users/guill/.jdks/jbr-17.0.14/bin/java
GB=/c/Users/guill/dev/tools/garmin-build
REPO=/c/Users/guill/dev/runBeat-wt/integration
mkdir -p "$GB/out-v2"
for d in fr245 fr255 fr265 fr955 fr965 fenix7 fenix7s fenix7x fenix8solar47mm fenix8solar51mm fenix843mm fenix847mm epix2 venu2 venu3 venu3s; do
  APPDATA=/c/Users/guill/dev/tools "$JAVA" -Xms1g -Dfile.encoding=UTF-8 \
    -cp "$SDK/bin/monkeybrains.jar" com.garmin.monkeybrains.Monkeybrains \
    -f "$REPO/garmin-watch/monkey.jungle" -d "$d" \
    -y "$GB/developer_key.der" \
    --override-devices-json "$GB/devices-local" \
    -o "$GB/out-v2/$d.prg" 2>&1 | tee "$GB/out-v2/$d.log"
  echo "$d exit=$?"
done
# Expect: 16/16 exit 0, one signed .prg per device in $GB/out-v2/
```

(Or reuse the wrapper: `bash /c/Users/guill/dev/tools/garmin-build/compile_one.sh <device> "$REPO/garmin-watch" "$GB/out-v2"` per device. `compile_one.sh`'s SDK path points at `/c/Users/guill/dev/tools/Garmin/ConnectIQ/Sdks/...` — same SDK 9.3.0; either copy works. The developer key at `$GB/developer_key.der` is RSA-4096 PKCS#8 DER — verified.)

### 6.2 Android APK + phone unit tests (JDK 17; `local.properties` already has `sdk.dir`/`ndk.dir`)

```bash
export JAVA_HOME=/c/Users/guill/.jdks/jbr-17.0.14
cd /c/Users/guill/dev/runBeat-wt/integration
./gradlew :app:testGarminDebugUnitTest      # codec + router + manager tests (incl. the new ones)
./gradlew :app:assembleGarminDebug         # garmin flavor only — fdroid has no Connect IQ SDK
```

### 6.3 Simulator end-to-end run (venu2, phone tethered via ADB)

1. Start `<SDK>/bin/simulator.exe` (AppData SDK), select the **venu2** device profile.
2. `adb forward tcp:7381 tcp:7381` — verify with `adb forward --list`.
3. `adb install -r app/build/outputs/apk/garmin/debug/<garmin-debug.apk>` — applicationId `com.RunBeat.run.audio`; the garmin build replaces the fdroid build (uninstall first if signatures differ).
4. Open RunBeat once on the phone (process start initializes Connect IQ) and **start playback of a known track** (note its artist/album/title).
5. Settings > GARMIN: check the connection state (should reach CONNECTED once the simulator link is up) and enable **"Use Connect IQ simulator (ADB)"** (debug builds only).
6. Push the watch app: `cd "$SDK/bin" && ./monkeydo "$GB/out-v2/venu2.prg" venu2` (or drag the `.prg` onto the simulator window).
7. In the simulator: **Connection menu > Start (CTRL-F1)**.
8. The watch app launches → sends `request_state` → the phone answers → the first screen shows the track's artist/album/title, the transport row, the volume row, and the connected status.

### 6.4 Native Music app "Phone" source

The simulator cannot exercise the watch's native Music app — verify on real hardware (venu2): Music app > Music Providers > **Phone** shows RunBeat with title/artist/album. On the phone, `adb shell dumpsys media_session` shows RunBeat's session with `MediaMetadata` (title/artist/album) + playback state — that is what GCM forwards to the watch's "Phone" source.

---

## 7. Task split for parallel implementation agents

| Agent | Owns (exclusive) | Implements |
|---|---|---|
| **A — watch app** | `garmin-watch/**` (source, resources, manifest, monkey.jungle) | Section 4 + the watch half of the section 3 wire format |
| **B — phone app** | `app/src/**` (main, garmin, test, testGarmin, res) + the **"Message protocol" section of `docs/companion_garmin_app/architecture.md`** (the normative wire format — the requirements doc says whoever changes the codec updates it) | Section 5 + the phone half of the section 3 wire format |
| **C — build + verify + docs** | `docs/companion_garmin_app/{README.md, device_test_plan.md, implementation_plan_v2_2026-10-24.md}` (status/results updates only) + build outputs outside the repo | Section 6 end-to-end, records results, updates docs — **no source edits** |

- A and B run **in parallel** (disjoint trees: `garmin-watch/**` vs `app/src/**`; B additionally owns one docs section). C runs **after** A and B (it needs both artifacts).
- **Shared contract: section 3 of this plan.** A and B must both implement exactly those field names, types, rounding and message shapes. Cross-checks that enforce it: B's `GarminMessageCodecTest.encodeCommandMatchesWatchWireFormat` asserts the exact JSON strings A's `MessageHandler.mc` sends; A's tolerant parser must accept every field B's `encodeState` emits (asserted by B's `decodesWatchExampleStateMessage` with the extended example).
- If C finds a contract mismatch during verification, **section 3 of this plan is authoritative** — A and B conform to it.
- No file is owned by two agents. `architecture.md` is split by section: protocol tables → B; everything else → C (read-only for both until C's docs pass).

---

## 8. Acceptance criteria (mirror of the requirements doc) and how each is verified in the simulator

| # | Criterion | Verified by |
|---|---|---|
| 1 | Watch `.prg` builds for all **16** devices (signed, RSA-4096 developer key) | Section 6.1 loop: 16/16 `exit=0`, one `.prg` per device in `$GB/out-v2/` (incl. `venu2`) |
| 2 | First screen shows live RunBeat track **artist / album / title** | Simulator run (6.3): play a known track on the phone; within ~2 s of the watch app launching (request_state handshake) the first screen shows its artist/album/title |
| 3 | On-screen **next / previous** change the phone track; **play/pause** toggles; **volume up/down** change the phone STREAM_MUSIC volume and the watch shows the current volume | Simulator: click the drawn zones (mouse = tap); confirm in logcat (`PlayerRepository` playNext/playPrevious/togglePlayPause) and via the phone's volume overlay; the watch volume row shows the new index |
| 4 | Watch shows **"connected"** when the phone link is up; **"Phone not connected"** otherwise | Simulator: after the handshake the header shows the source label (not the warning); stop the simulator connection (Connection > Stop) or force-stop RunBeat → "Phone not connected"; restore → recovers automatically via the 5 s `request_state` retry |
| 5 | Phone unit tests pass for the extended codec (artist/album round-trip) and router (`currentState()` includes artist/album from `MediaMetadata`) | `./gradlew :app:testGarminDebugUnitTest` green (6.2), incl. the new tests in 5.6 |
| 6 | `garminDebug` APK builds and installs on the phone | `./gradlew :app:assembleGarminDebug` + `adb install -r` (6.2/6.3); app opens; Settings shows CONNECTED in the simulator run |
| 7 | (Real hardware) Native Music app surfaces RunBeat via its built-in **Phone** source with title/artist/album + play state | venu2 device run: Music app > Music Providers > Phone; cross-checked on the phone with `adb shell dumpsys media_session` (6.4). The simulator cannot verify this one |

**Risks / open items:**
- The horizontal-swipe seek gesture must be verified in the simulator — if ViewLoop consumes horizontal swipes, drop the gesture (the `seek` protocol, codec, router and `sendSeek` stay; no button-device mapping either way).
- fr245 (CIQ 3.3.1) takes the MainView-only fallback: play/pause + the flat 4-item menu; cadence/controls pages unreachable on that one model (existing, accepted limitation).
- BLE traffic while playing ≈1 state push/s (position second changes) — small flat JSON; acceptable.
- The notification album line (`AudioService.kt`) is cosmetic consistency; the watch's native Music app reads the MediaSession metadata, which already carries album.
---

## 9. Verification results (agent C, 2026-10-25)

Agent C ran the section 6 verification on 2026-10-25 (no source edits; build outputs outside the repo). Results:

### 9.1 Wire-format contract cross-check (section 3 is normative) — PASS, no discrepancies

| Contract item (section 3) | Watch — `garmin-watch/source/MessageHandler.mc` | Phone — `GarminMessageCodec.kt` / `GarminCommandRouter.kt` / `GarminCompanionManager.kt` | Match |
|---|---|---|---|
| `request_state` command | `sendRequestState()` sends exactly `{"type":"request_state"}` (MessageHandler.mc:82-84); sent on app start right after `registerForPhoneAppMessages` (RunBeatCompanionApp.mc:44-47) and re-sent every 5 s while stale (RunBeatCompanionApp.mc:53-55, 74-77) | `encodeCommand(RequestState)` == `{"type":"request_state"}` (GarminMessageCodec.kt:195); `decodeCommand` maps `"request_state"` -> `RequestState` (GarminMessageCodec.kt:162) | yes |
| `seek` command | `sendSeek(p)` sends `{"type":"seek","positionMs":<p.format("%d")>}` (MessageHandler.mc:88-90) | `encodeCommand(Seek(N))` == `{"type":"seek","positionMs":N}` (GarminMessageCodec.kt:196-199); decode requires `positionMs` present, finite, integral, within 0..86_400_000 (`optPositionMsOrNull`, GarminMessageCodec.kt:305-311; `MAX_SEEK_POSITION_MS = 86_400_000.0` at :137) | yes |
| `state` `artist`/`album` (String) | parsed as String (MessageHandler.mc:142-148); cleared when `source != "RunBeat"` (MessageHandler.mc:166-167) | `encodeState` emits both when non-null (GarminMessageCodec.kt:235-236); `decodeState` reads them back | yes |
| `state` `durationMs`/`positionMs` (Number) | parsed as Number via `optionalNumber` (MessageHandler.mc:150-151, 228-238); cleared when `source != "RunBeat"` (MessageHandler.mc:168-169) | emitted when non-null via `JSONObject.put(Long)` (GarminMessageCodec.kt:237-238); `optLongOrNull` reads them back | yes |
| `positionMs` rounded to whole seconds | watch renders ms values as whole seconds (m:ss, `WatchState.formatMs`) | router `currentState()`: `currentPosition / 1000L * 1000L` (GarminCommandRouter.kt:181-183); manager `watchStateFlow()` rounds before `distinctUntilChanged` (GarminCompanionManager.kt:414) | yes |
| `durationMs` source | — | `extras.durationMs` (`takeIf { it > 0 }`) with fallback `PlayerState.duration` (`takeIf { it > 0 }`) (GarminCommandRouter.kt:173-180) | yes |
| `artist`/`album` source | — | `MediaMetadata.artist` / `MediaMetadata.albumTitle` (GarminCommandRouter.kt:171-172) | yes |
| RequestState interception | — | manager intercepts before the router and answers the sender with one state push, no ack: `scope.launch { recomputeConnection(); pushStateTo(device) }` (GarminCompanionManager.kt:251-257); `pushStateTo` sends `encodeState(router.currentState())` to that device when CONNECTED (GarminCompanionManager.kt:445-461); `needsAck()` unchanged — Seek is not acked (GarminCompanionManager.kt:477-486) | yes |
| Exact wire strings pinned by tests | — | `GarminMessageCodecTest.encodeCommandMatchesWatchWireFormat` asserts `{"type":"request_state"}` and `{"type":"seek","positionMs":42000}` byte-for-byte; `decodesWatchExampleStateMessage` asserts the extended state example decodes | yes |

Agents A and B match section 3 exactly; no discrepancy found.

### 9.2 Watch artifacts — PASS (16/16)

- `/c/Users/guill/dev/tools/garmin-build/out-v2/` holds **16 `.prg` files**, one per device: `fr245 fr255 fr265 fr955 fr965 fenix7 fenix7s fenix7x fenix8solar47mm fenix8solar51mm fenix843mm fenix847mm epix2 venu2 venu3 venu3s` — **exactly** the `<iq:products>` list in `garmin-watch/manifest.xml` (sorted diff of the two lists: empty).
- Every `$GB/out-v2/<device>.log` ends with `BUILD SUCCESSFUL` and contains no errors; `summary.txt` records `exit=0` for all 16 devices.
- All `.prg` files are 136,636–146,236 bytes (>100 KB — signed; the builds ran with `-y developer_key.der`, RSA-4096).
- Reproducibility: rebuilding venu2 with the section 6.1 command into a temp name (`venu2.recheck.prg`, the set untouched) → `BUILD SUCCESSFUL`, and the recheck file is **byte-identical** to `venu2.prg`.

### 9.3 Phone unit tests — PASS

`JAVA_HOME=/c/Users/guill/.jdks/jbr-17.0.14 ./gradlew :app:testGarminDebugUnitTest`, forced re-run (`--rerun --no-build-cache`, task executed 2026-10-25 07:47 local): **BUILD SUCCESSFUL — 214 tests, 0 failures, 0 errors** (19 test classes). All contract tests pass, incl. `encodeCommandMatchesWatchWireFormat`, `decodesRequestStateCommand`, `decodesSeekCommand`, `decodeCommandRejectsMissingInvalidOrAbsurdSeekPosition`, `decodesWatchExampleStateMessage`, `encodeStateEmitsArtistAlbumDurationAndPosition`, `encodeStateOmitsUnknownRunBeatOnlyFields`, `currentState_includesArtistAlbumDurationPositionWhenActive`, `currentState_omitsRunBeatOnlyFieldsWhenInactive`, `seek_routesToPlayerRepositorySeekToWhenActive`, `seek_ignoredWhenInactive`, `requestState_isAnsweredWithOneStatePushToThatDevice`.

### 9.4 Phone APK — PASS

`app/build/outputs/apk/garmin/debug/app-garmin-debug.apk` — 24,490,380 bytes, built **2026-10-25 07:33** (fresh). Re-running `:app:assembleGarminDebug` on 2026-10-25 → **UP-TO-DATE** (the APK is current with the sources; no rebuild needed).

### 9.5 Phone tether staging — BLOCKED (phone not connected)

- `adb devices` → **empty**. The adb server was restarted (`kill-server` / `start-server`) — still empty; `adb wait-for-device` timed out after 25 s.
- Windows PnP enumeration: the Fairphone 6 (adb serial `fe113fb3`) is **not attached** — all of the phone's USB devices show *Disconnected*: `USB\VID_18D1&PID_4EE2\fe113fb3` and `USB\VID_18D1&PID_4EEE\fe113fb3` (composite parents), `USB\VID_18D1&PID_4E11\fe113fb3` ("Android ADB Interface"), `USB\VID_18D1&PID_4EE2&MI_01\6&279f0498&1&0001` ("Android Composite ADB Interface") and the WPD "Fairphone 6" MTP entries. The driver bindings are present (oem55.inf Google USB driver / wpdmtp.inf), so the phone has been connected before — it is physically unplugged (or powered off / USB disconnected) at verification time.
- Therefore **`adb install -r app-garmin-debug.apk` was NOT run** and **`adb forward tcp:7381 tcp:7381` was NOT set** (`adb forward` fails with "no devices/emulators found"; `adb forward --list` is empty). Per this agent's scope, no simulator was launched and the phone UI was not driven.

### 9.6 Remaining step — live simulator e2e (pending user)

Section 6.3 with the phone connected: `adb install -r` the APK (9.4), `adb forward tcp:7381 tcp:7381`, start `simulator.exe` (venu2 profile), enable Settings > GARMIN > "Use Connect IQ simulator (ADB)" on the phone, push `out-v2/venu2.prg` (`monkeydo`), Connection > Start (CTRL-F1), start playback of a known track on the phone, then verify acceptance criteria 2–4 on the watch's first screen. Criterion 7 additionally needs real venu2 hardware (section 6.4).
