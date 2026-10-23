# Watch App Plan — Connect IQ (Monkey C)

Date: 2026-10-22

This is a **separate project** from the Android Gradle build: a Connect IQ
app written in Monkey C, built with the Garmin Connect IQ SDK (VS Code
extension or Eclipse + SDK manager), producing a `.prg` per device family.
Suggested location in this repo: `garmin-watch/` (not part of
`settings.gradle`).

## Purpose

Act as the user's **default music app** on the watch:

- When **RunBeat** is playing on the phone: full remote control (transport,
  target cadence, speed correction, mark not-for-running) plus feeding the
  runner's live cadence back as the target cadence.
- When **any other app** is playing (Spotify, Qobuz, audiobooks, ...):
  transport (play/pause/next/prev) and volume, like Garmin's built-in music
  app. Track title shown when the phone provides it (optional
  notification access on the phone); otherwise a generic "External app"
  label.

Music always stays on the phone; the watch never plays audio itself.

Note: this is different from `update_plans/garmin_app_plan.md`, which
describes playing music stored **on the watch**. The cadence-sampling
module design is shared between the two.

## Manifest permissions (Monkey C `manifest.xml`)

| Permission | Why |
|------------|-----|
| `Communications` | Send/receive messages with the phone companion (required). |
| `Sensor` | Accelerometer-based cadence sampling. |
| `FitContributor` | **No** — not needed; we are not a data field. |
| `Media` | **No** — music plays on the phone, not the watch. |

## App structure

```
garmin-watch/
├── manifest.xml
├── source/
│   ├── RunBeatCompanionApp.mc      (AppBase: lifecycle, ConnectIQ wiring)
│   ├── CadenceSampler.mc           (1 Hz sampling, Sensor/ActivityMonitor)
│   ├── CadenceRingBuffer.mc        (60-slot circular buffer + mean)
│   ├── MessageHandler.mc           (parse/build JSON messages)
│   └── view/
│       ├── MainView.mc             (play/pause, next/prev, track title)
│       ├── CadenceView.mc          (current 60s avg, target, +/- , "use current")
│       └── ControlView.mc          (speed-correction toggle, volume, mark bad)
├── resources/
│   ├── layouts.xml
│   ├── strings.xml
│   └── menus/ (per-view menus for hardware buttons)
└── monkey.jungle                 (device target matrix)
```

## Screens / interaction model

**Three screens** (confirmed), paged with `WatchUi.ViewLoop` (swipe to
change page; tap counts as select — the page's primary action; the menu
button opens the per-page menu):

1. **Main page** (transport — works for RunBeat and external apps)
   - Source label: "RunBeat" / external app name / "External app".
   - Current track title (truncated) + play/pause icon reflecting state.
   - Select: toggle play/pause. Menu: next, previous.
2. **Cadence page** (RunBeat only — greyed out when `source != "RunBeat"`)
   - Big number: live 60-second average cadence (spm).
   - Target cadence display (from phone state).
   - Select: use current as target (send `cadence_use_current` with the
     ring buffer mean). Menu entries:
     - `+` / `-`: send `cadence_delta` (+1 / -1).
     - `Use current as target`: send `cadence_use_current` with the ring
       buffer mean.
3. **Control page**
   - Speed correction toggle (RunBeat only; shows on/off from phone state).
   - Volume: `-` / `+` (send `volume_delta`; always STREAM_MUSIC).
   - `Mark as "not for running"` (RunBeat only; send
     `mark_not_for_running`; show the ack as a transient confirmation —
     the phone flags the track in the library and skips to the next one).

Fallback for devices without `WatchUi.ViewLoop` (API 3.4.0): a real
fr245 runs CIQ 3.3.1, so at runtime the app checks
`if (WatchUi has :ViewLoop)` in `RunBeatCompanionApp.mc` and otherwise
ships the main page only, with a fallback delegate (select/tap =
play/pause toggle, menu = next/previous). Play control stays usable on
that one model; the cadence and control pages are unreachable there.

