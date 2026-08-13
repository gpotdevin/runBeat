// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bpmapp.audio.audio.CadenceMatcher
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.audio.TempoStretcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import javax.inject.Inject

/**
 * ViewModel for Player UI state
 */
@HiltViewModel
@SuppressLint("StaticFieldLeak") // @ApplicationContext field is application-scoped
class PlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    val playerState = playerRepository.playerState
    val currentTrack = playerRepository.currentTrack
    val errorState = playerRepository.error

    // UI state for BPM, cadence
    var musicBpm by mutableStateOf<Int?>(null)
    var targetCadence by mutableIntStateOf(playerRepository.targetCadence.value.toInt()) // Mirrors repository state
    var selectedRatio by mutableFloatStateOf(1.0f)
    var adjustedCadence by mutableFloatStateOf(128.0f)
    var calculatedSpeed by mutableFloatStateOf(1.0f)
    var currentPlaybackSpeed by mutableFloatStateOf(1.0f)

    // Cadence matching state
    var cadenceMatchResult: CadenceMatcher.CadenceMatchResult? by mutableStateOf(null)
    var applyCadenceMatch: Boolean by mutableStateOf(true)

    // Tap BPM detection state
    private val tapTimestamps = ArrayDeque<Long>(10)
    var tapBpm by mutableStateOf<Int?>(null)
    var isTapping by mutableStateOf(false)
    
    // Auto BPM detection state
    var autoDetectedBpm by mutableStateOf<Float?>(null)

    init {
        // Monitor current track changes to update URI
        // This will be used for automatic BPM detection
        updateCalculatedSpeed()

        // Mirror the shared target cadence from the repository so the UI and the
        // cadence-matching calculations stay in sync with actual playback.
        viewModelScope.launch {
            playerRepository.targetCadence.collect { cadence ->
                targetCadence = cadence.toInt()
                updateCalculatedSpeed()
            }
        }
    }

    private fun updateCalculatedSpeed() {
        // Calculate best ratio and speed factor automatically
        // Using CadenceMatcher to find optimal rhythm pattern
        val bpmValue = musicBpm
        if (bpmValue != null && bpmValue > 0) {
            val matchResult = findBestCadenceMatch(bpmValue.toFloat())
            
            // Update selected ratio and adjusted cadence
            selectedRatio = matchResult.rhythmPattern.factor
            adjustedCadence = bpmValue * selectedRatio
            
            // Calculate speed factor: factor = target_cadence / adjusted_cadence
            calculatedSpeed = if (adjustedCadence > 0) {
                (targetCadence.toFloat() / adjustedCadence).coerceIn(TempoStretcher.RUNNER_MIN_TEMPO, TempoStretcher.RUNNER_MAX_TEMPO)
            } else {
                1.0f
            }
        } else {
            selectedRatio = 1.0f
            adjustedCadence = 0f
            calculatedSpeed = 1.0f
        }
        
        // Update cadence match when BPM changes
        updateCadenceMatch()
    }

    /**
     * Update cadence match result based on current BPM and target cadence
     */
    private fun updateCadenceMatch() {
        val bpmValue = musicBpm
        if (bpmValue != null && bpmValue > 0) {
            cadenceMatchResult = findBestCadenceMatch(bpmValue.toFloat())
        } else {
            cadenceMatchResult = null
        }
    }

    private fun findBestCadenceMatch(bpmValue: Float): CadenceMatcher.CadenceMatchResult {
        // Source of truth is the repository (shared with actual playback);
        // delegate selection-aware matching to it.
        return playerRepository.bestCadenceMatch(bpmValue, targetCadence.toFloat())
    }

    /**
     * Toggle applying cadence match speed factor
     */
    fun toggleApplyCadenceMatch() {
        applyCadenceMatch = !applyCadenceMatch
        if (applyCadenceMatch) {
            // Calculate match if not already done
            if (cadenceMatchResult == null && musicBpm != null) {
                updateCadenceMatch()
            }
            cadenceMatchResult?.let { result ->
                setPlaybackSpeed(result.speedFactor)
            } ?: setPlaybackSpeed(1.0f)
            // Keep the repository flag in sync so the notification toggle reflects
            // the in-app state
            playerRepository.enableSpeedCorrection()
        } else {
            // Reset to normal speed (1.0x) when disabling cadence match
            setPlaybackSpeed(1.0f)
            playerRepository.disableSpeedCorrection()
        }
    }

    fun updateTargetCadence(cadence: Int) {
        targetCadence = cadence.coerceIn(150, 195) // Range from requirements
        // Keep the repository cadence in sync so the notification speed toggle uses
        // the same target as the UI
        playerRepository.setTargetCadence(targetCadence.toFloat())
        updateCalculatedSpeed()
    }

    fun updateMusicBpm(bpm: Int?) {
        musicBpm = bpm
        updateCalculatedSpeed()
        // If cadence match is enabled, automatically apply the new speed factor
        if (applyCadenceMatch) {
            cadenceMatchResult?.let { result ->
                setPlaybackSpeed(result.speedFactor)
            } ?: run {
                // No cadence match result (BPM is null), reset to normal speed
                setPlaybackSpeed(1.0f)
            }
        }
    }

    // Delegate player control calls to repository
    fun togglePlayPause() = playerRepository.togglePlayPause()
    fun play() = playerRepository.play()
    fun pause() = playerRepository.pause()
    fun stop() = playerRepository.stop()
    fun seekTo(positionMs: Long) = playerRepository.seekTo(positionMs)
    fun seekForward(ms: Long = 10000) = playerRepository.seekForward(ms)
    fun seekBackward(ms: Long = 10000) = playerRepository.seekBackward(ms)
    fun setPlaybackSpeed(speed: Float) {
        playerRepository.setPlaybackSpeed(speed)
        currentPlaybackSpeed = speed
    }

    /**
     * Reset playback speed to normal (1.0x)
     */
    fun resetPlaybackSpeed() {
        setPlaybackSpeed(1.0f)
    }

    /**
     * Reset tap detection
     */
    fun resetTapBpm() {
        tapTimestamps.clear()
        tapBpm = null
        isTapping = false
    }

    /**
     * Clear auto-detected BPM
     */
    fun clearAutoDetectedBpm() {
        autoDetectedBpm = null
    }
}
