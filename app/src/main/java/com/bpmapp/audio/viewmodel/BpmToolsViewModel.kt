// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.bpmapp.audio.audio.BpmResolver
import com.bpmapp.audio.audio.CadenceMatcher
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.audio.TrackRepository
import com.bpmapp.audio.audio.UriUtils
import com.bpmapp.audio.data.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for BpmToolsScreen
 * Manages shared state for BPM detection and cadence matching
 */
@HiltViewModel
@SuppressLint("StaticFieldLeak") // @ApplicationContext field is application-scoped
class BpmToolsViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val bpmResolver: BpmResolver,
    private val trackRepository: TrackRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    companion object {
        private const val TAG = "BpmToolsViewModel"
        private const val MIN_CADENCE = 150
        private const val MAX_CADENCE = 195
    }
    
    // Tab state
    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()
    
    // Cadence Matcher Tab State
    // Target cadence and rhythm-pattern selection are owned by PlayerRepository
    // (shared with actual playback); these flows mirror its values for the UI.
    private val _targetCadence = MutableStateFlow(playerRepository.targetCadence.value.toInt())
    val targetCadence: StateFlow<Int> = _targetCadence.asStateFlow()
    
    private val _currentTrackBpm = MutableStateFlow<Float?>(playerRepository.currentTrackBpm())
    val currentTrackBpm: StateFlow<Float?> = _currentTrackBpm.asStateFlow()
    
    private val _selectedRhythmPatterns = MutableStateFlow(playerRepository.selectedRhythmPatterns.value)
    val selectedRhythmPatterns: StateFlow<Set<Float>> = _selectedRhythmPatterns.asStateFlow()
    
    private val _rhythmMatches = MutableStateFlow<List<CadenceMatcher.CadenceMatchResult>>(emptyList())
    val rhythmMatches: StateFlow<List<CadenceMatcher.CadenceMatchResult>> = _rhythmMatches.asStateFlow()
    
    // Detect BPM Tab State
    private val _detectedBpm = MutableStateFlow<Int?>(null)
    val detectedBpm: StateFlow<Int?> = _detectedBpm.asStateFlow()

    private val _detectedFromTap = MutableStateFlow(false)
    val detectedFromTap: StateFlow<Boolean> = _detectedFromTap.asStateFlow()
    
    private val _isDetectingBpm = MutableStateFlow(false)
    val isDetectingBpm: StateFlow<Boolean> = _isDetectingBpm.asStateFlow()
    
    private val _tapTimes = MutableStateFlow<List<Long>>(emptyList())
    val tapTimes: StateFlow<List<Long>> = _tapTimes.asStateFlow()
    
    // Manual Entry Tab State
    private val _manualBpmInput = MutableStateFlow("")
    val manualBpmInput: StateFlow<String> = _manualBpmInput.asStateFlow()
    
    // Shared state
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _currentTrack = MutableStateFlow<MediaItem?>(null)
    val currentTrack: StateFlow<MediaItem?> = _currentTrack.asStateFlow()

    val playbackSpeed: StateFlow<Float> = playerRepository.playbackSpeed
    
    private val cadenceMatcher = CadenceMatcher()

    init {
        // Keep the tab's BPM in sync with the currently playing track, so the
        // cadence matches shown here reflect what playback would apply.
        viewModelScope.launch {
            playerRepository.currentTrack.collect {
                setCurrentTrackBpm(playerRepository.currentTrackBpm())
            }
        }
    }
    
    // Tab navigation
    fun selectTab(index: Int) {
        _selectedTabIndex.value = index
    }
    
    // Cadence Matcher functions
    fun setTargetCadence(value: Int) {
        _targetCadence.value = value.coerceIn(MIN_CADENCE, MAX_CADENCE)
        playerRepository.setTargetCadence(_targetCadence.value.toFloat())
    }
    
    fun incrementTargetCadence() {
        _targetCadence.value = (_targetCadence.value + 1).coerceAtMost(MAX_CADENCE)
        playerRepository.setTargetCadence(_targetCadence.value.toFloat())
    }
    
    fun decrementTargetCadence() {
        _targetCadence.value = (_targetCadence.value - 1).coerceAtLeast(MIN_CADENCE)
        playerRepository.setTargetCadence(_targetCadence.value.toFloat())
    }
    
    fun setCurrentTrackBpm(bpm: Float?) {
        _currentTrackBpm.value = bpm
        updateRhythmMatches()
    }
    
    fun getRhythmGroups(): List<CadenceMatcher.RhythmGroup> = cadenceMatcher.getRhythmGroups()

    fun isGroupSelected(group: CadenceMatcher.RhythmGroup, selected: Set<Float>): Boolean =
        cadenceMatcher.groupIsSelected(group, selected)

    fun toggleRhythmGroup(group: CadenceMatcher.RhythmGroup) {
        val current = _selectedRhythmPatterns.value
        val updated = if (cadenceMatcher.groupIsSelected(group, current)) {
            current - group.factors
        } else {
            current + group.factors
        }
        _selectedRhythmPatterns.value = updated
        playerRepository.setSelectedRhythmPatterns(updated)
        updateRhythmMatches()
    }
    
    private fun updateRhythmMatches() {
        val bpm = _currentTrackBpm.value
        val cadence = _targetCadence.value
        
        if (bpm == null || bpm <= 0) {
            _rhythmMatches.value = emptyList()
            return
        }
        
        val allMatches = cadenceMatcher.getAllMatches(bpm, cadence.toFloat())
        _rhythmMatches.value = _selectedRhythmPatterns.value
            .mapNotNull { multiplier ->
                allMatches.find { it.rhythmPattern.factor == multiplier }
            }
    }
    
    // Detect BPM functions
    fun onTap() {
        val now = System.currentTimeMillis()
        val previousTimes = _tapTimes.value
        
        val newTimes = if (previousTimes.isEmpty()) {
            listOf(now)
        } else {
            // Keep only the last 10 taps to avoid memory issues
            (previousTimes + now).takeLast(10)
        }
        
        _tapTimes.value = newTimes
        
        // Calculate BPM if we have at least 2 taps
        if (newTimes.size >= 2) {
            calculateBpmFromTaps(newTimes)
        }
    }
    
    private fun calculateBpmFromTaps(tapTimes: List<Long>) {
        if (tapTimes.size < 2) {
            _detectedBpm.value = null
            return
        }
        
        // Calculate intervals between taps in milliseconds
        val intervals = mutableListOf<Long>()
        for (i in 1 until tapTimes.size) {
            intervals.add(tapTimes[i] - tapTimes[i - 1])
        }
        
        // Filter out very short intervals (likely accidental double taps)
        val filteredIntervals = intervals.filter { it > 50 } // Minimum 50ms between taps
        
        if (filteredIntervals.isEmpty()) {
            _detectedBpm.value = null
            return
        }
        
        // Calculate average interval
        val avgInterval = filteredIntervals.average()
        
        // Convert to BPM: 60000ms per minute / average interval in ms
        val bpm = (60000.0 / avgInterval).toInt()
        
        // Only update if BPM is in reasonable range
        if (bpm in 40..200) {
            _detectedBpm.value = bpm
            _detectedFromTap.value = true
        }
    }
    
    fun clearDetectedBpm() {
        _detectedBpm.value = null
        _detectedFromTap.value = false
        _tapTimes.value = emptyList()
    }
    
    private fun currentTrackId(): String? {
        val mediaItem = playerRepository.currentTrack.value ?: return null
        val mediaUri = mediaItem.localConfiguration?.uri ?: return null
        val rawTrackId = mediaItem.mediaMetadata?.extras?.getString("trackId")
        return rawTrackId?.takeIf { it.isNotBlank() } ?: mediaUri.toString()
    }

    fun useDetectedBpm() {
        val bpm = _detectedBpm.value ?: return
        val trackId = currentTrackId()
        if (trackId == null) {
            _errorMessage.value = "No track selected. Play a track first."
            return
        }
        setCurrentTrackBpm(bpm.toFloat())
        _manualBpmInput.value = bpm.toString()
        playerRepository.updateBpmForTrack(trackId, bpm.toFloat())
        viewModelScope.launch(Dispatchers.IO) {
            trackRepository.updateBpm(trackId, bpm.toFloat())
        }
        _errorMessage.value = null
    }
    
    fun detectBpmWithAubio() {
        viewModelScope.launch(Dispatchers.IO) {
            val mediaItem = playerRepository.currentTrack.value
            if (mediaItem == null) {
                _errorMessage.value = "No track selected. Play a track first."
                return@launch
            }
            
            val mediaUri = mediaItem.localConfiguration?.uri
            if (mediaUri == null) {
                _errorMessage.value = "Cannot access audio for the current track."
                return@launch
            }
            
            _isDetectingBpm.value = true
            _errorMessage.value = null
            
            try {
                val rawTrackId = mediaItem.mediaMetadata?.extras?.getString("trackId")
                val trackId = rawTrackId?.takeIf { it.isNotBlank() } ?: mediaUri.toString()
                val track = trackRepository.getTrackById(trackId).firstOrNull()
                    ?: Track(
                        id = trackId,
                        relativePath = "",
                        fileName = UriUtils.getDisplayName(context, mediaUri) ?: "track",
                        bpm = null,
                        fileSizeBytes = null,
                        durationMs = null
                    )
                val bpm = bpmResolver.resolveBpmForTrack(track, forceFileBpm = true)
                
                if (bpm != null && bpm > 0) {
                    _detectedBpm.value = bpm.toInt()
                    _detectedFromTap.value = false
                    setCurrentTrackBpm(bpm)
                    playerRepository.updateBpmForTrack(trackId, bpm)
                } else {
                    _errorMessage.value = "Could not detect BPM for this track"
                }
            } catch (e: Exception) {
                _errorMessage.value = "BPM detection error: ${e.message}"
            } finally {
                _isDetectingBpm.value = false
            }
        }
    }
    
    // Manual Entry functions
    fun setManualBpmInput(value: String) {
        // Only allow numeric input
        if (value.isEmpty() || value.all { it.isDigit() }) {
            _manualBpmInput.value = value
        }
    }
    
    fun saveManualBpm() {
        _manualBpmInput.value.toIntOrNull()?.let { bpm ->
            if (bpm in 40..200) {
                setCurrentTrackBpm(bpm.toFloat())
                _detectedBpm.value = bpm
                val trackId = currentTrackId()
                if (trackId == null) {
                    _errorMessage.value = "No track selected. Play a track first."
                    return@let
                }
                playerRepository.updateBpmForTrack(trackId, bpm.toFloat())
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        trackRepository.updateBpm(trackId, bpm.toFloat())
                        val written = trackRepository.saveBpmToFileMetadata(trackId, bpm.toFloat())
                        _errorMessage.value = if (written) {
                            "BPM saved to track and file ID3 tag"
                        } else {
                            "BPM saved to track library (could not write to file metadata)"
                        }
                    } catch (e: Exception) {
                        _errorMessage.value = "Failed to save BPM: ${e.message}"
                    }
                }
            }
        }
    }
    
    fun clearError() {
        _errorMessage.value = null
    }
}
