// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.hipxel.soundtouch.SoundTouch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 AudioProcessor that time-stretches 16-bit PCM with SoundTouch while
 * preserving pitch.
 *
 * Decoded PCM is streamed through the native SoundTouch engine (hipxel/
 * soundtouch-android): frames are pushed with putSamples() and processed frames
 * are pulled with receiveSamples(). The processor is inserted into ExoPlayer's
 * audio sink via [SoundTouchRenderersFactory], so the stretch is applied to live
 * playback.
 *
 * SoundTouchAudioProcessorChain routes playbackParameters.speed to this
 * processor and keeps the built-in Sonic processor at speed 1.0, so the audio
 * is stretched exactly once while ExoPlayer clock and position stay correct.
 *
 * Implemented directly against the AudioProcessor interface (like Media3's own
 * SonicAudioProcessor) because SoundTouch buffers samples internally: pending
 * output must survive queueEndOfStream until drained.
 */
@UnstableApi
class SoundTouchAudioProcessor : AudioProcessor {

    companion object {
        private const val TAG = "SoundTouchProcessor"

        const val MIN_TEMPO = 0.5f
        const val MAX_TEMPO = 2.0f

        // Frames pulled from SoundTouch per receiveSamples call (~90 ms stereo).
        private const val CHUNK_FRAMES = 4096
        private const val BYTES_PER_SAMPLE = 2

        // The hipxel JNI bridge supports mono/stereo 16-bit PCM only.
        private const val MAX_CHANNELS = 2

        // Upper bound for recycled direct buffers (4 x ~16-32 KB).
        private const val MAX_POOLED_BUFFERS = 4
    }

    private var pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET

    // Format of the PCM the native engine is currently configured for.
    private var engineAudioFormat = AudioProcessor.AudioFormat.NOT_SET

    private var soundTouch: SoundTouch? = null
    private var channels = 2
    private var tempo = 1.0f
    private var inputEnded = false

    // Diagnostics: cumulative frame counters plus one-shot/throttle state for
    // queueInput logging. Reset whenever a new engine is created.
    private var framesQueued = 0L
    private var framesOutput = 0L
    private var firstQueueInputLogged = false
    private var lastQueueInputLogMs = 0L

    // Processed output chunks ready for the sink, plus a recycle pool so
    // steady-state playback does not allocate new direct buffers.
    private val outputBuffers = ArrayDeque<ByteBuffer>()
    private val bufferPool = ArrayDeque<ByteBuffer>()

    private var inputScratch = ByteArray(0)
    private var outputScratch = ByteArray(0)

