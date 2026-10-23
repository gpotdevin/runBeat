# Feasibility Evaluation

Date: 2026-10-22

## Connectivity options

Garmin watches are **not** Wear OS devices. There is no Android
`MediaBrowserService` binding path from a Garmin watch to a phone app. The
realistic options are:

### Option A — Android MediaSession only (no watch app)

Garmin watches can control *any* Android media app through the Garmin
Connect Mobile "Music" app / music data fields, which drive the standard
Android media session (transport buttons, sometimes volume).

- **Cost:** zero. `AudioService` is already an exported
  `MediaSessionService` (`app/src/main/AndroidManifest.xml`), so this works
  today with no code change.
- **Covers:** play, pause, next, previous, volume (partially — depends on
  the Garmin music app UI).
- **Does NOT cover:** target cadence adjustment, speed-correction toggle,
  mark-as-bad. The media session protocol has no channel for app-specific
  commands that a Garmin music app would send.
- **Verdict:** insufficient alone; useful as a free fallback so basic
  control works even before/without the Connect IQ app installed.

### Option B — Garmin Connect IQ watch app + Connect IQ Mobile SDK (recommended)

- **Watch side:** a Connect IQ app written in Monkey C, installed on the
  watch (IQ Store or sideloaded via developer mode / Garmin Express).
- **Phone side:** the Garmin **Connect IQ Mobile SDK for Android**
  (`com.garmin.connectiq:ciq-companion-app-sdk`, distributed from Garmin's
  Maven repository) integrated into RunBeat.
- **Transport:** messages are routed phone <-> watch through **Garmin
  Connect Mobile** over BLE. The phone app never talks BLE directly; it
  registers an IQ app with the `ConnectIQ` singleton and exchanges small
  messages (`sendMessage` / `onMessage`).
- **Covers:** all five requested features, plus two-way state sync so the
  watch can display current track, BPM, target cadence, speed-correction
  state, volume, and playback speed.
- **Verdict:** the only option that covers the full feature set. Recommended.

### Option C — Wear OS companion app

Not applicable: Garmin watches do not run Wear OS. (Relevant only if the
user later wants to support Pixel Watch / Galaxy Watch.)

### Option D — BLE GATT directly from the phone app

Garmin does not expose a public BLE GATT API for third-party phone-to-watch
messaging; Connect IQ is Garmin's supported channel. Rejected.

## Per-feature feasibility

| Feature | Feasibility | How |
|---------|-------------|-----|
| Play control (play/pause/next/prev) | High | Watch button -> Connect IQ message -> `PlayerRepository.togglePlayPause()/playNext()/playPrevious()` (all exist). Free fallback via MediaSession (Option A). |
| Adjust target cadence manually (watch UI) | High | Watch +/- buttons -> message -> `PlayerRepository.setTargetCadence()` / `incrementTargetCadence()` / `decrementTargetCadence()` (exist; clamped to 150-195 in code). |
| Target cadence = avg cadence of last minute | High | The **watch** measures cadence (wrist-worn, most accurate source). Sample once per second (primary source: `Toybox.Sensor` `Sensor.getInfo().cadence`, accelerometer-based; last-resort fallback: step-delta from `ActivityMonitor.getInfo().steps`) into a 60-sample ring buffer; the dedicated button sends the rolling mean to the phone, which calls `setTargetCadence(mean)`. Phone-side step counting was considered and rejected (see below). |
| Toggle speed correction | High | Message -> `PlayerRepository.toggleSpeedCorrection()` (exists; enable re-applies cadence-matched speed, disable restores 1.0x). |
| Volume up/down | High | Message -> `exoPlayer.volume` +/- step (0.1), clamped [0, 1]. Small addition to `PlayerRepository`. Edge case: interaction with the mode-beep ducking (`volumeBeforeDuck` in `PlayerRepository`) — see [phone_app_changes.md](phone_app_changes.md). |
| Mark current track as "not for running" | High | Message with current `trackId` (already in `MediaItem` extras) -> new `Track.notForRunning` Room column (migration 6->7) + `TrackDao` update + `PlayerRepository.removeTrackFromUpcoming()` + `playNext()` to **skip immediately** + filter flagged tracks when the play queue is built (settings toggle, default on). Wording in the app must make clear it means "does not feel right to run on", not "bad track". |
| Control other music apps (Spotify, Qobuz, audiobooks...) | High | When RunBeat is not playing, the phone companion forwards transport commands as media key events (`AudioManager.dispatchMediaKeyEvent`) and volume via `AudioManager` STREAM_MUSIC — no permissions needed. Now-playing *metadata* from other apps requires notification-listener access (optional, user-granted). See "External app control" below. |

## Why the cadence average must come from the watch

- RunBeat has **no live cadence source today**: target cadence is purely
  manual (150-195, default 172 — see `PlayerRepository` and
  `docs/REQUIREMENTS.md` FR-004).
