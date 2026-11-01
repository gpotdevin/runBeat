// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import com.bpmapp.audio.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for M3uExporter.buildM3uContent
 */
class M3uExporterTest {

    private fun track(
        id: String,
        durationMs: Long? = 180_000L,
        metadataTitle: String? = "Title",
        metadataArtist: String? = "Artist",
        fileName: String = "file.mp3"
    ) = Track(
        id = id,
        relativePath = "",
        fileName = fileName,
        bpm = null,
        fileSizeBytes = null,
        durationMs = durationMs,
        metadataTitle = metadataTitle,
        metadataArtist = metadataArtist
    )

    @Test
    fun testBasicOutputFormat() {
        val tracks = listOf(
            track(id = "/music/a.mp3", durationMs = 180_000L, metadataTitle = "Song A", metadataArtist = "Artist A"),
            track(id = "/music/b.mp3", durationMs = 90_500L, metadataTitle = "Song B", metadataArtist = "Artist B")
        )

        val content = M3uExporter.buildM3uContent("My Playlist", tracks)
        val lines = content.trimEnd().split('\n')

        assertEquals("#EXTM3U", lines[0])
        assertEquals("#EXTINF:180,Artist A - Song A", lines[1])
        assertEquals("/music/a.mp3", lines[2])
        assertEquals("#EXTINF:90,Artist B - Song B", lines[3])
        assertEquals("/music/b.mp3", lines[4])
        assertEquals(5, lines.size)
    }

    @Test
    fun testDurationSecondsTruncation() {
        // 90_500 ms -> 90 seconds (integer division)
        val content = M3uExporter.buildM3uContent("p", listOf(track(id = "/x.mp3", durationMs = 90_500L)))
        assertTrue(content.contains("#EXTINF:90,"))
    }

    @Test
    fun testNullDurationAndMetadataFallbacks() {
        val track = track(
            id = "/music/no-meta.mp3",
            durationMs = null,
            metadataTitle = null,
            metadataArtist = null,
            fileName = "no-meta.mp3"
        )

        val content = M3uExporter.buildM3uContent("p", listOf(track))
        val lines = content.trimEnd().split('\n')

        assertEquals("#EXTM3U", lines[0])
        // duration falls back to 0, title/artist fall back to fileName
        assertEquals("#EXTINF:0,no-meta.mp3 - no-meta.mp3", lines[1])
        assertEquals("/music/no-meta.mp3", lines[2])
    }

    @Test
    fun testSpecialCharactersAndNewlinesEscaped() {
        val track = track(
            id = "/music/weird\npath.mp3",
            durationMs = 1000L,
            metadataTitle = "Line1\nLine2",
            metadataArtist = "Art\r\nist"
        )

        val content = M3uExporter.buildM3uContent("p", listOf(track))
        val lines = content.trimEnd().split('\n')

        // Newlines in metadata must be flattened so they cannot corrupt the line structure
        assertEquals(3, lines.size)
        assertEquals("#EXTM3U", lines[0])
        // both '\r' and '\n' are flattened to spaces
        assertEquals("#EXTINF:1,Art  ist - Line1 Line2", lines[1])
        assertEquals("/music/weird path.mp3", lines[2])
        assertFalse(content.contains("\r"))
    }

    @Test
    fun testEmptyPlaylist() {
        val content = M3uExporter.buildM3uContent("Empty", emptyList())
        assertEquals("#EXTM3U\n", content)
    }

    @Test
    fun testTrackOrderPreserved() {
        val tracks = listOf(
            track(id = "/c.mp3"),
            track(id = "/a.mp3"),
            track(id = "/b.mp3")
        )
        val content = M3uExporter.buildM3uContent("p", tracks)
        val paths = content.trimEnd().split('\n').filter { it.startsWith("/") }
        assertEquals(listOf("/c.mp3", "/a.mp3", "/b.mp3"), paths)
    }
}
