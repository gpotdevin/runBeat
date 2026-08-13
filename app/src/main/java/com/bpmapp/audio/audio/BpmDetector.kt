// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

/**
 * BPM Detection service - Manual/ Tap-to-beat only
 * Automatic BPM detection (TarsosDSP) has been removed as per docs/OPEN_UI_TASKS.md
 * Only tap-to-beat functionality remains for manual BPM entry
 */
class BpmDetector {
    
    companion object {
        // Typical BPM range for music
        const val MIN_BPM = 40f
        const val MAX_BPM = 200f
    }
    
    /**
     * Detect BPM from tap intervals (manual method)
     * @param tapIntervalsMs List of time intervals between taps in milliseconds
     * @return Detected BPM as integer, or null if insufficient taps
     */
    fun detectBpmFromTaps(tapIntervalsMs: List<Long>): Int? {
        if (tapIntervalsMs.size < 2) {
            return null
        }
        
        val avgInterval = tapIntervalsMs.average()
        val bpm = (60000.0 / avgInterval).toInt()
        
        return bpm.coerceIn(MIN_BPM.toInt(), MAX_BPM.toInt())
    }
}
