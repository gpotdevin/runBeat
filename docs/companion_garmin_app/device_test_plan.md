# Device Test Plan — Garmin Companion Watch App (manual, on-device)

Date: 2026-10-24

## Purpose

Verify the watch companion on **real hardware**. The compile matrix in
[implementation_plan.md](implementation_plan.md) (Status section) is
LOCAL verification only — it proves the app builds for a device, not
that it behaves correctly on it. This plan is a manual end-to-end test
run. Start with **Venu 2** (`venu2`, the developer's own device), then
reuse it for any other target device.

## Artifacts

- **Watch app:** the per-device `.prg` built from `garmin-watch/` (see
  the build instructions in
  [implementation_plan.md](implementation_plan.md)). It is signed with
  the LOCAL developer key (`developer_key.der`) — **sideload only**,
  never valid for the Connect IQ store.
- **Phone app:** the **garmin** flavor APK — Gradle task
  `:app:assembleGarminDebug`, applicationId
  `com.RunBeat.run.audio.garmin`. Only this flavor contains the Connect
  IQ Mobile SDK; the `fdroid` flavor cannot talk to the watch.

## Prerequisites

- Garmin Connect Mobile (GCM) installed and logged in on the phone —
  the IQ Mobile SDK WIRELESS connection goes through GCM.
- Watch paired and connected to the phone via GCM; Bluetooth on.
- RunBeat (garmin flavor) installed and **opened at least once** — this
  initializes the Connect IQ SDK on the phone.
- Watch in Developer Mode for sideloading: hold the LAP/back button on
  the watch, or Settings > System > Developer on newer firmware.

## Install

1. Connect the watch over USB.
2. `adb push <device>.prg /GARMIN/APPS/` (e.g. `adb push venu2.prg /GARMIN/APPS/`).
3. Eject the watch, then launch the app from the watch app list.

## Pairing / first run

The watch app should come up connected — it shows the current source
("RunBeat" / external app / "Nothing playing"). If it shows "Phone not
connected", check GCM/Bluetooth and reopen RunBeat on the phone.

## Test checklist

| # | Action | Expected result |
|---|--------|-----------------|
| 0 | Launch the app | Comes up connected: shows the current source, no "Phone not connected", no crash |
| 1 | Play control while RunBeat is playing | Main page shows the track title + source "RunBeat"; tap toggles play/pause on the phone |
| 2 | Manual target cadence +/- | Cadence page: the target cadence changes in RunBeat (clamped 150-195) |
| 3 | "Use current cadence" while actually running for 60+ seconds | The target becomes the 60 s average. EXPECTED: matches the watch's own live cadence. RED FLAG: the value is an exact multiple of 60 spm — the silent step-delta fallback fired (open review item "minor 4"); record the device + CIQ version |
| 4 | Speed-correction toggle | The watch shows the state; RunBeat applies it |
| 5 | Volume up/down | Control page: the phone's media volume changes |
| 6 | Mark current track not-for-running | The track is skipped immediately; the flag is stored in the RunBeat library; the not-for-running filter toggle is ON by default, so the track is excluded from the next session |
| 7 | External-app mode | With Spotify/Qobuz/an audiobook app playing: the watch shows the external source; watch play/pause/skip controls the external app; volume works |
| 8 | Phone-unreachable handling | Disable Bluetooth or force-stop RunBeat: the watch shows "Phone not connected"; restore — it recovers on its own |
| 9 | Navigation | 3 screens via swipe (ViewLoop); tap = select; the menu button opens the per-screen menu. On Venu 2 all three screens must be reachable (CIQ 5.0 >= 3.4.0); the MainView-only fallback is fr245-specific at CIQ 3.3.1 |
| 10 | Ack behavior | `cadence_set`, `cadence_delta`, `cadence_use_current`, `speed_correction_toggle` and `mark_not_for_running` produce acks on the watch; play and volume commands do not |

## Device notes

- **venu2 (Venu 2):** CIQ 5.0.0, round 416x416 AMOLED canvas, ViewLoop OK
  (all three screens reachable); launcher icon 70x70 with the 48px
  source upscaled — accepted for a sideloaded personal app.
- **fr245:** CIQ 3.3.1 < 3.4.0 — MainView-only fallback: play control
  works, the cadence/control pages are unreachable on that model.

## Recording results

One row per device run (P/F per checklist item):

| Device | CIQ version | Firmware | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | Notes |
|--------|-------------|----------|---|---|---|---|---|---|---|---|---|---|----|-------|
| venu2  | 5.0.0       |          |   |   |   |   |   |   |   |   |   |   |    |       |

If the app crashes: reproduce, note the exact steps, and collect the
watch-side logs via Garmin Express device logs.
