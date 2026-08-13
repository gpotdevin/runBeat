// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Track entities
 * Provides database operations for the music library
 */
@Dao
interface TrackDao {
    
    /**
     * Get all tracks in the library
     */
    @Query("SELECT * FROM tracks ORDER BY fileName COLLATE NOCASE")
    fun getAllTracks(): Flow<List<Track>>
    
    /**
     * Get a specific track by its ID
     */
    @Query("SELECT * FROM tracks WHERE id = :trackId")
    fun getTrackById(trackId: String): Flow<Track?>
    
    /**
     * Get all tracks with known BPM (not null)
     */
    @Query("SELECT * FROM tracks WHERE bpm IS NOT NULL ORDER BY fileName COLLATE NOCASE")
    fun getTracksWithKnownBpm(): Flow<List<Track>>
    
    /**
     * Get all favorite tracks
     */
    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY fileName COLLATE NOCASE")
    fun getFavoriteTracks(): Flow<List<Track>>
    
    /**
     * Update the favorite flag for a track
     */
    @Query("UPDATE tracks SET isFavorite = :isFavorite WHERE id = :trackId")
    suspend fun updateFavorite(trackId: String, isFavorite: Boolean): Int

    /**
     * Update the favorite flag for multiple tracks at once
     */
    @Query("UPDATE tracks SET isFavorite = :isFavorite WHERE id IN (:trackIds)")
    suspend fun updateFavorites(trackIds: List<String>, isFavorite: Boolean): Int
    
    /**
     * Insert a track, or replace if it already exists
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(track: Track): Long
    
    /**
     * Update an existing track
     */
    @Update
    suspend fun update(track: Track): Int
    
    /**
     * Update BPM for a specific track
     */
    @Query("UPDATE tracks SET bpm = :bpm, lastUpdated = :timestamp WHERE id = :trackId")
    suspend fun updateBpm(trackId: String, bpm: Float?, timestamp: Long = System.currentTimeMillis()): Int
    
    /**
     * Delete a track by its ID
     */
    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun delete(trackId: String): Int
    
    /**
     * Delete all tracks
     */
    @Query("DELETE FROM tracks")
    suspend fun deleteAll(): Int
    
    /**
     * Find a track by filename and file size (for duplicate detection)
     */
    @Query("SELECT * FROM tracks WHERE fileName = :fileName AND fileSizeBytes = :fileSizeBytes LIMIT 1")
    suspend fun findTrackByFilenameAndSize(fileName: String, fileSizeBytes: Long): Track?
}
