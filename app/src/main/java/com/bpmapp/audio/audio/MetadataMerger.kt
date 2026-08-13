// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.util.Log
import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackSource

/**
 * Metadata merging strategy used by both CSV import and system library scan.
 *
 * When multiple sources provide metadata for the same field, the value is
 * resolved using the following source priority (highest first):
 *  1. ID3 tags read from the actual audio file
 *  2. System library (MediaStore) data
 *  3. CSV data
 *  4. Existing data already stored in the app database
 *
 * Values that are blank or counted as not-set ("", "Unknown", "unknown", null)
 * are skipped so that a higher priority source never falls back to junk.
 */
object MetadataMerger {

    private const val TAG = "MetadataMerger"

    // Source priority order (used to resolve conflicts)
    enum class Priority(val rank: Int) {
        ID3_TAG(0), SYSTEM(1), CSV(2), EXISTING(3)
    }

    /**
     * Merge a set of metadata "overrides" from all available sources into the existing track.
     *
     * @param existing The current track in the DB (may be null).
     * @param id3Data Non-null values from the audio file's ID3 tags (keys: title, artist, album, genre, bpm).
     * @param systemData Non-null values revealed by the system library (keys: title, artist, album, genre).
     * @param csvData Non-null values from a CSV row (keys: title, artist, album, genre, bpm).
     * @return A map containing the merged values with keys: title, artist, album, genre, bpm, bpmSource.
     */
    fun mergeMetadata(
        existing: Track?,
        id3Data: Map<String, String?>,
        systemData: Map<String, String?>,
        csvData: Map<String, String?>
    ): Map<String, Any?> {
        val title = mergeStringField("title", existing, id3Data, systemData, csvData)
        val artist = mergeStringField("artist", existing, id3Data, systemData, csvData)
        val album = mergeStringField("album", existing, id3Data, systemData, csvData)
        val genre = mergeStringField("genre", existing, id3Data, systemData, csvData)
        val (bpm, bpmSource) = mergeBpm(existing, id3Data, systemData, csvData)

        val result = linkedMapOf<String, Any?>(
            "title" to title,
            "artist" to artist,
            "album" to album,
            "genre" to genre,
            "bpm" to bpm,
            "bpmSource" to bpmSource
        )

        Log.i(
            TAG,
            "Merged metadata: title=$title, artist=$artist, album=$album, genre=$genre, bpm=$bpm (source=$bpmSource)"
        )
        return result
    }

    /**
     * Build an actual [Track] instance from the merged metadata overrides.
     *
     * @param existing The current track in the DB (may be null). When present, its
     *   [Track.isFavorite] and [Track.lastUpdated] are preserved.
     * @param id3Data Non-null values from the audio file's ID3 tags (keys: title, artist, album, genre, bpm).
     * @param systemData Non-null values revealed by the system library (keys: title, artist, album, genre).
     * @param csvData Non-null values from a CSV row (keys: title, artist, album, genre, bpm).
     * @param newId The id to use for a new track (ignored when an existing track is present).
     * @param relativePath The relative path to use for a new track.
     * @param fileName The file name to use for a new track.
     * @param bpmFallback BPM to apply when no source provides a valid BPM.
     * @param fileSizeBytes File size for a new track (falls back to [existing] when null).
     * @param durationMs Duration for a new track (falls back to [existing] when null).
     * @param source The [TrackSource] to assign to the track.
     * @return A new [Track] with the merged metadata applied.
     */
    fun mergeIntoTrack(
        existing: Track?,
        id3Data: Map<String, String?>,
        systemData: Map<String, String?>,
        csvData: Map<String, String?>,
        newId: String,
        relativePath: String,
        fileName: String,
        bpmFallback: Float?,
        fileSizeBytes: Long?,
        durationMs: Long?,
        source: TrackSource
    ): Track {
        val merged = mergeMetadata(existing, id3Data, systemData, csvData)

        val mergedBpm = merged["bpm"] as? Float
        val bpm = if (mergedBpm != null) mergedBpm else bpmFallback

        Log.i(TAG, "Building '${existing?.fileName ?: fileName}' with bpm=$bpm (source=${merged["bpmSource"]})")

        return Track(
            id = existing?.id ?: newId,
            relativePath = existing?.relativePath ?: relativePath,
            fileName = existing?.fileName ?: fileName,
            bpm = bpm,
            fileSizeBytes = fileSizeBytes ?: existing?.fileSizeBytes,
            durationMs = durationMs ?: existing?.durationMs,
            lastUpdated = System.currentTimeMillis(),
            source = existing?.source ?: source,
            metadataTitle = merged["title"] as? String,
            metadataArtist = merged["artist"] as? String,
            metadataAlbum = merged["album"] as? String,
            metadataGenre = merged["genre"] as? String,
            isFavorite = existing?.isFavorite ?: false
        )
    }

    /**
     * Merge a single string field using the source priority order.
     *
     * @param field The field name (title, artist, album or genre).
     * @return The merged value, or null when no source has a non-blank value.
     */
    private fun mergeStringField(
        field: String,
        existing: Track?,
        id3Data: Map<String, String?>,
        systemData: Map<String, String?>,
        csvData: Map<String, String?>
    ): String? {
        val existingValue = when (field) {
            "title" -> existing?.metadataTitle
            "artist" -> existing?.metadataArtist
            "album" -> existing?.metadataAlbum
            "genre" -> existing?.metadataGenre
            else -> null
        }

        val candidates = listOf(
            id3Data[field],
            systemData[field],
            csvData[field],
            existingValue
        )

        for (candidate in candidates) {
            val value = candidate?.trim()
            if (!isNotSet(value)) {
                return value
            }
        }
        return null
    }

    /**
     * Merge the BPM field using the source priority order.
     *
     * @return A pair of the merged BPM (null when no source provides a valid one)
     *         and the name of the winning source ("ID3", "SYSTEM", "CSV", "EXISTING")
     *         or null when no source provides a BPM.
     */
    private fun mergeBpm(
        existing: Track?,
        id3Data: Map<String, String?>,
        systemData: Map<String, String?>,
        csvData: Map<String, String?>
    ): Pair<Float?, String?> {
        val id3Bpm = parseBpm(id3Data["bpm"])
        if (id3Bpm != null) return id3Bpm to "ID3"

        val systemBpm = parseBpm(systemData["bpm"])
        if (systemBpm != null) return systemBpm to "SYSTEM"

        val csvBpm = parseBpm(csvData["bpm"])
        if (csvBpm != null) return csvBpm to "CSV"

        val existingBpm = existing?.bpm
        if (existingBpm != null && existingBpm > 0) return existingBpm to "EXISTING"

        return null to null
    }

    /**
     * Parse a BPM value from a string. Null, blank, unparsable and non-positive
     * values are treated as not-set.
     */
    private fun parseBpm(raw: String?): Float? {
        if (raw == null) return null
        val parsed = raw.trim().toFloatOrNull()
        return if (parsed != null && parsed > 0) parsed else null
    }

    /**
     * True when the value is null, blank, or the generic "unknown" label.
     */
    private fun isNotSet(value: String?): Boolean {
        if (value == null) return true
        val trimmed = value.trim()
        return trimmed.isEmpty() || trimmed.equals("unknown", ignoreCase = true)
    }
}