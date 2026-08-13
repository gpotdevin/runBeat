// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a segment of audio to mono 16-bit PCM using a fresh synchronous
 * MediaCodec decoder. Avoids the async callback mode which can crash with
 * "Pending dequeue output buffer request cancelled" on newer Android versions.
 */
object SyncAudioDecoder {
    private const val TAG = "SyncAudioDecoder"
    private const val DEQUEUE_TIMEOUT_US = 10000L
    // Safety cap: each iteration decodes a single codec frame (~20-30ms of audio),
    // so very long files are still bounded. Prevents pathological infinite loops.
    private const val MAX_ITERATIONS = 300000

    /**
     * Decodes PCM samples for the given URI in the [skipDurationMs, skipDurationMs + listenDurationMs]
     * window.
     *
     * @return Pair of mono float samples in [-1, 1] and the decoded sample rate,
     *         or null when decoding fails.
     */
    fun extractPcm(
        context: Context,
        uri: Uri,
        skipDurationMs: Long,
        listenDurationMs: Long
    ): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (t: Throwable) {
            Log.w(TAG, "setDataSource failed for $uri", t)
            extractor.release()
            return null
        }

        val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
            (extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")
        }
        if (audioTrack == null) {
            Log.w(TAG, "No audio track found in $uri")
            extractor.release()
            return null
        }
        extractor.selectTrack(audioTrack)
        val trackFormat = extractor.getTrackFormat(audioTrack)
        val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: run {
            extractor.release()
            return null
        }
        val startMs = skipDurationMs.coerceAtLeast(0)
        val endMs = startMs + listenDurationMs.coerceAtLeast(1000)
        try {
            // Seek close to the analysis window so we don't decode the whole file.
            // SEEK_TO_PREVIOUS_SYNC guarantees the decoder starts on a decodable frame.
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        } catch (t: Throwable) {
            Log.w(TAG, "Seek to $startMs ms failed; decoding from start", t)
        }

        val decoder = try {
            MediaCodec.createDecoderByType(mime)
        } catch (t: Throwable) {
            Log.w(TAG, "createDecoderByType($mime) failed", t)
            extractor.release()
            return null
        }

        val chunks = ArrayList<FloatArray>()
        val allChunks = ArrayList<FloatArray>()
        var sampleRate = formatInt(trackFormat, MediaFormat.KEY_SAMPLE_RATE, 44100)
        var channelCount = formatInt(trackFormat, MediaFormat.KEY_CHANNEL_COUNT, 2)
        // Hard cap on retained samples so very long tracks cannot blow the heap.
        // Each output frame holds at most ~channelCount * 4096 samples, so one
        // analysis window (default 60s) of fallback data is more than enough.
        val windowTargetSamples = ((endMs - startMs) * sampleRate / 1000L).coerceAtLeast(44100L)
        var retainedSamples = 0L

        try {
            // Pass the track format directly so codec-specific data (csd-0/1/2) survives.
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var iterations = 0

            while (!outputEnded && iterations < MAX_ITERATIONS) {
                iterations++
                if (!inputEnded) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val size = extractor.readSampleData(inBuffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sampleRate = formatInt(outFormat, MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = formatInt(outFormat, MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        Log.d(TAG, "Output format: ${sampleRate}Hz, $channelCount channels")
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No decoded output yet; keep feeding input.
                    }
                    outIndex >= 0 -> {
                        val ptsMs = if (info.presentationTimeUs >= 0) info.presentationTimeUs / 1000L else 0L
                        if (sampleRate > 0 && channelCount > 0) {
                            decoder.getOutputBuffer(outIndex)?.let { buffer ->
                                toFloatMono(buffer, info.offset, info.size, channelCount)?.let { mono ->
                                    if (mono.isNotEmpty()) {
                                        // Keep the fallback buffer bounded: stop accumulating
                                        // once we hold one analysis window worth of audio.
                                        if (retainedSamples < windowTargetSamples) {
                                            allChunks.add(mono)
                                            retainedSamples += mono.size
                                        }
                                        if (ptsMs >= startMs && ptsMs < endMs) chunks.add(mono)
                                    }
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEnded = true
                        }
                        // Stop decoding as soon as the analysis window is fully covered.
                        // This prevents very long tracks from being decoded to the end.
                        if (ptsMs >= endMs && (chunks.isNotEmpty() || retainedSamples >= windowTargetSamples)) {
                            outputEnded = true
                        }
                        // PTS can be unreliable (always 0); in that case we never fill
                        // `chunks`, so stop once the bounded fallback buffer is full.
                        if (retainedSamples >= windowTargetSamples && chunks.isEmpty()) {
                            outputEnded = true
                        }
                    }
                }
            }
            if (iterations >= MAX_ITERATIONS) {
                Log.w(TAG, "Decode iteration cap reached for $uri")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Decode failed for $uri", t)
            return null
        } finally {
            try { decoder.stop() } catch (ignored: Throwable) {}
            try { decoder.release() } catch (ignored: Throwable) {}
            try { extractor.release() } catch (ignored: Throwable) {}
        }

        // Prefer the analysis window. If the decoder's timestamps were unreliable
        // the window can be empty even though we decoded audio - in that case fall
        // back to all decoded output instead of dropping the samples entirely.
        val usableChunks = chunks.ifEmpty { allChunks }
        if (usableChunks.isEmpty() || sampleRate <= 0) {
            Log.w(TAG, "Decoded no samples for $uri")
            return null
        }
        val total = usableChunks.sumOf { it.size }
        val merged = FloatArray(total)
        var offset = 0
        for (chunk in usableChunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.size)
            offset += chunk.size
        }
        Log.d(TAG, "Decoded ${merged.size} samples @ ${sampleRate}Hz from $uri")
        return merged to sampleRate
    }

    private fun toFloatMono(buffer: ByteBuffer, offset: Int, size: Int, channelCount: Int): FloatArray? {
        if (size <= 0 || offset < 0 || channelCount <= 0) return null
        val duplicate = buffer.duplicate()
        duplicate.position(offset)
        duplicate.limit(offset + size)
        val shorts = duplicate.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frameCount = shorts.remaining() / channelCount
        val mono = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0
            for (ch in 0 until channelCount) {
                sum += shorts.get().toInt()
            }
            mono[i] = (sum / channelCount) / 32768f
        }
        return mono
    }

    // getInteger(String, int) is only available on API 29+; keep the minSdk-safe variant.
    private fun formatInt(format: MediaFormat, key: String, default: Int): Int =
        if (format.containsKey(key)) format.getInteger(key) else default
}