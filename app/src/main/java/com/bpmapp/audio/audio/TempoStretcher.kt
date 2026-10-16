// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Applies the cadence-matched tempo to playback.
 *
 * With SoundTouch available, the tempo is applied inside ExoPlayer's audio sink
 * by [SoundTouchAudioProcessor]: pitch-preserving WSOLA time-stretching on the
 * decoded PCM. ExoPlayer's own playbackParameters speed stays at 1.0 so the
 * built-in Sonic processor remains a pass-through and does not stretch a second
 * time. When the SoundTouch native library is unavailable, the tempo falls back
 * to ExoPlayer's built-in Sonic time-stretch via playbackParameters (which also
 * preserves pitch at the ratios used here).
 *
 * Limitation of the SoundTouch path: ExoPlayer reports positions and durations
 * in the wall-clock of the stretched output, so the progress bar drifts by the
 * tempo factor (e.g. runs fast at 1.2x). Playback, track transitions and
 * auto-advance are not affected.
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

        if (player !is ExoPlayer) {
            Log.e(TAG, "Player is not ExoPlayer, cannot apply tempo stretching")
            return
        }

        if (isSoundTouchAvailable()) {
            // SoundTouch stretches inside the audio sink; keep the player's own
            // speed at 1.0 so Sonic stays a pass-through.
            soundTouchProcessor?.setTempo(clampedFactor)
            if (player.playbackParameters.speed != 1.0f) {
                player.playbackParameters = player.playbackParameters.withSpeed(1.0f)
            }
            Log.d(TAG, "Applied SoundTouch tempo stretching: tempo=$clampedFactor, pitch preserved")
        } else {
            // Fall back to ExoPlayer's built-in pitch-preserving time-stretch.
            player.playbackParameters = player.playbackParameters.withSpeed(clampedFactor)
            Log.d(TAG, "Applied ExoPlayer Sonic time-stretch: speed=$clampedFactor")
        }
    }

    /**
     * Get the current tempo factor
     */
    fun getTempoFactor(): Float {
        return currentTempoFactor
    }
}
