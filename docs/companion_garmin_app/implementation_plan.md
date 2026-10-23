# Implementation Plan — Garmin Companion for RunBeat

Date: 2026-10-22

## Status (2026-10-23)

**The watch app is implemented and compiles for all 16 target devices
with Connect IQ SDK 9.3.0 — 16/16, one `.prg` per device, zero
warnings.** Device matrix: `fr245`, `fr255`, `fr265`, `fr955`, `fr965`,
`fenix7`, `fenix7s`, `fenix7x`, `fenix8solar47mm`, `fenix8solar51mm`,
`fenix843mm`, `fenix847mm`, `epix2`, `venu2`, `venu3`, `venu3s`.
`venu2` (Venu 2) was added as the developer's own device for on-device
testing.

- **Product-id correction (commit `bbe20f2`):** the original
  manifest/jungle used 12 ids, several of which do not exist as Garmin
  products. Corrected mapping: `forerunner245` -> `fr245`,
  `forerunner255` -> `fr255`, `forerunner265` -> `fr265`,
  `forerunner955` -> `fr955`, `forerunner965` -> `fr965`, `fenix8` ->
  `fenix8solar47mm` + `fenix8solar51mm` + `fenix843mm` + `fenix847mm`
  (4 ids); `fenix7`, `fenix7s`, `fenix7x`, `epix2`, `venu3`, `venu3s`
  were already correct. Total: 15.
- **SDK 9.3.0 migration (commit `47f1ad3`):** APIs removed/changed by
  SDK 9.x and their replacements — `using` -> `import`; `Toybox.App` ->
  `Toybox.Application`; `Communications.PhoneAppMessage.payload` ->
  `.data`; module-level `WatchUi.setLayout(...)` -> `onLayout(dc)` +
  `setLayout(Rez.Layouts.X(dc))`; `new WatchUi.Menu(resId)` ->
  `new Rez.Menus.X()`; `onMenuItem(item as MenuItem)` /
  `MenuItem.getId()` -> `onMenuItem(item as Symbol)` + `switch (item)`;
  1-arg `Dc.setColor(color)` -> 2-arg `Dc.setColor(fg, bg)`;
  `SwitchableView` -> `WatchUi.ViewLoop` (3.4.0+, runtime-guarded with
  a MainView fallback); `COLOR_LIGHT_GRAY`/`COLOR_DARK_GRAY` ->
  `COLOR_LT_GRAY`/`COLOR_DK_GRAY`; `ActivityMonitor` is not a valid
  permission id (monkeyc rejects it) — permission removed. The manifest
  must be v3 (SDK 9.x requirement) with
  `launcherIcon="@Drawables.LauncherIcon"` plus a `resources/drawables/`
  PNG (commits `1d5ec06`, `2ff9ba0`).
- **Build instructions:** SDK 9.3.0 is installed at
  `C:/Users/guill/dev/tools/Garmin/ConnectIQ/Sdks/connectiq-sdk-win-9.3.0-2026-10-20-95888f262`;
  Java 17; the `monkeyc` CLI needs `APPDATA=/c/Users/guill/dev/tools`.
  Compile scripts live outside the repo at
  `/c/Users/guill/dev/tools/garmin-build/` (`compile_one.sh <device>
  [project] [out]`, and `compile_repo_all.sh [project] [out]`, which
  loops the 16 devices and writes `results.txt`). Gotcha: the SDK
  `bin/default.jungle` had to be regenerated for the `round-WxH`
  deviceFamily syntax — do not hand-edit device entries there. A local
  developer key exists at
  `/c/Users/guill/dev/tools/garmin-build/developer_key.der` — it signs
  sideloaded builds only and is NOT valid for the Connect IQ store.
- **Verification scope caveat:** the local device data was assembled
  locally; the compile matrix is LOCAL verification only. Running device
  simulators and obtaining an official (store) developer key require a
  Garmin developer account login; uploading to the Connect IQ store is
  impossible without it.
- **fr245 runtime note:** fr245 runs CIQ 3.3.1 < 3.4.0, so it takes the
  MainView-only fallback at runtime (play control preserved; the cadence
  and control pages are unreachable on that model).
