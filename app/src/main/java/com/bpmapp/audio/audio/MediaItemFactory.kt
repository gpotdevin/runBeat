// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the [MediaItem] play-queue entries for library tracks. Extracted
 * from LibraryViewModel so the Garmin watch companion router builds the same
 * MediaItems (same URI resolution, same trackId/bpm/fileName/durationMs
 * extras) when playing a playlist from the watch.
 */
@Singleton
class MediaItemFactory @Inject constructor() {

    /**
     * Get a MediaItem for a track to play it
     * Includes BPM information in the media metadata for display in playlist
     */
    fun mediaItemFor(track: Track): MediaItem {
        // Use track.id directly if it's already a valid URI
        // For APP tracks from CSV import, track.id contains the full file:// URI
        val uriString = if (track.id.startsWith("file://") || track.id.startsWith("content://")) {
            track.id
        } else if (track.source == TrackSource.APP) {
            // Fallback: construct file URI from relativePath and fileName
            val path = if (track.relativePath == ".") {
                track.fileName
            } else {
                "${track.relativePath}/${track.fileName}"
            }
            "file:///$path"
        } else {
            track.id
        }

        return MediaItem.Builder()
            .setUri(Uri.parse(uriString))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.metadataTitle ?: track.fileName)
                    .setArtist(track.metadataArtist ?: "Unknown")
                    .setAlbumTitle(track.metadataAlbum ?: "Unknown")
                    .apply {
                        val extras = Bundle().apply {
                            putString("trackId", track.id)
                            putString("bpm", track.bpm?.toString() ?: "")
                            putString("fileName", track.fileName)
                            putLong("durationMs", track.durationMs ?: 0L)
                        }
                        setExtras(extras)
                    }
                    .build()
            )
            .build()
    }
}