Connection handling: when the phone app is unreachable, an outgoing
send fails and the `Communications.ConnectionListener`'s `onError()`
callback marks the phone unreachable; the watch then shows "Phone not
connected" (header override plus a transient confirmation) until the
next incoming message marks it reachable again. The Garmin Connect IQ
`Communications` API is the exact API surface: incoming messages via
`Communications.registerForPhoneAppMessages()` (`PhoneAppMessage.data`),
outgoing via `Communications.transmit(content, options, listener)`; see
Garmin docs "Communications — Phone Messages".

## Cadence sampling

- Timer fires every 1000 ms (`Toybox.Timer`).
- Primary source: `Toybox.Sensor` (accelerometer-based cadence; the
  `Sensor` permission is declared in `manifest.xml`). Each tick just
  reads `Sensor.getInfo().cadence` (`Sensor.Info.cadence`, spm), guarded
  by a `Toybox has :Sensor` check — deliberately no
  `Sensor.setEnabledSensors()` enable call and no onShow/onHide power
  management (consistent with the SDK AccelMag sample, which reads
  `Sensor.getInfo()` on a timer with only the `Sensor` permission).
- Last-resort fallback: delta of `ActivityMonitor.getInfo().steps`
  between samples, converted to steps/minute. Coarse — with a 1 s tick
  this quantizes cadence to multiples of 60 spm (120/180/240 ...), so it
  is only used when `Toybox.Sensor` is unavailable or returns no
  cadence. The step baseline is only used while fresh (previous tick at
  most 1.5 s old); a stale baseline just re-baselines and yields no
  sample. `Toybox.ActivityMonitor` is not permission-gated, so the
  fallback needs no permission beyond `Sensor`.
- Feed each sample into `CadenceRingBuffer` (60 slots). `mean()` ignores
  samples older than 60 s and treats 0 as "not running" (excluded unless
  all are 0 — then report "—" and refuse to set target).
- Battery: 1 Hz accelerometer sampling is negligible; no phone polling.

## Message handling

Implement the protocol from [architecture.md](architecture.md):

- Outgoing commands are small JSON strings (see table there). Build them
  in `MessageHandler`.
- Incoming `state` messages update a local `WatchState` object (`source`,
  `isPlaying`, `title`, cadence fields, `speedCorrection`, `volume`); each
  view invalidates (`WatchUi.requestUpdate()`) on change.
- Views read `WatchState.source` to grey out RunBeat-only controls
  (cadence actions, speed-correction toggle, mark not-for-running).
- The phone sends an ack for the five state-changing RunBeat commands —
  `cadence_set`, `cadence_delta`, `cadence_use_current`,
  `speed_correction_toggle`, `mark_not_for_running`
  (`GarminCompanionManager.needsAck()`). Transport and volume commands
  get no ack; the pushed `state` message is their confirmation. The
  watch shows the ack as a transient confirmation.

## Device matrix (`monkey.jungle`)

Primary targets: Forerunner 245 / 255 / 265 / 955 / 965, fenix 7 / 8,
epix (Gen 2), Venu 3 — 15 product ids in `monkey.jungle` /
`manifest.xml`. All support live cadence via `Sensor.getInfo().cadence`
(needs no permission beyond `Sensor`) and Connect IQ phone messages.
Round vs square layouts handled via `layouts.xml` per device family;
keep text short (watch screens are small).

## Build & install

1. Install Garmin Connect IQ SDK Manager; add SDK + device `.sim` files.
2. Build per device: `developer key` from Garmin (free), `monkeyc` compile
   + package into `.prg`.
3. Sideload for personal use: enable developer mode on the watch, copy the
   `.prg` to `GARMIN/APPS/`, or use Garmin Express sideloading.
4. For public distribution: submit to the Connect IQ Store (review required;
   see [implementation_plan.md](implementation_plan.md)).

## Testing

- Connect IQ **simulator** (per-device `.sim`): verify views, message
  encode/decode, cadence buffer math, connection-lost behavior.
- Real device: cadence accuracy vs the watch's own running app; message
  round-trip latency (expect well under 1 s when the phone screen is on,
  possibly queued/delayed when the phone is doze-ing — Garmin Connect
  Mobile handles delivery; document observed behavior).
- External-app mode: with Spotify/Qobuz/an audiobook app playing on the
  phone, verify transport + volume from the watch; verify the RunBeat-only
  controls appear disabled.
- Pair with the phone app's `garmin` flavor build for end-to-end tests.
