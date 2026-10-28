# RunBeat <-> Garmin watch — end-to-end connection test report (simulator run, venu2)

**Last updated: 2026-10-26 22:00 CEST (UTC+1) / 21:00 UTC — rev. 3** (rev. 2: 06:09; rev. 1: 2026-10-25 22:14). Rev. 3 records the 2026-10-26 evening session: the criterion-4 fix (SDK empty-UUID registration) applied and PROVEN, five transport commands verified end-to-end, the simulator input model solved, and the phone->watch direction shown to be broken in this simulator environment (never delivered, in any session) — see the rev. 3 section at the end.

Aggregation of the fleet agents' output (research-docs, device-recon, planner-e2e, phone-preflight, worktree-diff-review, e2e-sim-test) for the RunBeat Garmin-watch end-to-end connection test: RunBeat Android app (garmin flavor) on an attached phone, connected to the Garmin Connect IQ simulator running the Venu 2 watch app, over the ADB tether.

Scope note: this is a read-and-document report. The only write made for it is this file. All verification commands were read-only (`netstat`, `adb forward --list`, `adb shell pidof/dumpsys/cat /proc/net/tcp6`, `grep` on logs, `git log/status/diff`, file listings, `Get-Process`/`Get-CimInstance`). No input was sent to the simulator or the phone. The e2e-sim-test agent was **paused by the orchestrator at ~22:12 local**; the evidence directory may still be receiving writes from its logcat captures (both were still running at 22:14 — see section 6), but no further test actions were in flight when this report was written.

Timezone convention: phone logcat timestamps and evidence-file mtimes are local (CEST, UTC+1). Some evidence files (progress.md, rc-*.txt) carry internal `Z` (UTC) timestamps in their headers; those are converted to local below.

---

## 1. Purpose & verdict scorecard

Goal (orchestrator): connect the RunBeat Android app to the user's Garmin watch, tested end-to-end with an attached Android phone + the Garmin Connect IQ simulator. The 5 "connection proven" criteria and their status:

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | `ConnectIQ SDK ready` + `Garmin watch connected` in logcat | **PROVEN** (with a retention caveat, see note) | `Garmin watch connected` is in `phone-logcat.log` at **20:56:24.368** (line 1571, tag `GarminCompanionManager`, PID 14771) and again at **22:08:39.636** (line 7920) after the reconnect. `ConnectIQ SDK ready` is **not** in the two retained logcat captures (both captures started after the app process started — see note below); the `Log.i` exists in code (`GarminCompanionManager.kt:230`) and no `ConnectIQ init error` line appears either. Orchestrator verified it live. |
| 2 | App Settings > GARMIN shows "Connected" | **NOT VERIFIED** | Never checked. No UI dump of the Settings > GARMIN screen exists (`ui1.xml`–`ui7.xml` and the `A3-*-ui.xml` dumps contain no `GARMIN`/`Connected` strings). The strings exist (`app/src/main/res/values/strings.xml:315` `settings_garmin_watch_connected` = "Connected"); the section is flavor-gated and reads `GarminWatchConnection` (`SettingsScreen.kt`, `SettingsViewModel.kt`). |
| 3 | Watch shows live track with header 'RunBeat' | **NOT PROVEN (rev. 3)** — the watch never received any phone message in this environment (see rev. 3 §3); yesterday's PASS rested on screenshot review, contradicted by the re-ask-stream evidence. Needs the real watch. |
| 4 | >=1 watch->phone command round-trip (tap -> `Watch command from ...` -> dumpsys state flip) | **PROVEN (rev. 3, 2026-10-26 evening)** — root cause was the companion SDK 2.4.0 re-broadcasting tethered app messages with an empty app UUID; fixed by registering a second listener under `IQApp("")`. Verified end-to-end: `toggle` (x2, pause+resume), `next`, `prev`, `volume_delta` (x3) all decoded and applied by the phone with real playback effects. See rev. 3 §2. |
| 5 | Watch position caption advances ~1/s | **NOT PROVEN (rev. 3)** — watch-side rendering requires phone->watch delivery, which never works in this simulator (rev. 3 §3). Phone-side push cadence remains proven. Needs the real watch. |

Note on criterion 1 retention: `phone-logcat.log` contains two `logcat` captures (capture 1 started 18:39:35, capture 2 started 21:45:49 — see the `--------- beginning of main` markers at lines 256 and 6949). The app process (PID 14771) started ~14:32 (`A6-restart.txt`), so the process-start-time `ConnectIQ SDK ready` line predates both captures. Lines in the capture-1/capture-2 overlap window (~21:45:49–21:49:20) are duplicated (e.g. the `Received 41` events at 21:49 appear twice).

**Overall verdict: the phone<->simulator connection is PROVEN and was UP at the last snapshot (re-established 22:08:39; last retained log line 23:07:30 still shows state pushes flowing — see the rev. 2 note for post-snapshot drift); the phone->watch state flow is PROVEN; the watch->phone command flow is NOT YET PROVEN (zero command round-trips; tap calibration unsolved).**

---

## 2. What works / What does not work (yet)

### Works (PROVEN — evidence observed)

- **Phone app installed and healthy.** `com.RunBeat.run.audio` v1.8 / versionCode 46, garmin flavor, process PID 14771 alive at 22:14 (`adb -s ae6e36e1 shell pidof com.RunBeat.run.audio` → `14771`). Installed APK is byte-identical to the worktree build output: sha256 `e6a209a62076760441b00b57360cb376f39961d8a825ebb8660f709d7361add9` in both `A2-sha256-buildoutput.txt` (worktree `app/build/outputs/apk/garmin/debug/app-garmin-debug.apk`) and `A2-sha256-installed.txt` (pulled `installed.apk`, 24,591,084 bytes). Debug signer cert SHA-256 `2633d3ce6390db90b8e6e73a5a659d342353d00edb051ae06b80acd0e5d7039b` (`A2-apksigner-certs.txt`).
- **Simulator (TETHERED/ADB) mode enabled on the phone.** `A4-AppSettings.xml` / `A4-pref-check.txt`: `<boolean name="ciq_simulator_adb_mode" value="true" />`. INTERNET permission granted (`A3-internet-permission.txt`: `android.permission.INTERNET: granted=true`); no data-saver/battery-saver/netpolicy restriction (`A3-data-saver.txt`, `A3-netpolicy.txt`, `A3-netpolicy-appuid.txt` — UID 10281 not restricted).
- **ADB tether active.** `adb forward --list` at 22:14 → `ae6e36e1 tcp:7381 tcp:7381`.
- **TCP chain end-to-end (PC side).** `powershell netstat -an | Select-String 7381` at 22:14 → `127.0.0.1:7381 LISTENING` (adb server forward listener) **plus** an `ESTABLISHED` pair `127.0.0.1:7381 <-> 127.0.0.1:54578` (simulator connected through the forward).
- **TCP chain end-to-end (phone side).** `adb -s ae6e36e1 shell 'grep -i 1CD5 /proc/net/tcp6'` at 22:14 → one line, local `...FFFF00000100007F:1CD5` (127.0.0.1:7381), state `01` (ESTABLISHED), UID `10281` — the app's Connect IQ SDK tether socket is connected. (Port 7381 = hex `1CD5`.)
- **Watch<->simulator link established (twice).** `grep -nE 'Garmin watch connected|Garmin watch disconnected|Simulator connected' phone-logcat.log` → `Simulator connected` + `Garmin watch connected` at **20:56:24.364/.368** (lines 1570–1571), `Garmin watch disconnected` at **21:53:46.630** (line 7738, after `End of input reached` / `Client connection shut down`), and `Simulator connected` + `Garmin watch connected` again at **22:08:39.636** (lines 7919–7920).
- **Phone->watch state flow.** `grep -c 'Wrote' phone-logcat.log` → `Wrote 324/325 bytes to output stream` ~1/s: 3,040 unique timestamps in 20:56:24→21:53:46 (window 1) and 364 unique timestamps in 22:08:39→22:14:30 (window 2). (`grep -oE 'Wrote [0-9]+ bytes' | sort | uniq -c`: 2184×325, 1180×324, 126×323, 16×85, 14×320, 13×321, 1×305. The 16×85-byte writes mark track-loop points and connect — see section 9, item 16.)
- **Watch->phone link-layer traffic.** `grep -oE 'Received [0-9]+ from simulator' | sort | uniq -c` → `Received 48` ×853 lines (689 unique timestamps in window 1 ≈ every 5 s; 68 more in window 2) and `Received 41` ×6 lines (4 unique timestamps: 21:07:22.838, 21:07:24.025, 21:49:18.816, 21:49:20.584 — the 21:49 pair duplicated by the logcat restart). The 48-byte message is the watch's periodic `request_state`; the 41-byte messages decode silently (only `RequestState` is handled without a log line — `GarminCompanionManager.kt:265-272`).
- **Watch app runs on the simulator.** `sim-monkeydo5.log` → `RunBeat companion started` (20:45); simulator process alive at 22:14: PID 10344, hwnd 4132298, title `CIQ Simulator - Venu® 2 (5.0.0)`, Responding=True; monkeydo java PID 28824 (`Get-Process simulator` / `Get-CimInstance Win32_Process`).
- **Watch shows the live track under the 'RunBeat' header** (criterion 3): screenshots `sim-shot-46-connected.png` (20:56:53), `sim-shot-47-watch-playing.png` (21:03:15), `sim-shot-48-watch-track.png` (21:05:12) — per the orchestrator's review of the images; corroborated by the monkeydo log and the ~1/s state pushes. (This agent cannot view images — see section 10.)
- **Playback on the phone.** `adb -s ae6e36e1 shell dumpsys media_session` at 22:14 → session `com.RunBeat.run.audio/androidx.media3.session.id.AudioService/25`, `state=PLAYING(3)`, position=226056, duration=268119, speed=1.0238096, metadata `Summer Romance, Incubus, Science`. Same in `media_session.txt` (21:05) and `media_session_now.txt` (21:33). The track is on repeat and has looped multiple times.
- **Phone-side connect/disconnect ground truth.** The manager only logs on events (`GarminCompanionManager.kt:401/408`); `grep -E 'connected|disconnected' phone-logcat.log` yields exactly the 4 event lines listed above — no spurious flapping.

### Does not work (yet) (NOT YET PROVEN — no evidence yet)

- **Watch->phone command round-trip (criterion 4): zero.** `grep -c 'Watch command' phone-logcat.log phone-errors.log` → `0` and `0`. The logcat capture (PID 7848) filters `GarminCompanionManager:V PlayerRepository:V MediaKeyDispatcher:V GarminCommandRouter:V ConnectIQ-AdbConnection:V AndroidRuntime:E *:S`, so a decoded command would be captured. No tap has ever landed on a watch button (simulator tap calibration unsolved — the e2e agent spent ~50+ min on it; see section 5).
- **Settings > GARMIN "Connected" (criterion 2): never checked.** No UI dump of that screen exists; `grep -oiE 'GARMIN|Connected' ui*.xml A3-*-ui.xml` finds nothing.
- **Watch position caption advancing ~1/s (criterion 5, watch side): not screenshot-verified.** Only the phone-side push cadence (~1/s `Wrote 324/325 bytes`) is proven.
- **'ConnectIQ SDK ready' in the retained log: not present.** Both logcat captures postdate the app process start (capture 1 began 18:39:35, app PID 14771 started ~14:32 per `A6-restart.txt`). The line exists in code (`GarminCompanionManager.kt:230`, `Log.i(TAG, "ConnectIQ SDK ready")` in `onSdkReady`); the orchestrator observed it live. No `ConnectIQ init error` line appears in either capture (init succeeded).
- **Watch-side "Phone not connected" display during the outage (checklist item 8, watch side): not verified.** The outage 21:53:46→22:08:39 is proven phone-side (`Garmin watch disconnected` → `Waiting for simulator connection` → reconnect) and was caused by the phone being disconnected off USB/adb twice (21:53 and 22:03, user-reported — rev. 2 note); no screenshot of the watch during that window was captured (screenshots 65–69 are window-management probes, and their content is not machine-verifiable by this agent).

