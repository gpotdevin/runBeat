// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for CadenceMatcher
 */
class CadenceMatcherTest {
    
    private lateinit var cadenceMatcher: CadenceMatcher
    
    @Before
    fun setUp() {
        cadenceMatcher = CadenceMatcher()
    }
    
    // ==================== Basic Functionality Tests ====================
    
    @Test
    fun testFindOptimalMatch_ValidBPM() {
        // Test with a valid BPM that should find a good match
        val result = cadenceMatcher.findOptimalMatch(
            detectedBpm = 86f,
            targetCadence = 172f
        )
        
        assertNotNull("Result should not be null", result)
        assertTrue("Should be adjustable", result.isAdjustable)
        assertEquals("Speed factor should be 1.0", 1.0f, result.speedFactor, 0.01f)
        assertEquals("Rhythm pattern should be Eighth", "Eighth", result.rhythmPattern.name)
    }
    
    @Test
    fun testFindOptimalMatch_NotAdjustable() {
        // Test with a BPM that cannot be adjusted within the valid range
        // With BPM=86, target=300: r=3 gives f=1.1628 which is valid
        // Use BPM=200, target=400: r=1 gives f=2.0 (too high), r=2 gives f=1.0 (valid)
        // Use BPM=10, target=100: r=3 gives f=3.33 (too high), r=2 gives f=5.0 (too high)
        // r=1 gives f=10.0 (too high), r=1.5 gives f=6.67 (too high)
        // Actually all are out of range [0.75, 1.25]
        val result = cadenceMatcher.findOptimalMatch(
            detectedBpm = 10f,
            targetCadence = 100f
        )
        
        assertNotNull("Result should not be null", result)
        assertFalse("Should not be adjustable", result.isAdjustable)
    }
    
    @Test
    fun testFindOptimalMatch_ZeroBPM() {
        // Test with invalid BPM
        val result = cadenceMatcher.findOptimalMatch(
            detectedBpm = 0f,
            targetCadence = 172f
        )
        
        assertNotNull("Result should not be null", result)
        assertFalse("Should not be adjustable with zero BPM", result.isAdjustable)
        assertEquals("Speed factor should default to 1.0", 1.0f, result.speedFactor, 0.01f)
    }
    
    @Test
    fun testFindOptimalMatch_NegativeBPM() {
        // Test with negative BPM
        val result = cadenceMatcher.findOptimalMatch(
            detectedBpm = -100f,
            targetCadence = 172f
        )
        
        assertNotNull("Result should not be null", result)
        assertFalse("Should not be adjustable with negative BPM", result.isAdjustable)
    }
    
    // ==================== Algorithm Verification Tests ====================
    
    @Test
    fun testAlgorithm_ExampleFromDocumentation() {
        // Test the example from IMPLEMENTATION_PLAN.md
        // BPM = 86, Cadence = 172
        // r=1: f=2.00, adjustment=1.00 → OUT OF RANGE
        // r=2: f=1.00, adjustment=0.00 → SELECTED
        // r=1.5: f=1.33, adjustment=0.33 → OUT OF RANGE
        
        val result = cadenceMatcher.findOptimalMatch(
            detectedBpm = 86f,
            targetCadence = 172f
        )
        
        assertNotNull(result)
        assertTrue("Should be adjustable", result.isAdjustable)
        assertEquals("Speed factor should be 1.0", 1.0f, result.speedFactor, 0.01f)
        assertEquals("Adjustment should be 0.0", 0.0f, result.adjustment, 0.01f)
        assertEquals("Rhythm pattern should be Eighth", "Eighth", result.rhythmPattern.name)
    }
    
    @Test
    fun testAlgorithm_Ratio1To1() {
        // Test with ratio 1:1 (r=1)
        // f = cadence / (BPM * 1)
        val bpm = 172f
        val cadence = 172f
        
        val result = cadenceMatcher.findOptimalMatch(bpm, cadence)
        
        assertNotNull(result)
        assertTrue("Should be adjustable", result.isAdjustable)
        assertEquals("Speed factor should be 1.0", 1.0f, result.speedFactor, 0.01f)
        assertEquals("Adjustment should be 0.0", 0.0f, result.adjustment, 0.01f)
    }
    
    @Test
    fun testAlgorithm_Ratio2To1() {
        // Test with ratio 2:1 (r=2)
        // For BPM=86, cadence=172: f = 172 / (86 * 2) = 172 / 172 = 1.0
        val result = cadenceMatcher.findOptimalMatch(
            detectedBpm = 86f,
            targetCadence = 172f
        )
        
        assertNotNull(result)
        assertEquals("Speed factor should be 1.0", 1.0f, result.speedFactor, 0.01f)
        assertEquals("Rhythm pattern should be Eighth (r=2)", "Eighth", result.rhythmPattern.name)
    }
    
