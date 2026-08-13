// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BpmDetector
 * Tests tap-to-beat BPM detection functionality
 * Automatic BPM detection (TarsosDSP) has been removed per requirements.
 */
class BpmDetectorTest {
    
    private lateinit var bpmDetector: BpmDetector
    
    @Before
    fun setUp() {
        bpmDetector = BpmDetector()
    }
    
    // ==================== Initial State Tests ====================

    @Test
    fun testInitialState_CreatedSuccessfully() {
        // Verify that the detector can be created
        assertNotNull("BpmDetector should be created successfully", bpmDetector)
    }
    
    // ==================== BPM Constants Tests ====================

    @Test
    fun testBpmConstants_AreDefined() {
        // Test that BPM constants are defined correctly
        assertEquals("MIN_BPM should be 40", 40f, BpmDetector.MIN_BPM)
        assertEquals("MAX_BPM should be 200", 200f, BpmDetector.MAX_BPM)
    }
    
    // ==================== Tap-to-Beat Tests ====================

    @Test
    fun testDetectBpmFromTaps_EmptyList() {
        // Test with empty tap intervals list
        val result = bpmDetector.detectBpmFromTaps(emptyList())
        assertNull("Result should be null for empty list", result)
    }

    @Test
    fun testDetectBpmFromTaps_SingleTap() {
        // Test with single tap - should return null as we need at least 2 taps
        val result = bpmDetector.detectBpmFromTaps(listOf(1000L))
        assertNull("Result should be null for single tap", result)
    }

    @Test
    fun testDetectBpmFromTaps_60BPM() {
        // Test with 1000ms intervals (60 BPM)
        // 60000 / 1000 = 60 BPM
        val result = bpmDetector.detectBpmFromTaps(listOf(1000L, 1000L, 1000L))
        assertNotNull("Result should not be null", result)
        assertEquals("BPM should be 60", 60, result)
    }

    @Test
    fun testDetectBpmFromTaps_120BPM() {
        // Test with 500ms intervals (120 BPM)
        // 60000 / 500 = 120 BPM
        val result = bpmDetector.detectBpmFromTaps(listOf(500L, 500L, 500L))
        assertNotNull("Result should not be null", result)
        assertEquals("BPM should be 120", 120, result)
    }

    @Test
    fun testDetectBpmFromTaps_180BPM() {
        // Test with 333ms intervals (180 BPM)
        // 60000 / 333 ≈ 180 BPM
        val result = bpmDetector.detectBpmFromTaps(listOf(333L, 333L, 333L))
        assertNotNull("Result should not be null", result)
        assertEquals("BPM should be approximately 180", 180.0, (result ?: 0).toDouble(), 1.0)
    }

    @Test
    fun testDetectBpmFromTaps_VaryingIntervals() {
        // Test with varying intervals - should use average
        // Average of 400, 600, 400 = 466.67ms
        // 60000 / 466.67 ≈ 128.57 BPM
        val result = bpmDetector.detectBpmFromTaps(listOf(400L, 600L, 400L))
        assertNotNull("Result should not be null", result)
        // Average interval = (400 + 600 + 400) / 3 = 466.67ms
        // BPM = 60000 / 466.67 ≈ 128.57
        val expectedBpm = (60000.0 / 466.67).toInt()
        assertEquals("BPM should be approximately 129", 129.0, (result ?: 0).toDouble(), 1.0)
    }

    @Test
    fun testDetectBpmFromTaps_MinBPMClamping() {
        // Test that BPM is clamped to minimum (40 BPM)
        // 60000 / 1500 = 40 BPM (exactly at minimum)
        val result = bpmDetector.detectBpmFromTaps(listOf(1500L, 1500L, 1500L))
        assertNotNull("Result should not be null", result)
        assertEquals("BPM should be clamped to minimum 40", 40, result)
    }

    @Test
    fun testDetectBpmFromTaps_MaxBPMClamping() {
        // Test that BPM is clamped to maximum (200 BPM)
        // 60000 / 300 = 200 BPM (exactly at maximum)
        val result = bpmDetector.detectBpmFromTaps(listOf(300L, 300L, 300L))
        assertNotNull("Result should not be null", result)
        assertEquals("BPM should be clamped to maximum 200", 200, result)
    }

    @Test
    fun testDetectBpmFromTaps_BelowMinBPM() {
        // Test with intervals that would give BPM below minimum
        // 60000 / 2000 = 30 BPM (below minimum of 40)
        val result = bpmDetector.detectBpmFromTaps(listOf(2000L, 2000L, 2000L))
        assertNotNull("Result should not be null", result)
        assertEquals("BPM should be clamped to minimum 40", 40, result)
    }

    @Test
    fun testDetectBpmFromTaps_AboveMaxBPM() {
        // Test with intervals that would give BPM above maximum
        // 60000 / 200 = 300 BPM (above maximum of 200)
        val result = bpmDetector.detectBpmFromTaps(listOf(200L, 200L, 200L))
        assertNotNull("Result should not be null", result)
        assertEquals("BPM should be clamped to maximum 200", 200, result)
    }
}
