# RunBeat Data Field — Architecture

**Status: converted from audio-content-provider companion app to data field (battery fix).**

The Garmin watch side is now a Connect IQ **data field** (`garmin-watch/`, manifest
type `datafield`) instead of an `audio-content-provider-app`. A data field runs
ONLY during an activity — the watch firmware owns its lifecycle — so it can
never run in the background or keep the BLE radio awake outside a workout.
This structurally fixes the battery drain that persisted despite the extensive
mitigations in the old companion app (adaptive ticks, idle exit, listener
unregistration, push-window gating).

Wire protocol (unchanged, normative): [architecture.md](architecture.md).

## System overview

```
+------------------+  Connect IQ messages via Garmin Connect Mobile (BLE)  +-----------------------+
|  Garmin watch    |  -- commands (toggle, cadence_delta, next) ----------->  |  RunBeat (phone)      |
|  Data field      |  <-- state pushes (only while activity runs) ---------  |  GarminCompanionManager|
|  (Monkey C)      |                                                       |         |             |
|  - runs only     |                                                       |  GarminCommandRouter -+-> PlayerRepository    |
|    during an     |                                                       |                       |                       |
|    activity      |                                                       |                       |                       |
|  - 1 Hz onUpdate |                                                       |                       |                       |
|  - 60 s ring buf |                                                       |                       |                       |
+------------------+                                                       +-----------------------+
```

## Why a data field fixes the battery drain

| Old companion app | Data field |
|---|---|
| Persistent app process — can linger and stay BLE-wakeable after "close" (known CIQ bug: System.exit() is a no-op, unregistered callback keeps process alive) | Runs only during an activity; firmware tears it down when the activity stops — zero cost outside a workout |
| Own timers (1 Hz cadence + 5 s state tick) waking the CPU even when idle | No own timers — the system calls `onUpdate()` at the configured rate (1 Hz) |
| Manual idle-exit, visibility tracking, listener unregistration hacks | None needed — the system owns the lifecycle |
| 20 s request_state keepalive + stale-retry BLE traffic while open | request_state sent on activity start/resume; the phone's push window expires naturally when the activity ends |
| Phone pushes state ~1/5 s while playing (BLE wakeups) | Same push rate, but only during an activity — bounded by workout duration |

## Watch components

| Component | Role |
|-----------|------|
| `RunBeatDataFieldApp` (extends `Application.App`) | Manifest entry point; `getInitialView()` returns the data field + delegate |
| `RunBeatDataField` (extends `WatchUi.DataField`) | The field itself: `onUpdate(dc)` draws at 1 Hz (system-driven), samples cadence, writes FIT fields; `onShow/onHide` register/unregister the phone-message listener; `onTimerStart/Resume/Lap/Stop/Pause` handle activity lifecycle |
| `RunBeatDataFieldDelegate` (extends `WatchUi.BehaviorDelegate`) | Input: SELECT/ENTER = play/pause toggle, UP = cadence +1, DOWN = cadence -1, LAP = next track |
| `MessageHandler` | Parse/build the JSON protocol (unchanged from companion app); all string comparisons use `.equals()` |
| `WatchState` | Latest phone state holder (unchanged) |
| `CadenceRingBuffer` | 60-slot circular buffer for the 60 s cadence average (unchanged) |

## Input mapping

| Key | Action | Phone command |
|-----|--------|---------------|
| SELECT / ENTER | Play/pause toggle | `toggle` |
| UP | Target cadence +1 | `cadence_delta {delta:1}` |
| DOWN | Target cadence -1 | `cadence_delta {delta:-1}` |
| LAP | Next track | `next` |

## Display

Single screen, drawn in `onUpdate()`:
- **Header**: connection status (Connecting... / Phone not connected / RunBeat / Nothing playing)
- **Wide screens (>=240 px)**: left column = live cadence (big) + 60 s avg; right column = target cadence + track title/artist
- **Narrow screens (<240 px)**: stacked vertically — live cadence (big), avg + target on one line, track title

## FIT contribution

The data field declares `FitContributor` permission and writes two custom
fields to the activity's FIT file (saved every 30 s):
- `cadence` (spm) — live cadence from the watch sensor
- `target_cadence` (spm) — the RunBeat target cadence

These appear in Garmin Connect after the run.

## Lifecycle

```
Activity start -> onShow() -> registerForPhoneAppMessages + request_state
     |
onUpdate() @ 1 Hz -> sample cadence, write FIT, draw
     |
Activity pause -> onTimerPause() -> send bye (phone drops push window)
Activity resume -> onTimerResume() -> request_state (re-arm push window)
LAP -> onTimerLap() -> send next
Activity stop -> onTimerStop() -> send bye + unregister listener + save FIT
     |
onHide() -> send bye + unregister (belt-and-suspenders)
```

The phone side (`GarminCompanionManager`) is unchanged: the existing push-window
gating (30 s per device, armed by request_state, dropped by bye) already handles
"watch active only during activity" correctly.

## Build

Same as before: Garmin Connect IQ SDK (VS Code extension or Eclipse), `monkeyc`
compile + package produces one `.prg` per device. See `garmin-watch/monkey.jungle`.
Data fields run on all Connect IQ devices (no music-capable restriction — the
product list now includes plain fr245/fr255).
