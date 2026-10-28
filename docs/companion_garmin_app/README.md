# Garmin Companion App — Plans & Information

**Status: implementation complete; artifacts built and verified (16/16 signed .prg, phone unit tests green 214/214, garminDebug APK built and current). Live simulator e2e pending user — phone tether not staged at verification time (Fairphone 6 not connected over adb). Updated 2026-10-25 — see [implementation_plan_v2_2026-10-24.md](implementation_plan_v2_2026-10-24.md) (section 9, Verification results).**
**Date: 2026-10-22 (updated after decision review)**

This folder contains the feasibility evaluation and implementation plans for a
Garmin watch companion app that remote-controls the RunBeat Android app
(this repository) and acts as the user's **default music app** on the watch.

## Goal

A Garmin watch app, usable as the default music app, exposing from the wrist:

| # | Feature | Notes |
|---|---------|-------|
| 1 | Play control | play / pause / next / previous |
| 2 | Adjust target cadence | manual +/- on the watch, or one button that takes the **average cadence of the last 60 seconds** (measured by the watch) as the new target cadence |
| 3 | Toggle speed correction | on/off (RunBeat only) |
| 4 | Volume up / down | media volume of the phone |
| 5 | Mark current track "not for running" | flags the track in the library (it may sound fine, just not feel right to run on), **skips it immediately**, and excludes it from future sessions via a filter toggle (on by default) |
| 6 | Control other music apps | when RunBeat is not the app playing (Spotify, Qobuz, audiobooks, ...), the same watch controls operate on whatever app holds the phone's media stream — like Garmin's built-in music app |

## Confirmed decisions

- **Transport:** Connect IQ watch app + Connect IQ Mobile SDK on the phone.
- **Mark "not for running":** skips the track immediately during the session
  *and* stores the flag in the library; the flag is a "this track does not
  feel right to run on" marker, not a quality judgment — the wording in the
  app must make that clear.
- **Distribution:** Connect IQ (sideload first; IQ Store submission optional
  later). The F-Droid build of RunBeat is unaffected.
- **Code organization:** two Gradle product flavors (`fdroid` / `garmin`)
  in **this repository**. A separate repo is not recommended (see
  [implementation_plan.md](implementation_plan.md)).
- **Watch UI:** three screens (main transport / cadence / controls). **Updated 2026-10-24:** the main/transport screen is the first-screen music control — current track artist/album/title + on-screen play/pause, next, previous, volume up/down (see [requirements_update_2026-10-24.md](requirements_update_2026-10-24.md)).
- **Bad-track filtering:** stored in the library, excluded from the play
  queue behind a settings toggle, **on by default**.

## Requirements update — 2026-10-24

