# Data field architecture

Two data field apps, each one full-screen rectangle on one activity screen.
Only the visible screen's field runs at a time — one process.

## Screen 1 — RunBeat Track (`RunBeatTrackField`)
```
 Playing              <- connection color
 |<    >/||    >|     <- tap zones, connection color
```
Tap left/center/right = prev / play-pause / next.

## Screen 2 — RunBeat Controls (`RunBeatControlsField`)
```
 Song Title           <- yellow if favorite, marquee scrolls on round screens
 Artist - Album
   -    172    +       <- tap: -1 / toggle speed-corr (green=ON) / +1
  168    NO GOOD RUN   <- tap: set avg as target (blue) / mark not-for-running (red)
```
Tap title/artist = favorite toggle. Three sections centered as a group.

## Watch components

| File | Role |
|------|------|
| `RunBeatFields.mc` | `RunBeatFieldBase` (shared: Communications, cadence sampling via `Activity.getActivityInfo().currentCadence`, conn color) + 2 field subclasses + 2 app entry classes + shared delegate |
| `MessageHandler.mc` | JSON codec; injects `apiVersion` into every outgoing message; parses state/ack/playlists |
| `WatchState.mc` | Latest phone state + `phoneApiVersion` |
| `CadenceRingBuffer.mc` | 60 s rolling cadence average |

## Lifecycle

```
onShow  -> registerForPhoneAppMessages + request_state
onUpdate @1Hz (system-driven) -> sample cadence, draw
onTimerStart/Resume -> request_state
onTimerLap -> no-op (auto km splits fire this too — do NOT skip track)
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
# per field, one .prg each:
monkeybrains -f <jungle pointing to manifest-{track,controls}.xml> -d venu2 \
  -y developer_key.der --override-devices-json devices-local -o venu2-{track,controls}.prg
```
