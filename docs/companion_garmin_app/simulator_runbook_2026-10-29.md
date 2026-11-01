# Simulator runbook — Connect IQ simulator (ADB tether), 2026-10-29

**Read this before any simulator session.** Verified procedure + division of labor between
user and agent. Code-level gotchas: [architecture.md](architecture.md) "Connect IQ simulator".

## 0. Who does what (binding rules)
1. **The simulator is never launched or handled directly by the user.** Starting `simulator.exe`,
   attaching the `.prg` (monkeydo), pinning the window, simulator menu commands (Connection >
   Start, Kill App, Background Events) and the adb plumbing (`adb forward`, pref writes, app
   restarts) are **agent work**, via background processes and helper scripts.
2. **All interaction with the watch app under test is performed by the user.** The agent does NOT
   read screenshots. It gives **clear, numbered test instructions** (tap/press/swipe + what to
   observe); the user reports what they see. The agent verifies side effects phone-side only, via
   `adb logcat` / `dumpsys` (`Garmin watch connected`, `Watch command ...`, `Wrote N bytes`,
   `dumpsys media_session`).

Session shape: agent stages everything (simulator up, app loaded, tether up, logcat running) →
"Test 1: on the watch, tap the center transport button. Tell me the header line and whether
playback paused." → user reports → agent verifies in logcat → next numbered step.

## 1. Toolchain paths (this machine)
| Item | Path |
|---|---|
| Connect IQ SDK 9.3.0 | `C:/Users/guill/AppData/Roaming/Garmin/ConnectIQ/Sdks/connectiq-sdk-win-9.3.0-2026-10-20-95888f262` (copy under `/c/Users/guill/dev/tools/Garmin/ConnectIQ/Sdks/`) |
| Simulator / monkeydo | `<SDK>/bin/simulator.exe`; `<SDK>/bin/monkeybrains.jar`, main class `com.garmin.monkeybrains.monkeydodeux.MonkeyDoDeux` |
| Java 17 | `/c/Users/guill/.jdks/jbr-17.0.14/bin/java` |
| adb (NOT on system PATH) | `/c/Users/guill/dev/tools/android-sdk/platform-tools/adb` |
| Watch build script | `bash /c/Users/guill/dev/tools/garmin-build/compile_one.sh <device> /c/Users/guill/dev/runBeat-wt/integration/garmin-watch <outDir>` |
| Helper scripts (agent) | `/c/Users/guill/dev/tools/garmin-build/sim-win.ps1` (list/pin/shot/cmd/click/key/focus/dismiss), `uia-dump.ps1` |
| Latest watch build at writing | `/c/Users/guill/dev/tools/garmin-build/out-battery-fix/venu2.prg`; per-session repro in `repro-<date>/` |
| Phones | FP6 `fe113fb3` (real-watch phone), FP4 `ae6e36e1` (old sim phone) |

Git Bash gotchas: unquoted remote adb paths (`/sdcard/...`) are MSYS-converted — use
`MSYS_NO_PATHCONV=1` or avoid absolute remote paths. `cmd /c ...` from Git Bash drops to an
interactive prompt — use `powershell -NoProfile -Command`.

## 2. Staging procedure (agent performs, in order)
1. **Phone attached**: `adb devices` lists the phone (e.g. `fe113fb3`).
2. **adb forward**: `adb -s <serial> forward tcp:7381 tcp:7381`, verify `adb forward --list`.
   A phone disconnect / adb server restart CLEARS the forward — always re-issue after any disconnect.
3. **Phone app in simulator mode**: install the **garmin-flavor debug** APK
   (`:app:assembleGarminDebug`, applicationId `com.RunBeat.run.audio`), start once, set
   `ciq_simulator_adb_mode=true` (Settings > GARMIN > "Use Connect IQ simulator (ADB)", debug
   builds only). CLI alternative: `adb push` prefs to `/data/local/tmp/`,
   `adb shell run-as com.RunBeat.run.audio cp /data/local/tmp/AppSettings.xml /data/user/0/com.RunBeat.run.audio/shared_prefs/AppSettings.xml`,
   then `am force-stop` + `am start -n com.RunBeat.run.audio/com.bpmapp.audio.MainActivity`.
   Verify: `adb shell 'grep -i 1CD5 /proc/net/tcp6'` shows LISTEN (`0A`) on port 7381 (hex
   `1CD5`) owned by the app UID (phone `ss` segfaults — use `/proc/net/tcp(6)`).
