# Changelog

All notable changes to RunBeat are documented in this file. Format based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0b] - 2026-08-12

First public beta release of RunBeat.

### Added

- Pitch-preserving tempo adjustment via SoundTouch (`soundtouch-android`).
- Automatic BPM detection via aubio (native CMake module) with MediaCodec /
  MediaMetadataRetriever PCM extraction for `content://` URIs.
- Manual tap-to-beat BPM detection.
- Cadence matching: playback speed follows a target cadence (default 172 BPM,
  range 160-195 BPM) with user-adjustable BPM/factor inputs.
- Library, playlist, and CSV-based BPM metadata handling.
- Offline-first data layer using Room.
- Foreground playback service with MediaSession, notifications (artist/song),
  and Bluetooth controls.
- Splash intro, adaptive launcher icon (SVG vector), watermark logo, locked
  portrait orientation.
- Local BPM and cadence-matching unit tests.

### Changed

- Unified BPM detection flows and synced playlist tiles.
- Standardized notification icons to 48dp to match UI touch targets.
- Reset-selection button next to "Play All" in the library screen.
- Consolidated aubio and soundtouch-android as vendored directories (fork
  documentation in each module).

### Fixed

- Library screen crash and playlist selection issues.
- BPM detection for MP3 files (PCM segment extraction) and `content://` URIs.
- BPM detection crash caused by a missing JNI wrapper for aubio.
- Lint errors (MissingSuperCall, NewApi, UnsafeOptInUsageError).

### Security

- Removed sensitive configuration from the repository in preparation for
  public release; release signing moved to environment variables.

## [1.0.1] - 2026-08-16

### Added

- Cadence-matching rhythm groups: **Binary** (1.0x / 2.0x) and **Ternary**
  (1.5x / 3.0x), replacing the four individual rhythm-factor chips.
- CSV import under **Settings → Advanced**, with an inline description of the
  expected CSV format.
- Warning banner and confirmation dialog when using tap-to-beat while playback
  speed is adjusted, so the tapped BPM reflects the actual tempo.
- "About" section in Settings with a link to the project repository.

### Changed

- Manual tap BPM is now written back to the track library and, where possible,
  to the file's ID3 tag.
- Manual BPM entry now uses a dedicated numeric input field.
- CSV import removed from the library overflow menu (moved to Settings →
  Advanced).

### Fixed

- Manual tap detection (tapped BPM now reflects the current playback tempo).
- Excessive memory usage when analyzing long tracks (decoding stops once the
  analysis window is covered).

### Planned

- Import existing playlists (M3U, PLS).
- Drag-and-drop playlist reordering.
- Convert git history to a clean public history (see `docs/HISTORY_CLEANUP.md`).

## [Unreleased]