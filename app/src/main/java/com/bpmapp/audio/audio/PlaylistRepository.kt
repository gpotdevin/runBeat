// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import com.bpmapp.audio.data.AppDatabase
import com.bpmapp.audio.data.Playlist
import com.bpmapp.audio.data.PlaylistTrack
import com.bpmapp.audio.data.Track
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for playlists and their track membership.
 * Thin suspend wrapper over [AppDatabase.playlistDao] / [TrackDao] for
 * one-shot reads (the watch companion asks for playlists on demand, not as
 * a flow).
 */
@Singleton
class PlaylistRepository @Inject constructor(
    appDatabase: AppDatabase,
) {

    private val playlistDao = appDatabase.playlistDao()
    private val trackDao = appDatabase.trackDao()

    /**
     * All playlists, ordered by name (case-insensitive), as a one-shot read.
     */
    suspend fun playlistsOnce(): List<Playlist> =
        playlistDao.getAllPlaylists().first()

    /**
     * The track relations of one playlist, ordered by position.
     */
    suspend fun tracksForPlaylist(playlistId: String): List<PlaylistTrack> =
        playlistDao.getPlaylistTracksOnce(playlistId)

    /**
     * The track entity for a track id, or null when it is not in the library.
     */
    suspend fun trackById(trackId: String): Track? =
        trackDao.getTrackById(trackId).first()
}
