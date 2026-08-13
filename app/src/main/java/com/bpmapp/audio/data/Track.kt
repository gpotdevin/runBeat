// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

/**
 * Source of the track in the library
 */
enum class TrackSource {
    APP,      // Track managed by the app (imported or user-added)
    SYSTEM    // Track from Android system media store
}

/**
 * Type converters for Room database
 */
class TrackTypeConverters {
    @androidx.room.TypeConverter
    fun fromTrackSource(source: TrackSource): String {
        return source.name
    }
    
    @androidx.room.TypeConverter
    fun toTrackSource(source: String): TrackSource {
        return TrackSource.valueOf(source)
    }
}

/**
 * Track entity for the music library
 * Stores information about audio tracks including BPM data
 */
@Entity(tableName = "tracks")
@TypeConverters(TrackTypeConverters::class)
data class Track(
    @PrimaryKey
    val id: String,  // Unique identifier: file path or content URI string
    
    val relativePath: String,  // Relative path (e.g., "GoGo_Penguin/A_Humdrum_Star")
    val fileName: String,     // Filename (e.g., "1-2-Raven-320.mp3")
    val bpm: Float?,          // BPM value, null if unknown
    val fileSizeBytes: Long?, // File size in bytes, null if unknown
    val durationMs: Long?,    // Duration in milliseconds, null if unknown
    val lastUpdated: Long = System.currentTimeMillis(),
    val source: TrackSource = TrackSource.APP,  // APP or SYSTEM
    val metadataTitle: String? = null,  // Title from metadata
    val metadataArtist: String? = null, // Artist from metadata
    val metadataAlbum: String? = null,  // Album from metadata
    val metadataGenre: String? = null,  // Genre from metadata (ID3 tag)
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false     // Whether the track is marked as a favorite
)
