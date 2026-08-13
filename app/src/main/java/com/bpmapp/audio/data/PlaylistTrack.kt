// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.data

import androidx.room.Entity

/**
 * Join entity linking tracks to playlists
 * Stores the track membership and ordering within a playlist
 */
@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"])
data class PlaylistTrack(
    val playlistId: String,
    val trackId: String,
    val position: Int
)