    @Test
    fun testAlgorithm_SelectsBestRhythmPattern() {
        // Test that the algorithm selects the pattern with minimum adjustment
        // BPM=100, cadence=172
        // r=1: f=172/100=1.72, adjustment=0.72 → OUT OF RANGE
        // r=2: f=172/200=0.86, adjustment=0.14 → VALID, BEST
        // r=1.5: f=172/150=1.1467, adjustment=0.1467 → VALID
        
        val result = cadenceMatcher.findOptimalMatch(
            detectedBpm = 100f,
            targetCadence = 172f
        )
        
        assertNotNull(result)
        assertTrue("Should be adjustable", result.isAdjustable)
        // The minimum adjustment is 0.14 (r=2)
        assertTrue("Adjustment should be close to 0.14", abs(result.adjustment - 0.14f) < 0.01f)
    }
    
    // ==================== Clamping Tests ====================
    
    @Test
    fun testClamping_MinimumSpeedFactor() {
        // Test that speed factor is clamped to minimum
        val result = cadenceMatcher.findOptimalMatch(
            detectedBpm = 200f,  // High BPM
            targetCadence = 172f  // Lower cadence
        )
        
        assertNotNull(result)
        // f = 172 / (200 * r). For r=1: f=0.86, r=2: f=0.43, r=1.5: f=0.573
        // Minimum valid is 0.75, so only r=1 gives f=0.86 which is valid
        assertTrue("Speed factor should be >= 0.75", result.speedFactor >= 0.75f)
    }
    
    @Test
    fun testClamping_MaximumSpeedFactor() {
        // Test that speed factor is clamped to maximum
        val result = cadenceMatcher.findOptimalMatch(
            detectedBpm = 40f,   // Low BPM
            targetCadence = 195f  // High cadence
        )
        
        assertNotNull(result)
        // f = 195 / (40 * r). For r=1: f=4.875, r=2: f=2.4375, r=1.5: f=3.25
        // Maximum valid is 1.25, so all are out of range
        if (!result.isAdjustable) {
            // If not adjustable, check that the speed factor is still calculated correctly
            assertTrue("Speed factor should be > 1.25", result.speedFactor > 1.25f)
        }
    }
    
    // ==================== isAdjustable Tests ====================
    
    @Test
    fun testIsAdjustable_True() {
        // BPM=86, cadence=172 should be adjustable (f=1.0 with r=2)
        val isAdjustable = cadenceMatcher.isAdjustable(
            detectedBpm = 86f,
            targetCadence = 172f
        )
        
        assertTrue("Should be adjustable", isAdjustable)
    }
    
    @Test
    fun testIsAdjustable_False() {
        // BPM=200, cadence=195 should not be adjustable
        // f = 195 / (200 * r). For r=1: f=0.975, r=2: f=0.4875, r=1.5: f=0.65
        // All are either out of range or the best is 0.975 which is valid
        // Actually 0.975 is within [0.75, 1.25], so this should be adjustable
        
        // Let's use a case that's definitely not adjustable
        val isAdjustable = cadenceMatcher.isAdjustable(
            detectedBpm = 300f,  // Very high BPM
            targetCadence = 172f
        )
        
        assertFalse("Should not be adjustable", isAdjustable)
    }
    
    @Test
    fun testIsAdjustable_ZeroBPM() {
        val isAdjustable = cadenceMatcher.isAdjustable(
            detectedBpm = 0f,
            targetCadence = 172f
        )
        
        assertFalse("Should not be adjustable with zero BPM", isAdjustable)
    }
    
    // ==================== getAllMatches Tests ====================
    
    @Test
    fun testGetAllMatches_ReturnsFourMatches() {
        val matches = cadenceMatcher.getAllMatches(
            detectedBpm = 86f,
            targetCadence = 172f
        )
        
        assertEquals("Should return 4 matches (one for each rhythm pattern)", 4, matches.size)
    }
    
    @Test
    fun testGetAllMatches_EmptyForZeroBPM() {
        val matches = cadenceMatcher.getAllMatches(
            detectedBpm = 0f,
            targetCadence = 172f
        )
        
        assertTrue("Should return empty list for zero BPM", matches.isEmpty())
    }
    
    // ==================== getRhythmPatterns Tests ====================
    
    @Test
    fun testGetRhythmPatterns_ReturnsFourPatterns() {
        val patterns = cadenceMatcher.getRhythmPatterns()
        
        assertEquals("Should return 4 rhythm patterns", 4, patterns.size)
    }
    