- **Review finding 4 (open verification item):**
  `Sensor.getInfo().cadence` is read without enabling sensors; if a
  device returns null while running, the app SILENTLY falls back to the
  coarse step-delta cadence (quantized to multiples of 60 spm), which
  could set a wrong target for "use current cadence". Action: verify
  cadence accuracy on a real fr245 AND on a fenix/Venu device during
  Phase 3 device testing; if null while running, either re-add sensor
  enabling or surface "no cadence source".
- **Review outcome:** independent review (Mistral Large 4) verdict
  **merge-ready** — 0 blockers, 0 majors, 4 minors, 5 nits. Minors
  1/2/5 were fixed in commit `69057c6`; minor 3 is the docs sync (this
  change); minor 4 is the real-device verification item above. Nits: 6
  (unused `import Toybox.Lang;` + dead `CadenceRingBuffer.size()` /
  `reset()`) deliberately NOT applied (harmless); 8 (no `Toybox.Test`
  watch test suite — future work: parser edge cases, ring-buffer math,
  command JSON shapes); 9 (48px launcher icon upscaled on 60/70px
  devices — accepted for a sideloaded personal app). Full report:
  [review_watch_app.md](review_watch_app.md).

## Phases

### Phase 0 — Decisions (confirmed 2026-10-22)

1. **Transport:** Connect IQ watch app + Connect IQ Mobile SDK. Confirmed.
2. **Mark "not for running":** flags the track in the library **and skips
   it immediately** during the session. The wording makes clear it means
   "does not feel right to run on", not a quality rating. Confirmed.
3. **Distribution:** Connect IQ, sideload first; IQ Store submission is an
   optional later phase. Confirmed.
4. **Code organization:** two Gradle product flavors (`fdroid` /
   `garmin`) **in this repository**. A separate repo is not recommended: it
   would duplicate the build setup, break the F-Droid metadata (which points
   at this repo), and complicate versionCode sync (`build-counter.txt`).
   If you want `main` clean during development, work on a
   `feature/garmin-companion` branch and merge — flavors make a permanent
   separate branch unnecessary.
5. **Watch UI:** three screens (transport / cadence / controls). Confirmed.
6. **Filtering:** flagged tracks stored in the library, excluded from the
   play queue behind a settings toggle, **on by default**. Confirmed.
7. **Default music app:** the watch also controls external apps
   (Spotify/Qobuz/audiobooks) via phone-side routing to media key events +
   STREAM_MUSIC volume. Confirmed (added after initial plan).

### Phase 1 — Phone app foundation (1-2 weeks)
- [ ] Room: `Track.notForRunning` + migration 6->7 + DAO/repository
      methods ([phone_app_changes.md](phone_app_changes.md) §1, §2a).
- [ ] `PlayerRepository`: mode-beep duck fix (multiplier-based) (§2b).
- [ ] `LibraryViewModel`: filter flagged tracks from the queue behind the
      settings toggle (default ON); `toggleNotForRunning()`.
- [ ] `LibraryScreen`/`TrackCard`: "Not for running" badge + unmark;
      `SettingsScreen`/`SettingsViewModel`: filter toggle (§4, §5).
- [ ] Unit tests for the above (migration, filtering honors toggle).
- [ ] Strings (en/fr/es), CHANGELOG entry.
- **Milestone:** "not for running" flag works end-to-end from the phone UI;
  nothing Garmin yet.

### Phase 2 — Phone-side Garmin integration (2-3 weeks)
- [ ] Product flavors `fdroid` / `garmin` + Garmin Maven repo + SDK
      dependency (§7).
- [ ] `GarminMessageCodec` + `CadenceAverager` + `MediaKeyDispatcher` with
      unit tests (§3).
- [ ] `GarminCompanionManager`: Connect IQ init, app registration, command
      **routing** (RunBeat vs media keys), state push incl. `source` (§3).
- [ ] Settings screen Garmin section (flavor-gated): connection state (§5).
- [ ] `docs/PRIVACY.md` update (§9).
- **Milestone:** phone app builds in both flavors; F-Droid flavor
      dependency set unchanged; manager logs watch messages and routes them.

### Phase 3 — Watch app (3-4 weeks, can overlap Phase 2)
- [x] Set up `garmin-watch/` Monkey C project, SDK, developer key.
- [x] `CadenceRingBuffer` + `CadenceSampler` (1 Hz, live-cadence with
      step-delta fallback).
- [x] `MessageHandler` + three views (transport / cadence / controls) per
      [watch_app_plan.md](watch_app_plan.md), incl. source label and
      RunBeat-only controls greyed out when `source != "RunBeat"`.
