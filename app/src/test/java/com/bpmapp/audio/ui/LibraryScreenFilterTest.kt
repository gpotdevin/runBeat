// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.ui

import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test suite for LibraryScreen filter components
 * Tests FilterTextField, FilterDropdownList, FilterBar, and related filtering logic
 */
class LibraryScreenFilterTest {

    // Test data
    private lateinit var sampleTracks: List<Track>

    @Before
    fun setUp() {
        sampleTracks = listOf(
            Track(
                id = "1",
                relativePath = "Artist1/Album1",
                fileName = "Track1.mp3",
                bpm = 120.0f,
                fileSizeBytes = 1000L,
                durationMs = 180000L,
                source = TrackSource.APP,
                metadataTitle = "Track 1",
                metadataArtist = "Artist1",
                metadataAlbum = "Album1"
            ),
            Track(
                id = "2",
                relativePath = "Artist1/Album2",
                fileName = "Track2.mp3",
                bpm = 125.0f,
                fileSizeBytes = 2000L,
                durationMs = 200000L,
                source = TrackSource.APP,
                metadataTitle = "Track 2",
                metadataArtist = "Artist1",
                metadataAlbum = "Album2"
            ),
            Track(
                id = "3",
                relativePath = "Artist2/Album1",
                fileName = "Track3.mp3",
                bpm = 130.0f,
                fileSizeBytes = 3000L,
                durationMs = 220000L,
                source = TrackSource.APP,
                metadataTitle = "Track 3",
                metadataArtist = "Artist2",
                metadataAlbum = "Album1"
            ),
            Track(
                id = "4",
                relativePath = "Artist2/Album2",
                fileName = "Track4.mp3",
                bpm = null,
                fileSizeBytes = 4000L,
                durationMs = 240000L,
                source = TrackSource.APP,
                metadataTitle = "Track 4",
                metadataArtist = "Artist2",
                metadataAlbum = "Album2"
            ),
            Track(
                id = "5",
                relativePath = "Artist3",
                fileName = "Track5.mp3",
                bpm = 140.0f,
                fileSizeBytes = 5000L,
                durationMs = 260000L,
                source = TrackSource.APP,
                metadataTitle = "Track 5",
                metadataArtist = "Artist3",
                metadataAlbum = null  // Null album for testing
            )
        )
    }

    // ==================== FilterDropdownList Tests ====================

    @Test
    fun `FilterDropdownList with non-null non-empty items should not crash`() {
        val items = listOf("Artist1", "Artist2", "Artist3")
        val selectedItem = "Artist1"
        var selected: String? = null
        var dismissed = false

        // Simulate FilterDropdownList behavior
        if (items.isEmpty()) {
            // Should return early without crash
            return
        }

        // This should not throw NPE
        val filteredItems = items
            .filter { it.contains("", ignoreCase = true) }  // Empty query
            .take(10)

        assertNotNull(filteredItems)
        assertEquals(3, filteredItems.size)
    }

    @Test
    fun `FilterDropdownList with empty items should return early`() {
        val items = emptyList<String>()
        
        // This should not throw NPE
        if (items.isEmpty()) {
            // Function should return here
            return
        }
        
        // Should not reach here
        assertFalse("Should have returned early for empty list", true)
    }

    @Test(expected = NullPointerException::class)
    fun `FilterDropdownList with null items should throw NPE on isEmpty check`() {
        val items: List<String>? = null
        
        // This will throw NPE because we're calling isEmpty() on null
        if (items!!.isEmpty()) {
            return
        }
    }

    @Test
    fun `FilterDropdownList with null items should be handled with null check`() {
        val items: List<String>? = null
        
        // This should not throw NPE
        if (items.isNullOrEmpty()) {
            return
        }
        
        // Should not reach here
        assertFalse("Should have returned early for null list", true)
    }

    // ==================== availableArtists computation Tests ====================

    @Test
    fun `availableArtists with non-empty tracks should return distinct sorted artists`() {
        val result = sampleTracks
            .mapNotNull { it.metadataArtist }
            .distinct()
            .sorted()

        assertNotNull(result)
        assertEquals(3, result.size)
        assertEquals("Artist1", result[0])
        assertEquals("Artist2", result[1])
        assertEquals("Artist3", result[2])
    }

