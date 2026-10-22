# Watch App Plan — Connect IQ (Monkey C)

Date: 2026-10-22

This is a **separate project** from the Android Gradle build: a Connect IQ
app written in Monkey C, built with the Garmin Connect IQ SDK (VS Code
extension or Eclipse + SDK manager), producing a `.prg` per device family.
Suggested location in this repo: `garmin-watch/` (not part of
`settings.gradle`).

## Purpose

Remote-control the RunBeat **phone** app (music stays on the phone) and
feed the runner's live cadence back as the target cadence.

Note: this is different from `update_plans/garmin_app_plan.md`, which
describes playing music stored **on the watch**. The cadence-sampling
module design is shared between the two.

## Manifest permissions (Monkey C `manifest.xml`)

| Permission | Why |
|------------|-----|
| `Communications` | Send/receive messages with the phone companion (required). |
| `Sensor` | Accelerometer-based cadence sampling. |
| `ActivityMonitor` | Step count / live cadence (`Toybox.ActivityMonitor`). |
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

Three pages, navigable with up/down, actions with the select button
(and/or per-page menus for devices with more buttons):

1. **Main page**
   - Current track title (truncated) + play/pause icon reflecting state.
   - Select: toggle play/pause. Menu: next, previous.
2. **Cadence page**
   - Big number: live 60-second average cadence (spm).
   - Target cadence display (from phone state).
   - Select: nothing / menu entries:
     - `+` / `-`: send `cadence_delta` (+1 / -1).
     - `Use current as target`: send `cadence_use_current` with the ring
       buffer mean.
3. **Control page**
   - Speed correction toggle (shows on/off from phone state).
   - Volume: `-` / `+` (send `volume_delta`).
   - `Mark track as bad` (send `mark_bad`; show ack as a transient
     confirmation; optionally auto-advance handled phone-side).

Connection handling: on `onMessage` absence / `onAppOOM`... — use
`ConnectIQ.registerForAppEvents()` / listen for device status; when the
phone app is unreachable, show "Phone not connected" and let button
presses queue nothing (or show a toast). The Garmin Connect IQ
`Communications` API (`PhoneAppMessage` via
`Communications.registerForPhoneAppMessages()` +
`Communications.transmitAckMessage()`...) is the exact API surface; see
Garmin docs "Communications — Phone Messages".

## Cadence sampling

- Timer fires every 1000 ms (`Toybox.Timer`).
- Preferred: `ActivityMonitor.getLiveCadence()` (current cadence, spm) —
  available on Connect IQ API level 3.x+ on most running watches; guard
  with ` Toybox has :ActivityMonitor` checks and `method(:getLiveCadence)`
  existence where needed.
- Fallback: delta of `ActivityMonitor.getInfo().steps` between samples,
  converted to steps/minute.
- Feed each sample into `CadenceRingBuffer` (60 slots). `mean()` ignores
  samples older than 60 s and treats 0 as "not running" (excluded unless
  all are 0 — then report "—" and refuse to set target).
- Battery: 1 Hz accelerometer sampling is negligible; no phone polling.

## Message handling

Implement the protocol from [architecture.md](architecture.md):

- Outgoing commands are small JSON strings (see table there). Build them
  in `MessageHandler`.
- Incoming `state` messages update a local `WatchState` object; each view
  invalidates (`WatchUi.requestUpdate()`) on change.
- Send an ack expectation only for `mark_bad` and `cadence_use_current`
  (transient confirmation on the watch).

## Device matrix (`monkey.jungle`)

Primary targets: Forerunner 245 / 255 / 265 / 955 / 965, fenix 7 / 8,
epix (Gen 2), Venu 3. All support `ActivityMonitor` live cadence and
Connect IQ phone messages. Round vs square layouts handled via
`layouts.xml` per device family; keep text short (watch screens are small).

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
- Pair with the phone app's `garmin` flavor build for end-to-end tests.