- [x] Compiles for all 16 target devices with Connect IQ SDK 9.3.0
      (16/16, one `.prg` per device — see Status above).
- [ ] Sideload on a real device; verify cadence accuracy (on a real
      fr245 AND a fenix/Venu device — review finding 4, see Status) and
      message round-trip.
- [ ] Optional future work: `Toybox.Test` watch test suite (parser edge
      cases, ring-buffer math, command JSON shapes) and per-device
      simulator runs (both need a Garmin developer account login).
- **Milestone:** all RunBeat features controllable from the watch.

### Phase 4 — External-app mode + end-to-end hardening (1-2 weeks)
- [ ] Verify watch transport + volume against Spotify/Qobuz/an audiobook
      app playing on the phone.
- [ ] Optional: `ExternalPlaybackMonitor` (notification listener) for
      external now-playing metadata; opt-in, documented in PRIVACY.md.
- [ ] Phone doze / app backgrounded: verify GCM still relays messages;
      document behavior.
- [ ] Connection-lost UX on the watch ("Phone not connected").
- [ ] Multi-device sanity (at least one Forerunner + one fenix/Venu).
- [ ] Battery sanity: overnight drain with the watch app idle.
- [ ] Regression: full existing test suite + F-Droid flavor build.

### Phase 5 — Distribution (optional)
- [ ] Connect IQ Store submission (screenshots, privacy/data disclosure,
      review loop) — only if public distribution is wanted.
- [ ] Release APKs: `garmin` flavor on GitHub releases; F-Droid keeps the
      `fdroid` flavor.

## Effort estimate

| Phase | Effort |
|-------|--------|
| 0 Decisions | 0.5-1 day |
| 1 Phone foundation | 1-2 weeks |
| 2 Phone Garmin integration | 2-3 weeks |
| 3 Watch app | 3-4 weeks (overlaps Phase 2) |
| 4 External-app mode + hardening | 1.5-2.5 weeks |
| 5 Distribution | 1-2 weeks (optional) |
| **Total (personal sideload)** | **~6.5-9.5 weeks** |

## Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Garmin Connect Mobile not installed / logged out | Medium | High (companion dead; basic MediaSession control still works) | Clear in-app status; document requirement; graceful watch UX |
| Proprietary SDK breaks F-Droid reproducible build | Medium | High | `garmin`/`fdroid` flavors; F-Droid build never resolves the SDK |
| Phone doze delays message delivery | Medium | Medium | Document; state push on change (no polling); acceptable for a run remote |
| `Toybox.Sensor` unavailable on some devices | Medium | Low | Step-delta fallback (`ActivityMonitor.getInfo().steps`) in `CadenceSampler`; simulator + real-device tests |
| Mode-beep volume restore clobbers user volume change | Low | Low | Multiplier-based ducking fix in Phase 1 |
| IQ Store review delays (if pursued) | Medium | Low (sideload path unaffected) | Submit early; keep sideload instructions in docs |
| Watch battery drain | Low | Medium | 1 Hz sampling only; no continuous phone traffic |

## Remaining open questions (non-blocking)

1. **External now-playing metadata:** implement the optional
   `ExternalPlaybackMonitor` (notification listener) in Phase 4, or ship
   without it (watch shows "External app")? It needs user-granted
   notification access — a privacy-relevant permission. Recommendation:
   implement, but keep it opt-in.
2. **IQ Store submission:** later, only if you want public distribution.
   Affects the IQ app UUID/branding only.
3. **Unmark path:** flagged tracks stay visible with a badge and are
   unmarkable (recommended, safer against accidental loss). Confirm.
4. **Paused-RunBeat routing nuance:** when RunBeat is loaded but *paused*,
   transport commands fall through to media keys (standard remote
   behavior). Acceptable, but flag if you want paused-RunBeat to still
   capture commands.

## Definition of done

- All RunBeat features (transport, cadence target incl. 60 s average,
  speed-correction toggle, volume, mark not-for-running with immediate
  skip) work from a real Garmin watch.
- External-app mode: transport + volume work from the watch while another
  app plays on the phone.
- `fdroid` flavor builds with zero Garmin/proprietary dependencies;
  existing test suite passes.
- Docs updated: PRIVACY.md, CHANGELOG.md, this folder.