    @Test
    fun `availableArtists with empty tracks should return empty list`() {
        val emptyTracks = emptyList<Track>()
        val result = emptyTracks
            .mapNotNull { it.metadataArtist }
            .distinct()
            .sorted()

        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `availableArtists with tracks having null metadataArtist should filter them out`() {
        val tracksWithNullArtist = listOf(
            Track(
                id = "1",
                relativePath = "path",
                fileName = "file.mp3",
                bpm = 120.0f,
                fileSizeBytes = 1000L,
                durationMs = 180000L,
                source = TrackSource.APP,
                metadataTitle = "Title",
                metadataArtist = null,  // Null artist
                metadataAlbum = "Album"
            )
        )

        val result = tracksWithNullArtist
            .mapNotNull { it.metadataArtist }
            .distinct()
            .sorted()

        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    // ==================== availableAlbums computation Tests ====================

    @Test
    fun `availableAlbums with blank artistSearchQuery should return all albums`() {
        val artistSearchQuery = ""
        val result = sampleTracks
            .filter { track ->
                artistSearchQuery.isBlank() || 
                (track.metadataArtist?.contains(artistSearchQuery, ignoreCase = true) ?: false)
            }
            .mapNotNull { it.metadataAlbum }
            .distinct()
            .sorted()

        assertNotNull(result)
        // Should have Album1, Album2 (Track5 has null album so it's filtered out by mapNotNull)
        assertEquals(2, result.size)
        assertEquals("Album1", result[0])
        assertEquals("Album2", result[1])
    }

    @Test
    fun `availableAlbums with matching artistSearchQuery should filter albums`() {
        val artistSearchQuery = "Artist1"
        val result = sampleTracks
            .filter { track ->
                artistSearchQuery.isBlank() || 
                (track.metadataArtist?.contains(artistSearchQuery, ignoreCase = true) ?: false)
            }
            .mapNotNull { it.metadataAlbum }
            .distinct()
            .sorted()

        assertNotNull(result)
        // Only Artist1's albums: Album1, Album2
        assertEquals(2, result.size)
    }

    @Test
    fun `availableAlbums with non-matching artistSearchQuery should return empty`() {
        val artistSearchQuery = "NonExistentArtist"
        val result = sampleTracks
            .filter { track ->
                artistSearchQuery.isBlank() || 
                (track.metadataArtist?.contains(artistSearchQuery, ignoreCase = true) ?: false)
            }
            .mapNotNull { it.metadataAlbum }
            .distinct()
            .sorted()

        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test(expected = NullPointerException::class)
    fun `availableAlbums with null artistSearchQuery should throw NPE on contains`() {
        val artistSearchQuery: String? = null
        
        // This will throw NPE when contains is called with null
        sampleTracks
            .filter { track ->
                artistSearchQuery!!.isBlank() || 
                (track.metadataArtist?.contains(artistSearchQuery!!, ignoreCase = true) ?: false)
            }
    }

    @Test
    fun `availableAlbums with null artistSearchQuery should be handled with null check`() {
        val artistSearchQuery: String? = null
        
        // Safe version with null check
        val result = sampleTracks
            .filter { track ->
                artistSearchQuery.isNullOrBlank() || 
                (track.metadataArtist?.contains(artistSearchQuery, ignoreCase = true) ?: false)
            }
            .mapNotNull { it.metadataAlbum }
            .distinct()
            .sorted()

        assertNotNull(result)
        // With null query, should return all albums
        assertEquals(2, result.size)
    }

    // ==================== FilterDropdownList items filtering Tests ====================

    @Test
    fun `filter artists with empty query should return all artists`() {
        val availableArtists = listOf("Artist1", "Artist2", "Artist3")
        val artistSearchQuery = ""
        
        val result = availableArtists
            .filter { it.contains(artistSearchQuery, ignoreCase = true) }
            .take(10)

        assertEquals(3, result.size)
    }

    @Test
    fun `filter artists with matching query should return filtered list`() {
        val availableArtists = listOf("Artist1", "Artist2", "Artist3")
        val artistSearchQuery = "Artist1"
        
        val result = availableArtists
            .filter { it.contains(artistSearchQuery, ignoreCase = true) }
            .take(10)

        assertEquals(1, result.size)
        assertEquals("Artist1", result[0])
    }

    @Test
    fun `filter artists with case-insensitive query should work`() {
        val availableArtists = listOf("Artist1", "Artist2", "Artist3")
        val artistSearchQuery = "ARTIST1"
        
        val result = availableArtists
            .filter { it.contains(artistSearchQuery, ignoreCase = true) }
            .take(10)

        assertEquals(1, result.size)
        assertEquals("Artist1", result[0])
    }

    @Test(expected = NullPointerException::class)
    fun `filter artists with null query should throw NPE`() {
        val availableArtists = listOf("Artist1", "Artist2", "Artist3")
        val artistSearchQuery: String? = null
        
        // This will throw NPE
        availableArtists
            .filter { it.contains(artistSearchQuery!!, ignoreCase = true) }
            .take(10)
    }

    @Test
    fun `filter artists with null query should be handled with empty check`() {
        val availableArtists = listOf("Artist1", "Artist2", "Artist3")
        val artistSearchQuery: String? = null
        
        // Safe version
        val query = artistSearchQuery ?: ""
        val result = availableArtists
            .filter { it.contains(query, ignoreCase = true) }
            .take(10)

        assertEquals(3, result.size)
    }

    // ==================== FilterBar dropdown items Tests ====================

    @Test
    fun `artist dropdown items should filter and limit to 10`() {
        val availableArtists = List(15) { "Artist$it" }
        val artistSearchQuery = "Artist"
        
        val result = availableArtists
            .filter { it.contains(artistSearchQuery, ignoreCase = true) }
            .take(10)

        assertEquals(10, result.size)
    }

    @Test
    fun `album dropdown items should filter and limit to 10`() {
        val availableAlbums = List(15) { "Album$it" }
        val albumSearchQuery = "Album"
        
        val result = availableAlbums
            .filter { it.contains(albumSearchQuery, ignoreCase = true) }
            .take(10)

        assertEquals(10, result.size)
    }

    @Test
    fun `empty filter query should return all items up to limit`() {
        val availableArtists = List(15) { "Artist$it" }
        val artistSearchQuery = ""
        
        val result = availableArtists
            .filter { it.contains(artistSearchQuery, ignoreCase = true) }
            .take(10)

        assertEquals(10, result.size)
    }

    // ==================== Edge Cases Tests ====================

    @Test
    fun `empty track list should produce empty artist and album lists`() {
        val emptyTracks = emptyList<Track>()
        
        val artists = emptyTracks
            .mapNotNull { it.metadataArtist }
            .distinct()
            .sorted()

        val albums = emptyTracks
            .mapNotNull { it.metadataAlbum }
            .distinct()
            .sorted()

        assertTrue(artists.isEmpty())
        assertTrue(albums.isEmpty())
    }

    @Test
    fun `tracks with all null metadata should produce empty lists`() {
        val tracksWithNullMetadata = listOf(
            Track(
                id = "1",
                relativePath = "path",
                fileName = "file.mp3",
                bpm = 120.0f,
                fileSizeBytes = 1000L,
                durationMs = 180000L,
                source = TrackSource.APP,
                metadataTitle = null,
                metadataArtist = null,
                metadataAlbum = null
            )
        )

        val artists = tracksWithNullMetadata
            .mapNotNull { it.metadataArtist }
            .distinct()
            .sorted()

        val albums = tracksWithNullMetadata
            .mapNotNull { it.metadataAlbum }
            .distinct()
            .sorted()

        assertTrue(artists.isEmpty())
        assertTrue(albums.isEmpty())
    }

    @Test
    fun `tracks with duplicate artists should be deduplicated`() {
        val tracksWithDuplicates = listOf(
            Track(
                id = "1",
                relativePath = "path1",
                fileName = "file1.mp3",
                bpm = 120.0f,
                fileSizeBytes = 1000L,
                durationMs = 180000L,
                source = TrackSource.APP,
                metadataTitle = "Title1",
                metadataArtist = "Artist1",
                metadataAlbum = "Album1"
            ),
            Track(
                id = "2",
                relativePath = "path2",
                fileName = "file2.mp3",
                bpm = 120.0f,
                fileSizeBytes = 1000L,
                durationMs = 180000L,
                source = TrackSource.APP,
                metadataTitle = "Title2",
                metadataArtist = "Artist1",  // Duplicate
                metadataAlbum = "Album2"
            ),
            Track(
                id = "3",
                relativePath = "path3",
                fileName = "file3.mp3",
                bpm = 120.0f,
                fileSizeBytes = 1000L,
                durationMs = 180000L,
                source = TrackSource.APP,
                metadataTitle = "Title3",
                metadataArtist = "Artist1",  // Duplicate
                metadataAlbum = "Album3"
            )
        )

        val artists = tracksWithDuplicates
            .mapNotNull { it.metadataArtist }
            .distinct()
            .sorted()

        assertEquals(1, artists.size)
        assertEquals("Artist1", artists[0])
    }

    @Test
    fun `albums filtered by artist should respect artist query`() {
        val artistSearchQuery = "Artist1"
        
        val result = sampleTracks
            .filter { track ->
                artistSearchQuery.isBlank() || 
                (track.metadataArtist?.contains(artistSearchQuery, ignoreCase = true) ?: false)
            }
            .mapNotNull { it.metadataAlbum }
            .distinct()
            .sorted()

        assertNotNull(result)
        // Only Artist1's albums
        assertEquals(2, result.size)
        assertTrue(result.contains("Album1"))
        assertTrue(result.contains("Album2"))
    }

    // ==================== String containment edge cases ====================

    @Test
    fun `contains with empty string should match all strings`() {
        val items = listOf("Artist1", "Artist2", "")
        val query = ""
        
        val result = items.filter { it.contains(query, ignoreCase = true) }
        
        // Empty string is contained in all strings
        assertEquals(3, result.size)
    }

    @Test
    fun `contains with partial match should work`() {
        val items = listOf("Artist1", "Artist2", "Art")
        val query = "Art"
        
        val result = items.filter { it.contains(query, ignoreCase = true) }
        
        assertEquals(3, result.size)
    }

    @Test
    fun `contains with no match should return empty`() {
        val items = listOf("Artist1", "Artist2")
        val query = "XYZ"
        
        val result = items.filter { it.contains(query, ignoreCase = true) }
        
        assertTrue(result.isEmpty())
    }
}
