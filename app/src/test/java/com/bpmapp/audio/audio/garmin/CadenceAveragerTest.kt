// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CadenceAverager].
 *
 * The clock is injected so staleness and wraparound are tested deterministically.
 */
class CadenceAveragerTest {

    private var now = 1_000_000L
    private lateinit var averager: CadenceAverager

    @Before
    fun setUp() {
        now = 1_000_000L
        averager = CadenceAverager(clock = { now })
    }

    @Test
    fun meanLast60s_emptyBuffer_returnsNull() {
        assertNull(averager.meanLast60s())
    }

    @Test
    fun meanLast60s_allZeroSamples_returnsNull() {
        repeat(10) { averager.add(0f) }
        assertNull(averager.meanLast60s())
    }

    @Test
    fun meanLast60s_ignoresZeroAndNegativeSamples() {
        averager.add(0f)
        averager.add(-5f)
        averager.add(180f)
        assertEquals(180f, averager.meanLast60s())
    }

    @Test
    fun meanLast60s_fractionalMean() {
        averager.add(170f)
        averager.add(171f)
        assertEquals(170.5f, averager.meanLast60s())
    }

    @Test
    fun meanLast60s_singleSample() {
        averager.add(165f)
        assertEquals(165f, averager.meanLast60s())
    }

    @Test
    fun meanLast60s_excludesSamplesOlderThan60s() {
        averager.add(100f)
        now += 61_000 // sample is now stale
        averager.add(180f)
        assertEquals(180f, averager.meanLast60s())
    }

    @Test
    fun meanLast60s_allSamplesStale_returnsNull() {
        averager.add(100f)
        now += 61_000
        assertNull(averager.meanLast60s())
    }

    @Test
    fun meanLast60s_sampleExactly60sOld_isIncluded() {
        averager.add(160f)
        now += 60_000 // boundary: not older than 60 s
        assertEquals(160f, averager.meanLast60s())
    }

    @Test
    fun meanLast60s_wraparound_keepsOnlyLast60Samples() {
        // Fill the buffer past capacity: the 5 oldest samples are evicted.
        repeat(5) { averager.add(100f) }
        repeat(60) { averager.add(200f) }
        assertEquals(200f, averager.meanLast60s())
    }

    @Test
    fun meanLast60s_wraparound_mixesEvictedAndKeptSamples() {
        // 3 old samples evicted once 60 newer ones are added.
        repeat(3) { averager.add(0f) } // also ignored as not-running
        repeat(60) { averager.add(150f) }
        assertEquals(150f, averager.meanLast60s())
    }

    @Test
    fun meanLast60s_smallCapacity_wrapsAtCapacity() {
        val small = CadenceAverager(capacity = 3, clock = { now })
        small.add(100f)
        small.add(200f)
        small.add(300f)
        small.add(400f) // evicts the 100
        assertEquals(300f, small.meanLast60s())
    }

    @Test
    fun meanLast60s_staleSamplesEvictedByWraparound_stayExcluded() {
        // Samples that aged out are excluded even before being overwritten.
        repeat(30) { averager.add(120f) }
        now += 30_000 // first 30 samples are 30 s old, still fresh
        repeat(30) { averager.add(180f) }
        assertEquals(150f, averager.meanLast60s())
        now += 31_000 // the 120 spm samples are now 61 s old
        assertEquals(180f, averager.meanLast60s())
    }
}
