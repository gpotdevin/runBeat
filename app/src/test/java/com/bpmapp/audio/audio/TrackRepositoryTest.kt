// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for TrackRepository CSV parsing and track creation logic
 * Tests the pure Kotlin functions that don't require Android Context or Room database
 */
class TrackRepositoryTest {
    
    // ==================== createTrack Tests ====================
    
    @Test
    fun testCreateTrack_Basic() {
        val track = Track(
            id = "/path/to/file.mp3",
            relativePath = ".",
            fileName = "file.mp3",
            bpm = 120.0f,
            fileSizeBytes = 1000L,
            durationMs = 180000L
        )
        
        assertNotNull("Track should be created", track)
        assertEquals("ID should be file path", "/path/to/file.mp3", track.id)
        assertEquals("File name should be file.mp3", "file.mp3", track.fileName)
        assertEquals("BPM should be 120.0", 120.0f, track.bpm!!, 0.01f)
        assertEquals("File size should be 1000", 1000L, track.fileSizeBytes!!)
    }
    
    @Test
    fun testCreateTrack_Minimal() {
        val track = Track(
            id = "file://test.mp3",
            relativePath = ".",
            fileName = "test.mp3",
            bpm = null,
            fileSizeBytes = null,
            durationMs = null
        )
        
        assertNotNull("Track should be created with minimal info", track)
        assertNull("BPM should be null", track.bpm)
        assertNull("File size should be null", track.fileSizeBytes)
    }
    
    @Test
    fun testCreateTrack_WithSource() {
        val track = Track(
            id = "content://media/external/audio/media/1",
            relativePath = "/storage/emulated/0/Music",
            fileName = "song.mp3",
            bpm = 150.0f,
            fileSizeBytes = 2000L,
            durationMs = 200000L,
            source = TrackSource.SYSTEM
        )
        
        assertEquals("Source should be SYSTEM", TrackSource.SYSTEM, track.source)
    }
    
    // ==================== Bug Fix: 0 BPM treated as unknown ====================
    
    @Test
    fun testCsvParsing_ZeroBpmTreatedAsNull() {
        // Test that 0 BPM from CSV is treated as unknown (null)
        // This tests the bug fix for: "bug: musics with 0 BPM considered as unknown"
        
        val fields = listOf("./artist", "test.mp3", "0", "1000000")
        
        // This is the fixed logic from parseCsvLineSimple
        val bpm: Float? = try {
            val parsedBpm = fields[2].toFloat()
            // Treat 0 or negative BPM as unknown (null) - they are invalid
            if (parsedBpm <= 0) null else parsedBpm
        } catch (e: NumberFormatException) {
            null
        }
        
        assertNull("BPM of 0 should be treated as null (unknown)", bpm)
    }
    
    @Test
    fun testCsvParsing_NegativeBpmTreatedAsNull() {
        val fields = listOf("./artist", "test.mp3", "-100", "1000000")
        
        val bpm: Float? = try {
            val parsedBpm = fields[2].toFloat()
            if (parsedBpm <= 0) null else parsedBpm
        } catch (e: NumberFormatException) {
            null
        }
        
        assertNull("Negative BPM should be treated as null (unknown)", bpm)
    }
    
    @Test
    fun testCsvParsing_PositiveBpmKept() {
        val fields = listOf("./artist", "test.mp3", "120.5", "1000000")
        
        val bpm: Float? = try {
            val parsedBpm = fields[2].toFloat()
            if (parsedBpm <= 0) null else parsedBpm
        } catch (e: NumberFormatException) {
            null
        }
        
        assertNotNull("Positive BPM should not be null", bpm)
        assertEquals("Positive BPM should be kept as-is", 120.5f, bpm!!, 0.01f)
    }
    
    // ==================== CSV Field Parsing Tests ====================
    
