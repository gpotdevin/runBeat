# Requirements update — 2026-10-24: music-source control & first-screen transport

**Status: requirements updated; implementation plan requested next.**

**Supersedes:** the "watch app is a music controller, not a music source" decision
and the "no Media permission — music always plays on the phone" note in
`architecture.md` / `watch_app_plan.md`, and the "Watch UI: three screens
(main transport / cadence / controls)" framing in `README.md`.

**Context (from simulator run, venu2):** the watch app launches and its three
screens are navigable, but (a) the RunBeat phone app is **not recognized** by
the watch — no live track info, commands do not land; (b) via the watch's
standard music controls play/pause works but **volume does not**; (c) the first
screen shows only the track **title** — no artist/album — and next/previous
live in a menu, not on screen. Target phone configuration: Garmin Connect +
Garmin Connect IQ installed and set up.

## 1. Hard technical constraint (verified against Connect IQ SDK 9.3.0)

The only Connect IQ mechanism for a watch app to appear as a **music source in
the watch's native Music app** is the **Audio Content Provider**: manifest
`type="audio-content-provider-app"`, base class
`Toybox.Application.AudioContentProviderApp`, and the `Toybox.Media` module
(`Content` / `ContentRef` / `ContentDelegate` / `PlaybackProfile` /
`setAlbumArt` / `startPlayback`). Garmin's own documentation states audio
content providers exist to "listen to their favorite content from your service
while **leaving their phone behind**" — i.e. **watch-side playback** driven by
the native watch media player.

That is incompatible with the product requirement that **music plays on the
phone** (RunBeat's tempo/cadence engine runs in the phone app). Converting to an
audio-content-provider would make it the "standalone app" the user explicitly
rejects ("not standalone app otherwise I cannot use it while running") and would
bypass the phone engine.

There is **no** `Toybox.Media.registerMediaPlayer` / `MediaPlayer` /
`PlaybackInfo` / `TrackInfo` in SDK 9.3.0 (the API page 404s and the device
`*.api.debug.xml` contains no such symbols).

**Decision (revised):** keep the watch app a `watch-app` remote (music plays on
the phone). "Registers as music source" is delivered as: **the watch app is the
phone's music-control source**, showing full track metadata and transport on its
**first screen**, tightly linked to the RunBeat phone app. The watch's **native**
Music app surfaces RunBeat through its built-in **"Phone"** source, which
reflects the phone's active MediaSession — so RunBeat's phone `MediaSession`
must expose complete metadata (title / artist / album) and playback state.

The implementation-plan agent must re-confirm this constraint (search for any
phone-playback source mechanism in SDK 9.3.0) and, if one exists, prefer it;
otherwise proceed as above and record the finding.

## 2. Goals (updated)

| # | Feature | Notes |
|---|---------|-------|
| 1 | First screen = music control | current track **artist / album / title**, play/pause, **next**, **previous**, **volume up/down** — all on the first screen, no menu digging |
| 2 | Phone recognized | watch app establishes the Connect IQ link to the RunBeat phone app and shows live track info; clear "connected / not connected" status |
| 3 | Volume works | volume up/down changes the phone STREAM_MUSIC volume and the watch shows the current volume |
| 4 | (unchanged) cadence, speed-correction, mark-not-for-running | keep on the cadence / controls screens |
| 5 | Native Music app shows RunBeat | RunBeat's phone MediaSession exposes title / artist / album + play state so the watch's "Phone" source displays it correctly |

## 3. Protocol extension (watch <-> phone)

Extend the phone→watch `state` message with `artist` and `album` (**required**)
and `durationMs`, `positionMs` (**recommended**, for a progress display). Keep
all existing fields. Optionally add a watch→phone `seek` command
`{"type":"seek","positionMs":N}`. The normative wire format lives in
`architecture.md` and must be updated by whoever changes the codec.

## 4. Non-goals

- Do NOT convert the watch app to `audio-content-provider-app` / watch-side playback.
- No watch-side audio decoding; music always plays on the phone.
- External-app (non-RunBeat) metadata reading stays out of scope (no notification listener).

## 5. Acceptance criteria (simulator, venu2, phone tethered via ADB)

1. Watch `.prg` builds for all 16 devices (signed, RSA-4096 developer key).
2. First screen shows live RunBeat track **artist / album / title**.
3. On-screen **next / previous** change the phone track; **play/pause** toggles;
   **volume up/down** change the phone STREAM_MUSIC volume and the watch shows
   the current volume.
4. Watch shows "connected" when the phone link is up; "Phone not connected" otherwise.
5. Phone unit tests pass for the extended codec (artist/album round-trip) and
   router (`currentState()` includes artist/album from `MediaMetadata`).
6. `garminDebug` APK builds and installs on the phone.

## 6. Task split (for the implementation phase)

- **Watch app** (`garmin-watch/`): extend `WatchState` + `MessageHandler`
  (artist/album/duration/position), rework `MainView` first screen (track info +
  transport + volume), keep cadence/controls screens, rebuild `.prg`.
- **Phone app** (`app/src/.../garmin/` + main source set): extend
  `GarminMessageCodec` / `GarminWatchState` / `GarminCommandRouter.currentState()`
  with artist/album (from `MediaMetadata.artist` / `albumTitle`) + duration /
  position; fix phone recognition (link setup, proactive state push on connect,
  watch→phone state request); ensure the MediaSession metadata is complete.
- **Build + verify**: build watch `.prg` + Android APK, run the simulator,
  verify the acceptance criteria, update docs.