### Known-limited (simulator limitation, not an app bug)

- **Cadence AVG reads 0/'--' on the simulator** (checklist item 3): per `device_test_plan.md`, both outcomes count as PASS. The watch computes cadence from `Toybox.Sensor`/step-delta, which the simulator does not provide.
- **The simulator cannot exercise the watch's native Music app "Phone" source** (plan v2 §6.4 — real venu2 hardware required for acceptance criterion 7).
- **250% display DPI + custom-drawn wxWidgets canvas** make tap coordinate mapping the hard part: UIA rects are physical (`uia-tree.txt`: root rect `0,0,3500,3000` vs logical 1400x1200; `uia-tree2.txt`: `250,250 1538x2308`; `uia-deep.txt`: `250,200 2500x1750`), and the watch screen is drawn by the simulator, not exposed as UIA controls.
- **fr245 (CIQ 3.3.1) takes the MainView-only fallback** (no ViewLoop; cadence/control pages unreachable) — accepted, documented limitation; not the device under test (venu2, CIQ 5.0.0).
- **This agent cannot view images** (no image-viewing tool): screenshot descriptions rely on filenames, timestamps, and corroborating logs, plus the orchestrator's review.

---

## 3. System overview

### Components

| Component | What it is | Key facts |
|---|---|---|
| Phone app | RunBeat, **garmin** flavor (`:app:assembleGarminDebug`), applicationId `com.RunBeat.run.audio` | v1.8 / versionCode 46, debug-signed; companion layer: `GarminCompanionManager` (Hilt `@Singleton`, the only file importing `com.garmin.*`), `GarminCommandRouter`, `GarminMessageCodec`, `MediaKeyDispatcher`, `GarminWatchConnection` (SDK-free core in `app/src/main/.../audio/garmin/`, SDK wiring in `app/src/garmin/...`) |
| Watch app | Connect IQ (Monkey C) app in `garmin-watch/` (not part of `settings.gradle`) | UUID `06c14376-0c60-4e4c-a977-e93364ace3eb` (`garmin-watch/manifest.xml`); permissions Communications + Sensor only; 16 product ids; venu2 = round 416x416 AMOLED, CIQ 5.0.0, ViewLoop 3-screen path |
| Simulator | Garmin Connect IQ SDK 9.3.0 `bin/simulator.exe` — a wxWidgets app (UIA class `wxWindowNR`) with a **native Win32 menu** | Windows PID 10344, hwnd 4132298, title `CIQ Simulator - Venu® 2 (5.0.0)`; display at 250% DPI; Connection menu = top-level item 4 `&adb Connection` → `Start  Ctrl+F1` id=6033, `Release  Ctrl+F2` id=6034 (`menu-dump.txt`, `menu-dump-full.txt`) |
| monkeydo | `MonkeyDoDeux` java process loading the .prg into the simulator | PID 28824; cmdline: `java -classpath .../connectiq-sdk-win-9.3.0-2026-10-20-95888f262/bin/monkeybrains.jar com.garmin.monkeybrains.monkeydodeux.MonkeyDoDeux -f C:/Users/guill/dev/tools/garmin-build/out-v2/venu2.prg -d venu2 -s .../bin/shell` (`B-java-cmdline.txt`, `rc-11-after-monkeydo.txt`) |
| ADB bridge | `adb forward tcp:7381 tcp:7381` (adb server PID 16312, listening 127.0.0.1:5037) | The simulator itself shells out to bare `adb` for its device/forward check and builds `adb forward tcp:7381 tcp:7381`; it connects to localhost:7381 |

### Transport chain (proven)

```
PC 127.0.0.1:7381 (simulator)  ->  adb forward tcp:7381  ->  phone [::]:7381 (app UID 10281)
                                                                      |
                                              app SDK: 'ConnectIQ-AdbConnection: Received 48 from simulator'
                                              'Wrote 324/325 bytes to output stream' ~1/s (state pushes)
```

Phone-side socket proof: `/proc/net/tcp6` port `1CD5` (hex for 7381), UID 10281 — LISTEN (state `0A`) while the bridge is down, ESTABLISHED (state `01`) while up (verified both ways during this run).

### Wire protocol (normative contract: `architecture.md` "Message protocol" + plan v2 §3)

Watch -> phone commands (12 types, `GarminMessageCodec.kt:118-130`): `play`/`pause`/`toggle`, `next`, `prev`, `cadence_set {cadence}`, `cadence_delta {delta}`, `cadence_use_current {cadence}`, `speed_correction_toggle`, `volume_delta {delta}`, `mark_not_for_running`, `request_state`, `seek {positionMs}`.

Phone -> watch: `state` pushes (on change, on connect, and in answer to `request_state`) with `source` ("RunBeat"/"none"), `isPlaying`, `trackId`, `title`, `artist`, `album`, `durationMs`, `positionMs` (rounded down to whole seconds), `bpm`, `targetCadence`, `speedCorrection`, `playbackSpeed`, `volume`; plus `ack` messages. Acked commands: `cadence_set`, `cadence_delta`, `cadence_use_current`, `speed_correction_toggle`, `mark_not_for_running`. Not acked: transport, volume, `request_state`, `seek` (the following state push confirms them).

Cadence is measured **on the watch** (1 Hz `Toybox.Sensor` cadence, 60-sample ring buffer; step-delta fallback), and only the 60 s mean is sent (`cadence_use_current`).

### Key constants

| Item | Value |
|---|---|
| Main repo | `/c/Users/guill/dev/runBeat` — branch `main`, HEAD `c6598e5`, clean, **no Garmin code** (verified: no `garmin-watch/`, no `garmin` in `app/`, no `docs/companion_garmin_app/`) |
| Integration worktree | `/c/Users/guill/dev/runBeat-wt/integration` — branch `feature/garmin-companion`, HEAD `561b44b`, 43 commits ahead of `main` (merge-base `c6598e5` = main HEAD), **not pushed** (only `origin/main` exists), plus 22 uncommitted files (19 modified + 3 untracked) |
| Phone | Fairphone FP4, Android 15, USB debugging on, adb serial `ae6e36e1` |
| App | `com.RunBeat.run.audio` v1.8 / versionCode 46, garmin flavor; UID 10281; PID 14771; launcher activity `com.RunBeat.run.audio/com.bpmapp.audio.MainActivity` |
| APK | worktree `app/build/outputs/apk/garmin/debug/app-garmin-debug.apk` — 24,591,084 bytes, built 2026-10-25 12:10, sha256 `e6a209a62076760441b00b57360cb376f39961d8a825ebb8660f709d7361add9` = installed APK (byte-identical). Signer cert SHA-256 `2633d3ce6390db90b8e6e73a5a659d342353d00edb051ae06b80acd0e5d7039b` |
| Watch app UUID | `06c14376-0c60-4e4c-a977-e93364ace3eb` |
| Watch .prg (correct) | `/c/Users/guill/dev/tools/garmin-build/out-v2/venu2.prg` — sha256 `77cd828d836226cba12bfe1e34cea1aa37b4024a792e31083eb9a97c727543a0` (verified on disk) — the one monkeydo loads. Older copies: `test-venu2/venu2.prg` = `8e38497a...`, `out-sim/venu2.prg` = `01b67f15...`, `out-repo/venu2.prg` = `5885ec48...` — all superseded |
| Developer key | `/c/Users/guill/dev/tools/garmin-build/developer_key.der` — RSA-4096 PKCS#8 DER, sha256 `266c6a56f17e549e0994d8f0f15ee0b14d98d4c6317483695ddbb14463f304ce` (verified on disk). **Sideload only — NOT valid for the Connect IQ Store** |
| Port | 7381 (hex `1CD5` in `/proc/net/tcp(6)`) |
| Simulator | Windows PID 10344, hwnd 4132298, `CIQ Simulator - Venu® 2 (5.0.0)`; monkeydo java PID 28824; adb server PID 16312 |
| Connect IQ SDK | 9.3.0 (`connectiq-sdk-win-9.3.0-2026-10-20-95888f262`) at both `C:/Users/guill/AppData/Roaming/Garmin/ConnectIQ/Sdks/` and `/c/Users/guill/dev/tools/Garmin/ConnectIQ/Sdks/` |
| adb | `/c/Users/guill/dev/tools/android-sdk/platform-tools/adb` (also at `%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe` — **not** on the system PATH; see section 5) |
| Evidence dir | `/c/Users/guill/dev/tools/garmin-build/test-venu2/sim-run-20261025T132724Z/` (141 files at 22:14) |
| Playback test track | Incubus "Summer Romance" (album Science), 4:28 = 268,119 ms, PLAYING(3), speed 1.0238096, on repeat |
| Checklist | `/c/Users/guill/dev/tools/garmin-build/test-venu2/device_test_plan.md` (pre-run copy) and `docs/companion_garmin_app/device_test_plan.md` (worktree copy, extended with the agent-C verification section) |

---

## 4. The integration work (branch `feature/garmin-companion`)

### 4.1 Branch / commit summary

43 commits ahead of `main` (merge-base = `c6598e5` = current main HEAD). Grouped by theme:

| Theme | Commits | Content |
|---|---|---|
| Planning & docs foundation | `25c6758`, `be5efac` (2) | Garmin companion feasibility evaluation + implementation plans; confirmed decisions (Connect IQ transport, mark-and-skip, sideload-first distribution, in-repo flavors, 3-screen watch UI, filter default ON, external-app control) |
| Phase 1 — phone foundation: "not for running" flag | `7f4cec7`, `df6fdd2`, `b35c3a7`, `278360c`, merge `813e0dd` (5) | `Track.notForRunning` Room column + migration 6→7, DAO/repository methods, play-queue filtering behind a settings toggle (default ON), badge/unmark UI, mode-beep ducking guard (multiplier-based), unit tests |
| Phase 2a — flavors + SDK-free companion core | `90efe45`, merge `8d0c3a5` (2) | `fdroid`/`garmin` product flavors (dimension `companion`); SDK-free core in the main source set (`GarminMessageCodec`, `CadenceAverager`, `MediaKeyDispatcher`, `CompanionInitializer` + no-op, `GarminConnectionState`) with unit tests — fdroid compiles without the SDK |
| Phase 3 — watch app (Monkey C) | `7a33442`, `1d5ec06`, `2ff9ba0`, `bbe20f2`, `47f1ad3`, `69057c6`, merge `6951660`, `ff0a1a8` (8) | `garmin-watch/` project (CadenceSampler/CadenceRingBuffer/MessageHandler/3 views); manifest v3 migration (SDK 9.x); launcherIcon drawable; ActivityMonitor permission removal (not a valid permission id); 15 real Garmin product ids; SDK 9.3.0 compile fixes (`using`→`import`, `Toybox.App`→`Toybox.Application`, `.payload`→`.data`, `onLayout`/`setLayout(Rez.Layouts.X(dc))`, `Rez.Menus`, `MenuItem`→`Symbol`, 2-arg `setColor`, `SwitchableView`→`ViewLoop` + fr245 fallback, `COLOR_LT_GRAY`/`COLOR_DK_GRAY`); stale-title/comment fixes; venu2 target + device test plan |
| Watch review fixes | `0fd0eed`, `cf62123`, merge `5bb0311`, merge `f370bc2`, `df08f66`, merge `fc7c43a` (6) | M3: cadence from `Toybox.Sensor` (not the non-existent `getLiveCadence`); M8/M9/I2: onTap support, placeholder app id removed, dead senders pruned; M1: NaN/Infinity guard in codec + `setTargetCadence` |
| Phase 2b — companion manager + command router | `e708ad2`, merge `69937f0`, `0f72ec8`, merge `6ce93f4`, `232f05b`, merge `6ad7b14`, merge `86dbfe1`, `40ee8ef`, merge `ccd98a8` (9) | `GarminCompanionManager` (Connect IQ init, app registration by UUID, message receive/decode, state push ~1 Hz + acks), `GarminCommandRouter` (RunBeat vs media-key routing, `currentState()`); review fixes M2/M4/M5/M6/M7/I1/I5: process-scope lifecycle, registration retry, single-track (add-to-upcoming) filter, ack filter, suspend `handleCommand`, title omission, non-finite cadence guard; N1/N2: command serialization (Mutex), connection recompute on device refresh; extra tests (MetadataMerger/SettingsViewModel/TrackRepository) |
| Docs alignment | `47fc236`, `e5805ce`, merge `8658eae`, `a2506b1`, `db2759c`, `a8373ca`, `b7b78e1`, merge `2a63d81` (8) | N3/N4 doc fixes (companion wiring, cadence select, omitted fields), cadence-source docs + manifest UUID comment, external-app source docs aligned with shipped behavior (no ExternalPlaybackMonitor), device test plan pre-test audit fixes, docs synced with the shipped watch app + build/review outcome |
| Release prep / plan v2 | `e83cf0f`, `13bf2b9`, `561b44b` (3) | Build number 46; drop applicationId suffix (both flavors = `com.RunBeat.run.audio`); debug-only Settings toggle "Use Connect IQ simulator (ADB)" + simulator test docs |

