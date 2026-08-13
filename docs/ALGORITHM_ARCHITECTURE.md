# RunBeat - Algorithmic Architecture

## Overview
This document details the algorithmic implementation of BPM detection and cadence matching in RunBeat. It focuses exclusively on the core audio processing algorithms that enable automatic BPM detection from audio files and the matching of music tempo to a runner's target cadence.

---

## 1. BPM Detection Architecture

### 1.1 Dual Detection System
The application implements two complementary BPM detection methods:

1. **Automatic Detection (AubioBpmDetector)**
   - Uses the Aubio library (LGPL) for automatic tempo analysis
   - Processes audio files to detect BPM without user intervention
   - Supports multiple input formats (MP3, AAC, FLAC, etc.)

2. **Manual Detection (BpmDetector)**
   - Tap-to-beat functionality for user-provided BPM
   - Calculates BPM from user tap intervals (average-based)

### 1.2 Automatic BPM Detection (AubioBpmDetector.kt)

#### Algorithm Overview
- **Library**: Aubio (via JNI through aubio-bpm-jni)
- **Input**: Audio files or PCM samples
- **Output**: Detected BPM (40-200 range)
- **Accuracy**: High for most music genres

#### Implementation Details

**Native Methods:**
```kotlin
// Direct file analysis
external fun nativeDetectBpm(
    audioPath: String,
    sampleRate: Int = 44100,
    hopSize: Int = 512,
    bufferSize: Int = 1024
): Float

// In-memory sample analysis  
external fun nativeDetectBpmFromSamples(
    samples: FloatArray,
    numSamples: Int,
    sampleRate: Int = 44100,
    hopSize: Int = 512,
    bufferSize: Int = 1024
): Float
```

**Detection Pipeline:**
1. **File Segment Extraction** (`detectBpmFromFileSegment`):
   - Skips intro (default: 60 seconds)
   - Extracts analysis segment (default: 60 seconds)
   - Uses `AudioSampleExtractor` for format-agnostic PCM conversion

2. **Sample Preprocessing** (`AudioSampleExtractor.kt`):
   - Uses Android's MediaCodec API for audio decoding
   - Converts to mono PCM at 44100Hz
   - Normalizes samples to [-1.0, 1.0] range
   - Handles edge cases: short files, unsupported formats

3. **Aubio Analysis:**
   - Applies Aubio's tempo detection algorithm
   - Uses configurable hop size (512-2048) and buffer size (1024-2048)
   - Returns raw BPM value clamped to [40.0, 200.0] range

**Optimized Detection:**
```kotlin
fun detectBpmFromFileOptimized(
    audioPath: String,
    sampleRate: Int = 44100
): Float {
    // Uses larger buffer (2048) and hop size (1024) for better accuracy
    val bufferSize = 2048
    val hopSize = 1024
    val rawBpm = nativeDetectBpm(audioPath, sampleRate, hopSize, bufferSize)
    return if (rawBpm > 0) rawBpm.coerceIn(MIN_BPM, MAX_BPM) else 0.0f
}
```

**Fallback Strategy:**
1. Segment extraction with MediaCodec (`detectBpmFromFileSegment`)
2. Retry from the track start if the skipped window yields no BPM
3. Fall back to `detectBpmFromFileOptimized` (direct native file read)
4. Return 0.0 if all methods fail

### 1.3 Manual BPM Detection (BpmDetector.kt)

#### Algorithm Overview
- **Input**: List of tap timestamps (milliseconds)
- **Output**: Detected BPM (40-200 range)
- **Methods**: Average-based calculation

#### Implementation Details

**Basic Calculation:**
```kotlin
fun detectBpmFromTaps(tapIntervalsMs: List<Long>): Int? {
    if (tapIntervalsMs.size < 2) return null
    
    val avgInterval = tapIntervalsMs.average()
    val bpm = (60000.0 / avgInterval).toInt()
    
    return bpm.coerceIn(MIN_BPM.toInt(), MAX_BPM.toInt())
}
```