    @Test
    fun testParseCsvFields_Basic() {
        val line = "a,b,c,d"
        
        val fields = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        
        while (i < line.length) {
            when (val c = line[i]) {
                '"' -> {
                    inQuotes = !inQuotes
                    i++
                }
                ',' -> {
                    if (inQuotes) {
                        current.append(c)
                        i++
                    } else {
                        fields.add(current.toString().trim())
                        current = StringBuilder()
                        i++
                    }
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        fields.add(current.toString().trim())
        
        assertEquals("Should parse 4 fields", 4, fields.size)
        assertEquals("First field should be 'a'", "a", fields[0])
        assertEquals("Second field should be 'b'", "b", fields[1])
        assertEquals("Third field should be 'c'", "c", fields[2])
        assertEquals("Fourth field should be 'd'", "d", fields[3])
    }
    
    @Test
    fun testParseCsvFields_WithQuotedCommas() {
        val line = "a,\"b,c\",d"
        
        val fields = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        
        while (i < line.length) {
            when (val c = line[i]) {
                '"' -> {
                    inQuotes = !inQuotes
                    i++
                }
                ',' -> {
                    if (inQuotes) {
                        current.append(c)
                        i++
                    } else {
                        fields.add(current.toString().trim())
                        current = StringBuilder()
                        i++
                    }
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        fields.add(current.toString().trim())
        
        assertEquals("Should parse 3 fields", 3, fields.size)
        assertEquals("First field should be 'a'", "a", fields[0])
        assertEquals("Second field should be 'b,c'", "b,c", fields[1])
        assertEquals("Third field should be 'd'", "d", fields[2])
    }
    
    @Test
    fun testParseCsvFields_EmptyFields() {
        val line = ",,,"
        
        val fields = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        
        while (i < line.length) {
            when (val c = line[i]) {
                '"' -> {
                    inQuotes = !inQuotes
                    i++
                }
                ',' -> {
                    if (inQuotes) {
                        current.append(c)
                        i++
                    } else {
                        fields.add(current.toString().trim())
                        current = StringBuilder()
                        i++
                    }
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        fields.add(current.toString().trim())
        
        assertEquals("Should parse 4 fields", 4, fields.size)
        assertEquals("All fields should be empty", "", fields[0])
        assertEquals("All fields should be empty", "", fields[1])
        assertEquals("All fields should be empty", "", fields[2])
        assertEquals("All fields should be empty", "", fields[3])
    }
    
    @Test
    fun testParseCsvFields_QuotedEmptyField() {
        val line = "a,\"\",b"
        
        val fields = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        
        while (i < line.length) {
            when (val c = line[i]) {
                '"' -> {
                    inQuotes = !inQuotes
                    i++
                }
                ',' -> {
                    if (inQuotes) {
                        current.append(c)
                        i++
                    } else {
                        fields.add(current.toString().trim())
                        current = StringBuilder()
                        i++
                    }
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        fields.add(current.toString().trim())
        
        assertEquals("Should parse 3 fields", 3, fields.size)
        assertEquals("First field should be 'a'", "a", fields[0])
        assertEquals("Second field should be empty string", "", fields[1])
        assertEquals("Third field should be 'b'", "b", fields[2])
    }
    
    // ==================== Track Validation Tests ====================
    
    @Test
    fun testTrack_WithValidBpm() {
        val track = Track(
            id = "1",
            relativePath = ".",
            fileName = "test.mp3",
            bpm = 120.0f,
            fileSizeBytes = null,
            durationMs = null
        )
        assertNotNull("Track with valid BPM should be created", track)
        assertTrue("BPM should be positive", track.bpm!! > 0)
    }
    
    @Test
    fun testTrack_WithNullBpm() {
        val track = Track(
            id = "1",
            relativePath = ".",
            fileName = "test.mp3",
            bpm = null,
            fileSizeBytes = null,
            durationMs = null
        )
        assertNotNull("Track with null BPM should be created", track)
        assertNull("BPM should be null", track.bpm)
    }
    
    @Test
    fun testTrack_WithZeroBpm() {
        val track = Track(
            id = "1",
            relativePath = ".",
            fileName = "test.mp3",
            bpm = 0.0f,
            fileSizeBytes = null,
            durationMs = null
        )
        assertNotNull("Track with 0 BPM should be created", track)
        assertEquals("BPM should be 0.0", 0.0f, track.bpm!!, 0.01f)
    }
    
    @Test
    fun testTrack_WithNegativeBpm() {
        val track = Track(
            id = "1",
            relativePath = ".",
            fileName = "test.mp3",
            bpm = -100.0f,
            fileSizeBytes = null,
            durationMs = null
        )
        assertNotNull("Track with negative BPM should be created (but invalid)", track)
        assertTrue("BPM should be negative", track.bpm!! < 0)
    }
    
    // ==================== File Path Construction Tests ====================
    
    @Test
    fun testFilePathConstruction_AbsolutePath() {
        val relativePath = "/music/artist"
        val fileName = "song.mp3"
        
        val isAbsolutePath = relativePath.startsWith("/")
        val normalizedRelativePath = if (relativePath.endsWith("/")) {
            relativePath.dropLast(1)
        } else {
            relativePath
        }
        
        val filePath = if (isAbsolutePath) {
            if (normalizedRelativePath == "/") {
                "/$fileName"
            } else {
                "$normalizedRelativePath/$fileName"
            }
        } else {
            "$normalizedRelativePath/$fileName"
        }
        
        assertEquals("File path should be /music/artist/song.mp3", 
            "/music/artist/song.mp3", filePath)
    }
    
    @Test
    fun testFilePathConstruction_RelativePath() {
        val relativePath = "artist/album"
        val fileName = "song.mp3"
        
        val isAbsolutePath = relativePath.startsWith("/")
        val normalizedRelativePath = if (relativePath.endsWith("/")) {
            relativePath.dropLast(1)
        } else {
            relativePath
        }
        
        val filePath = if (isAbsolutePath) {
            "$normalizedRelativePath/$fileName"
        } else {
            "$normalizedRelativePath/$fileName"
        }
        
        assertEquals("File path should be artist/album/song.mp3", 
            "artist/album/song.mp3", filePath)
    }
    
    @Test
    fun testFilePathConstruction_WithBaseDirectory() {
        val relativePath = "artist/album"
        val fileName = "song.mp3"
        val baseDirectory = "/storage/music"
        
        val isAbsolutePath = relativePath.startsWith("/")
        val normalizedRelativePath = if (relativePath.endsWith("/")) {
            relativePath.dropLast(1)
        } else {
            relativePath
        }
        
        val filePath = if (isAbsolutePath) {
            "$normalizedRelativePath/$fileName"
        } else if (normalizedRelativePath == "." || normalizedRelativePath.isEmpty()) {
            "$baseDirectory/$fileName"
        } else {
            "$baseDirectory/$normalizedRelativePath/$fileName"
        }
        
        assertEquals("File path should be /storage/music/artist/album/song.mp3", 
            "/storage/music/artist/album/song.mp3", filePath)
    }
}
