// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import android.util.Log

/**
 * BPM detector using Aubio library for automatic tempo detection.
 * 
 * Aubio is a library for audio labelling that provides BPM detection
 * using various algorithms (default, specdiff, complex, mck, klapuri, degara).
 * 
 * License: GPL v3 (same as Aubio)
 * Source: https://aubio.org
 */
class AubioBpmDetector {
    private var audioSampleExtractor: AudioSampleExtractor? = null
    
    companion object {
        private const val TAG = "AubioBpmDetector"
        
        // Default parameters for BPM detection
        private const val DEFAULT_HOP_SIZE = 512
        private const val DEFAULT_BUFFER_SIZE = 1024
        const val DEFAULT_SAMPLE_RATE = 44100
        const val TARGET_SAMPLE_RATE = 44100
        
        // Minimum valid BPM
        private const val MIN_BPM = 40.0f
        private const val MAX_BPM = 200.0f
        
        init {
            // Load the aubio library first (dependency of aubio-bpm-jni)
            System.loadLibrary("aubio")
            System.loadLibrary("aubio-bpm-jni")
        }
    }
    
    /**
     * Detect BPM from an audio file
     * 
     * @param audioPath Path to the audio file
     * @param sampleRate Sample rate of the audio (default: 44100)
     * @param hopSize Hop size for analysis (default: 512)
     * @param bufferSize Buffer size for analysis (default: 1024)
     * @return Detected BPM, or 0.0 if detection failed
     */
    external fun nativeDetectBpm(
        audioPath: String,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        hopSize: Int = DEFAULT_HOP_SIZE,
        bufferSize: Int = DEFAULT_BUFFER_SIZE
    ): Float
    
    /**
     * Detect BPM from float samples (for real-time or in-memory detection)
     * 
     * @param samples Array of audio samples (normalized float values)
     * @param numSamples Number of samples in the array
     * @param sampleRate Sample rate of the audio
     * @param hopSize Hop size for analysis
     * @param bufferSize Buffer size for analysis
     * @return Detected BPM, or 0.0 if detection failed
     */
    external fun nativeDetectBpmFromSamples(
        samples: FloatArray,
        numSamples: Int,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        hopSize: Int = DEFAULT_HOP_SIZE,
        bufferSize: Int = DEFAULT_BUFFER_SIZE
    ): Float
    
    /**
     * Initialize with Context for sample extraction
     * Must be called before using detectBpmFromFileSegment
     */
    fun initializeWithContext(context: Context) {
        if (audioSampleExtractor == null) {
            audioSampleExtractor = AudioSampleExtractor(context)
        }
    }

    /**
     * Detect BPM from a file by extracting a segment, converting to PCM, and analyzing.
     * This method skips the intro (default 60s) and analyzes a segment (default 60s).
     * Works with any audio format that ffmpeg can decode (MP3, AAC, FLAC, etc.).
     * 
     * @param context Android context for file operations
     * @param audioPath Path to the audio file
     * @param skipMs Number of milliseconds to skip from the beginning (default: 60000)
     * @param durationMs Number of milliseconds to analyze (default: 60000)
     * @return Detected BPM, or 0.0 if detection failed
     */
    suspend fun detectBpmFromFileSegment(
        context: Context,
        audioPath: String,
        skipMs: Long = AudioSampleExtractor.DEFAULT_SKIP_MS,
        durationMs: Long = AudioSampleExtractor.DEFAULT_DURATION_MS
    ): Float {
        // Initialize extractor if not done
        if (audioSampleExtractor == null) {
            audioSampleExtractor = AudioSampleExtractor(context)
        }
        
        return try {
            val extractor = audioSampleExtractor ?: return 0.0f

            // Try the requested window first (default: skip 60s, analyze 60s).
            var decoded = extractor.extractSamples(audioPath, skipMs, durationMs)
            var bpm = decodedToBpm(decoded, audioPath)

            // If the first window produced no usable audio or no clear tempo, retry
            // from the start of the track before giving up. Some files have unreliable
            // presentation timestamps that make the skipped window come back empty,
            // and the direct-file fallback below cannot decode MP3/AAC on Android.
            if ((decoded == null || decoded.samples.isEmpty()) || bpm <= 0) {
                Log.i(TAG, "Retrying segment from start for $audioPath (skip=0)")
                val fullStart = extractor.extractSamples(audioPath, 0L, durationMs)
                if (fullStart != null && fullStart.samples.isNotEmpty()) {
                    bpm = decodedToBpm(fullStart, audioPath)
                }
            }

            if (bpm > 0) {
                return bpm.coerceIn(MIN_BPM, MAX_BPM)
            }

            // Last resort: direct file detection (only works for formats aubio can
            // open natively, e.g. WAV; typically fails for MP3/AAC without FFmpeg).
            Log.w(TAG, "Segment detection produced no BPM for $audioPath; trying direct file read")
            val rawBpm = detectBpmFromFileOptimized(audioPath)
            if (rawBpm > 0) rawBpm.coerceIn(MIN_BPM, MAX_BPM) else 0.0f

        } catch (e: Exception) {
            Log.e(TAG, "Error detecting BPM from file segment", e)
            // Fall back to direct file detection
            val rawBpm = detectBpmFromFileOptimized(audioPath)
            if (rawBpm > 0) rawBpm.coerceIn(MIN_BPM, MAX_BPM) else 0.0f
        }
    }

    private fun decodedToBpm(decoded: AudioSampleExtractor.DecodedAudio?, path: String): Float {
        if (decoded == null || decoded.samples.isEmpty()) {
            Log.w(TAG, "No samples extracted from file segment for $path")
            return 0.0f
        }
        val rawBpm = detectBpmFromSamples(decoded.samples, decoded.sampleRate)
        return if (rawBpm > 0) {
            Log.d(TAG, "Segment detection: ${rawBpm.coerceIn(MIN_BPM, MAX_BPM)} BPM for $path")
            rawBpm.coerceIn(MIN_BPM, MAX_BPM)
        } else {
            0.0f
        }
    }
    
    /**
     * Detect BPM from audio samples with reasonable defaults
     */
    fun detectBpmFromSamples(samples: FloatArray, sampleRate: Int): Float {
        val rawBpm = nativeDetectBpmFromSamples(samples, samples.size, sampleRate)
        return if (rawBpm > 0) {
            rawBpm.coerceIn(MIN_BPM, MAX_BPM)
        } else {
            0.0f
        }
    }
    
    /**
     * Detect BPM with optimized parameters for music
     * Uses larger buffer for better accuracy on music
     */
    fun detectBpmFromFileOptimized(audioPath: String, sampleRate: Int = DEFAULT_SAMPLE_RATE): Float {
        // Use larger buffer and hop size for better accuracy
        val bufferSize = 2048
        val hopSize = 1024
        val rawBpm = nativeDetectBpm(audioPath, sampleRate, hopSize, bufferSize)
        return if (rawBpm > 0) {
            rawBpm.coerceIn(MIN_BPM, MAX_BPM)
        } else {
            0.0f
        }
    }
}
