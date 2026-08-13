// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.annotation.TargetApi
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Extracts PCM audio samples from audio files for BPM detection.
 * 
 * Uses Android's built-in MediaCodec API to decode audio to PCM format.
 * This allows BPM detection libraries (like Aubio) that require PCM format
 * to work with any audio format that MediaCodec supports (MP3, AAC, FLAC, etc.).
 * 
 * Key features:
 * - Skips intro portion (default: 60 seconds) to avoid intros that may not have steady beat
 * - Extracts a fixed duration segment (default: 60 seconds) for efficient analysis
 * - Handles edge cases: short songs, unsupported formats
 * - Returns mono PCM samples at 44100Hz, normalized to [-1.0, 1.0]
 * - No external dependencies (uses Android's built-in MediaCodec)
 */
@TargetApi(29)
class AudioSampleExtractor(private val context: Context) {
    
    /**
     * Decoded mono PCM audio plus the actual sample rate of the decoded stream.
     * The sample rate can differ from the file's declared rate depending on the
     * device codec, so it must be propagated to the tempo detector.
     */
    data class DecodedAudio(
        val samples: FloatArray,
        val sampleRate: Int
    )
    
    companion object {
        private const val TAG = "AudioSampleExtractor"
        
        // Default parameters for BPM analysis segment
        const val DEFAULT_SKIP_MS = 60000L    // Skip first 60 seconds
        const val DEFAULT_DURATION_MS = 60000L // Extract 60 seconds
        const val TARGET_SAMPLE_RATE = 44100   // Standard audio sample rate
        const val TARGET_CHANNELS = 1         // Mono for BPM detection
        
        // Minimum segment duration for reliable BPM detection (in milliseconds)
        const val MIN_SEGMENT_MS = 10000L     // At least 10 seconds needed
        
        // Timeout constants
        private const val DEQUEUE_TIMEOUT_US = 10000L // 10ms
        // Generous safety cap; the loop normally exits when the target segment
        // is fully collected (each iteration decodes a single codec frame, so
        // 1000 was too low and truncated the segment to ~25s on some codecs).
        private const val MAX_ITERATIONS = 200000
    }
    
    /**
     * Extracts a segment of audio as PCM samples from an audio file.
     * 
     * @param uri The URI of the audio file (file://, content://, etc.)
     * @param skipMs Number of milliseconds to skip from the beginning (default: 60000)
     * @param durationMs Number of milliseconds of audio to extract (default: 60000)
     * @return Decoded mono PCM samples plus the actual decoded sample rate, or null if extraction failed
     */
    suspend fun extractSamples(
        uri: Uri,
        skipMs: Long = DEFAULT_SKIP_MS,
        durationMs: Long = DEFAULT_DURATION_MS
    ): DecodedAudio? {
        // Get file path from URI
        val filePath = getLocalFilePath(uri) ?: run {
            Log.e(TAG, "Failed to get file path from URI: $uri")
            return null
        }
        
        // Validate file exists
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            Log.e(TAG, "File not accessible: $filePath")
            return null
        }
        
        // Get file duration using ExoPlayer
        val fileDurationMs = getFileDuration(uri)
        if (fileDurationMs <= 0) {
            Log.e(TAG, "Failed to get file duration")
            return null
        }
        
        Log.d(TAG, "File: $filePath, duration: ${fileDurationMs}ms")
        
        // Adjust parameters based on actual file duration
        val (adjustedSkipMs, adjustedDurationMs) = adjustParameters(
            fileDurationMs, skipMs, durationMs
        )
        
        if (adjustedDurationMs < MIN_SEGMENT_MS) {
            Log.w(TAG, "File too short for reliable BPM detection: ${adjustedDurationMs}ms < ${MIN_SEGMENT_MS}ms")
            return null
        }
        
        Log.d(TAG, "Extracting segment: skip=${adjustedSkipMs}ms, duration=${adjustedDurationMs}ms")

        // Prefer the synchronous decoder: a fresh codec with no async callbacks,
        // which avoids the "Pending dequeue output buffer request cancelled" crash
        // observed with the async path on newer Android versions.
        val syncAudio = SyncAudioDecoder.extractPcm(
            context, uri, adjustedSkipMs, adjustedDurationMs
        )
        if (syncAudio != null) {
            val (samples, rate) = syncAudio
            return DecodedAudio(samples, rate)
        }
        Log.w(TAG, "Sync decoder returned no audio; trying async MediaCodec path")

        // Use MediaCodec to decode the segment
        return extractSamplesWithMediaCodec(filePath, adjustedSkipMs, adjustedDurationMs)
    }
    
    /**
     * Extracts samples from a file path or URI string (convenience method).
     */
    suspend fun extractSamples(
        filePath: String,
        skipMs: Long = DEFAULT_SKIP_MS,
        durationMs: Long = DEFAULT_DURATION_MS
    ): DecodedAudio? {
        val uri = if (filePath.startsWith("file://") || filePath.startsWith("content://")) {
            Uri.parse(filePath)
        } else {
            Uri.fromFile(File(filePath))
        }
        return extractSamples(uri, skipMs, durationMs)
    }
    
    /**
     * Get local file path from URI.
     * Tries actual path first, then falls back to copying to temp.
     */
    private fun getLocalFilePath(uri: Uri): String? {
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> {
                // Try to get actual file path from MediaStore
                UriUtils.getFilePath(context, uri)?.let { path ->
                    if (File(path).exists() && File(path).canRead()) {
                        return path
                    }
                }
                
                // Fall back to copying to temp file
                UriUtils.copyUriToTempFile(context, uri, "tmp")
            }
            else -> uri.path
        }
    }
    
    /**
     * Get the duration of an audio file in milliseconds.
     * Uses MediaMetadataRetriever which can handle content:// URIs better than ExoPlayer.
     */
    private fun getFileDuration(uri: Uri): Long {
        return try {
            Log.d(TAG, "Getting file duration for URI: $uri")
            val retriever = android.media.MediaMetadataRetriever()
            
            // For content:// URIs on Android 11+, we need to set the data source with a file descriptor
            if (uri.scheme == "content") {
                val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    retriever.setDataSource(pfd.fileDescriptor)
                    pfd.close()
                } else {
                    // Fall back to URI string
                    retriever.setDataSource(uri.toString())
                }
            } else {
                retriever.setDataSource(uri.toString())
            }
            
            val durationStr = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )
            val duration = durationStr?.toLongOrNull() ?: -1L
            Log.d(TAG, "MediaMetadataRetriever duration for $uri: $duration")
            retriever.release()
            
            if (duration > 0) duration else -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file duration for $uri", e)
            -1L
        }
    }
    
    /**
     * Adjust skip and duration parameters based on actual file duration.
     * 
     * Logic:
     * - If file < skipMs: skip = 0, duration = fileDuration
     * - If file < skipMs + durationMs: skip = skipMs, duration = fileDuration - skipMs
     * - Otherwise: use requested parameters
     */
    private fun adjustParameters(
        fileDurationMs: Long,
        skipMs: Long,
        durationMs: Long
    ): Pair<Long, Long> {
        return when {
            // File is shorter than skip time - analyze entire file
            fileDurationMs <= skipMs -> {
                Log.d(TAG, "File shorter than skip time, analyzing entire file")
                0L to fileDurationMs
            }
            
            // File is longer than skip but shorter than skip + duration
            fileDurationMs < skipMs + durationMs -> {
                val remainingDuration = fileDurationMs - skipMs
                Log.d(TAG, "File shorter than requested segment, analyzing remaining ${remainingDuration}ms")
                skipMs to remainingDuration
            }
            
            // File is long enough for full segment
            else -> {
                skipMs to durationMs
            }
        }
    }
    
    /**
     * Extracts PCM samples using Android's MediaCodec API.
     * 
     * This method:
     * 1. Uses MediaExtractor to read the audio file
     * 2. Finds the audio track
     * 3. Configures MediaCodec to decode to PCM
     * 4. Feeds data to decoder, skipping to the desired start position
     * 5. Collects decoded PCM samples
     * 6. Converts to mono FloatArray at target sample rate
     */
    private fun extractSamplesWithMediaCodec(
        filePath: String,
        skipMs: Long,
        durationMs: Long
    ): DecodedAudio? {
        var mediaExtractor: MediaExtractor? = null
        var mediaCodec: MediaCodec? = null
        
        return try {
            mediaExtractor = MediaExtractor()
            mediaExtractor.setDataSource(filePath)
            
            // Find audio track
            val audioTrackIndex = findAudioTrack(mediaExtractor)
            if (audioTrackIndex == -1) {
                Log.e(TAG, "No audio track found in file: $filePath")
                return null
            }
            
            mediaExtractor.selectTrack(audioTrackIndex)
            
            val trackFormat = mediaExtractor.getTrackFormat(audioTrackIndex)
            val mimeType = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: run {
                    Log.e(TAG, "No MIME type found")
                    return null
                }
            val fileSampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
            val channelCount = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, TARGET_CHANNELS)
            
            Log.d(TAG, "Audio track: mime=$mimeType, sampleRate=$fileSampleRate, channels=$channelCount")
            
            // The decoder may output at a different sample rate/channel count than
            // the file's declared values; capture them via INFO_OUTPUT_FORMAT_CHANGED.
            var outputSampleRate = fileSampleRate
            var outputChannelCount = channelCount
            
            // Size the buffer for the file's (expected) sample rate. The actual
            // decoded rate is tracked separately and returned to the caller.
            val numSamplesExpected = ((durationMs * fileSampleRate) / 1000.0).toInt()
            var samples: FloatArray = FloatArray(numSamplesExpected)
            
            // Configure MediaCodec for decoding. Pass the track format itself so
            // codec-specific data (csd-0/csd-1/csd-2) is preserved; a freshly built
            // format drops CSD and breaks Vorbis/FLAC decoding.
            mediaCodec = MediaCodec.createDecoderByType(mimeType)
            mediaCodec.configure(trackFormat, null, null, 0)
            mediaCodec.start()
            
            val startTimeUs = skipMs * 1000
            val endTimeUs = startTimeUs + (durationMs * 1000)
            
            // Seek to start position
            mediaExtractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            
            val bufferInfo = MediaCodec.BufferInfo()
            var samplesCollected = 0
            var inputDone = false
            var outputDone = false
            var iterationCount = 0
            
            while (!outputDone && iterationCount < MAX_ITERATIONS) {
                iterationCount++
                
                // Feed input to decoder
                if (!inputDone) {
                    val inputBufferIndex = mediaCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = mediaExtractor.readSampleData(inputBuffer, 0)
                        
                        if (sampleSize > 0) {
                            val sampleTimeUs = mediaExtractor.sampleTime
                            
                            // Check if we've passed the end of our target segment
                            if (sampleTimeUs >= endTimeUs) {
                                inputDone = true
                                mediaCodec.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                            } else {
                                // Only queue samples that are at or after our start time
                                // (samples before start time were already skipped by seekTo)
                                mediaCodec.queueInputBuffer(
                                    inputBufferIndex, 0, sampleSize, sampleTimeUs, 0
                                )
                            }
                            mediaExtractor.advance()
                        } else {
                            // End of input stream
                            inputDone = true
                            mediaCodec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        }
                    }
                }
                
                // Get output from decoder
                val outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                
                when {
                    outputBufferIndex >= 0 -> {
                        processOutputBuffer(
                            mediaCodec, outputBufferIndex, bufferInfo,
                            samples, samplesCollected, outputChannelCount, startTimeUs, endTimeUs
                        ).let { newSamplesCollected ->
                            samplesCollected = newSamplesCollected
                            if (samplesCollected >= samples.size) {
                                samples = samples.copyOf(samples.size * 2)
                            }
                        }
                        
                        mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                        
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        Log.d(TAG, "Output buffers changed")
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = mediaCodec.outputFormat
                        outputSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, fileSampleRate)
                        outputChannelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        Log.d(TAG, "Output format changed: " +
                            "sampleRate=$outputSampleRate, " +
                            "channels=$outputChannelCount")
                    }
                }
            }
            
            if (samplesCollected > 0) {
                Log.d(TAG, "Successfully extracted $samplesCollected samples at ${outputSampleRate}Hz")
                DecodedAudio(samples.copyOfRange(0, samplesCollected), outputSampleRate)
            } else {
                Log.e(TAG, "No samples collected from MediaCodec")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during MediaCodec extraction", e)
            null
        } finally {
            try { mediaCodec?.stop() } catch (ignored: Exception) {}
            try { mediaCodec?.release() } catch (ignored: Exception) {}
            try { mediaExtractor?.release() } catch (ignored: Exception) {}
        }
    }
    
    /**
     * Finds the audio track in a MediaExtractor.
     */
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }
    
    /**
     * Processes an output buffer from MediaCodec and extracts samples.
     */
    private fun processOutputBuffer(
        mediaCodec: MediaCodec,
        outputBufferIndex: Int,
        bufferInfo: MediaCodec.BufferInfo,
        samples: FloatArray,
        samplesCollected: Int,
        channelCount: Int,
        startTimeUs: Long,
        endTimeUs: Long
    ): Int {
        var collected = samplesCollected
        
        // Only process samples within our target time range
        val presentationTimeUs = bufferInfo.presentationTimeUs
        if (presentationTimeUs < startTimeUs || presentationTimeUs >= endTimeUs) {
            return collected
        }
        
        val outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex) ?: return collected
        outputBuffer.clear()
        
        // Each sample is 2 bytes (16-bit PCM)
        val bytesPerSample = 2
        val bytesPerFrame = bytesPerSample * channelCount
        val frameSize = bufferInfo.size / bytesPerFrame
        
        for (i in 0 until frameSize) {
            if (collected >= samples.size) break
            
            var sampleValue = 0
            for (ch in 0 until channelCount) {
                val byteOffset = i * bytesPerFrame + ch * bytesPerSample
                if (byteOffset + 1 >= bufferInfo.size) break
                
                val byte1 = outputBuffer.get(byteOffset).toInt() and 0xFF            // low byte
                val byte2 = outputBuffer.get(byteOffset + 1).toInt() and 0xFF        // high byte
                val rawSample = (byte2 shl 8) or byte1
                sampleValue += if (byte2 < 128) rawSample else rawSample - 65536
            }
            
            // Average channels to mono and normalize to [-1.0, 1.0]
            samples[collected] = (sampleValue / channelCount).toFloat() / 32768.0f
            collected++
        }
        
        return collected
    }
}