The watch app must act as the phone's music-control source: full track metadata
(artist / album / title) and on-screen transport (play/pause, next, previous,
volume) on the **first screen**, with the RunBeat phone app recognized over
Connect IQ. The native watch Music app surfaces RunBeat through its built-in
"Phone" source (RunBeat's MediaSession must expose complete metadata). The app
stays a `watch-app` remote — it is **not** converted to an audio-content-provider
(that would force watch-side playback, i.e. the standalone app that is rejected).
Full details, the protocol extension, and acceptance criteria:
[requirements_update_2026-10-24.md](requirements_update_2026-10-24.md).

## Documents

| Document | Content |
|----------|---------|
| [feasibility.md](feasibility.md) | Connectivity options (MediaSession-only vs Garmin Connect IQ), per-feature feasibility incl. external-app control, constraints (F-Droid, privacy, Garmin Connect Mobile dependency) |
| [architecture.md](architecture.md) | System architecture, command routing (RunBeat vs external app), watch-to-phone message protocol, cadence-averaging design, mark-as-not-for-running data flow |
| [phone_app_changes.md](phone_app_changes.md) | Exact changes required in the existing RunBeat codebase (new classes, Room migration, repository/DAO/UI touch points, settings toggle) |
| [watch_app_plan.md](watch_app_plan.md) | Plan for the Connect IQ (Monkey C) watch app: 3 screens, cadence sampling, message handling, default-music-app behavior |
| [implementation_plan.md](implementation_plan.md) | Phased roadmap, effort estimates, risks, remaining open questions |
| [review_watch_app.md](review_watch_app.md) | Independent review of the shipped watch app: verdict, findings, verification evidence |
| [requirements_update_2026-10-24.md](requirements_update_2026-10-24.md) | 2026-10-24 requirements update: music-source control, first-screen transport, protocol extension, acceptance criteria |
| [implementation_plan_v2_2026-10-24.md](implementation_plan_v2_2026-10-24.md) | 2026-10-24 implementation plan v2: SDK constraint re-confirmation, recognition root-cause fix, extended wire format, watch/phone file-level changes, build+verify, task split, acceptance criteria |

## Headline conclusions

- **All six features are feasible.** The recommended transport is a Garmin
  **Connect IQ** watch app paired with the **Connect IQ Mobile SDK** on the
  phone. The app's existing exported `MediaSessionService` already provides
  free basic transport control (play/pause/next/prev/volume) to Garmin's
  generic music controls, but it **cannot** carry the custom commands
  (target cadence, speed-correction toggle, mark-as-not-for-running), so
  Connect IQ is required for the full feature set.
- **The "average cadence of the last minute" is measured on the watch.** The
  phone app has no step/cadence sensor and adding one would be less reliable
  (phone is not on the wrist) and would add the `ACTIVITY_RECOGNITION`
  permission against the project's privacy posture. The watch samples its
  own cadence once per second into a 60-sample ring buffer and sends the
  mean when the dedicated button is pressed.
- **Default-music-app behavior (feature 6)** is achieved by phone-side
  command routing: when RunBeat is playing, commands go to
  `PlayerRepository`; when it is not, transport commands are forwarded as
  media key events (`AudioManager.dispatchMediaKeyEvent`) and volume via
  `AudioManager` STREAM_MUSIC — both permission-free. Reading *metadata*
  (track title) from other apps needs notification-listener access and is an
  optional, user-granted extra. Details in [architecture.md](architecture.md).
- **Main codebase impact:** one new Room column (`Track.notForRunning`) with
  a migration 6→7, a handful of DAO/repository methods, small additions to
  `PlayerRepository`, one new phone-side manager class wrapping the Connect
  IQ SDK plus a media-key dispatcher, a settings toggle (filter, default on),
  and filtering of flagged tracks when the play queue is built. Details in
  [phone_app_changes.md](phone_app_changes.md).
- **Distribution caveat:** the Garmin Connect IQ Mobile SDK is proprietary,
  which conflicts with the F-Droid reproducible-build setup. Mitigation:
  a Gradle product flavor (`garmin` vs `fdroid`) so the F-Droid build stays
  clean. See [feasibility.md](feasibility.md) and
  [implementation_plan.md](implementation_plan.md).

## Status (2026-10-23)

The watch app is implemented and **compiles for all 16 target devices
with Connect IQ SDK 9.3.0 (16/16, one `.prg` per device)**. The
independent review verdict is **merge-ready** (0 blockers, 0 majors, 4
minors, 5 nits) with all actionable findings addressed — full report:
[review_watch_app.md](review_watch_app.md). Build instructions and
verification-scope caveats:
[implementation_plan.md](implementation_plan.md) (Status section).

## Status (2026-10-25 — plan v2 verification)

Plan v2 (music-source control & first-screen transport) is implemented
(agents A watch / B phone) and was verified by agent C on 2026-10-25 —
full results in
[implementation_plan_v2_2026-10-24.md](implementation_plan_v2_2026-10-24.md)
(section 9):

- **Wire-format contract** (plan v2 section 3): PASS — the watch
  `MessageHandler.mc` and the phone codec/router/manager match the
  normative contract exactly; the codec test
  `encodeCommandMatchesWatchWireFormat` pins the exact wire strings.
- **Watch artifacts:** 16/16 signed `.prg` in `$GB/out-v2/` — the
  device list matches `garmin-watch/manifest.xml` exactly, every build
  log ends `BUILD SUCCESSFUL`, and a venu2 rebuild is byte-identical
  (reproducible).
- **Phone tests:** `:app:testGarminDebugUnitTest` green — 214 tests,
  0 failures (forced re-run 2026-10-25).
- **APK:** `app-garmin-debug.apk` built 2026-10-25 07:33 and current
  with the sources (`:app:assembleGarminDebug` UP-TO-DATE).
- **Live simulator e2e:** pending user (plan v2 section 6.3). The phone
  tether could NOT be staged at verification time — the Fairphone 6 was
  not connected (`adb devices` empty; its USB devices Disconnected in
  the PnP enumeration), so `adb install -r` and
  `adb forward tcp:7381 tcp:7381` were not run. Connect the phone over
  USB (USB debugging on), run those two commands, then follow section
  6.3.

## Relationship to the existing `update_plans/garmin_app_plan.md`

That older plan describes a **standalone watch music player** (music stored
and played on the watch itself, tempo adjusted on the watch). The present
work is the opposite direction: the music stays on the phone, and the watch
is a **remote control** for the phone — extended to control *any* phone
media app, not just RunBeat. Only the cadence-monitoring module design is
shared.
