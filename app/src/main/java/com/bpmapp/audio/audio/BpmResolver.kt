// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import com.bpmapp.audio.data.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the BPM for a track with the following priority:
 *  1. BPM already known in the library database
 *  2. BPM embedded in the file's ID3 tags (when accessible)
 *  3. Automatic tempo detection via Aubio
 *
 * Any BPM resolved from ID3 tags or detection is persisted to the database.
 */
@Singleton
class BpmResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackRepository: TrackRepository,
    private val metadataEditor: MetadataEditor
) {

    companion object {
        private const val TAG = "BpmResolver"
    }

    /**
     * Resolve the BPM for a single track, saving any newly found value to the database.
     *
     * @param track The track to resolve BPM for
     * @param forceFileBpm When true, re-read ID3 tags/detection even if the database
     *        already has a BPM (used to refresh stale values)
     * @return The resolved BPM, or null if it could not be determined
     */
    suspend fun resolveBpmForTrack(track: Track, forceFileBpm: Boolean = false): Float? {
        val knownBpm = track.bpm?.takeIf { it > 0 }
        if (!forceFileBpm && knownBpm != null) {
            return knownBpm
        }

        val id3Bpm = readBpmFromId3(track)
        if (id3Bpm != null) {
            persistBpm(track.id, id3Bpm)
            return id3Bpm
        }

        val detectedBpm = detectBpmAutomatically(track)
        if (detectedBpm != null) {
            persistBpm(track.id, detectedBpm)
            return detectedBpm
        }

        return knownBpm
    }

    /**
     * Resolve BPM for multiple tracks, saving any newly found values to the database.
     *
     * @param tracks Tracks to resolve
     * @param onTrackCompleted Optional callback after each track with (index, total, bpm)
     * @return Map of track id -> resolved BPM (null when unresolved)
     */
    suspend fun resolveBpmForTracks(
        tracks: List<Track>,
        onTrackCompleted: ((completed: Int, total: Int, bpm: Float?) -> Unit)? = null
    ): Map<String, Float?> {
        val results = mutableMapOf<String, Float?>()
        tracks.forEachIndexed { index, track ->
            val bpm = try {
                resolveBpmForTrack(track)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve BPM for track ${track.id}", e)
                null
            }
            results[track.id] = bpm
            onTrackCompleted?.invoke(index + 1, tracks.size, bpm)
        }
        return results
    }

    private suspend fun readBpmFromId3(track: Track): Float? {
        return try {
            val uri = Uri.parse(track.id)
            val bpm = metadataEditor.readBpmFromFile(uri)
            when {
                bpm != null && bpm > 0 -> {
                    Log.d(TAG, "Read BPM $bpm from ID3 tags for ${track.fileName}")
                    bpm
                }
                else -> {
                    Log.d(TAG, "No BPM in ID3 tags for ${track.fileName}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read ID3 BPM for ${track.fileName}", e)
            null
        }
    }

    private suspend fun detectBpmAutomatically(track: Track): Float? {
        return try {
            val path = resolveLocalAudioPath(track) ?: return null
            val detector = AubioBpmDetector()
            detector.initializeWithContext(context)
            val bpm = detector.detectBpmFromFileSegment(
                context,
                path,
                AudioSampleExtractor.DEFAULT_SKIP_MS,
                AudioSampleExtractor.DEFAULT_DURATION_MS
            )
            if (bpm > 0) {
                Log.d(TAG, "Detected BPM $bpm for ${track.fileName}")
            } else {
                Log.w(TAG, "Automatic BPM detection returned 0 for ${track.fileName}")
            }
            bpm.takeIf { it > 0 }
        } catch (e: Throwable) {
            Log.w(TAG, "Automatic BPM detection failed for ${track.fileName}", e)
            null
        }
    }

    /**
     * Resolves the track to a real, locally readable file path so both MediaCodec
     * extraction and the native Aubio fallback get a path they can actually open.
     */
    private fun resolveLocalAudioPath(track: Track): String? {
        if (track.id.startsWith("file://")) {
            return track.id.removePrefix("file://").takeIf { File(it).exists() }
        }
        val uri = Uri.parse(track.id)
        if (uri.scheme == "content") {
            return UriUtils.getFilePath(context, uri)?.takeIf { File(it).exists() }
                ?: UriUtils.copyUriToTempFile(context, uri, getExtension(track))
        }
        return track.id
    }

    private fun getExtension(track: Track): String {
        val name = track.fileName.orEmpty()
        val dot = name.lastIndexOf('.')
        return if (dot in 0 until name.length - 1) name.substring(dot + 1).take(5).uppercase()
        else "ogg"
    }

    private suspend fun persistBpm(trackId: String, bpm: Float) {
        try {
            trackRepository.updateBpm(trackId, bpm)
            Log.i(TAG, "Saved BPM $bpm to database for track $trackId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist BPM for track $trackId", e)
        }
    }
}