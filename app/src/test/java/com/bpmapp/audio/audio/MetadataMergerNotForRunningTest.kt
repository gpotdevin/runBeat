// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the notForRunning flag preservation in [MetadataMerger]:
 * re-merging metadata for an existing track (CSV import / library scan) must
 * never set or clear the flag, mirroring how [Track.isFavorite] is preserved.
 */
@RunWith(RobolectricTestRunner::class)
class MetadataMergerNotForRunningTest {

    private fun existingTrack(notForRunning: Boolean, isFavorite: Boolean = false): Track = Track(
        id = "track-1",
        relativePath = ".",
        fileName = "song.mp3",
        bpm = 120.0f,
        fileSizeBytes = 1000L,
        durationMs = 180000L,
        isFavorite = isFavorite,
        notForRunning = notForRunning
    )

    private fun merge(existing: Track?): Track = MetadataMerger.mergeIntoTrack(
        existing = existing,
        id3Data = emptyMap(),
        systemData = emptyMap(),
        csvData = emptyMap(),
        newId = "new-id",
        relativePath = "artist/album",
        fileName = "song.mp3",
        bpmFallback = null,
        fileSizeBytes = null,
        durationMs = null,
        source = TrackSource.APP
    )

    @Test
    fun mergeIntoTrack_preservesNotForRunningTrue() {
        val merged = merge(existingTrack(notForRunning = true, isFavorite = true))

        assertTrue("notForRunning=true must survive a metadata merge", merged.notForRunning)
        assertTrue("isFavorite preservation must keep working alongside it", merged.isFavorite)
    }

    @Test
    fun mergeIntoTrack_preservesNotForRunningFalse() {
        val merged = merge(existingTrack(notForRunning = false))

        assertFalse("notForRunning=false must survive a metadata merge", merged.notForRunning)
    }

    @Test
    fun mergeIntoTrack_newTrackIsNotFlaggedByDefault() {
        val merged = merge(existing = null)

        assertFalse("A brand-new track must not be flagged not-for-running", merged.notForRunning)
    }
}
