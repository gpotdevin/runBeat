# Implementation Plan — Garmin Companion for RunBeat

Date: 2026-10-22

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
- [ ] Set up `garmin-watch/` Monkey C project, SDK, developer key.
- [ ] `CadenceRingBuffer` + `CadenceSampler` (1 Hz, live-cadence with
      step-delta fallback); simulator tests.
- [ ] `MessageHandler` + three views (transport / cadence / controls) per
      [watch_app_plan.md](watch_app_plan.md), incl. source label and
      RunBeat-only controls greyed out when `source != "RunBeat"`.
- [ ] Sideload on a real device; verify cadence accuracy and message
      round-trip.
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
| `getLiveCadence()` unavailable on some devices | Medium | Low | Step-delta fallback in `CadenceSampler`; simulator + real-device tests |
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
