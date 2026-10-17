// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Applies the cadence-matched tempo to playback.
 *
 * The tempo is always applied via playbackParameters.speed. The audio sink's
 * [SoundTouchAudioProcessorChain] routes that speed to
 * [SoundTouchAudioProcessor] when the native engine is available, keeping the
 * built-in Sonic processor at 1.0 (a pass-through) so the audio is stretched
 * exactly once; when SoundTouch is unavailable the speed falls back to
 * ExoPlayer's built-in Sonic time-stretch (which also preserves pitch at the
 * ratios used here). In both cases ExoPlayer's clock sees the true speed, so
 * positions and durations map by the tempo factor.
 */
@UnstableApi
class TempoStretcher(
    private val player: Player,
    private val soundTouchProcessor: SoundTouchAudioProcessor? = null,
    private val useSoundTouch: Boolean = true
) {

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

    private var currentTempoFactor = DEFAULT_TEMPO

    /**
     * Check if SoundTouch is available and being used
     */
    fun isSoundTouchAvailable(): Boolean {
        return useSoundTouch && soundTouchProcessor != null && soundTouchProcessor.engineAvailable
    }

    /**
     * Set the tempo factor (1.0 = normal, >1.0 = faster, <1.0 = slower)
     */
    fun setTempoFactor(factor: Float) {
        val clampedFactor = factor.coerceIn(MIN_TEMPO, MAX_TEMPO)
        currentTempoFactor = clampedFactor

        // The sink's processor chain applies this speed to SoundTouch when
        // available, or to ExoPlayer's Sonic time-stretch otherwise.
        player.playbackParameters = player.playbackParameters.withSpeed(clampedFactor)
        Log.d(TAG, "Applied tempo: speed=$clampedFactor")
    }

    /**
     * Get the current tempo factor
     */
    fun getTempoFactor(): Float {
        return currentTempoFactor
    }
}
