# RunBeat - High-Level Technical Architecture

## 1. Architecture Pattern
- **MVVM (Model-View-ViewModel)** with **Dependency Injection (Hilt)**
- **Repository Pattern** for data management
- **Clean Architecture** separation: UI → ViewModels → Repositories → Data Sources

---

## 2. Module Structure
```
app/
├── audio/
│   ├── audio/           # Audio processing, playback, BPM detection
│   ├── data/            # Data models, Room database
│   ├── di/              # Dependency injection modules
│   └── ui/              # Jetpack Compose UI components
├── aubio/              # Aubio library for BPM detection (native)
└── soundtouch-android/  # SoundTouch library for tempo stretching (native)
```

---

## 3. Core Components

### A. Application Layer
- **`BpmApp.kt`**: Main application class with Hilt initialization
- **`MainActivity.kt`**: Entry point, manages navigation between `PlayerScreen` and `LibraryScreen`
- **`AudioService.kt`**: Foreground service for background playback, Bluetooth controls, and notifications

### B. Data Layer
#### Database
- **`AppDatabase.kt`**: Room database (v4) with `Track` entity
- **`TrackDao.kt`**: Data access object for CRUD operations on tracks
- **`Track.kt`**: Entity class storing track metadata (BPM, file path, metadata)

#### Repositories
- **`TrackRepository.kt`**: Manages track library (CSV import, system library scanning, BPM updates)
- **`PlayerRepository.kt`**: Manages playback state, playlist, and ExoPlayer integration

### C. Audio Processing
- **`TempoStretcher.kt`**: Integrates **SoundTouch** (LGPL) for pitch-preserving tempo adjustment
- **`SoundTouchManager.kt`**: Wrapper for `hipxel/soundtouch-android` native library
- **`CadenceMatcher.kt`**: Algorithm to match music BPM to target running cadence using rhythm patterns
- **`AubioBpmDetector.kt`**: Automatic BPM detection using Aubio library
- **`BpmDetector.kt`**: Manual tap-to-beat BPM detection

### D. UI Layer (Jetpack Compose)
See [UI_ARCHITECTURE.md](UI_ARCHITECTURE.md) for detailed UI implementation.

#### Screens
- **`PlayerScreen`** (inline composable in `MainActivity.kt`): Primary playback interface
- **`LibraryScreen.kt`**: Music library management
- **`SettingsScreen.kt`**: App settings

#### ViewModels
- **`PlayerViewModel.kt`**: Player state and BPM management
- **`LibraryViewModel.kt`**: Library operations and BPM analysis

---

## 4. Data Models

### Track Entity
```kotlin
data class Track(
    val id: String,              // Unique identifier (file:// or content:// URI)
    val relativePath: String,    // Relative path (e.g., "Artist/Album")
    val fileName: String,        // Filename (e.g., "song.mp3")
    val bpm: Float?,             // BPM value (null if unknown)
    val fileSizeBytes: Long?,    // File size in bytes
    val durationMs: Long?,       // Duration in milliseconds
    val lastUpdated: Long,       // Timestamp of last update
    val source: TrackSource,     // APP or SYSTEM
    val metadataTitle: String?, // Title from metadata
    val metadataArtist: String?,// Artist from metadata
    val metadataAlbum: String?, // Album from metadata
    val metadataGenre: String?, // Genre from metadata (ID3 tag)
    val isFavorite: Boolean     // Whether the track is marked as a favorite
)
```

### PlayerState
```kotlin
data class PlayerState(
    val isPlaying: Boolean,
    val playbackState: Int,
    val currentPosition: Long,
    val duration: Long,
    val error: String?
)
```

### CadenceMatchResult
```kotlin
data class CadenceMatchResult(
    val speedFactor: Float,          // Playback speed to apply
    val rhythmPattern: RhythmPattern,// Selected rhythm pattern (1:1, 2:1, etc.)
    val adjustment: Float,            // |f - 1|: how much speed changes
    val isAdjustable: Boolean,        // true if speed factor is within [0.75, 1.25]
    val calculatedBpm: Float,         // BPM after adjustment
    val message: String               // User-facing message
)
```

---

## 5. Key Algorithms
See [ALGORITHM_ARCHITECTURE.md](ALGORITHM_ARCHITECTURE.md) for detailed implementation.

---

## 6. External Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| **AndroidX Media3 (ExoPlayer)** | Audio playback | 1.2.1 |
| **Hilt** | Dependency injection | 2.48 |
| **Room** | Local database | 2.6.0 |
| **Jetpack Compose** | UI framework | 1.5.3 |
| **Kotlin Coroutines** | Asynchronous programming | 1.7.3 |
| **SoundTouch (hipxel/soundtouch-android)** | Tempo stretching | LGPL |
| **Aubio** | BPM detection | LGPL |

---

## 7. Technical Implementation Details

### Audio Pipeline
1. **Playback**: ExoPlayer handles audio decoding and playback
2. **Tempo Adjustment**: SoundTouch for pitch-preserving tempo changes, ExoPlayer fallback for speed adjustment
3. **BPM Detection**: Manual tap-to-beat and automatic Aubio analysis

> **Note:** SoundTouch is currently disabled in production via DI (`useSoundTouch = false`); tempo adjustments use ExoPlayer's built-in playback-speed adjustment as the active path. The SoundTouch integration described above is how pitch-preserving tempo stretching would be applied when re-enabled.

See [ALGORITHM_ARCHITECTURE.md](ALGORITHM_ARCHITECTURE.md) for details.

### Data Flow
See [UI_ARCHITECTURE.md](UI_ARCHITECTURE.md) for UI-specific flows.

1. **Track Import**: `CSV/System Library → TrackRepository → TrackDao → Room DB`
2. **Playback**: `LibraryScreen → PlayerRepository → ExoPlayer → AudioService`
3. **BPM Detection**: Manual tap-to-beat and automatic Aubio analysis
4. **Tempo Adjustment**: `CadenceMatcher → TempoStretcher → SoundTouch/ExoPlayer`

### Duplicate Detection
- Uses `filename + fileSizeBytes` as unique identifier
- Skips duplicates during CSV import and system library scanning

### Offline-First Design
- Tracks and BPM data persisted in Room DB
- CSV import/export for backup and sharing
- System library integration via MediaStore API

---

## 8. Performance Considerations
- **Audio Latency**: Target <100ms for tempo changes (NFR-003)
- **Database**: Room DB with Flow for reactive updates
- **BPM Analysis**: Background thread processing
- **Memory**: SoundTouch native library loaded on-demand

---

## 9. Platform Targeting
- **Primary**: Android (API 26+)
- **Build**: Gradle with Kotlin DSL
- **Language**: Kotlin (Java 17 compatibility)
- **UI**: Jetpack Compose (Material Design 3)
