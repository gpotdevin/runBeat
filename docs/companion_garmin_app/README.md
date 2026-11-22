# Garmin Data Fields — RunBeat for activities

One Connect IQ **data field** (`garmin-watch/`) that remote-controls the RunBeat
Android app from the wrist during a run. Music plays on the **phone**.

A data field runs only during an activity — firmware-managed lifecycle, zero
battery cost outside a workout (this replaced the old audio-content-provider
companion app that drained the battery).

- [datafield.md](datafield.md) — watch-side architecture, screens, input
- [architecture.md](architecture.md) — normative wire protocol (codec/router tables)

## The data field

| Field | Shows | Tap actions |
|-------|-------|-------------|
| **RunBeat Controls** | title + artist/album (yellow=favorite, marquee scroll), `[-] num [+]`, avg/60s, NO GOOD RUN | title=favorite toggle, -/+ cadence, num=speed-corr toggle, avg=set as target, mark=not-for-running |

Add it to a full-screen data screen: Run → Data Screens → Screen 1.

Input: touch devices tap the zones directly; button-only watches (fr245/245m)
use UP/DOWN to move a focus outline and ENTER/START to activate it — both paths
share one dispatch, see [datafield.md](datafield.md#input).

## Build

Watch: Connect IQ SDK, one .prg (manifest XSD allows one app per file):
`manifest-controls.xml`. Phone: `./gradlew :app:assembleGarminRelease`.

## Where things live

- Watch: `garmin-watch/` — `RunBeatFields.mc` (controls field + shared base), `MessageHandler.mc`, `WatchState.mc`, `CadenceRingBuffer.mc`
- Phone: `app/src/main/java/.../garmin/` (codec, router — SDK-free) and `app/src/garmin/java/.../` (`GarminCompanionManager` — SDK wiring, registers the IQ app UUID)
