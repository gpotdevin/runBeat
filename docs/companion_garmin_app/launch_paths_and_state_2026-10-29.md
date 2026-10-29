# Watch app launch paths — investigation & state of implementation (2026-10-29)

## Question

Can the RunBeat watch app be a music provider (Music → Music Providers)
while ALSO being launchable directly, without the stock Music app
starting or being left behind on exit?

**Answer: yes — on this firmware (user's Venu 2), and the current
implementation already supports both paths.** The reference evidence is
Spotify on the same watch: it appears both in the music provider list and
as a standard app-menu entry, and launching it from the app menu runs it
standalone — exiting shows no sign of the stock Music app. (An earlier
claim in this repo that provider placement necessarily means "the stock
Music app hosts every session" was wrong: that is true only for the
provider-flow launch, not for the direct app-menu launch.)

## SDK facts (Connect IQ 9.3.0 api.debug.xml)

- `Application.AudioContentProviderApp.getPlaybackConfigurationView()` —
  the entry the system calls when the app is launched from the music
  provider flow. Documented as "the initial View and Input Delegate for
  configuring playback"; must be overridden.
- `AppBase.getInitialView()` — the standard direct-launch entry. In the
  ACP class it fills the same role: the app-menu shortcut launches the
  app and the system shows this view as a standalone session.
- An app of type `audio-content-provider-app` appears BOTH in the music
  provider list AND in the app menu on current firmware (observed:
  Spotify on Venu 2; RunBeat expected to behave identically — verify).

## What the app implements (both entry points return the same remote UI)

`RunBeatCompanionApp.getPlaybackConfigurationView()` returns
`getInitialView()`, which is the three-page ViewLoop remote
(Main / Controls / Cadence). Nothing distinguishes the two launch paths
in code — the session behavior differs only in which system context
hosts it:

| | Provider-flow launch | Direct (app-menu) launch |
|---|---|---|
| Entry point | Music → Music Providers → RunBeat | App menu → RunBeat |
| System calls | `getPlaybackConfigurationView` | `getInitialView` |
| Host context | stock Music app flow | standalone app session |
| Back from remote | lands on the Music app (one extra press) | expected: watch face, no Music app (Spotify-observed; verify) |
| `System.exit` | no-op on device (2026-10-29; guarded with try/catch) | expected to work as in a device-app session (verify: back-exit and the 3-min idle auto-exit) |

## State of implementation (commits)

- `1847866` — cadence page: three explicit action buttons (SET TARGET /
  USE AVG AS TARGET / TEMPO ON-OFF).
- `1d841e5` — battery: phone pushes state only within a 30 s
  watch-app active window (armed by any watch message, dropped by
  `bye`); `bye` farewell in onStop; `request_state` keepalive every 5 s
  while playing; `{"type":"wake"}` from the phone on playback start with
  no active watch app; background service
  (`RunBeatBackgroundService.mc`, `Background` permission) turns `wake`
  into a launch dialog.
- `14e51b3` — back never swallowed: `onBack` attempts `System.exit()`
  and always returns false (in the provider flow `System.exit` is a
  no-op; returning true had trapped the user).
- `8dede98` — brief experiment: plain `watch-app` type (app-list launch,
  clean exits, no provider list).
- `c3c4ca4` — reverted the experiment: provider-list placement is a
  hard requirement; ACP type restored; idle auto-exit guarded.

## Device-verification checklist (Venu 2, sideload venu2.prg)

1. RunBeat appears in the provider list AND in the app menu (like
   Spotify).
2. Direct launch from the app menu: back exits to the watch face, no
   stock Music app; repeat back/enter cycles cleanly.
3. Direct launch: after 3 min idle with music paused, the app closes
   itself (`System.exit` should work in that session type).
4. Provider-flow launch: back lands on the Music app (accepted
   trade-off of that entry point), back again leaves.
5. Wake: with the watch app closed and the phone app playing, starting
   playback shows the "RunBeat is playing" launch dialog.
6. Battery, full day with music playing part of it: back to multi-day
   idle drain (the phone no longer pushes at a closed app).