- A phone step counter would need `ACTIVITY_RECOGNITION` (Android 10+) plus
  a foreground service to count reliably with the screen off, and a phone in
  a pocket/bag underestimates cadence. The watch is on the wrist and exposes
  cadence directly via `Toybox.Sensor` (`Sensor.getInfo().cadence`,
  accelerometer-based; per-second sampling of step deltas from
  `ActivityMonitor.getInfo().steps` is the coarse last-resort fallback).
- This also keeps the project's privacy posture intact: no new phone
  permissions, no health-data collection on the phone
  (see `docs/PRIVACY.md`).

## External app control (feature 6: default music app)

Goal: when RunBeat is **not** the app producing sound, the watch remote
works on whatever app is (Spotify, Qobuz, audiobook players, ...) — the same
role Garmin's built-in music app plays.

- **Transport (play/pause/next/prev):** the phone companion calls
  `AudioManager.dispatchMediaKeyEvent(KeyEvent)`. Android routes the key to
  the app that currently holds media focus. No permission required.
- **Volume:** `AudioManager.adjustStreamVolume(STREAM_MUSIC, ...)` — global
  media volume, works for every app. Recommended as the *single* volume
  path for watch volume control (RunBeat included: ExoPlayer outputs to
  STREAM_MUSIC and its own volume stays a multiplier on top).
- **Now-playing metadata from other apps:** `MediaSessionManager
  .getActiveSessions()` requires the system-only `MEDIA_CONTENT_CONTROL`
  permission — not available to third-party apps. The realistic source is a
  `NotificationListenerService` reading MediaStyle notifications, which
  needs the user to grant notification access in system settings. Garmin
  Connect Mobile does exactly this for the built-in music app.
  **Decision: make metadata optional.** Without it the watch shows a generic
  "External app" label; with it, title/source app. This is a privacy-
  relevant permission, so it must stay opt-in and be documented in
  `docs/PRIVACY.md`.
- **Routing rule (phone side):** if RunBeat's playback service is active /
  its player is playing, commands go to `PlayerRepository`; otherwise they
  are forwarded as media keys. The watch stays dumb — it always sends the
  same commands and displays the `source` field pushed by the phone
  (`RunBeat` / external app name / none).
- **Custom commands** (cadence target, speed correction, mark
  not-for-running) only make sense for RunBeat; the watch disables them
  when `source != RunBeat`.

## Constraints and caveats

1. **Garmin Connect Mobile dependency.** The Connect IQ channel is routed
   through the Garmin Connect Mobile app. If GCM is not installed, not
   logged in, or the watch is not paired, the companion features do not
   work. Basic transport still works via Option A (MediaSession).
   The watch app should degrade gracefully (show "not connected").
2. **Proprietary SDK vs F-Droid.** RunBeat is distributed via F-Droid
   (`docs/fdroid-metadata.yml`, GPL-3.0-or-later, reproducible builds).
   The Connect IQ Mobile SDK is proprietary, which F-Droid's build servers
   cannot fetch reproducibly. **Recommended mitigation:** Gradle product
   flavors — a `garmin` flavor that includes the SDK, and the default/`fdroid`
   flavor without it. The F-Droid build keeps the current dependency set.
   Alternative: publish the companion as a separate small app (bigger
   distribution friction; not recommended).
3. **Privacy note.** With Connect IQ, watch<->phone messages transit
   Garmin Connect Mobile (and Garmin's infrastructure). Message content is
   limited to playback state and cadence numbers — no personal data — but
   `docs/PRIVACY.md` should be updated to mention the optional Garmin
   integration.
4. **IQ app distribution.** The watch app must be installed on the watch:
   either sideloaded (developer mode on the watch + Garmin Express or the
   SDK's sideloading tools — fine for personal use) or published on the
   Connect IQ Store (required for public distribution; subject to Garmin
   review).
5. **Device API coverage.** Cadence APIs differ across devices/API levels;
   the watch app must declare a sensible `minSdk` (Connect IQ API level) and
   fall back to step-delta sampling from `ActivityMonitor.getInfo().steps`
   when `Toybox.Sensor` is unavailable or returns no cadence.
   Target primary devices: Forerunner 245/255/265/955/965, fenix 7/8,
   epix, Venu 3 (all have accelerometer-based cadence and Connect IQ support).
6. **Battery.** Sampling cadence at 1 Hz from the watch app is cheap; avoid
   continuous phone polling. State should be pushed phone->watch on change,
   not polled.
7. **Offline posture.** The Connect IQ Mobile SDK does not require the
   `INTERNET` permission on the phone (communication is delegated to Garmin
   Connect Mobile), so the app's no-network manifest stance can be preserved.

## Recommendation

**Option B (Connect IQ), with Option A kept as-is** as the zero-cost basic
fallback. Implement the phone side behind a `garmin` product flavor so the
F-Droid build is unaffected.
