# Garmin Companion App — RunBeat watch remote

**Status: implemented and build-verified; on-device checklist in progress. Docs synced 2026-10-29.**

A Garmin Connect IQ watch app (`garmin-watch/`, Monkey C) that remote-controls the RunBeat
Android app from the wrist. Music always plays on the **phone**, never on the watch.
Wire protocol (normative): [architecture.md](architecture.md). On-device checklist:
[device_test_plan.md](device_test_plan.md). Simulator procedure:
[simulator_runbook_2026-10-29.md](simulator_runbook_2026-10-29.md).

## Features (all six shipped)

| # | Feature | How |
|---|---------|-----|
| 1 | Play / pause / next / prev | watch command → `PlayerRepository`; when RunBeat isn't the player, forwarded as media key events |
| 2 | Target cadence ±, or = 60 s average | watch samples its own cadence 1 Hz into a 60-sample ring buffer; "use current" sends the mean → `setTargetCadence` (clamped 150–195) |
| 3 | Speed-correction toggle | → `PlayerRepository.toggleSpeedCorrection()` |
| 4 | Volume up/down | `AudioManager` STREAM_MUSIC — global media volume, works for every app |
| 5 | Mark track "not for running" | sets `Track.notForRunning` in Room (migration 6→7) **and skips immediately**; excluded from future queues behind a settings toggle (default ON). Wording: "does not feel right to run on", not a quality rating |
| 6 | Control other music apps | With notification access granted (`GarminMediaNotificationListener`), transport → `MediaSessionManager.getActiveSessions` → playing external session's `MediaController.TransportControls`; fallback → `AudioManager.dispatchMediaKeyEvent` (unreliable on some builds). Volume → STREAM_MUSIC, permission-free. External-app metadata is **not** shipped; the watch shows "Nothing playing" then |
| 7 | Play a library playlist | Watch Playlists page (4th ViewLoop page) lists the phone's playlists (`playlist_list` → one `playlists` push); tap a row → `playlist_play` → phone loads the queue (not-for-running filter applied) and plays |

## Confirmed decisions

- **Transport:** Connect IQ watch app + Connect IQ Mobile SDK on the phone, relayed by Garmin
  Connect Mobile over BLE. The exported `MediaSessionService` remains a free fallback for basic
  transport only. Wear OS / raw BLE GATT not applicable.
- **Flavors:** `fdroid` (default, no Garmin SDK, what F-Droid builds via `assembleFdroidRelease`)
  and `garmin` (adds `com.garmin.connectiq:ciq-companion-app-sdk:2.4.0`). Both share applicationId
  `com.RunBeat.run.audio` → not co-installable.
- **Cadence is measured on the watch** (wrist sensor; avoids `ACTIVITY_RECOGNITION` on the
  phone). Only the 60 s mean crosses the link, on button press.
- **Manifest type `audio-content-provider-app`** (decided 2026-10-29): the app appears in both
  Music → Music Providers and the app menu; both launch entry points return the same 3-page
  remote UI — there is **no watch-side playback**. RunBeat also surfaces in the native Music
  app's "Phone" source via MediaSession metadata. Launch-path details: architecture.md.
- **Watch UI:** 4 ViewLoop pages — Main (artist/album/title + play/pause, next, prev, volume +
  "No good run" button), Controls, Cadence, Playlists. fr245 (CIQ 3.3.1 < 3.4.0) falls back to Main-only.
- **Battery:** phone pushes state only within a 30 s window of the last incoming watch message
  (armed by the 5 s `request_state` keepalive, dropped by `bye`); `wake` + background service
  re-activates a closed app when playback starts; 3-min idle auto-exit; back is never
  swallowed. Details: architecture.md "Battery & lifecycle".
- **Distribution:** sideload `.prg` with the local developer key (sideload only — never valid
  for the IQ Store); IQ Store submission optional later.

## Where things live

- Watch app: `garmin-watch/` — Connect IQ SDK 9.3.0, 16 product ids, IQ app UUID
  `06c14376-0c60-4e4c-a977-e93364ace3eb`, permissions `Communications` + `Sensor` + `Background`
- Phone Garmin layer: `app/src/main/java/com/bpmapp/audio/audio/garmin/` (SDK-free: codec,
  router, media-key dispatcher) and `app/src/garmin/java/...` (SDK wiring:
  `GarminCompanionManager`)
- Build scripts, developer key, per-device `.prg` outputs: outside the repo at
  `/c/Users/guill/dev/tools/garmin-build/`
