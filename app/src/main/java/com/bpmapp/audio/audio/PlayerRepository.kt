// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository class that manages audio playback state and provides a clean API
 * for the UI to interact with the player
 */
@Singleton
@Suppress("UnstableApi")
class PlayerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exoPlayer: ExoPlayer,
    private val tempoStretcher: TempoStretcher,
    private val modeBeepPlayer: ModeBeepPlayer
) {

    companion object {
        // Rhythm patterns selectable in the UI (matches CadenceMatcher's list)
        private val DEFAULT_RHYTHM_PATTERNS = listOf(1.0f, 1.5f, 2.0f, 3.0f)
        private const val PREFS_NAME = "AppSettings"
        private const val KEY_SELECTED_RHYTHM_PATTERNS = "selected_rhythm_patterns"

        // Rhythm-mode beep signal at track start (2 = binary, 3 = ternary).
        private const val KEY_MODE_BEEP_SIGNAL = "mode_beep_signal"
        private const val DEFAULT_MODE_BEEP_SIGNAL = true
    }
    
    // State management
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    
    private val _currentTrack = MutableStateFlow<MediaItem?>(null)
    val currentTrack: StateFlow<MediaItem?> = _currentTrack.asStateFlow()
    
    private val _playlist = MutableStateFlow<List<MediaItem>>(emptyList())
    val playlist: StateFlow<List<MediaItem>> = _playlist.asStateFlow()
    
    // In-session BPM values keyed by track id. Kept separate from MediaItem extras
    // because MediaMetadata.equals() ignores extras, which would make StateFlow
    // suppress emissions when only the BPM changes (see updateBpmForTrack).
    private val _trackBpms = MutableStateFlow<Map<String, Float>>(emptyMap())
    val trackBpms: StateFlow<Map<String, Float>> = _trackBpms.asStateFlow()
    
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Target cadence for speed correction (150-195 BPM range)
    private val _targetCadence = MutableStateFlow(CadenceMatcher.DEFAULT_CADENCE)
    val targetCadence: StateFlow<Float> = _targetCadence.asStateFlow()
    
    // Speed correction enabled/disabled state.
    // Defaults to on to match the in-app cadence matching toggle.
    private val _speedCorrectionEnabled = MutableStateFlow(true)
    val speedCorrectionEnabled: StateFlow<Boolean> = _speedCorrectionEnabled.asStateFlow()

    // Current playback speed factor (1.0 = normal). Single source of truth for the
    // actual tempo; updated whenever setPlaybackSpeed is called.
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()
    
    // Rhythm patterns (factors) the user selected for cadence matching.
    // Single source of truth shared by the in-app player and the notification
    // speed-correction path. Persisted to SharedPreferences.
    private val _selectedRhythmPatterns =
        MutableStateFlow(loadSelectedRhythmPatterns())
    val selectedRhythmPatterns: StateFlow<Set<Float>> = _selectedRhythmPatterns.asStateFlow()

    // Shuffle playback state
    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    // Shuffled order of playlist indices used when shuffle is enabled
    private val _shuffleOrder = MutableStateFlow<List<Int>>(emptyList())

    // Whether to play a binary/ternary beep signal when a track starts.
    // Persisted to SharedPreferences. Default: on.
    private val _modeBeepSignal = MutableStateFlow(loadModeBeepSignal())
    val modeBeepSignal: StateFlow<Boolean> = _modeBeepSignal.asStateFlow()

    // Track id for which a mode beep was already emitted, so the signal only
    // fires once per track start (not on every play/resume).
    private var lastBeepedTrackId: String? = null

    init {
        updatePlayerState()
        
         // Set up player listener for state changes and periodic position updates
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("PlayerRepository", "Player error: ${error.message}", error)
                _error.value = "Error: ${error.message}"
                _playerState.update { it.copy(error = error.message) }
            }
            
            override fun onPlaybackStateChanged(state: Int) {
                updatePlayerState()
                // Auto-advance to next track when current track ends
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    playNext()
                }
            }
            
            @androidx.media3.common.util.UnstableApi
            override fun onPositionDiscontinuity(reason: Int) {
                updatePlayerState()
            }
        })
        
        // Use a Handler to post periodic position updates
        val handler = Handler(Looper.getMainLooper())
        val positionUpdateInterval = 100L // ms
        val positionUpdateRunnable = object : Runnable {
            override fun run() {
                if (exoPlayer.isPlaying) {
                    _playerState.update { currentState ->
                        currentState.copy(
                            isPlaying = exoPlayer.isPlaying,
                            currentPosition = exoPlayer.currentPosition
                        )
                    }
                    _currentPosition.value = exoPlayer.currentPosition
                }
                handler.postDelayed(this, positionUpdateInterval)
            }
        }
        // Start periodic position updates
        handler.post(positionUpdateRunnable)
    }
    
    private fun updatePlayerState() {
        _playerState.update { currentState ->
            currentState.copy(
                isPlaying = exoPlayer.isPlaying,
                playbackState = exoPlayer.playbackState,
                currentPosition = exoPlayer.currentPosition,
                duration = if (exoPlayer.duration > 0) exoPlayer.duration else 0,
                error = _error.value
            )
        }
        
        _currentPosition.value = exoPlayer.currentPosition
    }
    
    /**
     * Load a specific media item without starting playback
     */
    fun loadMediaItem(mediaItem: MediaItem) {
        _error.value = null
        _playlist.value = listOf(mediaItem)
        _currentTrack.value = mediaItem
        lastBeepedTrackId = null
        Log.d("PlayerRepository", "Loading media item: ${mediaItem.mediaMetadata.title}, URI: ${mediaItem.localConfiguration?.uri}")
        try {
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = false // Don't auto-play
            updatePlayerState()
        } catch (e: Exception) {
            Log.e("PlayerRepository", "Error preparing media item: ${mediaItem.mediaId}", e)
            _error.value = "Error: Could not prepare media for playback"
            _playerState.update { it.copy(error = e.message) }
        }
    }

    /**
     * Play a specific media item
     */
    fun playMediaItem(mediaItem: MediaItem) {
        _error.value = null
        _currentTrack.value = mediaItem
        try {
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            updatePlayerState()
            maybePlayModeBeep()
        } catch (e: Exception) {
            Log.e("PlayerRepository", "Error preparing media item: ${mediaItem.mediaId}", e)
            _error.value = "Error: Could not prepare media for playback"
            _playerState.update { it.copy(error = e.message) }
        }
    }
    
    /**
     * Toggle play/pause
     */
    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
            maybePlayModeBeep()
        }
        updatePlayerState()
    }

    /**
     * Play the current track
     */
    fun play() {
        exoPlayer.play()
        updatePlayerState()
        maybePlayModeBeep()
    }

    /**
     * Pause the current track
     */
    fun pause() {
        exoPlayer.pause()
        updatePlayerState()
    }

    /**
     * Stop playback but keep the current track loaded
     */
    fun stop() {
        exoPlayer.stop()
        exoPlayer.playWhenReady = false
        // Don't clear media items or current track - keep it loaded
        updatePlayerState()
    }

    /**
     * Seek to a specific position
     */
    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        updatePlayerState()
    }

    /**
     * Seek forward by a specified amount
     */
    fun seekForward(ms: Long) {
        val newPosition = exoPlayer.currentPosition + ms
        exoPlayer.seekTo(Math.min(newPosition, exoPlayer.duration))
        updatePlayerState()
    }

    /**
     * Seek backward by a specified amount
     */
    fun seekBackward(ms: Long) {
        val newPosition = exoPlayer.currentPosition - ms
        exoPlayer.seekTo(Math.max(newPosition, 0L))
        updatePlayerState()
    }

    /**
     * Set playback speed (for tempo adjustment)
     * Uses TempoStretcher for pitch-preserving time-stretching when available,
     * falls back to ExoPlayer's built-in speed adjustment
     */
    fun setPlaybackSpeed(speed: Float) {
        // Clamp to reasonable range for music playback
        val clampedSpeed = speed.coerceIn(TempoStretcher.RUNNER_MIN_TEMPO, TempoStretcher.RUNNER_MAX_TEMPO)
        _playbackSpeed.value = clampedSpeed
        tempoStretcher.setTempoFactor(clampedSpeed)
        updatePlayerState()
    }

    /**
     * Set target cadence for speed correction
     */
    fun setTargetCadence(cadence: Float) {
        _targetCadence.value = cadence.coerceIn(150f, 195f)
        reapplySpeedCorrection()
    }
    
    /**
     * Increment target cadence by 1
     */
    fun incrementTargetCadence() {
        _targetCadence.value = (_targetCadence.value + 1).coerceAtMost(195f)
        reapplySpeedCorrection()
    }
    
    /**
     * Decrement target cadence by 1
     */
    fun decrementTargetCadence() {
        _targetCadence.value = (_targetCadence.value - 1).coerceAtLeast(150f)
        reapplySpeedCorrection()
    }
    
    /**
     * Update the selected rhythm patterns. Persisted across sessions and
     * re-applied to playback immediately when speed correction is enabled.
     */
    fun setSelectedRhythmPatterns(patterns: Set<Float>) {
        _selectedRhythmPatterns.value = patterns
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SELECTED_RHYTHM_PATTERNS, patterns.map { it.toString() }.toSet())
            .apply()
        reapplySpeedCorrection()
    }
    
    private fun loadSelectedRhythmPatterns(): Set<Float> {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_SELECTED_RHYTHM_PATTERNS, null)
        val parsed = stored?.mapNotNull { it.toFloatOrNull() }?.toSet().orEmpty()
        return parsed.ifEmpty { DEFAULT_RHYTHM_PATTERNS.toSet() }
    }

    /**
     * Toggle the rhythm-mode beep signal at track start. Persisted across sessions.
     */
    fun setModeBeepSignal(enabled: Boolean) {
        _modeBeepSignal.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MODE_BEEP_SIGNAL, enabled)
            .apply()
    }

    private fun loadModeBeepSignal(): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MODE_BEEP_SIGNAL, DEFAULT_MODE_BEEP_SIGNAL)
    
    /**
     * Best cadence match for the given BPM and target cadence, restricted to the
     * currently selected rhythm patterns. Falls back to the overall optimum when
     * no patterns are selected.
     */
    fun bestCadenceMatch(
        detectedBpm: Float,
        targetCadence: Float
    ): CadenceMatcher.CadenceMatchResult {
        return CadenceMatcher().findBestMatch(
            detectedBpm,
            targetCadence,
            _selectedRhythmPatterns.value
        )
    }
    
    /**
     * Toggle speed correction on/off.
     * Enabling matches the current track BPM to the target cadence and applies the
     * resulting speed factor; disabling restores normal (1.0x) playback speed.
     */
    fun toggleSpeedCorrection() {
        if (_speedCorrectionEnabled.value) {
            disableSpeedCorrection()
        } else {
            enableSpeedCorrection()
        }
    }
    
    /**
     * Enable speed correction
     */
    fun enableSpeedCorrection() {
        _speedCorrectionEnabled.value = true
        applySpeedCorrection()
    }
    
    /**
     * Disable speed correction
     */
    fun disableSpeedCorrection() {
        _speedCorrectionEnabled.value = false
        setPlaybackSpeed(1.0f)
    }
    
    /**
     * Apply the cadence-matched speed factor for the current track.
     * No-op when the current track BPM is unknown.
     */
    private fun applySpeedCorrection() {
        val bpm = currentTrackBpm()
        if (bpm == null || bpm <= 0) return
        val matchResult = bestCadenceMatch(bpm, _targetCadence.value)
        if (matchResult.rhythmPattern.factor > 0) {
            setPlaybackSpeed(matchResult.speedFactor)
        }
    }

    /**
     * Play a binary (2 beeps) or ternary (3 beeps) signal for the current track.
     *
     * Conditions: the signal setting is on, speed correction is enabled, and the
     * track BPM is known. Fires at most once per track via [lastBeepedTrackId].
     */
    private fun maybePlayModeBeep() {
        if (!_modeBeepSignal.value) return
        if (!_speedCorrectionEnabled.value) return

        val item = _currentTrack.value ?: return
        // Dedup key: prefer the persisted track id, fall back to the media id so
        // items without extras still only signal once per track start.
        val dedupKey = item.mediaMetadata.extras?.getString("trackId") ?: item.mediaId
        if (dedupKey == lastBeepedTrackId) return

        val bpm = currentTrackBpm()
        if (bpm == null || bpm <= 0) return

        val factor = bestCadenceMatch(bpm, _targetCadence.value).rhythmPattern.factor
        val count = modeBeepPlayer.beepCountForFactor(factor)
        if (count > 0) {
            lastBeepedTrackId = dedupKey
            // Duck the music to 30% of its current volume while the beeps play,
            // then restore the original volume once they finish.
            val originalVolume = exoPlayer.volume
            val duckedVolume = (originalVolume * 0.3f).coerceIn(0f, 1f)
            modeBeepPlayer.beep(
                count,
                onStart = { exoPlayer.volume = duckedVolume },
                onFinish = { exoPlayer.volume = originalVolume }
            )
        }
    }

    /**
     * Re-evaluate and apply cadence matching for the current track using the
     * currently selected rhythm patterns. No-op when speed correction is
     * disabled or the current track BPM is unknown.
     */
    fun reapplySpeedCorrection() {
        if (!_speedCorrectionEnabled.value) return
        applySpeedCorrection()
    }
    
    /**
     * Check if speed correction is enabled
     */
    fun isSpeedCorrectionEnabled(): Boolean {
        return _speedCorrectionEnabled.value
    }

    /**
     * Rebuild any playlist/current media items whose extras "trackId" matches the given
     * track id, writing the new BPM into the extras "bpm" string field, and re-emits
     * the playlist and currentTrack StateFlows.
     *
     * Note: MediaMetadata.equals() ignores extras, so the rebuilt MediaItems compare
     * equal to the old ones and the playlist/currentTrack StateFlows suppress the
     * emission. The in-session BPM is therefore also published via [trackBpms], which
     * the UI uses to refresh playlist tiles reactively.
     */
    fun updateBpmForTrack(trackId: String, bpm: Float) {
        val bpmString = bpm.roundToInt().toString()
        val updateItem: (MediaItem) -> MediaItem = { item ->
            val extraTrackId = item.mediaMetadata.extras?.getString("trackId")
            if (extraTrackId != trackId) {
                item
            } else {
                val extras = item.mediaMetadata.extras?.let { Bundle(it) } ?: Bundle()
                extras.putString("bpm", bpmString)
                item.buildUpon()
                    .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
                    .build()
            }
        }
        _currentTrack.value?.let { current ->
            _currentTrack.value = updateItem(current)
        }
        _playlist.value = _playlist.value.map(updateItem)
        _trackBpms.value = _trackBpms.value + (trackId to bpm)
    }

    /**
     * Best-known BPM for the currently loaded track (in-session map first, extras fallback).
     */
    fun currentTrackBpm(): Float? {
        val item = _currentTrack.value ?: return null
        val trackId = item.mediaMetadata.extras?.getString("trackId")
        trackId?.let { id ->
            _trackBpms.value[id]?.let { return it }
        }
        return item.mediaMetadata.extras?.getString("bpm")?.toFloatOrNull()
    }

    /**
     * Play next track in playlist.
     * When shuffle is enabled, navigation follows the randomized shuffle order.
     */
    fun playNext() {
        val items = _playlist.value
        if (items.isEmpty()) return

        if (_shuffleEnabled.value) {
            val shuffledIndex = nextShuffledIndex()
            if (shuffledIndex != null) {
                playMediaItem(items[shuffledIndex])
                return
            }
        }

        // Sequential fallback (also used when shuffle order is empty or stale)
        val currentIndex = items.indexOfFirst { it == _currentTrack.value }
        val nextIndex = if (currentIndex >= 0 && currentIndex < items.size - 1) {
            currentIndex + 1
        } else {
            0
        }
        playMediaItem(items[nextIndex])
    }

    /**
     * Play previous track in playlist.
     * When shuffle is enabled, navigation follows the randomized shuffle order.
     */
    fun playPrevious() {
        val items = _playlist.value
        if (items.isEmpty()) return

        if (_shuffleEnabled.value) {
            val shuffledIndex = previousShuffledIndex()
            if (shuffledIndex != null) {
                playMediaItem(items[shuffledIndex])
                return
            }
        }

        // Sequential fallback (also used when shuffle order is empty or stale)
        val currentIndex = items.indexOfFirst { it == _currentTrack.value }
        val previousIndex = if (currentIndex > 0) {
            currentIndex - 1
        } else {
            items.size - 1
        }
        playMediaItem(items[previousIndex])
    }

    /**
     * Index of the next track in the shuffle order, wrapping around to the start.
     * Returns null if the order is empty or no longer valid for the current playlist.
     */
    private fun nextShuffledIndex(): Int? {
        val order = _shuffleOrder.value
        if (order.isEmpty()) return null
        val currentIndex = _playlist.value.indexOfFirst { it == _currentTrack.value }
        val positionInOrder = order.indexOf(currentIndex)
        if (positionInOrder < 0) return null
        val nextPosition = (positionInOrder + 1) % order.size
        val target = order[nextPosition]
        return if (target in _playlist.value.indices) target else null
    }

    /**
     * Index of the previous track in the shuffle order, wrapping around to the end.
     * Returns null if the order is empty or no longer valid for the current playlist.
     */
    private fun previousShuffledIndex(): Int? {
        val order = _shuffleOrder.value
        if (order.isEmpty()) return null
        val currentIndex = _playlist.value.indexOfFirst { it == _currentTrack.value }
        val positionInOrder = order.indexOf(currentIndex)
        if (positionInOrder < 0) return null
        val previousPosition = (positionInOrder - 1 + order.size) % order.size
        val target = order[previousPosition]
        return if (target in _playlist.value.indices) target else null
    }

    /**
     * Rebuild the shuffle order with the current track first and the rest randomized.
     */
    private fun rebuildShuffleOrder() {
        val items = _playlist.value
        if (items.isEmpty()) {
            _shuffleOrder.value = emptyList()
            return
        }
        val currentIndex = items.indexOfFirst { it == _currentTrack.value }
        val startIndex = if (currentIndex >= 0) currentIndex else 0
        val remaining = items.indices.filter { it != startIndex }
        _shuffleOrder.value = listOf(startIndex) + remaining.shuffled(Random.Default)
    }

    /**
     * Load a playlist
     */
    fun loadPlaylist(items: List<MediaItem>) {
        _playlist.value = items
        _currentTrack.value = items.firstOrNull()
        if (_shuffleEnabled.value) {
            rebuildShuffleOrder()
        }
        exoPlayer.setMediaItems(items)
        exoPlayer.prepare()
    }

    /**
     * Enable or disable shuffle playback
     */
    fun setShuffleEnabled(enabled: Boolean) {
        if (_shuffleEnabled.value != enabled) {
            _shuffleEnabled.value = enabled
            if (enabled) {
                rebuildShuffleOrder()
            }
        }
    }

    /**
     * Toggle shuffle playback on/off
     */
    fun toggleShuffle() {
        setShuffleEnabled(!_shuffleEnabled.value)
    }

    /**
     * Add a track to the playlist
     */
    fun addToPlaylist(mediaItem: MediaItem) {
        _playlist.update { currentPlaylist ->
            currentPlaylist + mediaItem
        }
    }

    /**
     * Clear the playlist
     */
    fun clearPlaylist() {
        _playlist.value = emptyList()
        exoPlayer.clearMediaItems()
    }

    data class PlayerState(
        val isPlaying: Boolean = false,
        val playbackState: Int = 0,
        val currentPosition: Long = 0L,
        val duration: Long = 0L,
        val error: String? = null
    )
}