4. **Start the simulator WITH adb ON ITS PATH** — critical. It shells out to bare `adb` for its
   device/forward check; without it, Connection > Start raises a **modal** "no data connection"
   dialog that blocks ALL simulator input. `setx /M PATH` is denied (non-elevated). Working
   recipe (background process):
   ```
   powershell -NoProfile -Command '$env:PATH = "C:\\Users\\guill\\dev\\tools\\android-sdk\\platform-tools;" + $env:PATH; & "C:\\Users\\guill\\AppData\\Roaming\\Garmin\\ConnectIQ\\Sdks\\connectiq-sdk-win-9.3.0-2026-10-20-95888f262\\bin\\simulator.exe"'
   ```
5. **Pin the simulator window** (its ini drifts off-screen, e.g. `WindowX=2063`):
   `sim-win.ps1 list` → main hwnd (title `CIQ Simulator - Venu® 2 (5.0.0)` once the profile
   loaded) → `sim-win.ps1 pin <hwnd> 100 50 460 820`.
6. **Load the watch app** (monkeydo, background process):
   ```
   APPDATA=/c/Users/guill/dev/tools /c/Users/guill/.jdks/jbr-17.0.14/bin/java \
     -classpath "<SDK>/bin/monkeybrains.jar" \
     com.garmin.monkeybrains.monkeydodeux.MonkeyDoDeux \
     -f <outDir>/venu2.prg -d venu2 -s "<SDK>/bin/shell" > monkeydo.log 2>&1
   ```
   Runtime errors (e.g. `Class not available to 'Background'`) appear in this log.
7. **Activate the tether**: `sim-win.ps1 cmd <hwnd> 6033` (PostMessage `WM_COMMAND` wParam=6033 =
   menu `adb Connection > Start`). SendKeys Ctrl+F1 does NOT work. Dismiss any modal Error dialog
   first (`sim-win.ps1 dismiss <dialogHwnd>`). Menu ids: Start=6033, Release=6034, Kill App=6005,
   Background Events=6131, Phone App Message=6135.
8. **Verify the link** (phone side only): filtered logcat
   (`adb logcat -s GarminCompanionManager:V GarminCommandRouter:V MediaKeyDispatcher:V PlayerRepository:V ConnectIQ-AdbConnection:V AndroidRuntime:E *:S`)
   → `Simulator connected` then `Garmin watch connected`; `grep -i 1CD5 /proc/net/tcp6` shows
   ESTABLISHED (`01`) on 127.0.0.1:7381. While playing: `Wrote N bytes` ~1/s, `Received 48` every 5 s.
9. **Start a logcat capture before the test**, keep it running.

## 3. Simulator input model (user drives these)
- Click anywhere on the watch face = SELECT (page's primary action; on MainView: play/pause).
- `M` = MENU (per-screen resource menu). Menu nav = mouse swipe up/down; confirm = click/Enter.
- Vertical swipe = ViewLoop page cycling (3 pages, wrap). Horizontal swipe on page 1 = seek ±15 s.
- Cadence AVG reads 0/`--` in the simulator (no sensor data) — expected, counts as PASS.
- The simulator cannot exercise the watch's native Music app "Phone" source — real hardware only.

## 4. Known environment quirks (verified 2026-10-25/26/29)
- Watch→phone in TETHERED mode needs the phone-side empty-UUID listener (`IQApp("")`), already
  in the garmin flavor (`GarminCompanionManager`).
- The bridge (simulator TCP through the forward) can drop on its own (`End of input reached` →
  `Garmin watch disconnected`); recovery: re-issue the forward, then `cmd <hwnd> 6033` again.
- The modal "no data connection" dialog also appears ~2 min after app load if the bridge is
  down (the watch's `request_state` arrives then); dismiss with focus + Enter.
- Killing simulator or monkeydo drops the bridge; the phone app recovers when the simulator
  reconnects. Re-pin the window after device load.

## 5. Verified with this runbook (2026-10-29)
- The `Class not available to 'Background'` crash reproduced in the simulator at app load with
  `out-battery-fix/venu2.prg`: the app class runs in the background heap (manifest `Background`
  permission) and `initialize()`/`onStart()` constructed non-`(:background)` classes. Fix:
  `(:background)` on `RunBeatCompanionApp` + lazy `ensureForegroundStarted()` from the foreground
  entry points, null-guarded `onStop()`. After the fix: clean load; Background Events (6131) /
  Phone App Message (6135) no longer crash.
- Full tether verified end-to-end: `Simulator connected`, `Garmin watch connected`, state `Wrote`
  pushes. (2026-10-31 update: `wake` messages and the `Background` permission/service were
  removed afterwards — the phone no longer wakes a closed watch app; see architecture.md
  "Battery & lifecycle".)
