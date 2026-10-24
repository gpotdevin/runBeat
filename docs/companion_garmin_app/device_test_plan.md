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
  `com.RunBeat.run.audio` (same applicationId as the fdroid flavor: the
  garmin build is the same app WITH the Connect IQ integration, so the
  two flavors are not co-installable — installing the garmin APK
  replaces the fdroid build, and if the signatures differ Android
  requires uninstalling the other build first). Only this flavor
  contains the Connect IQ Mobile SDK; the `fdroid` flavor cannot talk
  to the watch.

## Simulator testing (PC)

The watch app can also be tested on a PC with the **Connect IQ
simulator**, without a real watch and without Garmin Connect Mobile.
This path uses the IQ Mobile SDK's BLE-simulation-over-ADB transport
(the `TETHERED` connect type — the SDK has no separate "simulator"
connect type; its `AdbConnection` talks to local port 7381).

Setup:

1. Install the Connect IQ SDK (the SDK whose Mobile SDK the garmin
   flavor depends on) and launch `<ConnectIQ SDK>/bin/simulator.exe`.
2. Select the **venu2** device profile (the primary target device of
   `garmin-watch/`).
3. Load the watch app: drag `venu2.prg` onto the simulator's watch
   window, or run `monkeydo venu2.prg venu2` from the SDK's `bin/`
   directory.

Standalone testing (no phone attached): the app comes up and the UI
can be exercised — the 3-screen swipe navigation, the menus, and the
"Phone not connected" state shown while nothing answers on the ADB
port.

End-to-end testing (simulated watch <-> phone):

1. Connect the phone over USB and enable USB debugging.
2. Install the **garmin-flavor debug APK** (`:app:assembleGarminDebug`).
   The fdroid flavor cannot talk to the simulator (it has no Connect IQ
   SDK).
3. In RunBeat, open Settings > GARMIN and turn ON **"Use Connect IQ
   simulator (ADB)"** (debug builds only — keep it OFF for a real
   watch).
4. Forward the simulator port: `adb forward tcp:7381 tcp:7381`.
5. In the simulator, use the **Connection** menu > **Start** (shortcut
   CTRL-F1) to begin the BLE simulation.

No Garmin Connect Mobile is needed on this path — the phone talks to
the simulator directly over ADB. Most of the test checklist below also
applies to a simulator run.

## Prerequisites

- Garmin Connect Mobile (GCM) installed and logged in on the phone —
  the IQ Mobile SDK WIRELESS connection goes through GCM.
- Watch paired and connected to the phone via GCM; Bluetooth on.
- RunBeat (garmin flavor) installed and **opened at least once** — this
  initializes the Connect IQ SDK on the phone.
- Watch in Developer Mode for sideloading: on Venu 2 (single physical
  button) enable Settings > System > Developer; the hold-the-LAP/back
  button method applies to button devices, not to Venu 2.

## Install

1. Connect the watch over USB.
2. `adb push <device>.prg /GARMIN/APPS/` (e.g. `adb push venu2.prg /GARMIN/APPS/`).
3. Eject the watch, then launch the app from the watch app list.

## Pairing / first run

The watch app should come up connected — it shows the current source.
Shipped display states are "RunBeat" and "Nothing playing" (the phone
pushes source "RunBeat" or "none"); the "External app" label is reserved
for the optional, not-implemented ExternalPlaybackMonitor. If it shows
"Phone not connected", check GCM/Bluetooth and reopen RunBeat on the
phone.

## Test checklist

| # | Action | Expected result |
|---|--------|-----------------|
| 0 | Launch the app | Comes up connected: shows the current source, no "Phone not connected", no crash |
| 1 | Play control while RunBeat is playing | Main page shows the track title + source "RunBeat"; tap toggles play/pause on the phone; open the MainView menu, select Previous (menu item id `menu_prev` in garmin-watch/resources/menus/MainViewMenu.xml): playback moves to the previous track |
| 2 | Manual target cadence +/- | Cadence page: the live AVG cadence and the target cadence are displayed; the target cadence changes in RunBeat (clamped 150-195) |
| 3 | "Use current cadence" while actually running for 60+ seconds | The target becomes the 60 s average. EXPECTED: the Cadence page AVG matches the live cadence of the watch's built-in running app while running (per the Testing section of [watch_app_plan.md](watch_app_plan.md)); the app's own AVG display is computed from the same CadenceRingBuffer, so it cannot be used to cross-check itself. HEURISTIC INDICATOR ONLY: a value that is an exact multiple of 60 spm (120/180/240 ...) suggests the silent step-delta fallback fired (open review item "minor 4") — but a mean of multiples of 60 need not be a multiple of 60, and a genuine 180 spm average is possible. Record the device + CIQ version |
| 4 | Speed-correction toggle | The watch shows the state; RunBeat applies it |
| 5 | Volume up/down | Control page: the current volume value is displayed and the phone's media volume changes |
| 6 | Mark current track not-for-running | The track is skipped immediately; the flag is stored in the RunBeat library; the not-for-running filter toggle is ON by default, so the track is excluded from the next session |
| 7 | External-app mode | With Spotify/Qobuz/an audiobook app playing: the watch shows "Nothing playing" (the optional ExternalPlaybackMonitor is not shipped, so the phone reports source "none"); watch play/pause/skip/previous still controls the external app via media keys; volume up/down works. The "External app" label would require the optional notification listener, which is not implemented |
| 8 | Phone-unreachable handling | Disable Bluetooth or force-stop RunBeat: the watch shows "Phone not connected"; restore — it recovers on its own |
| 9 | Navigation | 3 screens via swipe (ViewLoop); tap = select; the menu button opens the per-screen menu. On Venu 2 all three screens must be reachable (CIQ 5.0 >= 3.4.0); the MainView-only fallback is fr245-specific at CIQ 3.3.1 |
| 10 | Ack behavior | `cadence_set`, `cadence_delta`, `cadence_use_current`, `speed_correction_toggle` and `mark_not_for_running` produce acks on the watch; play and volume commands do not. Note: `cadence_set` and `play` are protocol-level commands not reachable from the current watch UI — the watch sends `cadence_delta` and `cadence_use_current` (no `cadence_set`) and a play/pause toggle (no separate `play`); a failed ack (ok=false) surfaces the failure string on the watch |

## Device notes

- **venu2 (Venu 2):** CIQ 5.0.0, round 416x416 AMOLED canvas, ViewLoop OK
  (all three screens reachable); launcher icon 70x70 with the 48px
  source upscaled — accepted for a sideloaded personal app.
- **fr245:** CIQ 3.3.1 < 3.4.0 — takes the MainView-only fallback path
  (main-page transport only; the cadence and control pages are
  unreachable on that model). Verifying this device is optional.
- **Any device:** RunBeat-only controls (cadence actions, speed-correction
  toggle, mark not-for-running) are greyed out with the "RunBeat only"
  hint when the source is not RunBeat.

## Recording results

One row per device run (P/F per checklist item):

| Device | CIQ version | Firmware | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | Notes |
|--------|-------------|----------|---|---|---|---|---|---|---|---|---|---|----|-------|
| venu2  | 5.0.0       |          |   |   |   |   |   |   |   |   |   |   |    |       |
| fr245  | 3.3.1       |          |   |   |   |   |   |   |   |   |   |   |    |       |

If the app crashes: reproduce, note the exact steps, and collect the
watch-side logs via Garmin Express device logs.
