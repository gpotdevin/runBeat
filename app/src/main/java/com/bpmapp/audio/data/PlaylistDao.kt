// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Playlist entities
 * Provides database operations for user-created playlists
 */
@Dao
interface PlaylistDao {

    /**
     * Get all playlists
     */
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun getAllPlaylists(): Flow<List<Playlist>>

    /**
     * Get a playlist by its ID
     */
    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: String): Playlist?

    /**
     * Insert a playlist, or replace if it already exists
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(playlist: Playlist): Long

    /**
     * Update an existing playlist
     */
    @Update
    suspend fun update(playlist: Playlist): Int

    /**
     * Delete a playlist by its ID
     */
    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun delete(playlistId: String): Int

    /**
     * Get all playlist-track relations
     */
    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId, position ASC")
    fun getAllPlaylistTracks(): Flow<List<PlaylistTrack>>

    /**
     * Get the playlist-track relations for a single playlist
     */
    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getPlaylistTracksOnce(playlistId: String): List<PlaylistTrack>

    /**
     * Insert multiple playlist-track relations, replacing any that already exist
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTracks(playlistTracks: List<PlaylistTrack>): List<Long>

    /**
     * Remove a track from a playlist
     */
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun deleteTrackFromPlaylist(playlistId: String, trackId: String): Int

    /**
     * Remove all tracks from a playlist
     */
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deleteTracksForPlaylist(playlistId: String): Int
}
