// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.bpmapp.audio.util.PermissionUtils
import java.io.File

/**
 * Editor for modifying audio file metadata, including BPM information.
 * Uses Android's MediaMetadataEditor on Android 13+ (API 33+) for ID3 tag modification.
 * 
 * For Android versions below 13, this class provides reading support via
 * MediaMetadataRetriever but writing operations will return false.
 */
class MetadataEditor(private val context: Context) {
    
    companion object {
        private const val TAG = "MetadataEditor"
        
        // Minimum Android version for MediaMetadataEditor
        const val MIN_API_FOR_MEDIA_METADATA_EDITOR = Build.VERSION_CODES.TIRAMISU
    }
    
    /**
     * Check if MediaMetadataEditor is available on this device
     */
    fun isMediaMetadataEditorAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= MIN_API_FOR_MEDIA_METADATA_EDITOR
    }
    
    /**
     * Save BPM value to an audio file's metadata
     * 
     * @param uri The URI of the audio file (file:// or content://)
     * @param bpm The BPM value to save
     * @return true if successful, false otherwise
     */
    suspend fun saveBpmToFile(uri: Uri, bpm: Float): Boolean {
        return try {
            if (!PermissionUtils.canModifyFile(context, uri)) {
                Log.w(TAG, "No permission to modify file: $uri")
                return false
            }
            
            if (isMediaMetadataEditorAvailable()) {
                saveBpmWithMediaMetadataEditor(uri, bpm)
            } else {
                // Fallback for older Android versions
                saveBpmFallback(uri, bpm)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save BPM to file: $uri", e)
            false
        }
    }
    
    /**
     * Save BPM using MediaMetadataEditor (Android 13+)
     * This will use reflection to access MediaMetadataEditor since it's only available on API 33+
     */
    private suspend fun saveBpmWithMediaMetadataEditor(uri: Uri, bpm: Float): Boolean {
        return try {
            // Get the actual file path for the URI
            val filePath = getFilePathForUri(uri) ?: run {
                Log.e(TAG, "Could not resolve file path for URI: $uri")
                return false
            }
            
            val file = File(filePath)
            if (!file.exists() || !file.canWrite()) {
                Log.e(TAG, "File not writable: $filePath")
                return false
            }
            
            // Use reflection to access MediaMetadataEditor
            // This is necessary because MediaMetadataEditor is only available on API 33+
            try {
                val mediaMetadataClass = Class.forName("androidx.media3.common.MediaMetadata")
                val editorClass = Class.forName("androidx.media3.common.MediaMetadataEditor")
                
                // Create MediaMetadata with BPM
                val builderClass = Class.forName("androidx.media3.common.MediaMetadata\$Builder")
                val metadataBuilder = builderClass.getDeclaredConstructor().newInstance()
                val setTempoMethod = metadataBuilder.javaClass.getMethod("setTempo", Float::class.javaPrimitiveType)
                setTempoMethod.invoke(metadataBuilder, bpm)
                
                val buildMethod = metadataBuilder.javaClass.getMethod("build")
                val metadata = buildMethod.invoke(metadataBuilder)
                
                // Create editor and apply metadata
                val createFromFileMethod = editorClass.getMethod("createFromFile", File::class.java)
                val editor = createFromFileMethod.invoke(null, file)
                
                val setMetadataMethod = editor.javaClass.getMethod("setMetadata", mediaMetadataClass)
                setMetadataMethod.invoke(editor, metadata)
                
                val applyMethod = editor.javaClass.getMethod("apply")
                applyMethod.invoke(editor)
                
                Log.d(TAG, "Successfully saved BPM $bpm to file: $filePath")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "MediaMetadataEditor reflection failed", e)
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "MediaMetadataEditor failed", e)
            false
        }
    }
    
    /**
     * Fallback method for saving BPM on older Android versions
     * This is a placeholder - actual implementation would require a library like jaudiotagger
     */
    private suspend fun saveBpmFallback(uri: Uri, bpm: Float): Boolean {
        Log.w(TAG, "MediaMetadataEditor not available on this Android version (${Build.VERSION.SDK_INT}). " +
              "Consider using jaudiotagger library for full ID3 tag support.")
        
        // For now, just log that we would save it
        Log.d(TAG, "Would save BPM $bpm to: $uri")
        
        // TODO: Implement with jaudiotagger if needed for older Android versions
        return false
    }
    
    /**
     * Read BPM value from an audio file's metadata
     * 
     * @param uri The URI of the audio file
     * @return The BPM value if found, null otherwise
     */
    suspend fun readBpmFromFile(uri: Uri): Float? {
        return try {
            if (isMediaMetadataEditorAvailable()) {
                readBpmWithMediaMetadataEditor(uri)
            } else {
                readBpmFallback(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read BPM from file: $uri", e)
            null
        }
    }
    
    /**
     * Read BPM using MediaMetadataEditor (Android 13+)
     */
    private suspend fun readBpmWithMediaMetadataEditor(uri: Uri): Float? {
        return try {
            val filePath = getFilePathForUri(uri) ?: run {
                Log.e(TAG, "Could not resolve file path for URI: $uri")
                return null
            }
            
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "File not readable: $filePath")
                return null
            }
            
            // Use reflection to access MediaMetadataEditor
            try {
                val mediaMetadataClass = Class.forName("androidx.media3.common.MediaMetadata")
                val editorClass = Class.forName("androidx.media3.common.MediaMetadataEditor")
                
                // Create editor to read metadata
                val createFromFileMethod = editorClass.getMethod("createFromFile", File::class.java)
                val editor = createFromFileMethod.invoke(null, file)
                
                val getMetadataMethod = editor.javaClass.getMethod("getMetadata")
                val metadata = getMetadataMethod.invoke(editor) as? Any
                
                if (metadata != null) {
                    val getTempoMethod = metadata.javaClass.getMethod("getTempo")
                    val tempo = getTempoMethod.invoke(metadata) as? Float
                    
                    if (tempo != null && tempo > 0) {
                        return tempo
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "MediaMetadataEditor read reflection failed", e)
            }
            
            null
            
        } catch (e: Exception) {
            Log.e(TAG, "MediaMetadataEditor read failed", e)
            null
        }
    }
    
    /**
     * Fallback method for reading BPM on older Android versions
     */
    private suspend fun readBpmFallback(uri: Uri): Float? {
        Log.w(TAG, "MediaMetadataEditor not available for reading on this Android version")
        val metadata = readCompleteMetadata(uri)
        return metadata["bpm"]?.toFloatOrNull()
    }
    
    /**
     * Read all metadata (title, artist, album, genre, bpm) from an audio file.
     *
     * @param uri The URI of the audio file (file:// or content://)
     * @return Map with keys "title", "artist", "album", "genre", "bpm".
     *         Missing/unknown fields are null. bpm value is stored as a String (nullable).
     */
    suspend fun readCompleteMetadata(uri: Uri): Map<String, String?> {
        return try {
            if (isMediaMetadataEditorAvailable()) {
                val viaEditor = readCompleteMetadataWithMediaMetadataEditor(uri)
                if (viaEditor.isNotEmpty()) {
                    viaEditor
                } else {
                    readCompleteMetadataWithRetriever(uri)
                }
            } else {
                // Fallback for older Android versions using MediaMetadataRetriever
                readCompleteMetadataWithRetriever(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read complete metadata from file: $uri", e)
            emptyMap()
        }
    }
    
    /**
     * Read all metadata from a file using reflection on MediaMetadataEditor (Android 13+)
     */
    private suspend fun readCompleteMetadataWithMediaMetadataEditor(uri: Uri): Map<String, String?> {
        return try {
            val filePath = getFilePathForUri(uri) ?: run {
                Log.e(TAG, "Could not resolve file path for URI: $uri")
                return emptyMap()
            }
            
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "File not readable: $filePath")
                return emptyMap()
            }
            
            // Use reflection to access MediaMetadataEditor for reading
            try {
                val mediaMetadataClass = Class.forName("androidx.media3.common.MediaMetadata")
                val editorClass = Class.forName("androidx.media3.common.MediaMetadataEditor")
                
                // Create editor to read metadata
                val createFromFileMethod = editorClass.getMethod("createFromFile", File::class.java)
                val editor = createFromFileMethod.invoke(null, file)
                
                val getMetadataMethod = editor.javaClass.getMethod("getMetadata")
                val metadata = getMetadataMethod.invoke(editor) as? Any
                
                val result = mutableMapOf<String, String?>()
                
                if (metadata != null) {
                    // Extract title
                    try {
                        val getTitleMethod = metadata.javaClass.getMethod("getTitle")
                        result["title"] = getTitleMethod.invoke(metadata) as? String
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to read title", e)
                    }
                    
                    // Extract artist
                    try {
                        val getArtistMethod = metadata.javaClass.getMethod("getArtist")
                        result["artist"] = getArtistMethod.invoke(metadata) as? String
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to read artist", e)
                    }
                    
                    // Extract album
                    try {
                        val getAlbumTitleMethod = metadata.javaClass.getMethod("getAlbumTitle")
                        result["album"] = getAlbumTitleMethod.invoke(metadata) as? String
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to read album", e)
                    }
                    
                    // Extract genre
                    try {
                        val getGenreMethod = metadata.javaClass.getMethod("getGenre")
                        result["genre"] = getGenreMethod.invoke(metadata) as? String
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to read genre", e)
                    }
                    
                    // Extract bpm, preferring getTempo() then getBpm()
                    try {
                        val tempo = try {
                            metadata.javaClass.getMethod("getTempo").invoke(metadata) as? Float
                        } catch (e: Exception) {
                            null
                        }
                        val bpm = tempo ?: try {
                            (metadata.javaClass.getMethod("getBpm").invoke(metadata) as? Number)?.toFloat()
                        } catch (e: Exception) {
                            null
                        }
                        result["bpm"] = bpm?.toString()
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to read BPM", e)
                    }
                }
                
                return result
                
            } catch (e: Exception) {
                Log.e(TAG, "MediaMetadataEditor complete metadata read reflection failed", e)
                return emptyMap()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "MediaMetadataEditor complete metadata read failed", e)
            return emptyMap()
        }
    }
    
    /**
     * Read metadata from a file using MediaMetadataRetriever (works on all Android versions)
     * Fallback for Android < 33 devices and for files MediaMetadataEditor cannot parse.
     */
    private suspend fun readCompleteMetadataWithRetriever(uri: Uri): Map<String, String?> {
        return try {
            val filePath = getFilePathForUri(uri) ?: run {
                Log.e(TAG, "Could not resolve file path for URI: $uri")
                return emptyMap()
            }
            
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                
                val result = mutableMapOf<String, String?>()
                
                // Extract title
                try {
                    result["title"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to read title via MediaMetadataRetriever", e)
                }
                
                // Extract artist
                try {
                    result["artist"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to read artist via MediaMetadataRetriever", e)
                }
                
                // Extract album
                try {
                    result["album"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to read album via MediaMetadataRetriever", e)
                }
                
                // Extract genre
                try {
                    result["genre"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to read genre via MediaMetadataRetriever", e)
                }
                
                // Extract bpm (only available on Android 14+ via MediaMetadataRetriever)
                // BPM is generally read through the MediaMetadataEditor path on API 33+.
                result["bpm"] = null
                
                return result
                
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to release MediaMetadataRetriever", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaMetadataRetriever metadata read failed for $uri", e)
            return emptyMap()
        }
    }
    
    /**
     * Get the actual file path for a URI
     */
    private fun getFilePathForUri(uri: Uri): String? {
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> UriUtils.getFilePath(context, uri)
            else -> null
        }
    }
    
    /**
     * Check if a file is writable for metadata modification
     */
    fun isFileWritable(uri: Uri): Boolean {
        return try {
            val filePath = getFilePathForUri(uri) ?: return false
            val file = File(filePath)
            file.exists() && file.canWrite()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if the editor can handle the given file type
     */
    fun canHandleFile(uri: Uri): Boolean {
        return try {
            val filePath = getFilePathForUri(uri) ?: return false
            val file = File(filePath)
            
            if (!file.exists()) return false
            
            // Check file extension
            val extension = file.extension.lowercase()
            val supportedExtensions = listOf("mp3", "flac", "ogg", "wav", "m4a", "aac")
            
            supportedExtensions.contains(extension)
        } catch (e: Exception) {
            false
        }
    }
}