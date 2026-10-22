# Garmin Companion App — Plans & Information

**Status: evaluation complete, plans documented, no code changes made (as requested).**
**Date: 2026-10-22**

This folder contains the feasibility evaluation and implementation plans for a
Garmin watch companion app that remote-controls the RunBeat Android app
(this repository).

## Goal

A Garmin watch app exposing, from the wrist:

| # | Feature | Notes |
|---|---------|-------|
| 1 | Play control | play / pause / next / previous |
| 2 | Adjust target cadence | manual +/- on the watch, or one button that takes the **average cadence of the last 60 seconds** (measured by the watch) as the new target cadence |
| 3 | Toggle speed correction | on/off |
| 4 | Volume up / down | music volume of the phone player |
| 5 | Mark current track as bad | so it can be filtered out during the next session |

## Documents

| Document | Content |
|----------|---------|
| [feasibility.md](feasibility.md) | Connectivity options (MediaSession-only vs Garmin Connect IQ), per-feature feasibility, constraints (F-Droid, privacy, Garmin Connect Mobile dependency) |
| [architecture.md](architecture.md) | System architecture, component responsibilities, watch-to-phone message protocol, cadence-averaging design, mark-as-bad data flow |
| [phone_app_changes.md](phone_app_changes.md) | Exact changes required in the existing RunBeat codebase (new classes, Room migration, repository/DAO/UI touch points) |
| [watch_app_plan.md](watch_app_plan.md) | Plan for the Connect IQ (Monkey C) watch app: screens, cadence sampling, message handling |
| [implementation_plan.md](implementation_plan.md) | Phased roadmap, effort estimates, risks, open questions |

## Headline conclusions

- **All five features are feasible.** The recommended transport is a Garmin
  **Connect IQ** watch app paired with the **Connect IQ Mobile SDK** on the
  phone. The app's existing exported `MediaSessionService` already provides
  free basic transport control (play/pause/next/prev/volume) to Garmin's
  generic music controls, but it **cannot** carry the custom commands
  (target cadence, speed-correction toggle, mark-as-bad), so Connect IQ is
  required for the full feature set.
- **The "average cadence of the last minute" must be measured on the watch.**
  The phone app has no step/cadence sensor and adding one would be less
  reliable (phone is not on the wrist) and would add the
  `ACTIVITY_RECOGNITION` permission against the project's privacy posture.
  The watch samples its own cadence once per second into a 60-sample ring
  buffer and sends the mean when the dedicated button is pressed.
- **Main codebase impact:** one new Room column (`Track.isBad`) with a
  migration 6→7, a handful of DAO/repository methods, small additions to
  `PlayerRepository` (volume, state exposure), one new phone-side manager
  class wrapping the Connect IQ SDK, and filtering of bad tracks when the
  play queue is built. Details in [phone_app_changes.md](phone_app_changes.md).
- **Distribution caveat:** the Garmin Connect IQ Mobile SDK is proprietary,
  which conflicts with the F-Droid reproducible-build setup. Recommended:
  a Gradle product flavor (`garmin` vs `fdroid`) so the F-Droid build stays
  clean. See [feasibility.md](feasibility.md) and
  [implementation_plan.md](implementation_plan.md).

## Relationship to the existing `update_plans/garmin_app_plan.md`

That older plan describes a **standalone watch music player** (music stored
and played on the watch itself, tempo adjusted on the watch). The present
work is the opposite direction: the music stays on the phone in RunBeat, and
the watch is a **remote control** for the phone app. The two are compatible
but distinct projects; only the cadence-monitoring module design is shared.