---

## 2. Cadence Matching Architecture

### 2.1 Algorithm Overview (CadenceMatcher.kt)

The cadence matching algorithm finds the optimal playback speed factor that aligns a runner's steps to musical rhythm patterns while minimizing speed adjustment.

**Core Formula:**
```
f = target_cadence / (BPM * r)
```

Where:
- `f`: Playback speed factor (1.0 = normal speed)
- `target_cadence`: Runner's desired steps per minute (default: 172)
- `BPM`: Detected music tempo
- `r`: Rhythm pattern multiplier

**Rhythm Patterns:**
| Pattern | Multiplier (r) | Description |
|---------|----------------|-------------|
| Quarter | 1.0 | Run on every beat |
| Eighth | 2.0 | Run on every half beat |
| Triplet | 1.5 | Run on every dotted quarter |
| Sixth | 3.0 | Run on every sixth beat |

### 2.2 Algorithm Implementation

**Main Matching Function:**
```kotlin
fun findOptimalMatch(
    detectedBpm: Float,
    targetCadence: Float = DEFAULT_CADENCE
): CadenceMatchResult {
    // 1. Validate input
    if (detectedBpm <= 0) return invalidResult
    
    // 2. Calculate all possible matches
    val candidates = RHYTHM_PATTERNS.map { pattern ->
        calculateMatch(detectedBpm, targetCadence, pattern)
    }
    
    // 3. Find best candidate (minimum adjustment)
    val bestCandidate = candidates.minByOrNull { it.adjustment }
    
    // 4. Validate speed factor range [0.75, 1.25]
    val isValid = bestCandidate.speedFactor in MIN_SPEED_FACTOR..MAX_SPEED_FACTOR
    
    // 5. Return result with validation
    return bestCandidate.copy(isAdjustable = isValid, message = generateMessage())
}
```

**Single Pattern Calculation:**
```kotlin
private fun calculateMatch(
    detectedBpm: Float,
    targetCadence: Float,
    pattern: RhythmPattern
): CadenceMatchResult {
    // Calculate speed factor: f = cadence / (BPM * r)
    val speedFactor = targetCadence / (detectedBpm * pattern.factor)
    
    // Calculate adjustment: |f - 1|
    val adjustment = abs(speedFactor - 1.0f)
    
    // Calculated BPM after adjustment equals target cadence
    val calculatedBpm = targetCadence
    
    return CadenceMatchResult(
        speedFactor = speedFactor,
        rhythmPattern = pattern,
        adjustment = adjustment,
        isAdjustable = false, // Set by caller
        calculatedBpm = calculatedBpm,
        message = ""
    )
}
```

### 2.3 Result Structure

**CadenceMatchResult:**
```kotlin
data class CadenceMatchResult(
    val speedFactor: Float,          // Playback speed to apply (0.75-1.25)
    val rhythmPattern: RhythmPattern,// Selected rhythm pattern
    val adjustment: Float,            // |f - 1|: speed change magnitude
    val isAdjustable: Boolean,        // true if speed factor is valid
    val calculatedBpm: Float,         // BPM after adjustment (equals target cadence)
    val message: String               // User-facing message
)
```

### 2.4 Algorithm Examples

**Example 1: Perfect Match (BPM=86, Cadence=172)**
```
Pattern: Quarter (r=1.0)
  f = 172 / (86 * 1.0) = 2.00 → adjustment = 1.00 → OUT OF RANGE

Pattern: Eighth (r=2.0)
  f = 172 / (86 * 2.0) = 1.00 → adjustment = 0.00 → SELECTED ✓

Pattern: Triplet (r=1.5)
  f = 172 / (86 * 1.5) = 1.33 → adjustment = 0.33 → OUT OF RANGE

Pattern: Sixth (r=3.0)
  f = 172 / (86 * 3.0) = 0.67 → adjustment = 0.33 → OUT OF RANGE

Result: speedFactor=1.0, rhythmPattern=Eighth, adjustment=0.0, isAdjustable=true
```

