// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.ui

import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the default library ordering: Genre -> Artist -> Album, with
 * tracks inside one album ordered by ID3 track number, falling back to
 * alphabetical file name when the track number is missing.
 */
class TrackOrderingTest {

    private fun track(
        id: String,
        fileName: String,
        genre: String? = null,
        artist: String? = null,
        album: String? = null,
        trackNumber: String? = null
    ) = Track(
        id = id,
        relativePath = "",
        fileName = fileName,
        bpm = null,
        fileSizeBytes = 0L,
        durationMs = 0L,
        source = TrackSource.APP,
        metadataTitle = null,
        metadataArtist = artist,
        metadataAlbum = album,
        metadataGenre = genre,
        metadataTrackNumber = trackNumber
    )

    @Test
    fun `orders by genre then artist then album`() {
        val unordered = listOf(
            track("a", "f1.mp3", genre = "Rock", artist = "B", album = "B2"),
            track("b", "f2.mp3", genre = "Jazz", artist = "A", album = "A1"),
            track("c", "f3.mp3", genre = "Rock", artist = "A", album = "A1"),
            track("d", "f4.mp3", genre = "Rock", artist = "B", album = "B1"),
            track("e", "f5.mp3", genre = "Electro", artist = "C", album = "C1")
        )
        val ordered = unordered.sortedWith(hierarchicalTrackOrder())
        assertEquals(listOf("e", "b", "c", "d", "a"), ordered.map { it.id })
    }

    @Test
    fun `orders tracks inside one album by id3 track number`() {
        val unordered = listOf(
            track("t10", "10-track.mp3", genre = "Rock", artist = "A", album = "Album", trackNumber = "10"),
            track("t2", "02-track.mp3", genre = "Rock", artist = "A", album = "Album", trackNumber = "2"),
            track("t1", "01-track.mp3", genre = "Rock", artist = "A", album = "Album", trackNumber = "1"),
            track("t21", "21-track.mp3", genre = "Rock", artist = "A", album = "Album", trackNumber = "21")
        )
        val ordered = unordered.sortedWith(hierarchicalTrackOrder())
        assertEquals(listOf("t1", "t2", "t10", "t21"), ordered.map { it.id })
    }

    @Test
    fun `supports track numbers in slash format`() {
        val unordered = listOf(
            track("t3of12", "03.mp3", genre = "Rock", artist = "A", album = "Album", trackNumber = "3/12"),
            track("t2of12", "02.mp3", genre = "Rock", artist = "A", album = "Album", trackNumber = "2/12"),
            track("t11", "11.mp3", genre = "Rock", artist = "A", album = "Album", trackNumber = "11/12")
        )
        val ordered = unordered.sortedWith(hierarchicalTrackOrder())
        assertEquals(listOf("t2of12", "t3of12", "t11"), ordered.map { it.id })
    }

    @Test
    fun `falls back to file name when track number is missing`() {
        val unordered = listOf(
            track("named", "z-song.mp3", genre = "Rock", artist = "A", album = "Album"),
            track("numbered", "a-song.mp3", genre = "Rock", artist = "A", album = "Album", trackNumber = "1"),
            track("named2", "a-song.mp3", genre = "Rock", artist = "A", album = "Album")
        )
        val ordered = unordered.sortedWith(hierarchicalTrackOrder())
        // Numbered track first, then unnumbered tracks alphabetically by file name
        assertEquals(listOf("numbered", "named2", "named"), ordered.map { it.id })
    }

    @Test
    fun `album without any track numbers is ordered by file name`() {
        val unordered = listOf(
            track("c", "03-song.mp3", genre = "Rock", artist = "A", album = "Album"),
            track("a", "01-song.mp3", genre = "Rock", artist = "A", album = "Album"),
            track("b", "02-song.mp3", genre = "Rock", artist = "A", album = "Album")
        )
        val ordered = unordered.sortedWith(hierarchicalTrackOrder())
        assertEquals(listOf("a", "b", "c"), ordered.map { it.id })
    }

    @Test
    fun `missing genre groups before known genres and does not crash`() {
        val unordered = listOf(
            track("known", "f.mp3", genre = "Rock", artist = "A", album = "Album"),
            track("unknown", "f.mp3", artist = "A", album = "Album")
        )
        val ordered = unordered.sortedWith(hierarchicalTrackOrder())
        assertEquals(listOf("unknown", "known"), ordered.map { it.id })
    }
}
