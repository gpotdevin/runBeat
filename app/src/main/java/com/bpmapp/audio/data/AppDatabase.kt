// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room Database for RunBeat
 * Stores tracks, playlists, BPM data, and user preferences
 */
@Database(
    entities = [
        Track::class,
        Playlist::class,
        PlaylistTrack::class
        // UserPreferences::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(TrackTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    // abstract fun preferencesDao(): PreferencesDao
    
    companion object {
        const val DATABASE_NAME = "bpm_app_database"
    }
}
