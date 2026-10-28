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

## Verification status (agent C, 2026-10-25)

Automated verification of the plan-v2 artifacts (details in
[implementation_plan_v2_2026-10-24.md](implementation_plan_v2_2026-10-24.md),
section 9):

- **Watch artifacts:** 16/16 signed `.prg` in `$GB/out-v2/` — the
  device list matches `garmin-watch/manifest.xml` `<iq:products>`
  exactly (`fr245 fr255 fr265 fr955 fr965 fenix7 fenix7s fenix7x
  fenix8solar47mm fenix8solar51mm fenix843mm fenix847mm epix2 venu2
  venu3 venu3s`), every build log ends with `BUILD SUCCESSFUL`, all
  `.prg` files are >100 KB (signed), and a venu2 rebuild is
  byte-identical (reproducible).
- **Phone tests:** `:app:testGarminDebugUnitTest` green — 214 tests,
  0 failures, 0 errors (forced re-run 2026-10-25 07:47 local),
  including the wire-format contract tests
  (`encodeCommandMatchesWatchWireFormat`,
  `decodesWatchExampleStateMessage`,
  `requestState_isAnsweredWithOneStatePushToThatDevice`,
  `currentState_includesArtistAlbumDurationPositionWhenActive`,
  `seek_routesToPlayerRepositorySeekToWhenActive`).
- **Phone APK:** `app/build/outputs/apk/garmin/debug/app-garmin-debug.apk`
  built 2026-10-25 07:33 (24,490,380 bytes) and current with the sources
  (`:app:assembleGarminDebug` UP-TO-DATE).
- **Wire-format contract:** PASS — watch `MessageHandler.mc` and phone
  `GarminMessageCodec.kt` / `GarminCommandRouter.kt` /
  `GarminCompanionManager.kt` match plan v2 section 3 exactly.

**Simulator-run readiness:** the simulator e2e (plan v2 section 6.3) is
the remaining verification step — **pending the user and a connected
phone**. At verification time the tether could NOT be staged: the
Fairphone 6 (adb serial `fe113fb3`) was not connected (`adb devices`
empty; all of the phone's USB devices — the Google VID_18D1 ADB
interfaces and the Fairphone MTP entries, serial `fe113fb3` — show
*Disconnected* in the Windows PnP enumeration), so `adb install -r` and
`adb forward tcp:7381 tcp:7381` were NOT run (both fail with "no
devices/emulators found"). Before the simulator session: connect the
phone over USB (USB debugging on), then run

    adb install -r app/build/outputs/apk/garmin/debug/app-garmin-debug.apk
    adb forward tcp:7381 tcp:7381

and continue with the "End-to-end testing" steps in the next section
(do not forget Settings > GARMIN > "Use Connect IQ simulator (ADB)" ON
on the phone).

Note: checklist rows 1 and 5 below were written for the pre-v2 UI; in
plan v2 the transport row (prev / play-pause / next) and the volume row
live on the first screen (with artist/album/title), so interpret row 1's
tap-to-toggle and row 5's volume display against the v2 first screen.

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
4. On the phone, allow RunBeat to use the network: Android Settings >
   Apps > RunBeat > Mobile data & Wi-Fi (data usage) > **Network
   access** ON. On LineageOS this per-app firewall toggle maps to a
   netpolicy REJECT_ALL for the app UID; while it is OFF the Connect
   IQ SDK's AdbConnection cannot create its server socket on port
   7381 (bind fails with ECONNREFUSED) and — before the pre-flight
   guard in GarminCompanionManager — the SDK cleanup path crashed the
   whole app process, which is what made the simulator report "no data
   connection". The app now probes the bind first and stays WIRELESS
   instead of crashing, but the simulator path needs the toggle ON.
5. Forward the simulator port: `adb forward tcp:7381 tcp:7381`.
6. In the simulator, use the **Connection** menu > **Start** (shortcut
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
- LineageOS only: RunBeat needs **Network access** enabled (Settings >
  Apps > RunBeat > Mobile data & Wi-Fi > Network access) — without it
  the per-app firewall blocks the Connect IQ SDK's simulator socket
  (see "Simulator testing (PC)" above).
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
