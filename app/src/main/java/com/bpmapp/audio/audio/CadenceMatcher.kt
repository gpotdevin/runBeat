// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import kotlin.math.abs

/**
 * Cadence Matching Algorithm
 * 
 * Given a detected music BPM and target running cadence, finds the optimal playback
 * speed factor that aligns steps to musical rhythm patterns while minimizing speed adjustment.
 * 
 * Algorithm: For each rhythm pattern r in {1, 2, 1.5}:
 *   - Calculate required factor: f = cadence / (BPM * r)
 *   - Calculate adjustment: |f - 1|
 *   - Select the r with minimum adjustment
 *   - Clamp f to [0.75, 1.25]; if outside range, mark as "not adjustable"
 */
class CadenceMatcher {
    
    companion object {
        private const val TAG = "CadenceMatcher"
        
        // Rhythm patterns: quarter note (1), eighth note (2), triplet of quarters (1.5), sixth note (3)
        private val RHYTHM_PATTERNS = listOf(
            RhythmPattern(1.0f, "Quarter", "Run on every beat"),
            RhythmPattern(2.0f, "Eighth", "Run on every half beat"),
            RhythmPattern(1.5f, "Triplet", "Run on every dotted quarter"),
            RhythmPattern(3.0f, "Sixth", "Run on every sixth beat")
        )
        
        // Speed factor clamp range
        const val MIN_SPEED_FACTOR = 0.75f
        const val MAX_SPEED_FACTOR = 1.25f
        
        // Default target running cadence (steps per minute)
        const val DEFAULT_CADENCE = 172f
    }
    
    /**
     * Rhythm pattern for step alignment
     */
    data class RhythmPattern(
        val factor: Float,        // r: multiplier for BPM
        val name: String,         // Display name
        val description: String   // User-friendly description
    )
    
    /**
     * Result of cadence matching
     */
    data class CadenceMatchResult(
        val speedFactor: Float,          // f: playback speed to apply
        val rhythmPattern: RhythmPattern,// Selected rhythm pattern
        val adjustment: Float,            // |f - 1|: how much speed changes
        val isAdjustable: Boolean,        // true if f is within [0.75, 1.25]
        val calculatedBpm: Float,         // BPM after adjustment: BPM * r * f
        val message: String               // User-facing message
    )
    
    /**
     * Find the optimal cadence match for given BPM and target cadence
     * 
     * @param detectedBpm The BPM of the music (must be > 0)
     * @param targetCadence The desired running cadence in steps per minute (default: 172)
     * @return CadenceMatchResult with optimal speed factor and rhythm pattern
     */
    fun findOptimalMatch(
        detectedBpm: Float,
        targetCadence: Float = DEFAULT_CADENCE
    ): CadenceMatchResult {
        return findBestMatch(detectedBpm, targetCadence, RHYTHM_PATTERNS.map { it.factor }.toSet())
    }

    /**
     * Find the best cadence match restricted to the given rhythm pattern factors.
     * When [selectedFactors] is empty, behaves exactly like [findOptimalMatch].
     */
    fun findBestMatch(
        detectedBpm: Float,
        targetCadence: Float = DEFAULT_CADENCE,
        selectedFactors: Set<Float> = emptySet()
    ): CadenceMatchResult {
        if (detectedBpm <= 0) {
            return CadenceMatchResult(
                speedFactor = 1.0f,
                rhythmPattern = RHYTHM_PATTERNS[0],
                adjustment = 0f,
                isAdjustable = false,
                calculatedBpm = 0f,
                message = "Invalid BPM: must be > 0"
            )
        }
        
        // Calculate all possible matches, filtered to the selected factors
        val candidates = RHYTHM_PATTERNS
            .filter { selectedFactors.isEmpty() || it.factor in selectedFactors }
            .map { pattern ->
                calculateMatch(detectedBpm, targetCadence, pattern)
            }
        
        // A non-empty selection that matches no pattern falls back to the optimum.
        if (candidates.isEmpty()) {
            return findBestMatch(detectedBpm, targetCadence, emptySet())
        }
        
        // Find the best candidate (minimum adjustment)
        val bestCandidate = candidates.minByOrNull { it.adjustment }
            ?: return CadenceMatchResult(
                speedFactor = 1.0f,
                rhythmPattern = RHYTHM_PATTERNS[0],
                adjustment = 0f,
                isAdjustable = false,
                calculatedBpm = 0f,
                message = "No valid match found"
            )
        
        // Check if best candidate is within valid range
        val isValid = bestCandidate.speedFactor in MIN_SPEED_FACTOR..MAX_SPEED_FACTOR
        
        // Generate message
        val message = if (isValid) {
            "Run on ${bestCandidate.rhythmPattern.name.lowercase()} notes at ${"%.2f".format(bestCandidate.speedFactor)}x speed"
        } else {
            "No valid rhythm match (speed would be ${"%.2f".format(bestCandidate.speedFactor)}x)"
        }
        
        return bestCandidate.copy(
            isAdjustable = isValid,
            message = message
        )
    }
    
    /**
     * Calculate match for a specific rhythm pattern
     */
    private fun calculateMatch(
        detectedBpm: Float,
        targetCadence: Float,
        pattern: RhythmPattern
    ): CadenceMatchResult {
        // f = cadence / (BPM * r)
        val speedFactor = targetCadence / (detectedBpm * pattern.factor)
        
        // |f - 1|
        val adjustment = abs(speedFactor - 1.0f)
        
        // Calculated BPM after adjustment: BPM * r * f = BPM * r * (cadence / (BPM * r)) = cadence
        val calculatedBpm = targetCadence
        
        return CadenceMatchResult(
            speedFactor = speedFactor,
            rhythmPattern = pattern,
            adjustment = adjustment,
            isAdjustable = false, // Will be set by caller
            calculatedBpm = calculatedBpm,
            message = ""
        )
    }
    
    /**
     * Get all possible matches for inspection
     */
    fun getAllMatches(
        detectedBpm: Float,
        targetCadence: Float = DEFAULT_CADENCE
    ): List<CadenceMatchResult> {
        if (detectedBpm <= 0) return emptyList()
        
        return RHYTHM_PATTERNS.map { pattern ->
            val speedFactor = targetCadence / (detectedBpm * pattern.factor)
            val adjustment = abs(speedFactor - 1.0f)
            val isValid = speedFactor in MIN_SPEED_FACTOR..MAX_SPEED_FACTOR
            
            CadenceMatchResult(
                speedFactor = speedFactor,
                rhythmPattern = pattern,
                adjustment = adjustment,
                isAdjustable = isValid,
                calculatedBpm = targetCadence,
                message = if (isValid) "Valid" else "Out of range"
            )
        }
    }
    
    /**
     * Check if a BPM can be adjusted to match target cadence
     */
    fun isAdjustable(
        detectedBpm: Float,
        targetCadence: Float = DEFAULT_CADENCE
    ): Boolean {
        if (detectedBpm <= 0) return false
        
        return RHYTHM_PATTERNS.any { pattern ->
            val speedFactor = targetCadence / (detectedBpm * pattern.factor)
            speedFactor in MIN_SPEED_FACTOR..MAX_SPEED_FACTOR
        }
    }
    
    /**
     * Get all available rhythm patterns
     */
    fun getRhythmPatterns(): List<RhythmPattern> {
        return RHYTHM_PATTERNS
    }
}