Review outcome (independent review, `review_watch_app.md`): **merge-ready** — 0 blockers, 0 majors, 4 minors (stale track title, misleading comment, doc drift, cadence real-device verification), 5 nits; all actionable findings addressed in later commits.

Plan-v2 verification (agent C, 2026-10-25, `implementation_plan_v2_2026-10-24.md` §9): wire-format contract cross-check PASS (watch `MessageHandler.mc` vs phone codec/router/manager match §3 exactly); 16/16 signed `.prg` in `out-v2/` matching the manifest product list, reproducible (venu2 rebuild byte-identical); `:app:testGarminDebugUnitTest` green — **214 tests, 0 failures** (forced re-run 07:47 local); APK built and current. Phone tether was BLOCKED at that time (the Fairphone 6 `fe113fb3` was not connected) — the live e2e was the remaining step, which is what this run executed (on a Fairphone FP4, serial `ae6e36e1`).

### 4.2 The 22 uncommitted files (19 modified + 3 untracked, `git status --short`)

`git diff --stat`: 19 tracked files changed, **+1226 / −85**. Verified coherent/commit-safe by the worktree-diff-review agent (orchestrator report). Grouped by area:

| Area | Files | What the uncommitted work adds |
|---|---|---|
| Phone — garmin source set (`app/src/garmin/`) | `GarminCompanionManager.kt` (M, +94), `GarminCompanionModule.kt` (M, +18), `AndroidManifest.xml` (**new, untracked**) | Simulator (TETHERED/ADB) mode: `simulatorAdbMode` preference selects `ConnectIQ.IQConnectType.TETHERED` vs `WIRELESS`; `tetherPortBindable()` pre-flight `ServerSocket(AdbConnection.DEFAULT_PORT)` probe so a failed bind (e.g. OS firewall) falls back to WIRELESS instead of crashing the process; serialized `sendScope` (single-threaded dispatcher) for SDK sends; init-error log hint. New manifest: garmin flavor only, adds `android.permission.INTERNET` (the SDK's `AdbConnection` opens a server socket on 7381 in simulator mode; the fdroid flavor keeps the no-network guarantee) |
| Phone — SDK-free core (`app/src/main/.../audio/garmin/`) | `GarminCommandRouter.kt` (M, +56), `GarminMessageCodec.kt` (M, +63) | `seek` command routing (`playerRepository.seekTo` when RunBeat active, ack ok=false otherwise); `RequestState` + `Seek` commands; `artist`/`album`/`durationMs`/`positionMs` in the `state` message |
| Phone — main | `AudioService.kt` (M, +3), `res/values/strings.xml` (M, +2) | Notification content text = album (native Music app "Phone" source consistency); `settings_garmin_simulator_adb_desc` string |
| Phone — tests | `GarminCommandRouterTest.kt` (M, +125), `GarminMessageCodecTest.kt` (M, +109), `testGarmin/.../GarminCompanionManagerCommandSerializationTest.kt` (M, +108) | seek/request_state/artist-album round-trip tests; command-serialization test |
| Watch app (`garmin-watch/`) | `source/view/MainView.mc` (M, +362), `source/RunBeatCompanionApp.mc` (M, +100), `source/WatchState.mc` (M, +52), `source/MessageHandler.mc` (M, +51), `source/view/BaseView.mc` (M, +15), `resources/menus/MainViewMenu.xml` (M, +2), `resources/strings.xml` (M, +2) | Plan-v2 first-screen redesign: drawn transport row [PREV][PLAY/PAUSE][NEXT] + volume row [VOL-]"VOL n"[VOL+] with tap hit-testing, progress bar with m:ss caption, adaptive to screen size (218–454 px, round+square); `request_state` sent on app start and re-sent every 5 s while stale; `WatchState` artist/album/durationMs/positionMs + `isStale()`; `MessageHandler.sendRequestState()`/`sendSeek()`; BaseView "Connecting…" header + stale handling; volume menu items; strings |
| Docs (`docs/companion_garmin_app/`) | `README.md` (M, +46), `architecture.md` (M, +34), `device_test_plan.md` (M, +69), `implementation_plan_v2_2026-10-24.md` (**new, untracked**, 45,225 B), `requirements_update_2026-10-24.md` (**new, untracked**, 5,887 B) | Plan-v2 docs: requirements update (music-source control, first-screen transport, protocol extension, acceptance criteria) and the normative implementation plan v2; README/architecture/device-test-plan updated with the verification status and the LineageOS per-app network-access note |

### 4.3 Flavors, dependencies, signing

- `app/build.gradle`: `flavorDimensions += 'companion'`; `fdroid` (default, no Garmin SDK) and `garmin`; `garminImplementation 'com.garmin.connectiq:ciq-companion-app-sdk:2.4.0'` (Maven Central, already declared).
- Both flavors share applicationId `com.RunBeat.run.audio` (no suffix since `13bf2b9`) → **not co-installable**; the garmin build replaces the fdroid build (same debug signature, so it update-installs).
- The garmin flavor adds the `INTERNET` permission (untracked `app/src/garmin/AndroidManifest.xml`) — a deliberate reversal of the repo's no-network stance, needed only for the simulator tether socket; the fdroid flavor is unaffected.
- APK: debug-signed (cert above). Watch `.prg`: signed with the local sideload key (`developer_key.der`, RSA-4096) — **not valid for the IQ Store**.
- Build state on disk (verified): APK 24,591,084 bytes built 2026-10-25 12:10, sha256 `e6a209a6...` = installed APK. Note: plan v2 §9.4 records "24,490,380 bytes, built 07:33" — **stale**; the APK was rebuilt at 12:10 (including the uncommitted changes) and is the one installed. The 214-test green run (07:47) also **predates** the uncommitted changes.
- Watch artifacts: `out-v2/` holds 16 signed `.prg` (136,636–146,236 bytes) + build logs + `summary.txt` (16/16 `exit=0`, `BUILD_LOOP_DONE`) — matches `garmin-watch/manifest.xml` `<iq:products>` exactly.

---

## 5. E2E execution log

### Phase A — phone preflight (14:27–14:33 local) — PHONE READY 8/8

| Time (local) | Step | Result (evidence) |
|---|---|---|
| 14:27:28–29 | Uninstall stale `com.RunBeat.run.audio.garmin` (v45); list packages | `A1-uninstall-stale.txt` → `Success`; `A1-pm-list-packages.txt` → only `package:com.RunBeat.run.audio` |
| 14:27:40–45 | Version, path, pull APK, sha256, signer certs | `A2-version.txt` → versionCode=46, versionName=1.8, minSdk=26 targetSdk=36; `A2-pull.txt` → 24,591,084 bytes pulled; `A2-sha256-buildoutput.txt` == `A2-sha256-installed.txt` → `e6a209a6...` (byte-identical); `A2-apksigner-certs.txt` → cert SHA-256 `2633d3ce...d7039b` |
| 14:27:58–59 | UID, data saver, INTERNET | `A3-app-uid.txt` → appId=10281; `A3-data-saver.txt` → null; `A3-internet-permission.txt` → INTERNET granted=true |
| 14:28:08–14:30:20 | Netpolicy + settings-page UI dumps/screenshots | `A3-netpolicy*.txt` → no background/power restriction, UID 10281 not restricted; `A3-*-ui.xml` + `A3-*.png` → app-details/battery/network pages |
| 14:32:40 | Simulator preference + adb forward | `A4-AppSettings.xml` / `A4-pref-check.txt` → `ciq_simulator_adb_mode=true`; `A5-forward.txt` (empty output) + `A5-forward-list.txt` → `ae6e36e1 tcp:7381 tcp:7381` |
| 14:32:57 | App restart with resolved launcher activity | `A6-restart.txt` → force-stop, then start with `com.RunBeat.run.audio/com.bpmapp.audio.MainActivity` (the literal `-n com.RunBeat.run.audio/.MainActivity` fails with `Error type 3`); new PID **14771**; `A6-phone-home.png` |
| 14:33:12 | Final verification sweep | `FINAL-verification.txt` (20261025T133312Z): device attached; only `com.RunBeat.run.audio`; 46/1.8; INTERNET granted; pref true; forward active; no data/battery saver; PID 14771; focus `com.bpmapp.audio.MainActivity`; screen awake → **PHONE READY 8/8** |

### Phase B — simulator bring-up (14:37–22:10 local) — root-cause saga

| Time (local) | Event (evidence) |
|---|---|
| 14:37:47 | Simulator #1 started (`sim-console.log`, 0 bytes). monkeydo attached 14:52:44 (`sim-monkeydo.log`, 0 bytes) |
| 14:48:59–14:57:19 | `sim-shot-02-venu2.png` / `03-after-monkeydo.png` / `04-fresh.png` — venu2 profile loaded, app started |
| 14:54:47–14:59:34 | `sim-dialog-error.png`, `sim-dialog-screen.png`, `sim-dialog-printwindow.png`, `sim-dialog-full.png` — the modal **Error** dialog captured early |
| 15:22:04 | `hung-state-evidence.txt` — `IsHungAppWindow` True (simulator frame + Ghost); Error dialog `#32770` (DS_MODALFRAME); watch display clipped off-screen; `sim-shot-05/06-hung-state.png`; `sim-dialog-error-capture.png` |
| 15:23:32 | `sim-monkeydo2.log` → `RunBeat companion started` |
| 18:31–18:33 | Resume after orchestrator interruption (`progress.md`, 18:31:39Z): simulator PID 38824 verified **ALIVE** (not hung; the "dead simulator" report was stale); forward re-issued; app 14771 alive |
| 19:31–19:33 | `B-app-alive.txt`, `B-stale-sim-check.txt` (sim 38824 + java 14708), `B-forward-reissue.txt`, `B-java-cmdline.txt` (monkeydo cmdline, out-v2/venu2.prg), `B-sim-state.txt` (PID 38824 hwnd 5835412, responding, no dialogs), `sim-shot-10-state.png` (19:33:30) |
| **19:34:28** | **`B6-connect-start.txt` — SendKeys CTRL-F1 sent.** It DID reach the simulator: Connection > Start was invoked, the ADB check failed, and a **modal Error dialog** ("There is no data connection. Please connect an Android device to ADB. Run the command \"adb forward tcp:7381 tcp:7381\" …") blocked **all** simulator input from 19:34 onward. `sim-shot-11-after-connect.png` (19:34:39) |
| 19:34–19:56 | Verification attempts: `B7-verify.txt` (logcat had only PlayerRepository lines), `B7b-after-connect.txt` (tag counts + banner pixel analysis), `B7c-tags.txt` (no Garmin/ConnectIQ lines), `B-hover.txt` + `sim-shot-hover-*.png`, `B-resize-wide.txt` (SetWindowPos 1400x1200) |
| 20:15:01 | `gt-01.txt` — ground truth while bridge down: netstat shows only `127.0.0.1:7381 LISTENING`; forward active; pidof 14771; phone `ss` **segfaults** (use `/proc/net/tcp(6)`) |
| 20:24:02 | `uia-tree.txt` (UTF-16) — UIA tree reveals the modal Error dialog and its full text |
| 20:26:31–20:30:05 | `gt-02-dialog-check.txt`, `sim-shot-31-dialog.png`, `gt-03-windowat.txt` (WindowFromPoint grid — Firefox in the foreground), `sim-shot-32-after-front.png` |
| **20:33:47** | **`rc-01-rootcause.txt` — ROOT CAUSE (orchestrator-verified): `adb` was NOT in the system PATH** (`where adb` → nothing; `adb.exe` exists at `tools/android-sdk/platform-tools` and `%LOCALAPPDATA%/Android/Sdk/platform-tools`). The simulator shells out to bare `adb` for its device/forward check, so Connection > Start failed the ADB check and raised the modal dialog. The real blocker all along was the modal dialog, not SendKeys. Doc reference: SDK `doc/docs/Core_Topics/Communicating_with_Mobile_Apps.html`, section "BLE Simulation Over Android Debug Bridge" (`sdk-doc-grep.txt`) |
| 20:34:09 | `rc-02-strings-kill.txt` — SDK `bin/` listing; UTF-16 strings scan of simulator.exe for adb/7381/forward; TASKKILL `/F` quoting failure; java cmdlines |
| 20:35:15 | `rc-03-killed.txt` — old simulator + monkeydo killed; device, forward, and app verified intact |
| 20:35:35 | `rc-04-pathlen.txt` — PATH lengths (machine 501, user 217, combined 719) |
| **20:35:38** | **FIX APPLIED — fresh simulator started with `PATH="/c/Users/guill/dev/tools/android-sdk/platform-tools:$PATH"` prepended** (`sim-console4.log`) |
| 20:36:04 / 20:37:14 | `rc-05-setx.txt` / `rc-05b-setx.txt` — `setx /M PATH` persistence **FAILED** (registry access denied, non-elevated). Do not retry setx; the PATH-prefix start is the fix |
| 20:36:19 / 20:37:23 / 20:37:56 | `rc-06-freshwin.txt` / `rc-07-win2.txt` / `rc-08-pin.txt` — fresh sim PID 10344 hwnd 4132298, title `CIQ Simulator`, rect `2063,-1171 2413x-471` (off-screen ini drift); netstat: only LISTENING (bridge down); re-pinned via SetWindowPos → `100,100 450x800` |
| 20:38:00–20:45:31 | Window/z-order investigation: `sim-shot-40-fresh.png`, `rc-09-winat.txt` (full window enumeration), `sim-shot-41-topmost.png`, `rc-10-zorder.txt` (FOREGROUND=Firefox; all WindowFromPoint probes hit Firefox), `sim-shot-42-fullscreen.png`; 20:45:07 monkeydo re-attached → `sim-monkeydo5.log` "RunBeat companion started", java PID 28824 (`rc-11-after-monkeydo.txt`), `sim-shot-43-app.png` |
| 20:47:36 | `uia-tree2.txt` (UTF-16) — fresh sim + the Error dialog still present (it also appears ~2 min after app load: the watch's `request_state` arrives while the bridge is down) |
| 20:48:27 / 20:50:14 | `sim-shot-44-fullscreen2.png` / `sim-shot-45-fullscreen3.png` |
| 20:51:12 | `menu-dump.txt` — native Win32 menu dump: top-level item 4 `&adb Connection` → `Start  Ctrl+F1` **id=6033**, `Release  Ctrl+F2` **id=6034** (`menu-dump-full.txt` at 21:30 identical) |
| 20:51:14 / 20:55:20 | `rc-12-dismiss.txt` / `rc-13-dismiss2.txt` — modal Error dialogs dismissed via focus + Enter |
| **20:56:24** | **`rc-14-connect-loop.txt` — connect loop: dismiss any dialog + `PostMessage WM_COMMAND (0x0111)` wParam=6033 to hwnd 4132298, 8 loops until no dialogs.** Result: `Simulator connected` 20:56:24.364 + **`Garmin watch connected` 20:56:24.368** (`phone-logcat.log:1570-1571`); ESTABLISHED socket on 127.0.0.1:7381; state pushes ~1/s; `Received 48` every 5 s from 20:56:26. **`SendKeys` CTRL-F1 never worked; PostMessage/SendMessage WM_COMMAND id=6033 does** |
| 20:56:53 | `sim-shot-46-connected.png` — watch connected, live track |
| 20:58:01–21:05:12 | Playback test: phone home (`phone-20-home.png`), playback of Incubus "Summer Romance" started (`phone-30-playing.png` 21:03:13, `phone-31-playing.png` 21:05:11); `media_session.txt` (21:05:00, position 12213, PLAYING); watch shows the live track: `sim-shot-47-watch-playing.png` (21:03:15), `sim-shot-48-watch-track.png` (21:05:12); `media_session_now.txt` (21:33:35, position 157133) |
| 21:06:54–21:30:07 | Window-sizing probes + Save Screen Capture dialog: `sim-shot-49/50/51/52/53` |
| 21:07:22.838 / 21:07:24.025 | `Received 41 from simulator` ×2 — decoded silently (request_state path; no command log line) |
| 21:33:33 | `progress.md` last update (post-compaction resume note) |
| 21:34:12–21:51:17 | Tap-calibration window probes: `sim-shot-60-state.png`, `61-clean.png`, `62-default615.png`, `63-wide1000.png`, `64-615x884.png` — **0 `Watch command` lines** |
| 21:45:49 | Logcat capture restarted (capture 2; duplicates the overlap window incl. the 21:49 `Received 41` pair) |
| 21:47:24 | `uia-deep.txt` — UIA root rect `250,200 2500x1750` vs GetWindowRect (DPI probe) |
| 21:49:18.816 / 21:49:20.584 | `Received 41 from simulator` ×2 (again silent; duplicated in the file by the logcat restart) |
| **21:53:46** | **Bridge dropped:** `End of input reached` / `Client connection shut down` (21:53:46.626) → `Garmin watch disconnected` (21:53:46.630) → `Waiting for simulator connection` (21:53:46.632) (`phone-logcat.log:7736-7739`). **Cause (user-reported, rev. 2): the phone was disconnected off USB/adb at 21:53** — the simulator's tether socket through the adb forward broke |
| 21:56:00–22:06:22 | Playback continues (repeat loop); more window probes: `sim-shot-65-centered.png` (21:56), `66-compact.png` (22:02), `67-restored.png` (22:03), `68-savedialog.png` (22:06) |
| **22:03** | **Phone disconnected again (user-reported, rev. 2)** — during the outage; a device disconnect clears adb forwards, so the bridge could not recover on its own when the phone returned |
| 22:07:53 | This report's first live check: **`adb forward --list` EMPTY, nothing on 7381** (the forward had been cleared by the 22:03 phone disconnect — user-reported, rev. 2); phone app still PLAYING; phone still LISTENING on `[::]:7381` |
| **22:08:39** | **Bridge re-established** (phone back on adb; the e2e agent re-issued the forward and re-activated Connection > Start before being paused): `Simulator connected` + `Garmin watch connected` (`phone-logcat.log:7919-7920`); state pushes resume ~1/s |
| 22:10:15 | `sim-shot-69-repositioned.png` — newest evidence file |
| ~22:12 | Orchestrator pauses the e2e-sim-test agent |
| 22:14:00 | This report's fresh verification (sections 6–7): bridge UP, playback PLAYING, 0 `Watch command` lines, no calibration/checklist result files |
| 23:07:30 | Last retained logcat lines: bridge still UP (`Wrote 324 bytes` + `Received 48`); **both logcat captures stop here** (`phone-logcat.log` mtime 23:07, `phone-errors.log` mtime 23:07) — rev. 2 note |
| 2026-10-26 06:09 | Rev. 2 read-only check (orchestrator): `adb forward --list` **empty**, nothing on 127.0.0.1:7381 (forward gone, cause not recorded — captures stopped 23:07:30); phone attached, app alive PLAYING(3) position 183119; simulator PID 10344 alive; **watch connection state unknown since 23:07:30** |

---

## 6. Current live state (snapshot 2026-10-25 22:14 CEST / 21:14 UTC)

All checks below were run read-only at ~22:14 local, 2026-10-25. The e2e-sim-test agent is **paused**; both logcat captures are still appending, so `phone-logcat.log`/`phone-errors.log` keep growing.

| Check | Command | Result at 22:14 |
|---|---|---|
| Local / UTC time | `date` / `date -u` | 2026-10-25 22:14:00 CEST / 21:14:01 UTC |
| Bridge (PC side) | `powershell netstat -an | Select-String 7381` | `127.0.0.1:7381 LISTENING` **+ ESTABLISHED pair** `127.0.0.1:7381 <-> 127.0.0.1:54578` → **bridge UP** |
| adb forward | `adb forward --list` | `ae6e36e1 tcp:7381 tcp:7381` (active) |
| Device | `adb devices` | `ae6e36e1  device` |
| App process | `adb -s ae6e36e1 shell pidof com.RunBeat.run.audio` | `14771` (alive) |
| Playback | `adb -s ae6e36e1 shell dumpsys media_session` | `com.RunBeat.run.audio/androidx.media3.session.id.AudioService/25`, `state=PLAYING(3)`, position=226056, duration=268119, speed=1.0238096, `Summer Romance, Incubus, Science` — track has looped (repeat) |
| Phone tether socket | `adb -s ae6e36e1 shell 'grep -i 1CD5 /proc/net/tcp6'` | ESTABLISHED (state `01`) on 127.0.0.1:7381, UID 10281 |
| Simulator | `Get-Process simulator` | PID 10344, hwnd 4132298, `CIQ Simulator - Venu® 2 (5.0.0)`, Responding=True |
| monkeydo | `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` | PID 28824 alive |
| adb server + logcats | `Get-CimInstance Win32_Process -Filter "Name='adb.exe'"` | server PID 16312; logcat PID 7848 (filtered: `GarminCompanionManager:V PlayerRepository:V MediaKeyDispatcher:V GarminCommandRouter:V ConnectIQ-AdbConnection:V AndroidRuntime:E *:S` → `phone-logcat.log`); logcat PID 1564 (`*:E` → `phone-errors.log`) — **both captures still running** |
| Watch connection | `grep -nE 'Garmin watch connected\|disconnected' phone-logcat.log` | connected 20:56:24.368; disconnected 21:53:46.630; **re-connected 22:08:39.636 (last known state; logcat captures stopped 23:07:30 — rev. 2 note)** |
| State pushes | log tail at 22:14 | `Wrote 325 bytes` every ~0.9–1.1 s (364 unique timestamps since 22:08:39) |
| Watch->phone commands | `grep -c 'Watch command' phone-logcat.log phone-errors.log` | **0 / 0** — no watch->phone command has ever been received |
| Calibration artifacts | `ls | grep -iE 'cal\|checklist\|result\|tap'` in evidence dir | **NONE** — no `calibration.txt`, no `cal-tap-test.txt`, no `checklist-results.md` → checklist **IN PROGRESS** (checked 22:14 local) |
| progress.md | mtime | last updated 21:33:33 (stale relative to the 21:34–22:10 events) |
| Evidence dir | `ls | wc -l` | 141 files; newest `sim-shot-69-repositioned.png` (22:10:15); `phone-logcat.log` mtime 22:14:30 and growing |

Connection windows so far: **window 1** 20:56:24 → 21:53:46 (57 min 22 s; ended by a phone USB/adb disconnect at 21:53 — user-reported), **outage** 21:53:46 → 22:08:39 (14 min 53 s; the phone was disconnected again at 22:03, which cleared the adb forward — user-reported), **window 2** 22:08:39 → at least 23:07:30 (last retained log line; bridge UP, state pushes flowing). Post-snapshot (rev. 2): the forward is gone as of 2026-10-26 06:09 and the watch state is unknown since 23:07:30.

---

## 7. Checklist items 0–10 (from `device_test_plan.md`)

The plan states "Most of the test checklist below also applies to a simulator run." Status as of 22:14 local:

| # | Action (abridged) | Status | Evidence / note |
|---|---|---|---|
| 0 | Launch the app — comes up connected, shows current source, no "Phone not connected", no crash | **PASS** | `sim-monkeydo5.log` "RunBeat companion started"; `Garmin watch connected` 20:56:24 + 22:08:39; watch showed live track under 'RunBeat' (`sim-shot-46/47/48`, per orchestrator); state pushes flowing; no watch-app crash in logs. Caveat: the bridge was down 21:53:46→22:08:39 (watch-side display during the outage not verified) |
| 1 | Play control while RunBeat is playing — tap toggles play/pause; menu Previous | **BLOCKED** | Needs a tap on a watch button; 0 `Watch command` lines |
| 2 | Manual target cadence +/- — target changes in RunBeat (clamped 150–195) | **BLOCKED** | Needs cadence-page input |
| 3 | "Use current cadence" while running 60+ s | **BLOCKED** — **KNOWN-LIMITED on the simulator** | Per the plan and the orchestrator: the cadence AVG reads 0/'--' on the simulator (no sensor data); **both outcomes count as PASS** once reachable. Still needs the cadence page to be reached |
| 4 | Speed-correction toggle — watch shows state; RunBeat applies it | **BLOCKED** | Needs control-page input |
| 5 | Volume up/down — current volume displayed; phone media volume changes | **BLOCKED** | Needs a tap on the watch volume row |
| 6 | Mark current track not-for-running — track skipped, flag stored | **BLOCKED** | Needs control-page input |
| 7 | External-app mode — watch shows "Nothing playing"; media keys control the external app | **BLOCKED** | Needs another app playing (not staged) + watch input |
| 8 | Phone-unreachable handling — watch shows "Phone not connected"; recovers on its own | **BLOCKED** | Phone side proven: disconnect logged 21:53:46, auto-reconnect 22:08:39 (`Waiting for simulator connection` → `Garmin watch connected`). The outage was a genuine phone-unreachable event (two phone USB/adb disconnects: 21:53 and 22:03, user-reported). Watch-side display during the outage not verified (no screenshot of the watch in that window) |
| 9 | Navigation — 3 screens via swipe (ViewLoop); tap = select; menu button opens per-screen menu | **BLOCKED** for this tethered run | No swipe verified in this run. Partial prior evidence: an earlier standalone simulator run observed "the watch app launches and its three screens are navigable" (`requirements_update_2026-10-24.md`, Context) |
| 10 | Ack behavior — cadence/speed/mark commands produce acks; play and volume do not | **BLOCKED** | Needs commands to be sent (0 received so far) |

Summary: **item 0 PASSES on current evidence (watch shows live track + connection activity); items 1–10 are BLOCKED on tap calibration, except item 3 which is KNOWN-LIMITED on the simulator (cadence AVG reads 0/'--'; both outcomes count as PASS per the test plan).**

---

## 8. Evidence inventory

### 8.1 Evidence dir: `/c/Users/guill/dev/tools/garmin-build/test-venu2/sim-run-20261025T132724Z/` (141 files at 22:14)

**Phase A preflight (phone):**

| File | Description |
|---|---|
| `A1-pm-list-packages.txt` | `pm list packages`: only `com.RunBeat.run.audio` installed |
| `A1-uninstall-stale.txt` | `pm uninstall com.RunBeat.run.audio.garmin` (stale v45) → `Success` |
| `A2-version.txt` | versionCode=46, versionName=1.8, minSdk=26 targetSdk=36 |
| `A2-pm-path.txt` | installed APK path under `/data/app/...` |
| `A2-pull.txt` | `adb pull` of the installed APK: 24,591,084 bytes |
| `A2-sha256-buildoutput.txt` | sha256 of the worktree build-output APK: `e6a209a6...` |
| `A2-sha256-installed.txt` | sha256 of the pulled installed APK: identical `e6a209a6...` (byte-identical) |
| `A2-apksigner-certs.txt` | signer cert DN + SHA-256 `2633d3ce...d7039b` |
| `A3-app-uid.txt` | app UID 10281 |
| `A3-data-saver.txt` | data-saver mode: null (off) |
| `A3-internet-permission.txt` | manifest permissions + INTERNET granted=true |
| `A3-netpolicy.txt` | netpolicy summary: no restrictions |
| `A3-netpolicy-appuid.txt` | per-UID policy for 10281: not blocked |
| `A3-netpolicy-full.txt` | full netpolicy dump |
| `A3-netpolicy-restrict.txt` | restrict-background UID list (app not in it) |
| `A3-app-details-initial.png` (14:28) | screenshot: app-details page |
| `A3-app-details-ui.xml` | UIA dump: app-details page |
| `A3-back-ui.xml` | UIA dump after navigating back |
| `A3-battery-mode.png` (14:30) | screenshot: battery settings page |
| `A3-battery-mode-ui.xml` | UIA dump: battery page |
| `A3-battery-page-ui.xml` | UIA dump: battery page (settings) |
| `A3-network-page-ui.xml` | UIA dump: network settings page |
| `A4-AppSettings.xml` | app shared_prefs: `intro_seen`, `ciq_simulator_adb_mode=true` |
| `A4-pref-check.txt` | grep of AppSettings.xml: simulator ADB mode true |
| `A4-run-as-stderr.txt` | empty (run-as attempt produced no stderr) |
| `A5-forward.txt` | empty output of `adb forward tcp:7381 tcp:7381` |
| `A5-forward-list.txt` | `adb forward --list`: `ae6e36e1 tcp:7381 tcp:7381` |
| `A6-phone-home.png` (14:32) | screenshot: phone home after app restart |
| `A6-restart.txt` | force-stop + relaunch via resolved launcher activity → new PID 14771 |
| `FINAL-verification.txt` | 14:33 final preflight sweep (8 checks, all green) — PHONE READY 8/8 |

**Phase B simulator bring-up:**

| File | Description |
|---|---|
| `B-app-alive.txt` | app process 14771 alive (`ps`) |
| `B-stale-sim-check.txt` | tasklist: simulator PID 38824 + java 14708 (first sim instance) |
| `B-forward-reissue.txt` | adb forward re-issue output (port 7381) |
| `B-java-cmdline.txt` | monkeydo java cmdline (SDK 9.3.0, `-f out-v2/venu2.prg -d venu2`) |
| `B-shot10.txt` | `ls` evidence for `sim-shot-10-state.png` |
| `B-sim-state.txt` | simulator state: PID 38824 hwnd 5835412, responding, not hung, no dialogs |
| `B6-connect-start.txt` | CTRL-F1 (SendKeys) sent 19:34 local; screenshot ref |
| `B6-focus-diag.txt` | SetForegroundWindow/AttachThreadInput diagnostics on the sim hwnd |
| `B7-verify.txt` | phone-logcat size + tail after the connect attempt (only PlayerRepository lines) |
| `B7b-after-connect.txt` | app-line tag counts + banner pixel-threshold analysis (s10) |
| `B7c-tags.txt` | all tags in phone-logcat.log; grep Garmin/ConnectIQ/watch → none |
| `B-adb-devices.txt` | `adb devices`: ae6e36e1 |
| `B-hover.txt` | hover screenshot refs (hover1–3, afteralt) |
| `B-resize-wide.txt` | SetWindowPos 1400x1200 @0,0 succeeded; wide capture saved |

**Root cause & fix (rc-*):**

| File | Description |
|---|---|
| `rc-01-rootcause.txt` | **ROOT CAUSE**: modal Error dialog found via UIA (full text); adb NOT in system PATH; the 19:34 CTRL-F1 did invoke Connection > Start; SDK doc reference |
| `rc-02-strings-kill.txt` | SDK `bin/` listing; UTF-16 strings scan of simulator.exe; TASKKILL `/F` quoting failure; java cmdlines |
| `rc-03-killed.txt` | old simulator + monkeydo killed; device/forward/app verified intact |
| `rc-04-pathlen.txt` | system PATH lengths (machine 501 / user 217 / combined 719) |
| `rc-05-setx.txt` | `setx /M PATH` attempt (no visible change) |
| `rc-05b-setx.txt` | setx via PowerShell → **registry access denied** (non-elevated) |
| `rc-06-freshwin.txt` | fresh sim PID 10344 hwnd 4132298, off-screen rect; netstat: only LISTENING |
| `rc-07-win2.txt` | re-check of the fresh sim window |
| `rc-08-pin.txt` | SetWindowPos pin → 100,100 450x800 |
| `rc-09-winat.txt` | full top-level window enumeration (WindowFromPoint) |
| `rc-10-zorder.txt` | sim rect + z-order; FOREGROUND=Firefox; AT() probes hit Firefox |
| `rc-11-after-monkeydo.txt` | after monkeydo attach: "RunBeat companion started"; java 28824 |
| `rc-12-dismiss.txt` | modal Error dialog dismissed via focus+Enter |
| `rc-13-dismiss2.txt` | second dialog dismissed the same way |
| `rc-14-connect-loop.txt` | 8-loop connect: dismiss dialogs + PostMessage WM_COMMAND 6033 → connected 20:56:24 |

**Ground truth (gt-*):**

| File | Description |
|---|---|
| `gt-01.txt` (20:15) | bridge-down ground truth: only 127.0.0.1:7381 LISTENING; forward active; pidof 14771; phone `ss` segfaults |
| `gt-02-dialog-check.txt` (20:26) | window state + dialog check |
| `gt-03-windowat.txt` (20:27) | WindowFromPoint grid — Firefox in the foreground |

**Logs:**

| File | Description |
|---|---|
| `phone-logcat.log` | filtered logcat capture (2 captures: started 18:39:35 and 21:45:49; growing; 965 KB at 22:14) — the primary connection evidence |
| `phone-errors.log` | `logcat *:E` capture (crash buffer + system errors; 0 Garmin/ConnectIQ lines; latest entries are `ndroid.systemui` resource warnings) |
| `progress.md` | e2e agent's running log (last entry 21:33 local) |
| `sim-console.log` / `sim-console2.log` / `sim-console4.log` | simulator console output — all 0 bytes |
| `sim-monkeydo.log` | empty (first monkeydo) |
| `sim-monkeydo2.log` (15:23) | `RunBeat companion started` |
| `sim-monkeydo5.log` (20:45) | `RunBeat companion started` (current instance) |

**Simulator control / diagnostics:**

| File | Description |
|---|---|
| `sim-ctl.ps1` | PowerShell sim-control script: click/swipe/tap helpers, screenshots, and `Connect-Start` via `SendKeys ^{F1}` (the action that never worked) |
| `menu-dump.txt` (20:51) / `menu-dump-full.txt` (21:30) | native Win32 menu dump: item 4 `&adb Connection` → `Start  Ctrl+F1` id=6033, `Release  Ctrl+F2` id=6034 |
| `uia-tree.txt` (20:24, UTF-16) | UIA tree of sim + modal Error dialog with full text (root rect 0,0 3500x3000 — 250% DPI) |
| `uia-tree2.txt` (20:47, UTF-16) | UIA tree of the fresh sim + Error dialog (rect 250,250 1538x2308) |
| `uia-deep.txt` (21:47) | UIA deep dump: GetWindowRect vs UIA rect (250,200 2500x1750) — DPI probe |
| `hung-state-evidence.txt` (15:22) | hung-state evidence: IsHungAppWindow True, dialog rect/class, clipped watch display |
| `sdk-doc-grep.txt` (20:19) | SDK doc tree + grep hits (incl. `Communicating_with_Mobile_Apps.html`) |

**Media session:**

| File | Description |
|---|---|
| `media_session.txt` (21:05) | `dumpsys media_session`: PLAYING(3), position 12213, `Summer Romance, Incubus, Science`, speed 1.0238096 |
| `media_session_now.txt` (21:33) | same session, position 157133 |

**Phone UI dumps:** `ui1.xml` … `ui7.xml` (20:59–21:03) — phone home/player UIA dumps during the playback test; none shows the Settings > GARMIN screen.

**Artifacts:** `installed.apk` (14:27) — APK pulled from the phone, 24,591,084 bytes, sha256 `e6a209a6...` (byte-identical to the worktree build output).

**Simulator screenshots (all in the evidence dir; times are mtimes, local):**

| File | Time | Description |
|---|---|---|
| `sim-shot-00-first.png` | 14:38 | first simulator screenshot |
| `sim-shot-01-settled.png` | 14:39 | settled state |
| `sim-shot-02-venu2.png` | 14:48 | venu2 device profile loaded |
| `sim-shot-03-after-monkeydo.png` | 14:52 | after monkeydo attach |
| `sim-shot-04-fresh.png` | 14:57 | fresh app state |
| `sim-shot-05-hung-state.png` / `sim-shot-06-fullscreen-hung.png` | 15:22 | hung state / fullscreen hung |
| `sim-shot-07-layout.png` | 15:54 | layout probe |
| `sim-shot-10-state.png` | 19:33 | state just before CTRL-F1 |
| `sim-shot-11-after-connect.png` | 19:34 | after CTRL-F1 (modal dialog appeared) |
| `sim-shot-12-wide-window.png` | 19:56 | 1400x1200 wide window |
| `sim-shot-30-current.png` | 20:15 | current window state |
| `sim-shot-31-dialog.png` | 20:26 | Error dialog |
| `sim-shot-32-after-front.png` | 20:30 | after foreground attempt |
| `sim-shot-40-fresh.png` | 20:38 | fresh simulator window |
| `sim-shot-41-topmost.png` | 20:40 | topmost check |
| `sim-shot-42-fullscreen.png` | 20:42 | fullscreen attempt |
| `sim-shot-43-app.png` | 20:45 | app loaded (monkeydo5) |
| `sim-shot-44-fullscreen2.png` / `sim-shot-45-fullscreen3.png` | 20:48 / 20:50 | further fullscreen attempts |
| `sim-shot-46-connected.png` | 20:56 | **watch connected, live track** (criterion 3) |
| `sim-shot-47-watch-playing.png` | 21:03 | watch showing playing track |
| `sim-shot-48-watch-track.png` | 21:05 | watch track view |
| `sim-shot-49-fullscreen.png` / `50-full-resized.png` / `51-full-615.png` | 21:06 / 21:11 / 21:14 | window-sizing probes |
| `sim-shot-52-after-savecap.png` / `53-savedialog.png` | 21:22 / 21:30 | Save Screen Capture dialog |
| `sim-shot-60-state.png` / `61-clean.png` / `62-default615.png` / `63-wide1000.png` / `64-615x884.png` | 21:34–21:51 | calibration window probes |
| `sim-shot-65-centered.png` / `66-compact.png` / `67-restored.png` / `68-savedialog.png` / `69-repositioned.png` | 21:56–22:10 | further window-management probes (69 is the newest file) |
| `sim-shot-hover-hover1/2/3.png` / `sim-shot-hover-afteralt.png` | 19:47 | hover probes (all four byte-identical, 83,886 B) |
| `sim-dialog-error.png` | 14:54 | modal Error dialog capture |
| `sim-dialog-screen.png` / `sim-dialog-printwindow.png` | 14:55 | dialog screen / PrintWindow capture |
| `sim-dialog-full.png` | 14:59 | full dialog capture |
| `sim-dialog-error-capture.png` | 15:22 | dialog capture in hung state |

**Phone screenshots:** `phone-settings-network.png` (14:29), `phone-settings-battery.png` (14:29), `A3-app-details-initial.png` (14:28), `A3-battery-mode.png` (14:30), `A6-phone-home.png` (14:32) — preflight; `phone-20-home.png` (20:58), `phone-30-playing.png` (21:03), `phone-31-playing.png` (21:05) — playback test.

### 8.2 Key files outside the evidence dir

| File | Description |
|---|---|
| `/c/Users/guill/dev/tools/garmin-build/out-v2/venu2.prg` | **the correct watch app** (sha256 `77cd828d...`, verified) — the one monkeydo loads |
| `/c/Users/guill/dev/tools/garmin-build/out-v2/` | 16 signed `.prg` + per-device logs + `.prg.debug.xml` + `summary.txt` (16/16 `exit=0`, `BUILD_LOOP_DONE`) |
| `/c/Users/guill/dev/tools/garmin-build/test-venu2/venu2.prg` | older prg (sha256 `8e38497a...`) — superseded |
| `/c/Users/guill/dev/tools/garmin-build/out-sim/venu2.prg` / `out-repo/venu2.prg` | older prgs (`01b67f15...` / `5885ec48...`) — superseded |
| `/c/Users/guill/dev/tools/garmin-build/test-venu2/app-garmin-debug.apk` | older garmin APK (Oct 24, sha256 `6c07d7ca...`) — superseded by the worktree build output |
| `/c/Users/guill/dev/tools/garmin-build/test-venu2/app-fdroid-debug.apk` | fdroid-flavor APK (Oct 24, sha256 `ea4bcf3f...`) — cannot talk to the watch |
| `/c/Users/guill/dev/tools/garmin-build/test-venu2/device_test_plan.md` | pre-run copy of the test plan (sha256 `b4a1f3ec...`) |
| `/c/Users/guill/dev/tools/garmin-build/test-venu2/README.txt` | test-package README — **names a Fairphone 6 / serial `fe113fb3` / Android 16 as the test phone and a stale `developer_key.der` sha256 (`f31219e0...`)**; the actual run used a Fairphone FP4 / `ae6e36e1` / Android 15 and the key's real sha256 is `266c6a56...` (verified on disk) |
| `/c/Users/guill/dev/runBeat-wt/integration/app/build/outputs/apk/garmin/debug/app-garmin-debug.apk` | worktree build output — 24,591,084 bytes, built 2026-10-25 12:10, sha256 `e6a209a6...` = installed APK |
| `/c/Users/guill/dev/tools/garmin-build/developer_key.der` | signing key, RSA-4096, sha256 `266c6a56f17e549e0994d8f0f15ee0b14d98d4c6317483695ddbb14463f304ce` (verified) — sideload only |
| `/c/Users/guill/dev/tools/` | toolchain: `android-sdk/` (platform-tools incl. adb, platforms, build-tools), `gradle/` (Gradle 8.7 wrapper), `garmin-build/` (watch build scripts + outputs), `Garmin/` + `connect-iq-sdk-manager/` (SDK manager), `ffmpeg/`; README documents the layout |
| Connect IQ SDK 9.3.0 | `C:/Users/guill/AppData/Roaming/Garmin/ConnectIQ/Sdks/connectiq-sdk-win-9.3.0-2026-10-20-95888f262` and `/c/Users/guill/dev/tools/Garmin/ConnectIQ/Sdks/connectiq-sdk-win-9.3.0-2026-10-20-95888f262` (same SDK, two copies) |

---

## 9. Findings, gotchas & lessons (verified against the evidence)

1. **Root cause of the hours-long bridge outage: `adb` was not in the system PATH.** The simulator shells out to bare `adb` for its device/forward check (`rc-01-rootcause.txt`, `rc-04-pathlen.txt`). Invoking Connection > Start then failed the ADB check and raised a **modal** Error dialog ("There is no data connection. Please connect an Android device to ADB. Run the command \"adb forward tcp:7381 tcp:7381\" …") that blocked all simulator input (`uia-tree.txt`, `hung-state-evidence.txt`). Fix: start the simulator with `PATH="/c/Users/guill/dev/tools/android-sdk/platform-tools:$PATH"` prepended. `setx /M PATH` persistence is **denied** (non-elevated; `rc-05-setx.txt`, `rc-05b-setx.txt`) — do not retry setx.
2. **`SendKeys` CTRL-F1 never worked; `PostMessage`/`SendMessage WM_COMMAND (0x0111)` wParam=6033 to hwnd 4132298 works** (menu ids from `menu-dump.txt`: Start=6033, Release=6034; applied in `rc-14-connect-loop.txt`).
3. **The modal Error dialog also appears ~2 min after app load** (the watch app's `request_state` arrives while the bridge is down); dismiss it via focus + Enter (`rc-12-dismiss.txt`, `rc-13-dismiss2.txt`, `uia-tree2.txt`).
4. **Phone `ss` segfaults** (`gt-01.txt`: "Cannot open netlink socket: Permission denied / Segmentation fault"). Use `/proc/net/tcp(6)` with hex port `1CD5` (=7381); state `0A`=LISTEN, `01`=ESTABLISHED.
5. **`cmd /c ...` from Git Bash drops to an interactive prompt** — use `powershell -NoProfile -Command` with the PS script in a bash-single-quoted string.
6. **`read_file` needs Windows paths** (`C:\...`); `/c/...` gets mangled to `C:\c\...`.
7. **Git Bash mangles unquoted remote adb paths** — always quote them. A harmless `SecurityException: Shell does not have permission to access user 10` appears on some `pm` commands (`FINAL-verification.txt` item 2).
8. **netstat ground truth:** bridge DOWN = only `127.0.0.1:7381 LISTENING` (the adb server's forward listener); bridge UP = an additional ESTABLISHED pair on `127.0.0.1:7381`. (At 22:07:53 the forward itself was gone — nothing on 7381; see item 17.)
9. **Phone-side ground truth:** `grep -E 'connected|disconnected' phone-logcat.log` — the manager only logs on events (`GarminCompanionManager.kt:401/408`).
10. **The simulator window drifts to off-screen ini values** after device load (`WindowX=2063, WindowY=-1171`; observed rects `2063,-1171 2413x-471` and `2298,-1197` in `rc-06/07/10`) — re-pin with SetWindowPos (`rc-08-pin.txt`).
11. **Windows PID vs Git Bash PID differ** — use `Get-CimInstance Win32_Process` for cmdlines (`B-java-cmdline.txt`, `rc-02-strings-kill.txt`). `Get-Process` is not available in Git Bash (`gt-01.txt`: "Get-Process: command not found").
12. **Orchestrator-side agent messaging pattern:** interrupt → sleep 3 s → send (send-then-interrupt can discard the message).
13. **250% display DPI:** UIA rects are physical (`uia-tree.txt` root `0,0,3500,3000` vs logical 1400x1200; `uia-deep.txt` `250,200 2500x1750`) — coordinate mapping for taps is the hard part of calibration. The simulator is a wxWidgets app (UIA class `wxWindowNR`) with a **native Win32 menu** and a custom-drawn watch canvas (no UIA controls for the watch screen).
14. **`uia-*.txt` files are UTF-16** — read them with `powershell Get-Content -Encoding Unicode` (plain `cat` shows garbage).
15. **Logcat captures:** `phone-logcat.log` holds two captures (started 18:39:35 and 21:45:49 — `--------- beginning of main` markers at lines 256 and 6949); lines in the overlap window are duplicated (the 21:49 `Received 41` pair appears twice). `ConnectIQ SDK ready` predates both captures.
16. **Playback is a single-track repeat loop** (268,119 ms at speed 1.0238096 → ~262 s wall per loop). The 16 `Wrote 85 bytes` lines (2 at connect + 14 at loop points, every ~262 s: 21:03:11, 21:09:05, 21:13:28, … 21:52:48) mark track-change moments — a smaller state payload when the track transitions.
17. **Phone disconnects break the tether in two ways (cause user-reported, rev. 2):** the phone was disconnected off USB/adb at 21:53 and again at 22:03. The 21:53 disconnect dropped the bridge (`End of input reached` → `Garmin watch disconnected`, 21:53:46); the 22:03 disconnect cleared the adb forward (empty `adb forward --list` at 22:07:53, although the forward had been active since 14:32 — `A5-forward-list.txt`, `B-forward-reissue.txt`), so the bridge stayed down until the e2e agent re-issued the forward before the 22:08:39 reconnect. The forward also vanished again after the captures stopped (gone by 2026-10-26 06:09; cause not recorded). Lesson: a device disconnect / adb server restart clears forwards — always re-check `adb forward --list` (and re-issue `adb forward tcp:7381 tcp:7381`) after any phone disconnect or adb server restart.
18. **`TASKKILL /F` with Git Bash paths fails** (`rc-02-strings-kill.txt`: "ERROR: Invalid argument/option - 'F:/'") — use `Get-CimInstance`/`Stop-Process` or Windows-form paths.
19. **Two phones exist in this project's history:** the test package (`test-venu2/README.txt`, plan v2 §9.5) was prepared for a Fairphone 6 / `fe113fb3` / Android 16 (not connected at agent-C verification time); the actual e2e run used a **Fairphone FP4 / `ae6e36e1` / Android 15** (verified via `adb devices` and `FINAL-verification.txt`).
20. **Simulator/monkeydo lifecycle:** killing the simulator drops the bridge (`End of input reached` → `Garmin watch disconnected` → `Waiting for simulator connection`); the phone app recovers on its own when the simulator reconnects (observed 22:08:39). `sim-console*.log` and the first `sim-monkeydo.log` stay 0 bytes (no console output).
21. **The 41-byte `Received 41` messages** (21:07:22/24, 21:49:18/20) decode silently — only `RequestState` is handled without a log line (`GarminCompanionManager.kt:265-272`), and no `Ignoring undecodable watch message` lines exist; their exact nature is not determinable from the retained logs. They coincide with the e2e agent's tap-test windows but produced **no** `Watch command` lines, i.e. no command round-trip.
22. **`phone-errors.log` contains no app-relevant errors:** old `com.artisancompanion` crash (10-24, different app), AppOps START_FOREGROUND/WAKE_LOCK notices (13:27, pre-install), `HCPackageInfoUtils NameNotFoundException for com.RunBeat.run.audio.garmin` (14:27, the stale-package uninstall), Finsky (Play Store) RPC failures for the sideloaded app (20:44), and `ndroid.systemui` resource warnings. 0 Garmin/ConnectIQ lines.

## 10. Open items & recommendations

### 10.1 Merge recommendation — AWAITING USER APPROVAL (do not commit / push / merge)

Recommended by the planner-e2e and worktree-diff-review agents; **not yet executed and not yet approved by the user**:

1. In the integration worktree `/c/Users/guill/dev/runBeat-wt/integration` (branch `feature/garmin-companion`, HEAD `561b44b`): commit the 22 uncommitted files (19 modified + 3 untracked; diff +1226/−85). The worktree-diff-review agent verified them coherent and commit-safe.
2. `git push -u origin feature/garmin-companion` — the branch has **not** been pushed; only `origin/main` exists on the remote.
3. In the main repo `/c/Users/guill/dev/runBeat` (branch `main`, HEAD `c6598e5`): `git merge --no-ff feature/garmin-companion`.

Known consequences (documented in the worktree docs and verified here):

- The `garmin` and `fdroid` flavors share applicationId `com.RunBeat.run.audio` — they are **not co-installable**.
- The `garmin` flavor adds the `INTERNET` permission via the untracked `app/src/garmin/AndroidManifest.xml` — a deliberate reversal of the no-network stance; the `fdroid` flavor is unaffected.
- The watch `.prg` is signed with the local sideload key `developer_key.der` (sha256 `266c6a56...`) — **not valid for the Connect IQ Store**.
- Before committing, re-run `./gradlew :app:testGarminDebugUnitTest :app:assembleGarminDebug`: the green 214-test run (2026-10-25 07:47) and plan v2 §9.4's APK size note (24,490,380 bytes @ 07:33) both **predate** the uncommitted changes. The APK currently installed on the phone (built 12:10, 24,591,084 bytes) is byte-identical to the worktree build output.

### 10.2 Remaining test work (priority order)

1. **Criterion 4 / checklist items 1–10 — simulator tap calibration (the blocker).** No tap has ever landed on a watch button (0 `Watch command` lines in `phone-logcat.log`). The orchestrator's deterministic recipe: measure the simulator window's physical geometry via UIA vs `GetWindowRect` (DPI factor; display is 250%, UIA rects are physical, e.g. 3500x3000 vs logical 1400x1200), find the round watch screen, scale = D/416, map logical button coordinates to physical, verify with a single PLAY/PAUSE tap checked against `adb -s ae6e36e1 shell dumpsys media_session` (state must flip), grid-scan fallback, 20-minute timebox. If unsolved: checklist item 0 still PASSES on current evidence; items 1–10 stay BLOCKED.
2. **Criterion 2:** open the phone app's Settings > GARMIN screen and verify it shows "Connected" (never checked; no evidence either way).
3. **Criterion 5:** screenshot-verify the watch position caption advancing ~1/s (state pushes flow in logcat; the caption itself is not screenshot-verified).
4. **Checklist item 8 (watch side):** observe the watch showing "Phone not connected" during an outage and its recovery — the 21:53:46→22:08:39 outage was a genuine phone-unreachable event (two phone USB/adb disconnects: 21:53 and 22:03, user-reported) but no watch screenshot was captured during it. (Bridge state since 2026-10-25 23:07:30 is unknown — captures stopped and the forward is currently gone; rev. 2 note.)
5. **Checklist item 3 is KNOWN-LIMITED on the simulator** (cadence AVG reads 0/'--'; both outcomes count as PASS per the test plan). Real venu2 hardware remains for cadence accuracy (review finding 4) and for the native Music app "Phone" source (plan v2 §6.4, criterion 7).
6. **Bridge maintenance:** the bridge is currently UP with the e2e-sim-test agent paused. If the adb server restarts, re-issue `adb forward tcp:7381 tcp:7381` (the simulator builds this command itself; the forward must exist before Connection > Start).

### 10.3 What could NOT be verified from disk (explicit list)

1. **'ConnectIQ SDK ready'** is not in the retained logcat captures (both captures postdate the app process start ~14:32); it was verified live by the orchestrator. Code location: `GarminCompanionManager.kt:230`.
2. **Screenshot contents** — this agent cannot view images; criterion 3 rests on the orchestrator's review of `sim-shot-46/47/48` plus corroborating log lines (`Garmin watch connected`, state pushes).
3. **Criterion 2** (Settings > GARMIN "Connected") was never checked — no evidence either way.
4. **Cause of the 21:53:46 disconnect and of the adb forward disappearing (22:03–22:07):** not recorded in any log (`progress.md` was last updated 21:33:33; the 22:08:39 reconnect is logged but the recovery actions are not). **Resolved by the user's report (rev. 2): the phone was disconnected off USB/adb at 21:53 and again at 22:03.** The log evidence is consistent (`End of input reached` at 21:53:46; forward gone by 22:07:53) but circumstantial — the disconnects themselves appear in no retained log. (The overnight forward loss after 23:07:30 remains unexplained — the captures had stopped.)
5. **Exact nature of the 41-byte `Received 41` messages** — decoded silently on the `RequestState` path; not determinable from the retained logs.
6. **The agents' chat reports are not on disk** — treated as primary context per the orchestrator; file evidence was used wherever available.
7. **Exact list of the 8 PHONE READY criteria** — `FINAL-verification.txt` supports 8/8, but the A1–A6 list came from the orchestrator.
8. **Watch-side display during the 21:53:46→22:08:39 outage** — no screenshot was captured.
9. **`test-venu2/README.txt` inconsistencies** — names Fairphone 6 / `fe113fb3` / Android 16 vs the actual FP4 / `ae6e36e1` / Android 15; stale key sha `f31219e0...` vs the verified `266c6a56...`.
10. **`progress.md` is stale** (last update 21:33:33) relative to the 21:34–22:10 events recorded in `phone-logcat.log`.

---

## Rev. 3 — 2026-10-26 evening session: criterion 4 FIXED and PROVEN; phone->watch shown broken in the simulator

Environment: same worktree (`/c/Users/guill/dev/runBeat-wt/integration`, HEAD 561b44b + uncommitted work), same phone (FP4 `ae6e36e1`, garmin APK v1.8/46 with the new fix), same SDK 9.3.0 simulator. Evidence dir for this session: `/c/Users/guill/dev/tools/garmin-build/test-venu2/sim-run-20261026/` (`phone-logcat-resume.log` is the capture; `progress-20261026.md` the session notes).

### R3.1 The criterion-4 root cause and fix (watch -> phone)

The SDK quirk: in TETHERED (simulator/ADB) mode, the companion SDK 2.4.0 re-broadcasts every incoming tether packet with hardcoded `IQDevice(12345,"Simulator")` and **`IQApp("", "Simulator App", 1)` — an empty app UUID** (`AdbDataHandler.handleMessage` → `IQMessageReceiver.onReceive` dispatches by app UUID). Our manager registers its listener under the real UUID `06c14376-0c60-4e4c-a977-e93364ace3eb` → lookup under `""` misses → the message is dropped **silently** (no log line at all). This is a known, community-documented simulator bug (Garmin forums thread "Android Mobile SDK - tethered connection - app status UNKNOWN"; the recommended workaround is registering a second listener under `IQApp("")`). The watch-side symptom of the drop was the red transmit-failure ack ("Phone not connected") on 2026-10-25 taps.

The fix (uncommitted, in `app/src/garmin/java/com/bpmapp/audio/audio/garmin/GarminCompanionManager.kt`): a `simulatorIqApp: IQApp("")` registered for app events **additionally** (only when `simulatorAdbMode` is on) next to the real-UUID listener. `registerForAppEvents` merges app listeners into the existing device container, so both registrations coexist; WIRELESS (real-watch) mode is untouched.

### R3.2 Verified end-to-end (watch -> phone, all in `phone-logcat-resume.log`)

| Command | Time(s) | Phone effect (verified) |
|---|---|---|
| `{"type":"toggle"}` | 20:49:18, 20:49:53 (user taps), 20:58:50-20:59:03 (programmatic) | isPlaying flipped true→false→true→false→true in `dumpsys media_session`/PlayerRepository lines |
| `{"type":"next"}` | 21:20:31 (menu) | next track loaded 33 ms later ("Nobody Beg You") |
| `{"type":"prev"}` | 21:23:41 (menu) | previous track loaded 53 ms later ("Dem Bobo") |
| `{"type":"volume_delta","delta":-1/+1/+1` | 21:21:31, 21:21:40, 21:21:44 (menu) | phone STREAM_MUSIC volume changed accordingly (dumpsys audio) |

Checklist impact: item 1 (toggle/prev/next) **PASS on the simulator**; item 5 (volume) **PASS** (phone volume changes; the watch's "VOL n" display itself unprovable — needs phone->watch). Items 9/0 partially PASS (3-page navigation via vertical swipe works; menus reachable; app stable through the whole session, no crash).

### R3.3 The simulator input model (solves the "tap calibration" mystery)

The 2026-10-25 conclusion "tap calibration unsolved" is superseded: **this simulator build (SDK 9.3.0) delivers every mouse click on the watch face as the device's SELECT button, not as a positional tap.** Proof: a programmatic click on the watch title area (no button there; device-logical (208,100), a calibrated 432 px above the play rect) produced a `toggle`, as did clicks on the computed PREV/NEXT/VOL+ button centers. The watch app's `onTap`/hit-testing (correct in code, and exercised by unit-tested hit rects) is never reached in this environment. With Settings > "Toggle Touch Screen" switched OFF, clicks produce nothing at all.

What DOES work on the simulator (verified with the user driving):
- **Click anywhere on the watch face = SELECT** (page primary action: MainView=toggle, CadenceView=cadence_use_current, ControlView=speed_correction_toggle — the last two are gated by the local RunBeat-only check).
- **'M' key = MENU** (opens the per-screen menu: transport menu on page 1 with Next/Previous/Volume Up/Volume Down; cadence and control menus on pages 2/3) — this is how all commands are reachable on the sim.
- **Menu navigation = mouse swipe up/down; confirm = click/Enter.**
- **Vertical swipe on a page = ViewLoop page cycling** (wrap=true, 3 pages).
- **Horizontal swipe on page 1 = seek ±15 s** — verified gated: `MainView.onHorizontalSwipe` no-ops while position/duration are unknown (which is always the case here, see R3.4). Arrow keys (keybd_event) produced no observable effect.

### R3.4 Phone -> watch delivery is broken in this simulator — in every session

Decisive evidence: the watch app's own recovery loop (`RunBeatCompanionApp.mc` re-sends `request_state` every 5 s while no message has arrived for 10 s — `WatchState.isStale`) emits a 48-byte packet every 5 s. **A gap analysis of the 48-byte stream across BOTH session logs shows gaps only at app kill/reload moments — never a receipt-driven pause.** The watch app has therefore never marked a single message received since its first load. Consequences:

- The "48-byte every-5s packets" in the rev. 1-2 report are the app's `request_state` re-asks, **not** simulator keepalives (rev. 2's interpretation correction is itself corrected).
- The rev. 1 "PROVEN" for criterion 3 (watch shows live track) cannot be true at the message level; the phone's `Wrote N bytes` lines only prove socket writes to the simulator, never delivery to the app. Criteria 3 and 5 are re-marked NOT PROVEN pending a real-watch run.
- The RunBeat-only watch commands (speed correction, cadence actions, mark-not-for-running) are locally refused with the red "RunBeat only" hint because the watch state's `source` is never updated from its default "none" — a direct consequence of R3.4, not a bug in the command path (the phone-side routing of all these commands is covered by the 214-test unit suite).

Attempted and disproven fixes (all with the app fresh-loaded in the correct order — bridge before app):
1. Watch-app reload (Kill App + monkeydo) — still deaf.
2. Full ADB connection restart (Release 6034 → forward → Start 6033) + app reload — still deaf.
3. **Complete clean stack**: phone app force-stop + fresh SDK init, simulator process killed + freshly started, forward, Connection Start, THEN monkeydo — still deaf. (This is the exact sequence that the 06:35 session used, so the difference vs. yesterday's "working" impression is the *interpretation*, not the environment.)
4. The simulator's own **Simulation > Phone App Message** injection (target "RunBeat", valid state JSON) — never reached the app. Per the Garmin forums, that path feeds the background `ServiceDelegate.onPhoneAppMessage` API, not the classic foreground `Communications.registerForPhoneAppMessages()` our app uses.

**Conclusion: phone->watch app-message delivery does not work in this simulator environment at all (SDK 9.3.0 ADB tether). Everything requiring it moves to the real-watch checklist.**

### R3.5 Simulator ADB bridge lifecycle (new observations)

The bridge (sim's TCP connection through the forward) can drop on its own while everything else stays healthy: observed 20:37:52 (~68 s after connect) and 20:45:52 (~6.3 min after connect), both logged phone-side as `End of input reached` → `Client connection shut down` → `Waiting for simulator connection`. The watch app's next send then raises the modal "There is no data connection" dialog. Recovery that works: verify/re-issue `adb forward tcp:7381 tcp:7381`, then `PostMessage WM_COMMAND 6033` (Connection > Start). Taps/commands do NOT themselves drop the connection (stable 20:48→21:29 across 17 commands). Note the fix in R3.1 made watch->phone survive these reconnects transparently.

### R3.6 Findings for the app itself (not environment)

1. **`isRunBeatActive()` (phone) = isPlaying || buffering** (`GarminCommandRouter.kt:49`): while playback is merely PAUSED with a track loaded, the phone pushes `source:"none"` — the watch then hides the track info and refuses RunBeat-only commands ("RunBeat only"). Observed live at 21:25-21:29 (85-byte "none" pushes while "Dem Bobo" was loaded-but-paused). Design review recommended: a loaded RunBeat track arguably means RunBeat is the active app even when paused. (On the simulator this is masked by R3.4; it will be visible on the real watch.)
2. The watch's re-ask loop (request_state every 5 s while stale) is a good link-layer probe — its packet cadence is a reliable "is the app receiving?" indicator for future sessions (gap in the 48-byte stream = a message was received).

### R3.7 Updated checklist status (simulator, end of 2026-10-26 session)

| # | Status | Note |
|---|---|---|
| 0 | PASS (sim) | App stable across a full session, multiple reloads, bridge drops; menus/navigation work |
| 1 | PASS (sim, via menu/SELECT) | toggle, next, prev verified with playback effects |
| 2 | BLOCKED (env) | cadence menu gated by RunBeat-only; needs real watch |
| 3 | KNOWN-LIMITED (sim) | no sensor data; needs real watch for AVG |
| 4 | BLOCKED (env) | watch refuses locally (source never "RunBeat"); phone-side routing unit-tested |
| 5 | PASS (phone side) | volume_delta x3, phone STREAM_MUSIC changed; watch "VOL n" display needs real watch |
| 6 | BLOCKED (env) | mark_not_for_running gated; needs real watch |
| 7 | BLOCKED (env/staging) | needs an external app playing + phone->watch display |
| 8 | PARTIAL (phone side) | bridge drops + auto-reconnect logged; watch-side "Phone not connected" display needs phone->watch (real watch) |
| 9 | PASS (sim) | 3 pages via vertical swipe; menus via M; click=SELECT |
| 10 | BLOCKED (env) | acks are phone->watch messages; needs real watch |

**Next step: real-watch run (Venu 2)** — the phone already carries the fixed garmin APK; sideload `out-v2/venu2.prg` (`adb push ... /GARMIN/APPS/`, watch in developer mode), turn OFF Settings > GARMIN > "Use Connect IQ simulator (ADB)", GCM installed/paired, then walk the full checklist in `device_test_plan.md` (its on-device sections cover exactly the items the simulator cannot reach).

*Rev. 3 ends. Prepared 2026-10-26 ~22:00 CEST from the live session (user driving the simulator UI, agent verifying via logcat/dumpsys); writes limited to this report, the session progress notes, and the architecture notes below.*

---

## Rev. 4 — 2026-10-26 late evening: ROOT CAUSE of the phone->watch failure found and fixed (real watch)

Rev. 3's conclusion "phone->watch delivery does not work in this simulator environment" was
**wrong** — the messages were delivered all along and silently dropped by the watch app
itself, in every environment (simulator AND real watch over BLE). Evidence chain that found it:

1. Real-watch (Venu 2 over GCM/BLE) testing reproduced the exact simulator symptoms: no state
   display, "RunBeat only" refusals, red diagnostics. So the environment was not the cause.
2. A debug watch build (ack-line diagnostics + loopback echo of received payloads back to the
   phone) proved the payload arrives **byte-perfect**: the phone logged the echoed
   `{"type":"state","source":"none","isPlaying":false,"volume":2}` verbatim.
3. The parser diagnostic showed `PF@0/349 '{'` — parse failing at position 0 while the
   character there IS `{`.

**Root cause: Monkey C `==`/`!=` on Strings compares object references, not content.** The
watch's hand-rolled JSON parser (and every other string comparison in the receive path) used
`==`/`!=`, so `text.substring(0,1) != "{"` was always true and every incoming message was
rejected at the first character check. The official SDK Comm sample's use of `.equals()`
corroborates the semantics. This also explains why the watch's 5 s `request_state` re-ask
stream never paused: `markMessageReceived()` only runs after a successful parse, so a
parse-dropping app looks exactly like a deaf app in the packet-gap analysis used in rev. 3.

**Fix (uncommitted, 2026-10-26):** all string comparisons in `MessageHandler.mc` (parser,
`handleParsed`, `applyState` gate, `applyAck`), `WatchState.mc` (`isRunBeatActive`), and
`view/MainView.mc` (`sourceLabelText`/`sourceLabelColor`) now use `.equals()`.

**Verified on the real watch (user-confirmed):** track title + green "RunBeat" header now
display while playing; RunBeat-only controls no longer refuse; speed correction unlocked.
Checklist items 0/3 (state display), 5 (watch volume display), and the preconditions for
2/4/6/10 are now PASSING/PASSABLE on-device.

**Remaining known bug (real watch + simulator):** every tap on the drawn transport/volume
buttons produces `{"type":"toggle"}` — the per-page `RunBeatPageDelegate.onTap` is apparently
never invoked; taps fall through to `onSelect` (MainView's primary action). This also
corrects rev. 3 §R3.3: simulator clicks were real positional taps all along; the app was
converting them to select. Next: CIQ docs on ViewLoop/BehaviorDelegate touch dispatch, then
fix and re-verify. The `M`-menu path (all commands), swipes, and page cycling all work.

**Temporary debug instrumentation present in the working tree (marked
`TEMP DEBUG BUILD (2026-10-26, revert before commit)`, must be stripped before committing):**
watch `MessageHandler.mc` (OK/PF/T ack-line diagnostics, `_dbgFailPos` markers in the parser,
loopback echo of the first 3 payloads, startup marker), watch `BaseView.mc` (60 s ack expiry,
ack line moved up), phone `GarminCompanionManager.kt` (`TX <len>B: <json>` logging in
`sendTo`).
