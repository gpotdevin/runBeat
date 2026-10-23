# Garmin Companion App — Plans & Information

**Status: decisions confirmed; watch app implemented and compiling for all 15 target devices; independent review verdict: merge-ready.**
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
- **Watch UI:** three screens (main transport / cadence / controls).
- **Bad-track filtering:** stored in the library, excluded from the play
  queue behind a settings toggle, **on by default**.

## Documents

| Document | Content |
|----------|---------|
| [feasibility.md](feasibility.md) | Connectivity options (MediaSession-only vs Garmin Connect IQ), per-feature feasibility incl. external-app control, constraints (F-Droid, privacy, Garmin Connect Mobile dependency) |
| [architecture.md](architecture.md) | System architecture, command routing (RunBeat vs external app), watch-to-phone message protocol, cadence-averaging design, mark-as-not-for-running data flow |
| [phone_app_changes.md](phone_app_changes.md) | Exact changes required in the existing RunBeat codebase (new classes, Room migration, repository/DAO/UI touch points, settings toggle) |
| [watch_app_plan.md](watch_app_plan.md) | Plan for the Connect IQ (Monkey C) watch app: 3 screens, cadence sampling, message handling, default-music-app behavior |
| [implementation_plan.md](implementation_plan.md) | Phased roadmap, effort estimates, risks, remaining open questions |
| [review_watch_app.md](review_watch_app.md) | Independent review of the shipped watch app: verdict, findings, verification evidence |

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

The watch app is implemented and **compiles for all 15 target devices
with Connect IQ SDK 9.3.0 (15/15, one `.prg` per device)**. The
independent review verdict is **merge-ready** (0 blockers, 0 majors, 4
minors, 5 nits) with all actionable findings addressed — full report:
[review_watch_app.md](review_watch_app.md). Build instructions and
verification-scope caveats:
[implementation_plan.md](implementation_plan.md) (Status section).

## Relationship to the existing `update_plans/garmin_app_plan.md`

That older plan describes a **standalone watch music player** (music stored
and played on the watch itself, tempo adjusted on the watch). The present
work is the opposite direction: the music stays on the phone, and the watch
is a **remote control** for the phone — extended to control *any* phone
media app, not just RunBeat. Only the cadence-monitoring module design is
shared.