**Example 2: Best Fit (BPM=100, Cadence=172)**
```
Pattern: Quarter (r=1.0)
  f = 172 / (100 * 1.0) = 1.72 → adjustment = 0.72 → OUT OF RANGE

Pattern: Eighth (r=2.0)
  f = 172 / (100 * 2.0) = 0.86 → adjustment = 0.14 → VALID ✓

Pattern: Triplet (r=1.5)
  f = 172 / (100 * 1.5) = 1.1467 → adjustment = 0.1467 → VALID

Pattern: Sixth (r=3.0)
  f = 172 / (100 * 3.0) = 0.573 → adjustment = 0.427 → OUT OF RANGE

Result: speedFactor=0.86, rhythmPattern=Eighth, adjustment=0.14, isAdjustable=true
```

### 2.5 Validation and Clamping

**Speed Factor Range:** [0.75, 1.25]
- Factors outside this range are marked as non-adjustable
- The algorithm still returns the best mathematical match
- The UI displays appropriate messaging for out-of-range cases

**Edge Cases:**
- Zero or negative BPM: Returns default result with isAdjustable=false
- No valid matches: Returns first pattern with isAdjustable=false

---

## 3. Tempo Stretching Architecture

### 3.1 TempoStretcher.kt

The TempoStretcher integrates with ExoPlayer to provide time-stretching without affecting pitch using SoundTouch library.

> **Note:** In the current build, SoundTouch integration is **disabled** via DI (`useSoundTouch = false` in `di/AppModule.kt`); `initializeSoundTouch()` short-circuits and ExoPlayer's built-in playback-speed adjustment is used as the active fallback. The SoundTouch path below describes how tempo stretching would be applied when re-enabled.

**Core Functionality:**
- Set tempo factor (0.5-2.0 range)
- Preserve pitch during tempo changes
- Fallback to ExoPlayer speed adjustment when SoundTouch unavailable

### 3.2 SoundTouchManager.kt

**Wrapper for SoundTouch Library:**
- Initializes SoundTouch with audio parameters (sample rate, channels)
- Sets tempo and pitch factors independently
- Processes audio samples through SoundTouch
- Manages resource lifecycle

**Key Parameters:**
- Tempo range: [0.5, 2.0]
- Pitch range: [0.5, 2.0]
- Default sample rate: 44100Hz
- Default channels: 2 (stereo)

### 3.3 AudioSampleExtractor.kt

**PCM Sample Extraction:**
- Uses Android's MediaCodec API for audio decoding
- Extracts segments from audio files
- Converts to mono PCM at 44100Hz
- Normalizes samples to [-1.0, 1.0] range

**Extraction Process:**
1. Validate file and get duration
2. Adjust skip and duration parameters based on file length
3. Use MediaExtractor to read audio file
4. Configure MediaCodec for PCM decoding
5. Skip to start position
6. Collect decoded PCM samples
7. Convert to mono FloatArray

---

## 4. Data Flow

### 4.1 BPM Detection Flow
```
Audio File
    ↓
[AubioBpmDetector]
    ├── nativeDetectBpm() → Direct file analysis
    └── detectBpmFromFileSegment() → Segment extraction + analysis
        ├── AudioSampleExtractor.extractSamples()
        │   └── MediaCodec decoding
        └── nativeDetectBpmFromSamples()
    ↓
Detected BPM (40-200)
```

### 4.2 Manual BPM Flow
```
User Taps
    ↓
[BpmDetector.detectBpmFromTaps()]
    ├── Calculate intervals between taps
    ├── Compute average interval
    └── Convert to BPM: BPM = 60000 / interval_ms
    ↓
Detected BPM (40-200)
```

