// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.ui

import com.bpmapp.audio.data.Track

/**
 * Default library ordering, mirroring the browse-by dimension order:
 * Genre, then Artist, then Album. Within one album, tracks are ordered by
 * their ID3/CD track number (e.g. "3" or "3/12") when available; tracks
 * without a track number fall back to alphabetical file name and sort after
 * the numbered tracks of that album.
 */
fun hierarchicalTrackOrder(): Comparator<Track> = compareBy(
    { it.metadataGenre?.lowercase() ?: "" },
    { it.metadataArtist?.lowercase() ?: "" },
    { it.metadataAlbum?.lowercase() ?: "" },
    { it.metadataTrackNumber?.substringBefore("/")?.toIntOrNull() ?: Int.MAX_VALUE },
    { it.fileName.lowercase() }
)
