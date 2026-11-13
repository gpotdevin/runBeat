# RunBeat — High-Level Architecture

MVVM + Hilt + Repository pattern, Clean Architecture layering: UI → ViewModels → Repositories → data sources. Jetpack Compose UI, ExoPlayer (Media3) playback.

## Modules
```
app/                 # Android app (package com.bpmapp.audio, applicationId com.RunBeat.run.audio)
└── audio/           # playback, BPM detection, data (Room), DI, ui, garmin/
aubio/               # vendored native BPM detection (GPL v3)
soundtouch-android/  # vendored native tempo stretching (LGPL)
garmin-watch/        # Connect IQ watch companion (Monkey C) — see docs/companion_garmin_app/
```
Build flavors: `fdroid` (default, what F-Droid ships) and `garmin` (adds the proprietary
Connect IQ Mobile SDK). The `garmin` flavor keeps applicationId `com.RunBeat.run.audio` (Play Store);
the `fdroid` flavor uses `com.runBeat.run.audio.fdroid` → co-installable.

## Core components
- `BpmApp.kt` (Hilt Application), `MainActivity.kt` (NavHost + bottom nav: Player / Library / BPM Tools / Settings; PlayerScreen is inline here), `AudioService.kt` (foreground playback, media notification, Bluetooth controls)
- Data: `AppDatabase` (Room, **version 7**) + `TrackDao`; `TrackRepository` (library, CSV import, scan, BPM, file metadata), `PlayerRepository` (playback, playlist, ExoPlayer)
- Audio: `TempoStretcher` / `SoundTouchManager` (pitch-preserving tempo), `CadenceMatcher`, `AubioBpmDetector`, `BpmDetector` (tap tempo) — algorithms in [ALGORITHM_ARCHITECTURE.md](ALGORITHM_ARCHITECTURE.md)
- UI details in [UI_ARCHITECTURE.md](UI_ARCHITECTURE.md)

## Track entity (Room v7)
```kotlin
data class Track(
    val id: String,                // file:// or content:// URI
    val relativePath: String,
    val fileName: String,
    val bpm: Float?,               // null = unknown
    val fileSizeBytes: Long?,
    val durationMs: Long?,
    val lastUpdated: Long,
    val source: TrackSource,       // APP or SYSTEM
    val metadataTitle: String?,
    val metadataArtist: String?,
    val metadataAlbum: String?,
    val metadataGenre: String?,
    val isFavorite: Boolean,
    val notForRunning: Boolean     // "does not feel right to run on" (set from Garmin watch)
)
```

## Dependencies
| Library | Purpose | License |
|---|---|---|
| Aubio (vendored `aubio/`) | BPM detection | GPL v3 |
| SoundTouch (vendored `soundtouch-android/`) | tempo stretch, pitch preserved | LGPL |
| AndroidX Media3 (ExoPlayer) | playback | Apache 2.0 |
| Connect IQ Mobile SDK 2.4.0 | garmin flavor only | proprietary |
