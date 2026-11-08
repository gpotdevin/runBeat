# RunBeat Data Field — Architecture

**Status: two-screen data field (battery fix). Docs synced 2026-11-08.**

A Connect IQ **data field** (`garmin-watch/`, manifest type `datafield`) that
remote-controls RunBeat during an activity. Runs ONLY during an activity —
firmware-managed lifecycle, zero battery cost outside a workout.

Wire protocol (unchanged, normative): [architecture.md](architecture.md).

## Screens

### Screen 1/2 — Track
- Header: connection status (Connecting... / Phone not connected / RunBeat / Nothing playing)
- Play state (Playing / Paused)
- Track title (big) + artist
- Bottom hint: `|<  >/||  >|`
- Tap zones: left 1/3 = previous, center = play/pause, right 1/3 = next
- Keys: SELECT/ENTER = play/pause, LAP = next

### Screen 2/2 — Controls (2x2 grid)
| Cell | Label | Color | Tap action |
|------|-------|-------|------------|
| Top-left | TARGET xxx | green = speed-corr ON, gray = OFF | toggle speed correction |
| Top-right | AVG/60s xxx | blue | set 60 s avg as target cadence |
| Bottom-left | NO GOOD RUN | red | mark current track not-for-running |
| Bottom-right | connection status | gray | (no action) |

Switch screens: UP/DOWN key or vertical swipe.

No live cadence display — the stock running app already shows it.

## Input mapping

| Input | Screen 1 (Track) | Screen 2 (Controls) |
|-------|-----------------|---------------------|
| SELECT / ENTER | play/pause toggle | — |
| LAP | next track | — |
| Tap left 1/3 | previous track | — |
| Tap center | play/pause | — |
| Tap right 1/3 | next track | — |
| Tap top-left cell | — | toggle speed correction |
| Tap top-right cell | — | set avg as target |
| Tap bottom-left cell | — | mark not-for-running |
| UP / DOWN key | switch screen | switch screen |
| Swipe up/down | switch screen | switch screen |

## Watch components

| Component | Role |
|-----------|------|
| `RunBeatDataFieldApp` (extends `Application.App`) | Manifest entry; returns field + delegate |
| `RunBeatDataField` (extends `WatchUi.DataField`) | `onUpdate()` at 1 Hz (system-driven): samples cadence, writes FIT, draws current screen. `onShow/onHide`: register/unregister phone listener. `onTimerStart/Resume/Lap/Stop/Pause`: activity lifecycle |
| `RunBeatDataFieldDelegate` (extends `WatchUi.BehaviorDelegate`) | Routes keys, taps, swipes to the field |
| `MessageHandler` | JSON protocol codec (unchanged); `.equals()` for strings |
| `WatchState` | Phone state holder (unchanged) |
| `CadenceRingBuffer` | 60 s rolling cadence average (unchanged) |

## FIT contribution

`FitContributor` permission: writes `cadence` (spm) and `target_cadence` (spm)
to the FIT activity file every 30 s — visible in Garmin Connect after the run.

## Lifecycle

```
Activity start -> onShow() -> registerForPhoneAppMessages + request_state
     |
onUpdate() @ 1 Hz -> sample cadence, write FIT, draw current screen
     |
Activity pause -> onTimerPause() -> bye (phone drops push window)
Activity resume -> onTimerResume() -> request_state (re-arm)
LAP -> onTimerLap() -> next
Activity stop -> onTimerStop() -> bye + unregister + save FIT
     |
onHide() -> bye + unregister
```

Phone side (`GarminCompanionManager`) unchanged: 30 s push-window gating handles
activity-scoped watch correctly.
