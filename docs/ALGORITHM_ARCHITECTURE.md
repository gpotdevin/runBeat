# RunBeat — Algorithm Architecture (BPM detection + cadence matching)

## BPM detection (two methods)

**Automatic — `AubioBpmDetector`** (Aubio via JNI, GPL v3):
- Decode to mono PCM at 44.1 kHz via MediaCodec (`AudioSampleExtractor`), normalize to [-1, 1]
- Skip 60 s intro, analyze a 60 s segment; fallbacks in order: retry from track start → direct native file read (`detectBpmFromFileOptimized`, buffer 2048 / hop 1024) → return 0
- Result clamped to [40, 200]. Config: hop 512–2048, buffer 1024–2048

**Manual — `BpmDetector.detectBpmFromTaps`** (tap tempo):
`BPM = 60000 / mean(tap intervals)`; needs ≥ 2 taps, clamped to [40, 200], null if insufficient.

## Cadence matching — `CadenceMatcher.findOptimalMatch`
```
f = target_cadence / (BPM * r)      r ∈ RHYTHM_PATTERNS = {1.0, 2.0, 1.5, 3.0}
                                    (shown in UI as Binary {1.0, 2.0} / Ternary {1.5, 3.0})
```
- Pick the pattern minimizing |f − 1|; result valid only if f ∈ [0.75, 1.25]
- Applied via `TempoStretcher.setTempoFactor`: SoundTouch (pitch preserved) if available, else ExoPlayer `playbackParameters.speed` (changes pitch)
- O(4) per track — real-time. Target latency for tempo changes < 100 ms

## Constants
| Group | Values |
|---|---|
| BPM detection | MIN_BPM 40, MAX_BPM 200, sample rate 44100, hop 512, buffer 1024 |
| Cadence | DEFAULT_CADENCE 172 (UI slider 150–195), speed factor [0.75, 1.25] |
| Tempo | SoundTouch [0.5, 2.0]; runner clamp [0.8, 1.25] |

## Tests
- `CadenceMatcherTest`: valid/edge BPM (zero, negative), known examples, clamping, all patterns, `isAdjustable()`
- `BpmDetectorTest` (12 tests): tap math, insufficient taps → null, [40, 200] clamping
