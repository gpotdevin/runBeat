// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Audio processor chain that routes the playback speed to
 * [SoundTouchAudioProcessor] when the native engine is available.
 *
 * The chain is [SoundTouchAudioProcessor, silence skipping, Sonic]. When
 * SoundTouch is engaged, the requested speed is applied as the SoundTouch
 * tempo while the base chain's Sonic processor stays at speed 1.0 (a
 * pass-through), so the audio is stretched exactly once. ExoPlayer's clock
 * still sees the requested speed because the chain reports the original
 * [PlaybackParameters] back to the sink and maps playout to media durations
 * by the tempo factor itself.
 *
 * When the native engine is unavailable the base chain's behavior applies
 * unchanged: Sonic stretches by the requested speed.
 */
@UnstableApi
class SoundTouchAudioProcessorChain(
    private val soundTouchProcessor: SoundTouchAudioProcessor
) : DefaultAudioSink.DefaultAudioProcessorChain(soundTouchProcessor) {

    // Tempo most recently applied to SoundTouch; 1.0 until the sink reports
    // playback parameters.
    private var lastAppliedTempo = 1.0f

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        if (!soundTouchProcessor.engineAvailable) {
            // No native engine: let the base chain stretch with Sonic.
            return super.applyPlaybackParameters(playbackParameters)
        }
        val tempo = playbackParameters.speed
        soundTouchProcessor.setTempo(tempo)
        lastAppliedTempo = tempo
        // Keep Sonic at speed 1.0 so it passes audio through; discard the
        // result and report the original parameters so ExoPlayer's clock
        // tracks the true stretch ratio.
        super.applyPlaybackParameters(playbackParameters.withSpeed(1f))
        return playbackParameters
    }

    override fun getMediaDuration(playoutDuration: Long): Long {
        if (!soundTouchProcessor.engineAvailable) {
            return super.getMediaDuration(playoutDuration)
        }
        // Sonic is a pass-through in the SoundTouch path, so scale the
        // playout->media mapping by the tempo to stay consistent with the
        // reported playbackParameters.speed.
        return Math.round(playoutDuration * lastAppliedTempo.toDouble())
    }
}
