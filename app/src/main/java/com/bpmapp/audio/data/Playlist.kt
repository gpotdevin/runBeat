// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Playlist entity for the music library
 * Stores user-created playlists
 */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey
    val id: String,
    val name: String
)
