# Device Test Plan — Garmin Companion Watch App (manual, on-device)

Date: 2026-10-24, synced 2026-10-31. Start with the **Venu 2** (`venu2`, the developer's own
device). The compile matrix (16/16 `.prg`) is local verification only — this plan is the
real-hardware run. Simulator staging: [simulator_runbook_2026-10-29.md](simulator_runbook_2026-10-29.md).

## Artifacts
- **Watch app:** per-device `.prg` built from `garmin-watch/` (scripts in
  `/c/Users/guill/dev/tools/garmin-build/`), signed with the **local developer key** — sideload
  only, never valid for the Connect IQ Store.
- **Phone app:** garmin-flavor APK (`:app:assembleGarminDebug`, applicationId
  `com.RunBeat.run.audio`). The `fdroid` flavor has no Connect IQ SDK; the two flavors are not
  co-installable.

## Prerequisites
- Garmin Connect Mobile (GCM) installed/logged in; watch paired; Bluetooth on.
- RunBeat (garmin flavor) installed and opened at least once (initializes the Connect IQ SDK).
- LineageOS only: allow RunBeat **Network access** (Settings > Apps > RunBeat > Mobile data &
  Wi-Fi) — the per-app firewall otherwise blocks the SDK's simulator socket on port 7381.
- Watch in Developer Mode (Venu 2: Settings > System > Developer).

## Install (real watch)
`adb push venu2.prg /GARMIN/APPS/`, eject, then launch from **both** the **app menu** (direct,
no stock app) and Music → Music Providers → RunBeat (provider flow: stock Music app is the
host — brief flash on launch, extra back press; see architecture.md "Launch paths").

Simulator short version: phone over USB, `adb install -r app-garmin-debug.apk`,
`adb forward tcp:7381 tcp:7381`, Settings > GARMIN > "Use Connect IQ simulator (ADB)" ON (debug
builds only), simulator.exe with the venu2 profile, load `venu2.prg` (monkeydo), Connection >
Start. No GCM needed on this path. Full procedure: runbook.

## Test checklist
| # | Action | Expected result |
|---|--------|-----------------|
| 0 | Launch the app | Comes up connected, shows current source, no "Phone not connected", no crash |
| 1 | Play control while RunBeat plays | First screen shows artist/album/title + source "RunBeat"; tap toggles play/pause; next/prev change track |
| 2 | Manual target cadence ± | Cadence page shows live 60 s AVG and target; target changes in RunBeat (clamped 150–195) |
| 3 | "Use current cadence" after running 60+ s | Target becomes the 60 s average. Heuristic: an exact multiple of 60 spm suggests the step-delta fallback fired — cross-check with the watch's running app |
| 4 | Speed-correction toggle | Watch shows the state; RunBeat applies it |
| 5 | Volume up/down | First screen shows current volume; phone STREAM_MUSIC changes |
| 6 | Mark current track not-for-running | Track skipped immediately; flag stored; filter toggle ON by default → excluded next session |
| 7 | External-app mode (Spotify/Qobuz/audiobook) | With notification access: Main page shows the external app's current track name/artist/album (header "External app"); transport controls the external session (media-key fallback); volume works. Without notification access: "Nothing playing" |
| 8 | Phone unreachable | Disable BT / force-stop RunBeat → "Phone not connected"; recovers on its own |
| 9 | Navigation | 3 screens via vertical swipe (ViewLoop); tap = select; `M` = per-screen menu; horizontal swipe on page 1 = seek ±15 s. fr245 (CIQ 3.3.1) is Main-only |
| 10 | Ack behavior | `cadence_*`, `speed_correction_toggle`, `mark_not_for_running` produce acks; transport/volume/request_state/seek do not (next state push confirms). `cadence_set` and `play` are protocol-level, not reachable from the UI |
| 11 | Launch paths & battery | App-menu launch: direct, no stock app, back to watch face. Provider-flow launch: stock Music app hosts (flash + extra back — expected firmware behavior). 2-min idle with music paused → app goes inert (timers stopped, listener unregistered, `bye` sent) and exits itself. While paused the 1 Hz cadence tick performs no sensor read. App closed → phone sends NOTHING (no wake, no background service): verify no BLE traffic / no drain; app reconnects only when started manually |
| 12 | Battery | Full day with music playing part of it: back to multi-day idle drain (phone must not push at a closed app — 30 s window) |

## Device notes
- **venu2:** CIQ 5.0.0, round 416x416, ViewLoop OK (all 3 screens).
- **fr245:** CIQ 3.3.1 < 3.4.0 → MainView-only fallback; verifying optional.
- Any device: RunBeat-only controls grey out with a "RunBeat only" hint when source ≠ RunBeat.
- Simulator: cadence AVG reads 0/`--` (no sensor data) — counts as PASS.

## Recording results
One row per device run (P/F per checklist item). On a crash: note exact steps + watch-side logs
via Garmin Express device logs.

| Device | CIQ version | Firmware | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | Notes |
|--------|-------------|----------|---|---|---|---|---|---|---|---|---|---|----|----|----|-------|
| venu2  | 5.0.0       |          |   |   |   |   |   |   |   |   |   |   |    |    |    |       |
| fr245  | 3.3.1       |          |   |   |   |   |   |   |   |   |   |   |    |    |    |       |
