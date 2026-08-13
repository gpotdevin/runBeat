// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.util.Log
import com.hipxel.soundtouch.SoundTouch

/**
 * Manages SoundTouch library for time-stretching and pitch-shifting operations.
 * Uses hipxel/soundtouch-android library for actual audio processing.
 * 
 * The underlying SoundTouch library is LGPL-licensed. We use dynamic linking
 * (JNI) which complies with LGPL requirements.
 * 
 * Current status: FULLY ACTIVATED with hipxel/soundtouch-android native library
 */
class SoundTouchManager {
    
    companion object {
        private const val TAG = "SoundTouchManager"
        
        // Default audio parameters
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_CHANNELS = 2
        const val DEFAULT_TEMPO = 1.0f
        const val DEFAULT_PITCH = 1.0f
        
        // Minimum and maximum values
        const val MIN_TEMPO = 0.5f
        const val MAX_TEMPO = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
    }
    
    private var soundTouch: SoundTouch? = null
    private var isInitialized = false
    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var channels: Int = DEFAULT_CHANNELS
    private var currentTempo: Float = DEFAULT_TEMPO
    private var currentPitch: Float = DEFAULT_PITCH
    
    /**
     * Initialize the SoundTouch processor with specified audio parameters
     * @param sampleRate Audio sample rate in Hz
     * @param channels Number of audio channels (1 = mono, 2 = stereo)
     * @return true if initialization succeeded, false otherwise
     */
    fun initialize(sampleRate: Int = DEFAULT_SAMPLE_RATE, channels: Int = DEFAULT_CHANNELS): Boolean {
        return try {
            this.sampleRate = sampleRate
            this.channels = channels
            this.soundTouch = SoundTouch()
            this.soundTouch?.setSampleRate(sampleRate)
            this.soundTouch?.setChannels(channels)
            this.soundTouch?.setTempo(1.0)
            this.soundTouch?.setPitch(1.0)
            this.isInitialized = true
            Log.d(TAG, "SoundTouch initialized with sampleRate=$sampleRate, channels=$channels")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SoundTouch", e)
            isInitialized = false
            soundTouch?.release()
            soundTouch = null
            false
        }
    }
    
    /**
     * Check if SoundTouch is available and initialized
     */
    fun isAvailable(): Boolean {
        return isInitialized && soundTouch != null
    }
    
    /**
     * Set the tempo factor (1.0 = normal speed, >1.0 = faster, <1.0 = slower)
     * @param tempo Tempo factor, will be clamped to [MIN_TEMPO, MAX_TEMPO]
     */
    fun setTempo(tempo: Float) {
        currentTempo = tempo.coerceIn(MIN_TEMPO, MAX_TEMPO)
        soundTouch?.setTempo(currentTempo.toDouble())
        Log.d(TAG, "Tempo set to: $currentTempo")
    }
    
    /**
     * Set the pitch factor (1.0 = normal pitch, >1.0 = higher, <1.0 = lower)
     * @param pitch Pitch factor, will be clamped to [MIN_PITCH, MAX_PITCH]
     */
    fun setPitch(pitch: Float) {
        currentPitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
        soundTouch?.setPitch(currentPitch.toDouble())
        Log.d(TAG, "Pitch set to: $currentPitch")
    }
    
    /**
     * Release SoundTouch resources
     */
    fun release() {
        soundTouch?.release()
        soundTouch = null
        isInitialized = false
        Log.d(TAG, "SoundTouch: released")
    }
}
