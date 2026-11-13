# RunBeat Requirements

## Functional
| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-001 | Playback: MP3, WAV, FLAC, AAC, OGG | Core | done |
| FR-002 | Tempo adjustment ±20% with pitch preservation | Core | done |
| FR-003 | BPM:cadence ratios 1:1, 2:1, 3:2, 6:2 | Core | done |
| FR-004 | Target cadence 172 spm (range 160–195, ±1) | Core | done |
| FR-005 | OS integration (MediaSession, Bluetooth controls) | Core | done |
| FR-006 | Automatic BPM detection | High | done |
| FR-007 | Manual BPM input (tap-to-beat) | Core | done |
| FR-008 | Playback controls (play/pause/ff/rew/seek) | Core | done |
| FR-009 | Playlist management (create/edit/import) | Core | done (M3U/PLS import, repeat modes pending) |
| FR-010 | Shuffle/random play | Expected | done |
| FR-011 | Streaming (Qobuz/Spotify/…) | Low | **not feasible — legal** (see below) |
| FR-012 | Export modified music files | Low | not done |

## Non-functional
Android only (v1); MIT-style permissive license (repo is GPL v3+); < 100 ms tempo-change latency;
runner-friendly UI (large targets, clear BPM/cadence display) — UI details in
[UI_ARCHITECTURE.md](UI_ARCHITECTURE.md).

## BPM sources
1. CSV import (pre-analyzed library), 2. tap-to-beat in the app, 3. automatic Aubio detection;
   system-scanned tracks start as `bpm = null`. BPM can be written back to file metadata (ID3 TBPM,
   see [ID3_TAG_INTEGRATION.md](ID3_TAG_INTEGRATION.md)). Track model: see
   [HIGH_LEVEL_ARCHITECTURE.md](HIGH_LEVEL_ARCHITECTURE.md).

## Not feasible: streaming integration (FR-011)
Spotify, Qobuz, Amazon Music and Deezer all prohibit modifying their streamed content in their
developer terms — incompatible with RunBeat's core tempo-changing feature (API termination,
copyright risk). The app therefore focuses on **local files**, which users own and may process.

## UI/UX guidelines
Material Design 3 throughout; touch targets ≥ 48dp; visual confirmation for all actions; clear
error messages with recovery; lazy-loaded lists.