    /**
     * Whether the SoundTouch native library loaded successfully. When false this
     * processor reports itself inactive and playback falls back to ExoPlayer's
     * built-in time-stretching.
     */
    val engineAvailable: Boolean by lazy {
        try {
            SoundTouch().release()
            Log.d(TAG, "SoundTouch native library available")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "SoundTouch native library not available", t)
            false
        }
    }

    /**
     * Target tempo (1.0 = original speed, >1.0 = faster). Applied live while
     * playing; picked up on the next track when idle.
     */
    fun setTempo(newTempo: Float) {
        tempo = newTempo.coerceIn(MIN_TEMPO, MAX_TEMPO)
        soundTouch?.setTempo(tempo.toDouble())
        Log.d(TAG, "Tempo set to $tempo")
    }

    /** Currently requested tempo. */
    fun getTempo(): Float = tempo

    override fun configure(inputFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pendingInputAudioFormat = inputFormat
        pendingOutputAudioFormat =
            if (engineAvailable &&
                inputFormat.encoding == C.ENCODING_PCM_16BIT &&
                inputFormat.channelCount in 1..MAX_CHANNELS
            ) {
                AudioProcessor.AudioFormat(
                    inputFormat.sampleRate,
                    inputFormat.channelCount,
                    C.ENCODING_PCM_16BIT
                )
            } else {
                Log.w(TAG, "Unsupported audio format for SoundTouch: $inputFormat, bypassing")
                AudioProcessor.AudioFormat.NOT_SET
            }
        Log.d(
            TAG,
            "configure: input=$inputFormat, active=${pendingOutputAudioFormat != AudioProcessor.AudioFormat.NOT_SET}"
        )
        return pendingOutputAudioFormat
    }

    override fun isActive(): Boolean =
        pendingOutputAudioFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputEnded) return
        val st = ensureEngine() ?: return

        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (inputScratch.size < remaining) {
            inputScratch = ByteArray(remaining)
        }
        inputBuffer.get(inputScratch, 0, remaining)

        // The hipxel JNI counts samples per channel; each frame is
        // channels * 16-bit values, so frames = bytes / (2 * channels).
        val frames = remaining / (BYTES_PER_SAMPLE * channels)
        st.putSamples(inputScratch, 0, frames)
        framesQueued += frames.toLong()
        if (!firstQueueInputLogged) {
            firstQueueInputLogged = true
            Log.d(
                TAG,
                "First buffer queued: format=$engineAudioFormat, channels=$channels, frames=$frames, tempo=$tempo"
            )
        }
        drain(st)
        val now = SystemClock.elapsedRealtime()
        if (now - lastQueueInputLogMs >= 5000L) {
            lastQueueInputLogMs = now
            Log.d(
                TAG,
                "queueInput status: framesQueued=$framesQueued, framesOutput=$framesOutput, " +
                    "stNumSamples=${st.numSamples()}, stUnprocessed=${st.numUnprocessedSamples()}"
            )
        }
    }

    override fun queueEndOfStream() {
        // Media3 calls queueEndOfStream repeatedly (the renderer polls
        // handleEndOfStream until the sink reports ended), so this must be
        // idempotent: a second call must not produce more output.
        if (inputEnded) return
        Log.d(TAG, "queueEndOfStream: framesQueued=$framesQueued, framesOutput=$framesOutput")
        soundTouch?.let { st ->
            // Deliberately do NOT call SoundTouch::flush() here: this vendored
            // implementation feeds blank samples into the pipeline (up to
            // 200 x 128) and pads the output buffer, i.e. it MANUFACTURES
            // silence (~0.5s per call). With Media3 polling until ended, that
            // becomes an endless silence generator that plays instead of the
            // next track. flush() is for offline file processing only. The few
            // hundred ms of samples still inside the pipeline are dropped; the
            // processor's flush() clears them when the next stream starts.
            drain(st)
        }
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        while (outputBuffers.firstOrNull()?.hasRemaining() == false) {
            recycle(outputBuffers.removeFirst())
        }
        return outputBuffers.firstOrNull() ?: AudioProcessor.EMPTY_BUFFER
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffers.isEmpty()

    override fun flush() {
        Log.d(TAG, "flush: framesQueued=$framesQueued, framesOutput=$framesOutput, inputEnded=$inputEnded")
        // Seek / (re)start of playback: discard any partial output.
        inputEnded = false
        while (outputBuffers.isNotEmpty()) {
            recycle(outputBuffers.removeFirst())
        }
        soundTouch?.clear()
    }

    override fun reset() {
        Log.d(TAG, "reset: framesQueued=$framesQueued, framesOutput=$framesOutput")
        releaseEngine()
        inputEnded = false
        pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        while (outputBuffers.isNotEmpty()) {
            recycle(outputBuffers.removeFirst())
        }
        bufferPool.clear()
    }

    /**
     * Returns the native engine for the pending input format, creating or
     * re-creating it when the format changed. Called lazily from queueInput so
     * the engine always matches the audio that actually flows.
     */
    private fun ensureEngine(): SoundTouch? {
        val format = pendingInputAudioFormat
        if (format == AudioProcessor.AudioFormat.NOT_SET) return null
        val existing = soundTouch
        if (existing != null && format == engineAudioFormat) return existing
        if (existing != null) {
            Log.d(TAG, "Audio format changed, recreating engine: previous=$engineAudioFormat, new=$format")
        }
        releaseEngine()
        return try {
            val st = SoundTouch()
            st.setSampleRate(format.sampleRate)
            st.setChannels(format.channelCount)
            st.setTempo(tempo.toDouble())
            st.setPitch(1.0)
            soundTouch = st
            channels = format.channelCount
            engineAudioFormat = format
            framesQueued = 0
            framesOutput = 0
            firstQueueInputLogged = false
            lastQueueInputLogMs = 0
            Log.d(TAG, "SoundTouch engine created: $format, tempo=$tempo")
            st
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create SoundTouch engine for $format", t)
            soundTouch = null
            engineAudioFormat = AudioProcessor.AudioFormat.NOT_SET
            null
        }
    }

    private fun releaseEngine() {
        soundTouch?.release()
        soundTouch = null
        engineAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    }

    /** Pulls all frames SoundTouch has ready into the output queue. */
    private fun drain(st: SoundTouch) {
        val bytesPerFrame = BYTES_PER_SAMPLE * channels
        val chunkBytes = CHUNK_FRAMES * bytesPerFrame
        if (outputScratch.size < chunkBytes) {
            outputScratch = ByteArray(chunkBytes)
        }
        while (true) {
            val received = st.receiveSamples(outputScratch, 0, CHUNK_FRAMES)
            if (received <= 0) break
            val length = received * bytesPerFrame
            val buffer = allocateOutputBuffer(length)
            buffer.put(outputScratch, 0, length)
            buffer.flip()
            outputBuffers.addLast(buffer)
            framesOutput += received.toLong()
            if (received < CHUNK_FRAMES) break
        }
    }

    private fun allocateOutputBuffer(size: Int): ByteBuffer {
        val pooled = bufferPool.firstOrNull()
        if (pooled != null && pooled.capacity() >= size) {
            bufferPool.removeFirst()
            pooled.clear()
            pooled.limit(size)
            return pooled
        }
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
    }

    private fun recycle(buffer: ByteBuffer) {
        if (bufferPool.size < MAX_POOLED_BUFFERS) {
            bufferPool.addLast(buffer)
        }
    }
}
