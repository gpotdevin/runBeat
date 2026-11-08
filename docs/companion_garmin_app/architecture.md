# Architecture — Garmin Companion for RunBeat

**Normative reference for the watch↔phone wire protocol** — referenced from code:
`GarminMessageCodec.kt` and `GarminCommandRouter.kt` must match the tables below
(pinned by unit tests such as `encodeCommandMatchesWatchWireFormat`).
Synced 2026-11-08. Watch side is data fields — see [datafield.md](datafield.md).

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


## Message protocol (NORMATIVE)

Transport: Connect IQ phone messages, JSON UTF-8 strings. Every message carries `"apiVersion":N` (watch→phone in all commands; phone→watch in state/ack) — see [datafield.md](datafield.md#protocol-versioning).

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

