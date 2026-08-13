// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackDao
import com.bpmapp.audio.data.TrackSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing the music library
 * Handles track storage, BPM data, CSV import, and system library integration
 */
@Singleton
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao,
    @ApplicationContext private val context: Context
) {
    
    private val metadataEditor: MetadataEditor by lazy {
        MetadataEditor(context)
    }
    
    companion object {
        private const val TAG = "TrackRepository"
    }
    
    // All tracks in the library
    val allTracks: Flow<List<Track>> = trackDao.getAllTracks()
    
    /**
     * Get a specific track by its ID
     */
    fun getTrackById(trackId: String): Flow<Track?> {
        return trackDao.getTrackById(trackId)
    }
    
    /**
     * Add or update a track in the library
     */
    suspend fun addOrUpdateTrack(track: Track): Long {
        return withContext(Dispatchers.IO) {
            trackDao.insertOrReplace(track)
        }
    }
    
    /**
     * Update BPM for a specific track
     */
    suspend fun updateBpm(trackId: String, bpm: Float?): Int {
        return withContext(Dispatchers.IO) {
            trackDao.updateBpm(trackId, bpm)
        }
    }
    
    /**
     * Delete a track from the library
     */
    suspend fun deleteTrack(trackId: String): Int {
        return withContext(Dispatchers.IO) {
            trackDao.delete(trackId)
        }
    }
    
    /**
     * Delete all tracks from the library
     */
    suspend fun deleteAllTracks(): Int {
        return withContext(Dispatchers.IO) {
            trackDao.deleteAll()
        }
    }
    
    /**
     * Import tracks from a CSV file
     * Expected format: relative_path,filename,bpm,file_size_bytes[,title,artist,album]
     * Example: GoGo_Penguin/A_Humdrum_Star,1-2-Raven-320.mp3,152.0,12166665,Song Title,Artist Name,Album Name
     * Note: Filenames may be quoted if they contain commas. Fields 5-7 (title, artist, album) are optional.
     * 
     * Duplicate detection: If a track with the same filename and file size already exists,
     * the existing track's metadata will be merged with CSV data and ID3 tags using MetadataMerger.
     * 
     * @param uri The URI of the CSV file
     * @param baseDirectory Optional base directory to prepend to relative paths
     * @return Number of tracks successfully imported
     */
    suspend fun importFromCsv(uri: Uri, baseDirectory: String? = null): Int = withContext(Dispatchers.IO) {
        var count = 0
        var lineNumber = 0
        
        Log.i(TAG, "Starting CSV import from: $uri")
        
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "Failed to open input stream for CSV: $uri")
                return@withContext 0
            }
            
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var isHeader = true
            
            while (reader.readLine().also { line = it } != null) {
                lineNumber++
                
                if (isHeader) {
                    isHeader = false
                    continue
                }
                
                val nonNullLine = line!!
                val trimmedLine = nonNullLine.trim()
                if (trimmedLine.isEmpty()) continue
                
                val track = parseCsvLineSimple(trimmedLine, baseDirectory)
                if (track != null) {
                    try {
                        Log.d(TAG, "Processing CSV line $lineNumber: ${track.fileName}")
                        
                        // Read ID3 tags from the actual file
                        val id3Data = readId3TagsForTrack(track)
                        Log.d(TAG, "ID3 data for ${track.fileName}: $id3Data")
                        
                        // Convert CSV track data to map for merging
                        val csvData = createCsvDataMap(track)
                        
                        // Check for existing track
                        val existingTrack = track.fileSizeBytes?.let { fileSize ->
                            trackDao.findTrackByFilenameAndSize(track.fileName, fileSize)
                        }
                        
                        val finalTrack = MetadataMerger.mergeIntoTrack(
                            existing = existingTrack,
                            id3Data = id3Data,
                            systemData = emptyMap(), // No system data for CSV import
                            csvData = csvData,
                            newId = track.id,
                            relativePath = track.relativePath,
                            fileName = track.fileName,
                            bpmFallback = track.bpm,
                            fileSizeBytes = track.fileSizeBytes,
                            durationMs = track.durationMs,
                            source = TrackSource.APP
                        )
                        
                        if (existingTrack != null) {
                            Log.d(TAG, "Found existing track for ${track.fileName}, merging metadata")
                        } else {
                            Log.d(TAG, "Creating new track for ${track.fileName}")
                        }
                        
                        // Save the final track
                        if (existingTrack != null) {
                            trackDao.update(finalTrack)
                        } else {
                            trackDao.insertOrReplace(finalTrack)
                        }
                        
                        Log.d(TAG, "Successfully imported/updated track: ${finalTrack.fileName} with BPM: ${finalTrack.bpm}")
                        count++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to insert/update track from line $lineNumber: ${track.fileName}", e)
                    }
                } else {
                    Log.w(TAG, "Failed to parse CSV line $lineNumber: $trimmedLine")
                }
            }
            
            reader.close()
            inputStream.close()
            
            Log.i(TAG, "Successfully imported $count tracks from CSV")
            count
            
        } catch (e: Exception) {
            Log.e(TAG, "Error importing CSV at line $lineNumber", e)
            count
        }
    }
    
    /**
     * Parse CSV line with proper handling of quoted fields
     * Format: relative_path,filename,bpm,file_size_bytes[,title,artist,album,genre]
     * Fields 5-8 (title, artist, album, genre) are optional and will be extracted from filename if not provided
     */
    private fun parseCsvLineSimple(line: String, baseDirectory: String? = null): Track? {
        val fields = parseCsvFields(line)
        
        if (fields.size < 3) {
            Log.w(TAG, "CSV line has insufficient fields (${fields.size}): $line")
            return null
        }
        
        val relativePath = fields[0].ifEmpty { "." }
        val fileName = fields[1]
        
        val bpm: Float? = try {
            val parsedBpm = fields[2].toFloat()
            // Treat 0 or negative BPM as unknown (null) - they are invalid
            if (parsedBpm <= 0) null else parsedBpm
        } catch (e: NumberFormatException) {
            null
        }
        
        val fileSizeBytes: Long? = try {
            if (fields.size >= 4 && fields[3].isNotEmpty()) {
                fields[3].toLong()
            } else {
                null
            }
        } catch (e: NumberFormatException) {
            null
        }
        
        // Extract metadata from CSV fields if available (fields 4, 5, 6)
        // If not provided, extract title from filename
        val metadataTitle = if (fields.size >= 5 && fields[4].isNotEmpty()) {
            fields[4]
        } else {
            val title = fileName.substringBeforeLast(".")
            if (title.isNotEmpty()) title else fileName
        }
        
        val metadataArtist = if (fields.size >= 6 && fields[5].isNotEmpty()) {
            fields[5]
        } else {
            null
        }
        
        val metadataAlbum = if (fields.size >= 7 && fields[6].isNotEmpty()) {
            fields[6]
        } else {
            null
        }
        
        val metadataGenre = if (fields.size >= 8 && fields[7].isNotEmpty()) {
            fields[7]
        } else {
            null
        }
        
        // Construct proper file URI
        // Check if relativePath is already an absolute path
        val isAbsolutePath = relativePath.startsWith("/")
        
        // Normalize relativePath to remove trailing slashes
        val normalizedRelativePath = if (relativePath.endsWith("/")) {
            relativePath.dropLast(1)
        } else {
            relativePath
        }
        
        val filePath = if (isAbsolutePath) {
            // Use relativePath as absolute path
            if (normalizedRelativePath == "/") {
                "/$fileName"
            } else {
                "$normalizedRelativePath/$fileName"
            }
        } else if (normalizedRelativePath == "." || normalizedRelativePath.isEmpty()) {
            if (baseDirectory != null) {
                "$baseDirectory/$fileName"
            } else {
                fileName
            }
        } else {
            if (baseDirectory != null) {
                "$baseDirectory/$normalizedRelativePath/$fileName"
            } else {
                "$normalizedRelativePath/$fileName"
            }
        }
        
        // Create file URI for the track ID
        val id = "file://$filePath"
        
        return Track(
            id = id,
            relativePath = normalizedRelativePath,
            fileName = fileName,
            bpm = bpm,
            fileSizeBytes = fileSizeBytes,
            durationMs = null,
            source = TrackSource.APP,
            metadataTitle = metadataTitle,
            metadataArtist = metadataArtist,
            metadataAlbum = metadataAlbum,
            metadataGenre = metadataGenre,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * Parse CSV fields handling quoted values
     * Example: a,b,"c,d",e -> [a, b, c,d, e]
     */
    private fun parseCsvFields(line: String): List<String> {
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
        return fields
    }
    
    /**
     * Read ID3 metadata tags from the actual audio file for a track
     * 
     * @param track The track to read ID3 tags for
     * @return Map with keys "title", "artist", "album", "genre", "bpm"
     */
    private suspend fun readId3TagsForTrack(track: Track): Map<String, String?> {
        return try {
            val uri = Uri.parse(track.id)
            metadataEditor.readCompleteMetadata(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read ID3 tags for ${track.fileName}: ${e.message}")
            emptyMap()
        }
    }
    
    /**
     * Convert a parsed CSV track into a metadata map for merging
     * Only includes explicitly provided fields (skips null metadata values)
     *
     * @param track The track parsed from CSV
     * @return Map with keys "title", "artist", "album", "genre", "bpm"
     */
    private fun createCsvDataMap(track: Track): Map<String, String?> {
        return mapOf(
            "title" to track.metadataTitle,
            "artist" to track.metadataArtist,
            "album" to track.metadataAlbum,
            "genre" to track.metadataGenre,
            "bpm" to track.bpm?.toString()
        )
    }
    
    /**
     * Create a track from basic file information
     * Used for manually adding tracks or from system library
     */
    fun createTrack(
        filePath: String,
        fileName: String,
        relativePath: String = ".",
        bpm: Float? = null,
        fileSizeBytes: Long? = null,
        durationMs: Long? = null,
        source: TrackSource = TrackSource.APP,
        metadataTitle: String? = null,
        metadataArtist: String? = null,
        metadataAlbum: String? = null,
        metadataGenre: String? = null
    ): Track {
        return Track(
            id = filePath,
            relativePath = relativePath,
            fileName = fileName,
            bpm = bpm,
            fileSizeBytes = fileSizeBytes,
            durationMs = durationMs,
            source = source,
            metadataTitle = metadataTitle,
            metadataArtist = metadataArtist,
            metadataAlbum = metadataAlbum,
            metadataGenre = metadataGenre,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * Add a track from the system library, merging metadata from all sources.
     * 
     * If a track with the same filename and file size already exists (e.g. imported
     * from CSV), the existing metadata is merged with ID3 tags and system library
     * data, preserving BPM and favorites while filling in genre/artist/album from
     * the system library when the CSV did not provide them.
     * 
     * @param track The system library track to add
     * @return true if the track was added or updated successfully
     */
    suspend fun addOrMergeSystemTrack(track: Track): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val id3Data = readId3TagsForTrack(track)
                Log.d(TAG, "ID3 data for system track ${track.fileName}: $id3Data")
                
                val systemData = mapOf(
                    "title" to track.metadataTitle,
                    "artist" to track.metadataArtist,
                    "album" to track.metadataAlbum,
                    "genre" to track.metadataGenre
                )
                
                val existingTrack = track.fileSizeBytes?.let { fileSize ->
                    trackDao.findTrackByFilenameAndSize(track.fileName, fileSize)
                }
                
                val finalTrack = MetadataMerger.mergeIntoTrack(
                    existing = existingTrack,
                    id3Data = id3Data,
                    systemData = systemData,
                    csvData = emptyMap(),
                    newId = track.id,
                    relativePath = track.relativePath,
                    fileName = track.fileName,
                    bpmFallback = track.bpm,
                    fileSizeBytes = track.fileSizeBytes,
                    durationMs = track.durationMs,
                    source = track.source
                )
                
                if (existingTrack != null) {
                    trackDao.update(finalTrack)
                } else {
                    trackDao.insertOrReplace(finalTrack)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add/update system track: ${track.fileName}", e)
                false
            }
        }
    }
    
    /**
     * Check if a track exists by filename and file size (for duplicate detection)
     * and return the full existing track for merging
     */
    suspend fun findTrackByFilenameAndSizeOnce(fileName: String, fileSizeBytes: Long): Track? {
        return withContext(Dispatchers.IO) {
            trackDao.findTrackByFilenameAndSize(fileName, fileSizeBytes)
        }
    }
    
    // ==================== Metadata Editing Methods ====================

    private val metadataWorker: MetadataWorker by lazy {
        MetadataWorker(context, metadataEditor)
    }

    /**
     * Save BPM value to the audio file's metadata
     * 
     * @param trackId The ID of the track
     * @param bpm The BPM value to save
     * @return true if successful, false otherwise
     */
    suspend fun saveBpmToFileMetadata(trackId: String, bpm: Float): Boolean {
        return withContext(Dispatchers.IO) {
            val track = trackDao.getTrackById(trackId).firstOrNull() ?: run {
                Log.e(TAG, "Track not found: $trackId")
                return@withContext false
            }

            val uri = Uri.parse(track.id)
            metadataEditor.saveBpmToFile(uri, bpm)
        }
    }

    /**
     * Read BPM value from the audio file's metadata (ID3 tag)
     * 
     * @param trackId The ID of the track
     * @return The BPM value if found, null otherwise
     */
    suspend fun readBpmFromFileMetadata(trackId: String): Float? {
        return withContext(Dispatchers.IO) {
            val track = trackDao.getTrackById(trackId).firstOrNull() ?: run {
                Log.e(TAG, "Track not found: $trackId")
                return@withContext null
            }

            val uri = Uri.parse(track.id)
            metadataEditor.readBpmFromFile(uri)
        }
    }

    /**
     * Sync BPM from database to file metadata for a specific track
     * 
     * @param trackId The ID of the track
     * @return true if successful, false otherwise
     */
    suspend fun syncBpmToFileMetadata(trackId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val track = trackDao.getTrackById(trackId).firstOrNull() ?: run {
                Log.e(TAG, "Track not found: $trackId")
                return@withContext false
            }

            val bpm = track.bpm ?: run {
                Log.w(TAG, "Track has no BPM set: $trackId")
                return@withContext false
            }

            saveBpmToFileMetadata(trackId, bpm)
        }
    }

    /**
     * Sync BPM from database to file metadata for all tracks
     * Uses parallel processing for better performance
     * 
     * @param concurrency Number of concurrent operations (default: 4)
     * @param onProgress Optional progress callback
     * @return Number of tracks successfully synced
     */
    suspend fun syncAllBpmToFileMetadata(
        concurrency: Int = MetadataWorker.DEFAULT_CONCURRENCY,
        onProgress: (MetadataWorker.MetadataProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val tracks = trackDao.getTracksWithKnownBpm().firstOrNull() ?: return@withContext 0
        
        var finalSuccessCount = 0
        
        metadataWorker.processTracksInParallel(
            tracks = tracks,
            concurrency = concurrency,
            onProgress = onProgress,
            onComplete = { result ->
                finalSuccessCount = result.successCount
                Log.i(TAG, "Synced BPM to file metadata for ${result.successCount} tracks, ${result.failedCount} failed")
            }
        )
        
        finalSuccessCount
    }
    
    /**
     * Check if a track's file can be modified for metadata editing
     * 
     * @param trackId The ID of the track
     * @return true if the file can be modified, false otherwise
     */
    suspend fun canModifyTrackFileMetadata(trackId: String): Boolean {
        return try {
            val track = trackDao.getTrackById(trackId).firstOrNull() ?: return false
            val uri = Uri.parse(track.id)
            metadataEditor.isFileWritable(uri) && metadataEditor.canHandleFile(uri)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if MediaMetadataEditor is available on this device
     */
    fun isMetadataEditingAvailable(): Boolean {
        return metadataEditor.isMediaMetadataEditorAvailable()
    }
}
