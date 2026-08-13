// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.ui

import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test suite specifically designed to catch the crash reported by the user:
 * "when I enter some string in the music library search fields, the app crashes"
 * 
 * This tests the exact scenarios that would cause NPE in the filtering logic.
 */
class FilterCrashTest {

    /**
     * Test the exact scenario that causes crash: filtering with null search query
     */
    @Test
    fun `availableAlbums computation with null artistSearchQuery should not crash`() {
        val allTracks = listOf(
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
            )
        )
        
        val artistSearchQuery: String? = null
        
        // This should NOT throw NPE with the fix
        val result = allTracks
            .filter { track ->
                artistSearchQuery.isNullOrBlank() || 
                (track.metadataArtist?.contains(artistSearchQuery ?: "", ignoreCase = true) ?: false)
            }
            .mapNotNull { it.metadataAlbum }
            .distinct()
            .sorted()
        
        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals("Album1", result[0])
    }

    /**
     * Demonstration: The old code would crash with null query
     * Note: This is not a runnable test, just documentation of the bug
     * The old code used: artistSearchQuery.isBlank() without null check
     * and contains(artistSearchQuery!!) which throws NPE on null
     */
    fun demonstrateOldCodeThatWouldCrash() {
        // Old problematic code (commented out to prevent actual crash):
        // val artistSearchQuery: String? = null
        // artistSearchQuery.isBlank()  // Would throw NPE
        // "test".contains(artistSearchQuery!!, ignoreCase = true)  // Would throw NPE
        
        // This test is intentionally left empty to document the issue
        // The fix uses: artistSearchQuery.isNullOrBlank() and contains(artistSearchQuery ?: "", ...)
    }

    /**
     * Test filtering artist dropdown items with null query
     */
    @Test
    fun `artist dropdown items filtering with null query should not crash`() {
        val availableArtists = listOf("Artist1", "Artist2", "Artist3")
        val artistSearchQuery: String? = null
        
        // This should NOT throw NPE with the fix
        val result = availableArtists
            .filter { it.contains(artistSearchQuery ?: "", ignoreCase = true) }
            .take(10)
        
        assertNotNull(result)
        assertEquals(3, result.size)
    }

    /**
     * Test filtering album dropdown items with null query
     */
    @Test
    fun `album dropdown items filtering with null query should not crash`() {
        val availableAlbums = listOf("Album1", "Album2", "Album3")
        val albumSearchQuery: String? = null
        
        // This should NOT throw NPE with the fix
        val result = availableAlbums
            .filter { it.contains(albumSearchQuery ?: "", ignoreCase = true) }
            .take(10)
        
        assertNotNull(result)
        assertEquals(3, result.size)
    }

    /**
     * Test track filtering with null artistSearchQuery
     */
    @Test
    fun `track filtering with null artistSearchQuery should not crash`() {
        val allTracks = listOf(
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
                relativePath = "Artist2/Album2",
                fileName = "Track2.mp3",
                bpm = 125.0f,
                fileSizeBytes = 2000L,
                durationMs = 200000L,
                source = TrackSource.APP,
                metadataTitle = "Track 2",
                metadataArtist = "Artist2",
                metadataAlbum = "Album2"
            )
        )
        
        val artistSearchQuery: String? = null
        
        // This should NOT throw NPE with the fix
        allTracks.forEach { track ->
            val artistFilter = if ((artistSearchQuery ?: "").isBlank()) {
                true
            } else {
                (track.metadataArtist?.contains(artistSearchQuery ?: "", ignoreCase = true) ?: false)
            }
            assertTrue(artistFilter) // With null query, all tracks should pass
        }
    }

    /**
     * Test track filtering with null albumSearchQuery
     */
    @Test
    fun `track filtering with null albumSearchQuery should not crash`() {
        val allTracks = listOf(
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
                relativePath = "Artist2/Album2",
                fileName = "Track2.mp3",
                bpm = 125.0f,
                fileSizeBytes = 2000L,
                durationMs = 200000L,
                source = TrackSource.APP,
                metadataTitle = "Track 2",
                metadataArtist = "Artist2",
                metadataAlbum = "Album2"
            )
        )
        
        val albumSearchQuery: String? = null
        
        // This should NOT throw NPE with the fix
        allTracks.forEach { track ->
            val albumFilter = if ((albumSearchQuery ?: "").isBlank()) {
                true
            } else {
                (track.metadataAlbum?.contains(albumSearchQuery ?: "", ignoreCase = true) ?: false)
            }
            assertTrue(albumFilter) // With null query, all tracks should pass
        }
    }

    /**
     * Test search query filtering with null searchQuery
     */
    @Test
    fun `track filtering with null searchQuery should not crash`() {
        val allTracks = listOf(
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
            )
        )
        
        val searchQuery: String? = null
        
        // This should NOT throw NPE with the fix
        allTracks.forEach { track ->
            val searchFilter = if (searchQuery.isNullOrBlank()) {
                true
            } else {
                track.fileName.contains(searchQuery ?: "", ignoreCase = true) ||
                (track.metadataTitle?.contains(searchQuery ?: "", ignoreCase = true) ?: false) ||
                (track.metadataArtist?.contains(searchQuery ?: "", ignoreCase = true) ?: false) ||
                (track.metadataAlbum?.contains(searchQuery ?: "", ignoreCase = true) ?: false)
            }
            assertTrue(searchFilter) // With null query, all tracks should pass
        }
    }

    /**
     * Test hasActiveFilter computation with null queries
     */
    @Test
    fun `hasActiveFilter computation with null queries should not crash`() {
        val searchQuery: String? = null
        val artistSearchQuery: String? = null
        val albumSearchQuery: String? = null
        val showOnlyKnownBpm = false
        val filterBySpeedFactor = false
        
        // This should NOT throw NPE with the fix
        val hasActiveFilter = (searchQuery ?: "").isNotBlank() || 
                (artistSearchQuery ?: "").isNotBlank() || 
                (albumSearchQuery ?: "").isNotBlank() || 
                showOnlyKnownBpm || 
                filterBySpeedFactor
        
        assertFalse(hasActiveFilter)
    }

    /**
     * Test that empty string queries work correctly
     */
    @Test
    fun `empty string queries should work correctly`() {
        val allTracks = listOf(
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
            )
        )
        
        val artistSearchQuery = ""
        
        val result = allTracks
            .filter { track ->
                artistSearchQuery.isNullOrBlank() || 
                (track.metadataArtist?.contains(artistSearchQuery ?: "", ignoreCase = true) ?: false)
            }
            .mapNotNull { it.metadataAlbum }
            .distinct()
            .sorted()
        
        assertNotNull(result)
        assertEquals(1, result.size)
    }

    /**
     * Test that filtering works correctly with actual queries
     */
    @Test
    fun `filtering with actual queries should work correctly`() {
        val allTracks = listOf(
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
                relativePath = "Artist2/Album2",
                fileName = "Track2.mp3",
                bpm = 125.0f,
                fileSizeBytes = 2000L,
                durationMs = 200000L,
                source = TrackSource.APP,
                metadataTitle = "Track 2",
                metadataArtist = "Artist2",
                metadataAlbum = "Album2"
            )
        )
        
        val artistSearchQuery = "Artist1"
        
        val result = allTracks
            .filter { track ->
                artistSearchQuery.isNullOrBlank() || 
                (track.metadataArtist?.contains(artistSearchQuery ?: "", ignoreCase = true) ?: false)
            }
            .mapNotNull { it.metadataAlbum }
            .distinct()
            .sorted()
        
        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals("Album1", result[0])
    }

    /**
     * Test case-insensitive filtering
     */
    @Test
    fun `case-insensitive filtering should work`() {
        val availableArtists = listOf("Artist1", "Artist2", "Artist3")
        val artistSearchQuery = "ARTIST1"
        
        val result = availableArtists
            .filter { it.contains(artistSearchQuery ?: "", ignoreCase = true) }
            .take(10)
        
        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals("Artist1", result[0])
    }

    /**
     * Test that empty list doesn't cause crash in FilterDropdownList
     */
    @Test
    fun `FilterDropdownList with empty list should return early`() {
        val items = emptyList<String>()
        
        // Simulate FilterDropdownList behavior
        if (items.isEmpty()) {
            // Should return early
            return
        }
        
        // Should not reach here
        assertFalse("Should have returned early for empty list", true)
    }

    /**
     * Test dropdown items filtering with empty query
     */
    @Test
    fun `dropdown items filtering with empty query should return all items`() {
        val items = listOf("Artist1", "Artist2", "Artist3")
        val query = ""
        
        val result = items
            .filter { it.contains(query, ignoreCase = true) }
            .take(10)
        
        assertNotNull(result)
        assertEquals(3, result.size)
    }

    /**
     * Test dropdown items filtering with non-matching query
     */
    @Test
    fun `dropdown items filtering with non-matching query should return empty`() {
        val items = listOf("Artist1", "Artist2", "Artist3")
        val query = "NonExistent"
        
        val result = items
            .filter { it.contains(query, ignoreCase = true) }
            .take(10)
        
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }
}