### 4.3 Cadence Matching Flow
```
Detected BPM + Target Cadence
    ↓
[CadenceMatcher.findOptimalMatch()]
    ├── For each rhythm pattern:
    │   └── Calculate: f = cadence / (BPM * r)
    │   └── Calculate: adjustment = |f - 1|
    ├── Select pattern with minimum adjustment
    └── Validate: f ∈ [0.75, 1.25]
    ↓
CadenceMatchResult
    ↓
[TempoStretcher.setTempoFactor()]
    ├── If SoundTouch available:
    │   └── SoundTouchManager.setTempo()
    └── Else:
        └── ExoPlayer.playbackParameters.withSpeed()
    ↓
Adjusted Playback
```

---

## 5. Performance Considerations

### 5.1 BPM Detection
- **Segment Extraction**: Skips intro (60s) and analyzes 60s segment for accuracy
- **Sample Rate**: Uses 44100Hz for optimal Aubio performance
- **Buffer Sizes**: Configurable hop size (512-2048) and buffer size (1024-2048)
- **Fallback Strategy**: Multiple detection methods ensure robustness

### 5.2 Cadence Matching
- **Algorithm Complexity**: O(n) where n = number of rhythm patterns (4)
- **Real-time**: Suitable for real-time adjustments during playback
- **Validation**: Speed factor clamping ensures safe playback ranges

### 5.3 Tempo Stretching
- **SoundTouch**: High-quality time-stretching with pitch preservation
- **Fallback**: ExoPlayer speed adjustment (affects pitch)
- **Latency**: Target <100ms for tempo changes

---

## 6. Testing

### 6.1 CadenceMatcher Tests
Comprehensive unit tests in `CadenceMatcherTest.kt` cover:
- Basic functionality with valid BPM values
- Edge cases (zero, negative BPM)
- Algorithm verification with known examples
- Clamping behavior for out-of-range speed factors
- All rhythm patterns validation
- isAdjustable() function correctness

### 6.2 BPM Detector Tests
Unit tests in `BpmDetectorTest.kt` (12 tests) verify:
- Tap-to-beat BPM calculation via `detectBpmFromTaps` (`BPM = 60000 / interval_ms`)
- Insufficient-tap handling (empty or single-tap lists return null)
- Range clamping to [40, 200]

---

## 7. Constants and Ranges

### 7.1 BPM Detection
| Parameter | Value | Description |
|-----------|-------|-------------|
| MIN_BPM | 40 | Minimum valid BPM |
| MAX_BPM | 200 | Maximum valid BPM |
| DEFAULT_SAMPLE_RATE | 44100 | Standard audio sample rate |
| DEFAULT_HOP_SIZE | 512 | Aubio hop size |
| DEFAULT_BUFFER_SIZE | 1024 | Aubio buffer size |

### 7.2 Cadence Matching
| Parameter | Value | Description |
|-----------|-------|-------------|
| DEFAULT_CADENCE | 172 | Default target running cadence |
| MIN_SPEED_FACTOR | 0.75 | Minimum playback speed |
| MAX_SPEED_FACTOR | 1.25 | Maximum playback speed |
| RHYTHM_PATTERNS | [1.0, 2.0, 1.5, 3.0] | Rhythm multipliers |

### 7.3 Tempo Stretching
| Parameter | Value | Description |
|-----------|-------|-------------|
| MIN_TEMPO | 0.5 | Minimum tempo factor |
| MAX_TEMPO | 2.0 | Maximum tempo factor |
| RUNNER_MIN_TEMPO | 0.8 | Runner-specific minimum |
| RUNNER_MAX_TEMPO | 1.25 | Runner-specific maximum |

---

## 8. Dependencies

### 8.1 External Libraries
| Library | Purpose | License |
|---------|---------|---------|
| Aubio | BPM detection | GPL v3 |
| SoundTouch (hipxel/soundtouch-android) | Tempo stretching | LGPL |
| AndroidX Media3 (ExoPlayer) | Audio playback | Apache 2.0 |

### 8.2 Android APIs
- MediaCodec: Audio decoding
- MediaExtractor: Audio file reading
- MediaMetadataRetriever: File duration extraction
