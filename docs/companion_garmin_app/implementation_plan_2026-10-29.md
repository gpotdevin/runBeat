# Implementation plan — 2026-10-29 (coordinator: Vibe)

Three features, stepwise, each step ending with real-device (Venu 2) testing.
Simulator is not trusted for verification until further notice.
Per step: build phone APK (`:app:assembleGarminDebug`) + watch `.prg`
(`compile_one.sh`), `adb push venu2.prg /GARMIN/APPS/`, test on device,
sync normative docs (`architecture.md`, codec + `MessageHandler.mc` headers —
pinned by unit tests), extend `device_test_plan.md`.

## Step 1 — Watch: tag current track "not for running" (completes feature 2 watch-side)

- Watch: `MessageHandler.mc` `sendMarkNotForRunning()` → `{"type":"mark_not_for_running"}`;
  `MainViewMenu.xml` + MainView menu delegate: "Not for running" item, source-gated
  (`source != "RunBeat"` → ack "RunBeat only", do not send); new strings.
- Phone: nothing new — codec, router (`markCurrentTrackNotForRunning` = Room flag +
  `playNext()`), ack already exist. Verify + unit tests + build APK.
- Device test: mark from watch → ack, immediate skip, next track in state push;
  Android badge + Settings exclusion ON removes track from queue, OFF restores;
  unmark from TrackCard. Both launch paths.

## Step 2 — External music app control (feature 3: verify on device, fix-forward)

- Phone code complete (MediaKeyDispatcher media keys + STREAM_MUSIC; transport routed
  to media keys only when RunBeat not playing/buffering).
- Device matrix: Spotify/Qobuz/audiobook playing, RunBeat stopped → watch shows
  "Nothing playing", transport + volume work on external app, RunBeat-only controls
  ack ok=false; paused-but-loaded RunBeat must not capture keys.
- Docs + checklist rows 7/8.

## Step 3 — Select & play a playlist from the watch (feature 1)

Protocol (flat JSON only — watch parser skips nested arrays/objects and drops
`\uXXXX` escapes; lists go as indexed fields):

```
watch→phone  {"type":"playlist_list"}                       (link-layer, answered with one push, no ack)
phone→watch  {"type":"playlists","count":N,"id0":"...","name0":"...",...}
watch→phone  {"type":"playlist_play","playlistId":"..."}    (acked)
```

- Phone: codec + `GarminCommand.PlaylistList`/`PlaylistPlay` + `GarminPlaylists`
  payload (unit-test pinned); extract `LibraryViewModel.getMediaItemForTrack()` into
  injectable `MediaItemFactory`; share the `exclude_not_for_running_tracks` pref
  key; new `PlaylistRepository`; router plays playlist (loadPlaylist + play, ack);
  manager intercepts `playlist_list` like `request_state`.
- Watch: `WatchState` playlist list + selection; `MessageHandler` send/apply;
  4th ViewLoop page `PlaylistsView` (fetch on show, move highlight, Select plays);
  fr245 CIQ 3.3.1 fallback stays Main-only.
- Device test: list matches Android playlists, play starts first eligible track,
  next/prev stay in playlist, all-marked playlist → ack ok=false; regression of
  steps 1–2.

## Execution order

Step 1 → Step 2 → Step 3, one build + device session per step.
Assumption: watch playlist playback respects the "exclude not-for-running"
setting, same as the Android UI.
