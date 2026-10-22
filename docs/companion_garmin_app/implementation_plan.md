# Implementation Plan — Garmin Companion for RunBeat

Date: 2026-10-22

## Phases

### Phase 0 — Decisions (before coding)
1. Confirm the Connect IQ route (recommended) vs MediaSession-only
   (partial features).
2. Resolve the open questions below.
3. Confirm distribution: personal sideload vs Connect IQ Store, and the
   `garmin` product flavor approach for F-Droid compatibility.

### Phase 1 — Phone app foundation (1-2 weeks)
- [ ] Room: `Track.isBad` + migration 6->7 + DAO/repository methods
      ([phone_app_changes.md](phone_app_changes.md) §1, §2a).
- [ ] `PlayerRepository`: volume API + mode-beep duck fix (§2b).
- [ ] `LibraryViewModel` + `LibraryScreen`/`TrackCard`: filter bad tracks
      from the queue; badge + unmark in the phone UI (§4, §5).
- [ ] Unit tests for the above (migration, volume clamp).
- [ ] Strings (en/fr/es), CHANGELOG entry.
- **Milestone:** mark-as-bad works from the phone UI; nothing Garmin yet.

### Phase 2 — Phone-side Garmin integration (2-3 weeks)
- [ ] Product flavors `fdroid` / `garmin` + Garmin Maven repo + SDK
      dependency (§6).
- [ ] `GarminMessageCodec` + `CadenceAverager` with unit tests (§3).
- [ ] `GarminCompanionManager`: Connect IQ init, app registration, message
      dispatch, state push (§3).
- [ ] Settings screen section (flavor-gated): connection state (§5).
- [ ] `docs/PRIVACY.md` update (§7).
- **Milestone:** phone app builds in both flavors; F-Droid flavor
      byte-identical dependency set; manager logs watch messages.

### Phase 3 — Watch app (3-4 weeks, can overlap Phase 2)
- [ ] Set up `garmin-watch/` Monkey C project, SDK, developer key.
- [ ] `CadenceRingBuffer` + `CadenceSampler` (1 Hz, live-cadence with
      step-delta fallback); simulator tests.
- [ ] `MessageHandler` + three views (main / cadence / control) per
      [watch_app_plan.md](watch_app_plan.md).
- [ ] Sideload on a real device; verify cadence accuracy and message
      round-trip.
- **Milestone:** all five features controllable from the watch.

### Phase 4 — End-to-end hardening (1-2 weeks)
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
| 4 Hardening | 1-2 weeks |
| 5 Distribution | 1-2 weeks (optional) |
| **Total (personal sideload)** | **~6-9 weeks** |

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

## Open questions (need user decision)

1. **Mark-bad behavior during the current session:** when the current track
   is marked bad from the watch, should the phone (a) just flag it for
   future sessions, or (b) also skip to the next track immediately?
   Recommendation: (b) — a "bad for running" track is usually skipped
   right away. Implement as a Settings toggle if unsure.
2. **Distribution:** personal sideload only, or also Connect IQ Store
   submission? (Affects Phase 5 and the IQ app UUID/branding.)
3. **F-Droid:** confirm the product-flavor approach (Garmin code in a
   `garmin` flavor, F-Droid keeps the clean `fdroid` flavor), or do you
   prefer a fully separate companion app?
4. **Watch UI granularity:** one-page compact remote vs the three-page
   layout in [watch_app_plan.md](watch_app_plan.md)? (Three pages is
   recommended for running with gloves/large targets.)
5. **Unmark path:** bad tracks hidden entirely from the library, or shown
   with a badge and unmarkable? Recommendation: badge + unmarkable (safer
   against accidental loss).

## Definition of done

- All five features work from a real Garmin watch with RunBeat playing on
  the phone.
- `fdroid` flavor builds with zero Garmin/proprietary dependencies;
  existing test suite passes.
- Docs updated: PRIVACY.md, CHANGELOG.md, this folder.
