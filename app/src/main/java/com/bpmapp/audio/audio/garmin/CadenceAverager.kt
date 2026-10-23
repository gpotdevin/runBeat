// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

/**
 * 60-sample ring buffer of cadence samples (steps per minute) with timestamps.
 *
 * Mirrors the watch-side CadenceRingBuffer (garmin-watch/source/CadenceRingBuffer.mc):
 * one sample per second, so 60 slots cover a rolling 60-second window.
 *
 * A sample value of 0 means "not running" and is ignored by [meanLast60s].
 *
 * Fallback path if raw cadence samples are ever streamed to the phone; the
 * primary path computes the 60-second average on the watch itself.
 *
 * @param capacity number of ring-buffer slots (60 = 60 s at 1 Hz)
 * @param windowMillis age threshold: samples older than this are excluded
 * @param clock millisecond time source, injectable for tests
 */
class CadenceAverager(
    private val capacity: Int = CAPACITY,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val timestamps = LongArray(capacity)
    private val values = FloatArray(capacity)
    private var size = 0
    private var nextIndex = 0

    /** Adds one cadence sample (spm), timestamped with [clock]. */
    fun add(sample: Float) {
        timestamps[nextIndex] = clock()
        values[nextIndex] = sample
        nextIndex = (nextIndex + 1) % capacity
        if (size < capacity) size++
    }

    /**
     * Mean of the samples not older than [windowMillis], ignoring 0
     * ("not running") samples. Returns null when there is no usable sample
     * (buffer empty, or every recent sample is 0).
     */
    fun meanLast60s(): Float? {
        val now = clock()
        var sum = 0f
        var count = 0
        for (i in 0 until size) {
            if (now - timestamps[i] > windowMillis) continue // stale
            val value = values[i]
            if (value <= 0f) continue // not running
            sum += value
            count++
        }
        return if (count == 0) null else sum / count
    }

    companion object {
        const val CAPACITY = 60
        const val WINDOW_MILLIS = 60_000L
    }
}
