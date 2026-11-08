# Garmin Data Field — RunBeat activity companion

**Status: converted to data field (battery fix). Docs synced 2026-11-08.**

A Garmin Connect IQ **data field** (`garmin-watch/`, Monkey C) that shows live
cadence and the current RunBeat track during an activity, and remote-controls
the RunBeat Android app from the wrist. Music always plays on the **phone**,
never on the watch.

**Why a data field:** the previous audio-content-provider companion app drained
the watch battery even when closed (persistent process + BLE wakeups). A data
field runs ONLY during an activity — the firmware owns its lifecycle — so it
costs zero battery outside a workout. Details: [datafield.md](datafield.md).

Wire protocol (normative): [architecture.md](architecture.md). Data field
architecture: [datafield.md](datafield.md).

## Features

| # | Feature | How |
|---|---------|-----|
| 1 | Play / pause toggle | SELECT/ENTER key -> `toggle` command |
| 2 | Next track | LAP key -> `next` command |
| 3 | Target cadence +/-1 | UP/DOWN keys -> `cadence_delta` |
| 4 | Live cadence display | Watch sensor, 1 Hz via system-driven `onUpdate()` |
| 5 | 60 s cadence average | `CadenceRingBuffer`, displayed on the field |
| 6 | Track info | Phone pushes `state` -> title/artist shown on the field |
| 7 | FIT recording | Cadence + target cadence written to the FIT activity file |

Removed from the companion app (not applicable to data fields): volume control,
shuffle toggle, speed-correction toggle, mark-not-for-running, playlists,
multi-page UI, menus.

## Confirmed decisions

- **Manifest type `datafield`** (converted 2026-11-08 from
  `audio-content-provider-app`): runs only during activities, firmware-managed
  lifecycle, no background execution possible — this is the battery fix.
- **Permissions:** `Communications` + `Sensor` + `FitContributor` (no
  `Background` — data fields cannot run in the background by design).
- **Product list:** all Connect IQ devices (plain fr245/fr255 now included —
  data fields have no music-capable restriction).
- **Phone side unchanged:** `GarminCompanionManager`, `GarminCommandRouter`,
  `GarminMessageCodec` work as-is; the existing 30 s push-window gating handles
  the activity-scoped watch correctly.
- **Cadence is measured on the watch** (wrist sensor). Only the display uses it;
  the phone receives cadence commands only on explicit key press.
- **Distribution:** sideload `.prg` with the local developer key (sideload only).

## Where things live

- Watch data field: `garmin-watch/` — Connect IQ SDK, IQ app UUID
  `06c14376-0c60-4e4c-a977-e93364ace3eb`, manifest type `datafield`
- Phone Garmin layer: `app/src/main/java/com/bpmapp/audio/audio/garmin/` (SDK-free:
  codec, router) and `app/src/garmin/java/...` (SDK wiring: `GarminCompanionManager`)
- Build scripts, developer key, per-device `.prg` outputs: outside the repo at
  `/c/Users/guill/dev/tools/garmin-build/`
