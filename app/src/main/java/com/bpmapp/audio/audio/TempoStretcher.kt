// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Tempo stretching service that integrates with ExoPlayer to provide time-stretching
 * without affecting pitch. This class manages the integration between the player
 * and time-stretching algorithms using SoundTouch for high-quality time-stretching.
 */
class TempoStretcher(private val player: Player, useSoundTouch: Boolean = true) {
    
    companion object {
        private const val TAG = "TempoStretcher"
        
        // Default tempo factor (1.0 = normal speed)
        const val DEFAULT_TEMPO = 1.0f
        
        // Minimum and maximum tempo factors
        const val MIN_TEMPO = 0.5f  // 50% speed (half speed)
        const val MAX_TEMPO = 2.0f  // 200% speed (double speed)
        
        // Typical runner cadence range adjustments
        const val RUNNER_MIN_TEMPO = 0.8f  // 80% speed (for slower cadence)
        const val RUNNER_MAX_TEMPO = 1.25f // 125% speed (for faster cadence)
    }
    
    private var currentTempoFactor: Float = DEFAULT_TEMPO
    private var pitchPreservationEnabled: Boolean = true
    
    // SoundTouch integration
    private val soundTouchManager = SoundTouchManager()
    private var isSoundTouchInitialized = false
    private var useSoundTouch = useSoundTouch
    
    /**
     * Initialize SoundTouch with the player's audio format
     * Should be called when the player is prepared and audio format is known
     */
    fun initializeSoundTouch(sampleRate: Int = 44100, channels: Int = 2): Boolean {
        if (!this.useSoundTouch) {
            Log.d(TAG, "SoundTouch disabled, using ExoPlayer built-in speed adjustment")
            return false
        }
        
        isSoundTouchInitialized = soundTouchManager.initialize(sampleRate, channels)
        if (isSoundTouchInitialized) {
            Log.d(TAG, "SoundTouch initialized successfully")
        } else {
            Log.w(TAG, "SoundTouch initialization failed, falling back to ExoPlayer speed adjustment")
        }
        
        return isSoundTouchInitialized
    }
    
    /**
     * Check if SoundTouch is available and being used
     */
    fun isSoundTouchAvailable(): Boolean {
        return useSoundTouch && isSoundTouchInitialized && soundTouchManager.isAvailable()
    }
    
    /**
     * Set the tempo factor (1.0 = normal, >1.0 = faster, <1.0 = slower)
     */
    fun setTempoFactor(factor: Float) {
        val clampedFactor = factor.coerceIn(MIN_TEMPO, MAX_TEMPO)
        currentTempoFactor = clampedFactor
        applyTempoStretching()
        Log.d(TAG, "Tempo factor set to: $currentTempoFactor")
    }
    
    /**
     * Get the current tempo factor
     */
    fun getTempoFactor(): Float {
        return currentTempoFactor
    }
    
    /**
     * Apply the current tempo factor to the player
     * Uses SoundTouch for true time-stretching without pitch change when available
     */
    private fun applyTempoStretching() {
        if (player is ExoPlayer) {
            // Try to use SoundTouch first if available
            if (useSoundTouch && isSoundTouchInitialized && soundTouchManager.isAvailable()) {
                soundTouchManager.setTempo(currentTempoFactor)
                
                if (pitchPreservationEnabled) {
                    // With SoundTouch, we can preserve pitch while changing tempo
                    soundTouchManager.setPitch(1.0f)
                    Log.d(TAG, "Applied SoundTouch tempo stretching: tempo=$currentTempoFactor, pitch preserved")
                } else {
                    // If pitch preservation is disabled, we might want to adjust pitch
                    // For now, keep pitch at 1.0 (normal)
                    soundTouchManager.setPitch(1.0f)
                    Log.d(TAG, "Applied SoundTouch tempo stretching: tempo=$currentTempoFactor")
                }
            } else {
                // Fall back to ExoPlayer's built-in speed adjustment
                // This changes both speed and pitch
                val playbackParams = player.playbackParameters
                    .withSpeed(currentTempoFactor)
                
                if (pitchPreservationEnabled) {
                    Log.w(TAG, "Pitch preservation requested but not supported with ExoPlayer speed adjustment. Use SoundTouch for true time-stretching.")
                }
                
                player.playbackParameters = playbackParams
                Log.d(TAG, "Applied ExoPlayer speed adjustment: speed=$currentTempoFactor (pitch changed too)")
            }
        } else {
            Log.e(TAG, "Player is not ExoPlayer, cannot apply tempo stretching")
        }
    }
}