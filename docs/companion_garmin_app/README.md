# Garmin Data Fields — RunBeat for activities

Two Connect IQ **data fields** (`garmin-watch/`) that remote-control the RunBeat
Android app from the wrist during a run. Music plays on the **phone**.

A data field runs only during an activity — firmware-managed lifecycle, zero
battery cost outside a workout (this replaced the old audio-content-provider
companion app that drained the battery).

- [datafield.md](datafield.md) — watch-side architecture, screens, input
- [architecture.md](architecture.md) — normative wire protocol (codec/router tables)

## The two fields

| Field | Shows | Tap actions |
|-------|-------|-------------|
| **RunBeat Track** | play state, transport `\|< >/\|\| >\|` | left=prev, center=play/pause, right=next |
| **RunBeat Controls** | title + artist/album (yellow=favorite, marquee scroll), `[-] num [+]`, avg/60s, NO GOOD RUN | title=favorite toggle, -/+ cadence, num=speed-corr toggle, avg=set as target, mark=not-for-running |

Add each to a separate full-screen data screen: Run → Data Screens → Screen 1/2.

## Build

Watch: Connect IQ SDK, one .prg per field (manifest XSD allows one app per file):
`manifest-track.xml` / `manifest-controls.xml`. Phone: `./gradlew :app:assembleGarminRelease`.

## Where things live

- Watch: `garmin-watch/` — `RunBeatFields.mc` (both fields + shared base), `MessageHandler.mc`, `WatchState.mc`, `CadenceRingBuffer.mc`
- Phone: `app/src/main/java/.../garmin/` (codec, router — SDK-free) and `app/src/garmin/java/.../` (`GarminCompanionManager` — SDK wiring, registers both IQ app UUIDs)
