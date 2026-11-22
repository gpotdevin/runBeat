# Data field architecture

One data field app, one full-screen rectangle on an activity screen.

## Screen 1 — RunBeat Controls (`RunBeatControlsField`)
```
 Song Title           <- yellow if favorite, marquee scrolls on round screens
 Artist - Album
   -    172    +       <- tap: -1 / toggle speed-corr (green=ON) / +1
  168    NO GOOD RUN   <- tap: set avg as target (blue) / mark not-for-running (red)
```
Tap title/artist = favorite toggle. Three sections centered as a group.

## Input

Two input paths, one shared dispatch (`activateZone(index)`, zones 0-5 in reading
order: title, `-`, num, `+`, avg, mark) so touch and buttons can never diverge.

| Device | Interaction |
|--------|-------------|
| Touch (venu, fenix, epix, ...) | Tap a zone directly (hit-test -> `activateZone`) |
| Buttons only (fr245, fr245m, ...) | `KEY_UP`/`KEY_DOWN` move a focus outline - a 2px white rectangle drawn around the focused zone in `draw()`, only when `System.getDeviceSettings().isTouchScreen == false` - wrapping across the six zones in visual reading order; `KEY_ENTER`/select activates the focused zone; `KEY_BACK` falls through to the system. Zones that are inactive (avg/mark while `source != "RunBeat"`) are skipped, mirroring the tap gating |

## Watch components

| File | Role |
|------|------|
| `RunBeatFields.mc` | `RunBeatFieldBase` (shared: Communications, cadence sampling via `Activity.getActivityInfo().currentCadence`, conn color) + 1 field subclass + 1 app entry class + shared delegate |
| `MessageHandler.mc` | JSON codec; injects `apiVersion` into every outgoing message; parses state/ack/playlists |
| `WatchState.mc` | Latest phone state + `phoneApiVersion` |
| `CadenceRingBuffer.mc` | 60 s rolling cadence average |

## Lifecycle

```
onShow  -> registerForPhoneAppMessages + request_state
onUpdate @1Hz (system-driven) -> sample cadence, draw
onTimerStart/Resume -> request_state
onTimerLap -> no-op (auto km/lap splits fire this too)
onTimerStop/Pause -> bye + unregister
onHide  -> bye + unregister
```

Data fields cannot use `Sensor.getInfo()` — use `Activity.getActivityInfo().currentCadence`.

## Protocol versioning

`API_VERSION = 1` (watch) / `PROTOCOL_API_VERSION = 1` (phone). Watch broadcasts
it in every message; phone logs it per device and includes its own in state/ack
pushes. Bump both on incompatible changes.

## Build

```
# one .prg for the controls field:
monkeybrains -f <jungle pointing to manifest-controls.xml> -d venu2 \
  -y developer_key.der --override-devices-json devices-local -o venu2-controls.prg
```