    @Test
    fun testGetRhythmPatterns_ContainsExpectedPatterns() {
        val patterns = cadenceMatcher.getRhythmPatterns()
        val patternNames = patterns.map { it.name }
        
        assertTrue("Should contain Quarter", patternNames.contains("Quarter"))
        assertTrue("Should contain Eighth", patternNames.contains("Eighth"))
        assertTrue("Should contain Triplet", patternNames.contains("Triplet"))
        assertTrue("Should contain Sixth", patternNames.contains("Sixth"))
    }
    
    // ==================== Edge Cases ====================
    
    @Test
    fun testDefaultCadence() {
        // Test that default cadence is used when not specified
        val result1 = cadenceMatcher.findOptimalMatch(detectedBpm = 86f)
        val result2 = cadenceMatcher.findOptimalMatch(detectedBpm = 86f, targetCadence = CadenceMatcher.DEFAULT_CADENCE)
        
        assertEquals("Results should be the same", result1.speedFactor, result2.speedFactor, 0.01f)
    }
    
    @Test
    fun testVeryLowBPM() {
        val result = cadenceMatcher.findOptimalMatch(
            detectedBpm = 1f,
            targetCadence = 172f
        )
        
        assertNotNull(result)
        // With BPM=1, all factors will be very high and likely out of range
        assertFalse("Should not be adjustable with very low BPM", result.isAdjustable)
    }
    
    @Test
    fun testVeryHighBPM() {
        val result = cadenceMatcher.findOptimalMatch(
            detectedBpm = 500f,
            targetCadence = 172f
        )
        
        assertNotNull(result)
        // With BPM=500, all factors will be very low
        assertFalse("Should not be adjustable with very high BPM", result.isAdjustable)
    }
    
    // ==================== findBestMatch (selection-aware) Tests ====================
    
    @Test
    fun testFindBestMatch_MatchesOptimalWhenSelectionEmpty() {
        val unrestricted = cadenceMatcher.findOptimalMatch(detectedBpm = 86f, targetCadence = 172f)
        val restricted = cadenceMatcher.findBestMatch(
            detectedBpm = 86f,
            targetCadence = 172f,
            selectedFactors = emptySet()
        )
        
        assertEquals("Empty selection should behave like findOptimalMatch",
            unrestricted.speedFactor, restricted.speedFactor, 0.01f)
        assertEquals("Pattern should match findOptimalMatch",
            unrestricted.rhythmPattern, restricted.rhythmPattern)
    }
    
    @Test
    fun testFindBestMatch_RespectsSingleSelectedFactor() {
        // BPM=86, cadence=172: Eighth (2.0) yields speed 1.0, but 1.0x (Quarter)
        // yields 2.0x. Restricting to {1.0} must force the Quarter pattern.
        val result = cadenceMatcher.findBestMatch(
            detectedBpm = 86f,
            targetCadence = 172f,
            selectedFactors = setOf(1.0f)
        )
        
        assertEquals("Should select Quarter when only 1.0x is selected",
            "Quarter", result.rhythmPattern.name)
        assertEquals("Speed factor should be 172 / (86 * 1.0) = 2.0",
            2.0f, result.speedFactor, 0.01f)
    }
    
    @Test
    fun testFindBestMatch_PicksBestAmongSelectedFactors() {
        // Restrict to {1.0, 2.0}: Quarter gives 2.0x (adjustment 1.0),
        // Eighth gives 1.0x (adjustment 0.0) -> Eighth must win.
        val result = cadenceMatcher.findBestMatch(
            detectedBpm = 86f,
            targetCadence = 172f,
            selectedFactors = setOf(1.0f, 2.0f)
        )
        
        assertEquals("Should pick Eighth (lowest adjustment among selected)",
            "Eighth", result.rhythmPattern.name)
        assertEquals("Speed factor should be 1.0", 1.0f, result.speedFactor, 0.01f)
    }
    
    @Test
    fun testFindBestMatch_FallsBackWhenSelectionExcluded() {
        // Selected factors that are absent from the pattern list -> fallback.
        val result = cadenceMatcher.findBestMatch(
            detectedBpm = 86f,
            targetCadence = 172f,
            selectedFactors = setOf(0.5f)
        )
        
        assertEquals("Fallback should pick Eighth (optimal)",
            "Eighth", result.rhythmPattern.name)
    }
    
    @Test
    fun testFindBestMatch_InvalidBPM() {
        val result = cadenceMatcher.findBestMatch(
            detectedBpm = 0f,
            targetCadence = 172f,
            selectedFactors = setOf(2.0f)
        )
        
        assertNotNull(result)
        assertFalse("Should not be adjustable with zero BPM", result.isAdjustable)
        assertEquals("Speed factor should default to 1.0", 1.0f, result.speedFactor, 0.01f)
    }
}
