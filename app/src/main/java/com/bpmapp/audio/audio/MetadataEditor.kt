// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.bpmapp.audio.util.PermissionUtils
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.ID3v24Frames
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import java.io.File
import java.io.FileOutputStream

/**
 * Editor for modifying audio file metadata, including BPM information.
 *
 * Uses JAudiotagger to read and write tags (ID3 for MP3/WAV, Vorbis comments
 * for FLAC/OGG, MP4 atoms for M4A/AAC). Files are addressed by content URI;
 * when the URI is not directly writable, a temp copy is tagged and written
 * back through the ContentResolver. No broad storage permission is needed —
 * write access is obtained per file via MediaStore write requests.
 */
class MetadataEditor(private val context: Context) {
    
    companion object {
        private const val TAG = "MetadataEditor"
        
        // File extensions JAudiotagger can tag
        val TAGGABLE_EXTENSIONS = listOf("mp3", "flac", "ogg", "wav", "m4a", "aac")
        
        /**
         * Format a BPM value for storage in a tag: integer when whole,
         * decimal otherwise
         */
        fun formatBpmValue(bpm: Float): String {
            return if (bpm == bpm.toInt().toFloat()) bpm.toInt().toString() else bpm.toString()
        }
        
        // ID3v2 TXXX (user-defined text) frame description used for RunBeat flags
        const val FLAGS_TXXX_DESCRIPTION = "RunBeat"
        
        // Prefix used when the flags are stored in the COMMENT field instead
        // (formats without ID3v2 tags, or when TXXX writing is not reliable)
        const val FLAGS_COMMENT_PREFIX = "RunBeat:"
        
        /**
         * Format the notForRunning/isFavorite flags for storage in a tag:
         * "NFR=1;FAV=1"
         */
        fun formatFlags(notForRunning: Boolean, isFavorite: Boolean): String {
            return "NFR=${if (notForRunning) 1 else 0};FAV=${if (isFavorite) 1 else 0}"
        }
        
        /**
         * Parse a flags string ("NFR=1;FAV=1").
         * @return Pair of (notForRunning, isFavorite), or null when no flag is present
         */
        fun parseFlags(raw: String?): Pair<Boolean, Boolean>? {
            if (raw.isNullOrBlank()) return null
            var notForRunning: Boolean? = null
            var isFavorite: Boolean? = null
            for (part in raw.split(';')) {
                val kv = part.split('=', limit = 2)
                if (kv.size != 2) continue
                val value = kv[1].trim() == "1" || kv[1].trim().equals("true", ignoreCase = true)
                when (kv[0].trim().uppercase()) {
                    "NFR" -> notForRunning = value
                    "FAV" -> isFavorite = value
                }
            }
            if (notForRunning == null && isFavorite == null) return null
            return (notForRunning ?: false) to (isFavorite ?: false)
        }
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
            if (!PermissionUtils.canWriteUriNow(context, uri)) {
                Log.w(TAG, "No write access to file: $uri")
                return false
            }
            writeTagsToUri(uri, bpm = bpm, flags = null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save BPM to file: $uri", e)
            false
        }
    }
    
    /**
     * Save the notForRunning/isFavorite flags to an audio file's metadata
     *
     * @param uri The URI of the audio file (file:// or content://)
     * @param notForRunning The "not for running" flag to save
     * @param isFavorite The favorite flag to save
     * @return true if successful, false otherwise
     */
    suspend fun saveFlagsToFile(uri: Uri, notForRunning: Boolean, isFavorite: Boolean): Boolean {
        return try {
            if (!PermissionUtils.canWriteUriNow(context, uri)) {
                Log.w(TAG, "No write access to file: $uri")
                return false
            }
            writeTagsToUri(uri, bpm = null, flags = notForRunning to isFavorite)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save flags to file: $uri", e)
            false
        }
    }
    
    /**
     * Save BPM and the notForRunning/isFavorite flags to an audio file's
     * metadata in a single tag pass.
     *
     * @param uri The URI of the audio file (file:// or content://)
     * @param bpm The BPM value to save
     * @param notForRunning The "not for running" flag to save
     * @param isFavorite The favorite flag to save
     * @return true if successful, false otherwise
     */
    suspend fun saveBpmAndFlagsToFile(
        uri: Uri,
        bpm: Float,
        notForRunning: Boolean,
        isFavorite: Boolean
    ): Boolean {
        return try {
            if (!PermissionUtils.canWriteUriNow(context, uri)) {
                Log.w(TAG, "No write access to file: $uri")
                return false
            }
            writeTagsToUri(uri, bpm = bpm, flags = notForRunning to isFavorite)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save BPM+flags to file: $uri", e)
            false
        }
    }
    
    /**
     * Write the BPM tag using JAudiotagger.
     *
     * For file:// URIs the file is tagged in place. For content:// URIs the
     * file is copied to a temp file, tagged, and written back through the
     * ContentResolver (truncating write).
     */
    private fun writeTagsToUri(uri: Uri, bpm: Float?, flags: Pair<Boolean, Boolean>?): Boolean {
        return if (uri.scheme == "file") {
            val file = File(uri.path ?: return false)
            if (!file.exists() || !file.canWrite()) {
                Log.e(TAG, "File not writable: $uri")
                return false
            }
            writeTagTo(file, bpm, flags)
        } else {
            val tempPath = UriUtils.copyUriToTempFile(
                context, uri,
                getExtension(uri) ?: "bin"
            ) ?: run {
                Log.e(TAG, "Could not copy file for tagging: $uri")
                return false
            }
            val tempFile = File(tempPath)
            try {
                if (!writeTagTo(tempFile, bpm, flags)) return false
                writeTempFileBackToUri(tempFile, uri)
            } finally {
                tempFile.delete()
            }
        }
    }
    
    /**
     * Apply the BPM and/or RunBeat flags to a local file with JAudiotagger
     */
    private fun writeTagTo(file: File, bpm: Float?, flags: Pair<Boolean, Boolean>?): Boolean {
        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            if (bpm != null) {
                tag.setField(FieldKey.BPM, formatBpmValue(bpm))
            }
            if (flags != null) {
                writeFlagsToTag(tag, flags.first, flags.second)
            }
            audioFile.commit()
            Log.d(TAG, "Saved tags (bpm=$bpm, flags=$flags) to file: ${file.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Tag write failed for ${file.name}", e)
            false
        }
    }
    
    /**
     * Write the RunBeat flags into a tag. For ID3v2 tags (v2.3/v2.4) a TXXX
     * (user-defined text) frame with description "RunBeat" is used; other
     * formats fall back to the COMMENT field with a "RunBeat:" prefix.
     */
    private fun writeFlagsToTag(tag: Tag, notForRunning: Boolean, isFavorite: Boolean) {
        val value = formatFlags(notForRunning, isFavorite)
        if (tag is AbstractID3v2Tag && tag !is ID3v22Tag) {
            // Drop a previous RunBeat TXXX frame but keep other TXXX frames
            val kept = tag.getFields(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO).filterNot { field ->
                ((field as? AbstractID3v2Frame)?.body as? FrameBodyTXXX)?.description == FLAGS_TXXX_DESCRIPTION
            }
            tag.removeFrame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
            kept.forEach { tag.addField(it) }
            val body = FrameBodyTXXX()
            body.description = FLAGS_TXXX_DESCRIPTION
            body.text = value
            val frame = tag.createFrame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
            frame.body = body
            tag.addField(frame)
        } else {
            tag.setField(FieldKey.COMMENT, FLAGS_COMMENT_PREFIX + value)
        }
    }
    
    /**
     * Write the tagged temp file back over the content URI
     */
    private fun writeTempFileBackToUri(tempFile: File, uri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.flush()
            } != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write back to URI: $uri", e)
            false
        }
    }
    
    /**
     * Read BPM value from an audio file's metadata
     *
     * @param uri The URI of the audio file
     * @return The BPM value if found, null otherwise
     */
    suspend fun readBpmFromFile(uri: Uri): Float? {
        return try {
            readBpmWithJaadiotagger(uri) ?: readBpmFallback(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read BPM from file: $uri", e)
            null
        }
    }
    
    /**
     * Read the BPM field with JAudiotagger (temp copy for content URIs)
     */
    private fun readBpmWithJaadiotagger(uri: Uri): Float? {
        return withLocalFile(uri) { file ->
            try {
                val tag = AudioFileIO.read(file).tag
                val bpmString = tag?.getFirst(FieldKey.BPM)
                bpmString?.toFloatOrNull()
            } catch (e: Exception) {
                Log.d(TAG, "Could not read BPM tag from $uri", e)
                null
            }
        }
    }
    
    /**
     * Read the RunBeat flags (notForRunning, isFavorite) from an audio file's
     * metadata (ID3v2 TXXX frame, or COMMENT fallback).
     *
     * @param uri The URI of the audio file
     * @return Pair of (notForRunning, isFavorite), or null when no flags are stored
     */
    suspend fun readFlagsFromFile(uri: Uri): Pair<Boolean, Boolean>? {
        return try {
            withLocalFile(uri) { file ->
                try {
                    val tag = AudioFileIO.read(file).tag ?: return@withLocalFile null
                    readFlagsFromTag(tag)
                } catch (e: Exception) {
                    Log.d(TAG, "Could not read flags tag from $uri", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read flags from file: $uri", e)
            null
        }
    }
    
    /**
     * Read the RunBeat flags from a tag: ID3v2 TXXX frame first, then the
     * COMMENT fallback.
     */
    private fun readFlagsFromTag(tag: Tag): Pair<Boolean, Boolean>? {
        if (tag is AbstractID3v2Tag) {
            for (field in tag.getFields(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)) {
                val body = (field as? AbstractID3v2Frame)?.body as? FrameBodyTXXX
                if (body?.description == FLAGS_TXXX_DESCRIPTION) {
                    parseFlags(body.text)?.let { return it }
                }
            }
        }
        val comment = try {
            tag.getFirst(FieldKey.COMMENT)
        } catch (e: Exception) {
            null
        }
        if (!comment.isNullOrBlank() && comment.startsWith(FLAGS_COMMENT_PREFIX)) {
            return parseFlags(comment.removePrefix(FLAGS_COMMENT_PREFIX))
        }
        return null
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
            val viaTags = readCompleteMetadataWithJaudiotagger(uri)
            if (viaTags["title"] != null || viaTags["artist"] != null ||
                viaTags["album"] != null || viaTags["bpm"] != null) {
                viaTags
            } else {
                readCompleteMetadataWithRetriever(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read complete metadata from file: $uri", e)
            emptyMap()
        }
    }
    
    /**
     * Read metadata fields with JAudiotagger (temp copy for content URIs)
     */
    private fun readCompleteMetadataWithJaudiotagger(uri: Uri): Map<String, String?> {
        return withLocalFile(uri) { file ->
            try {
                val tag = AudioFileIO.read(file).tag ?: return@withLocalFile emptyMap()
                mapOf(
                    "title" to tag.getFirst(FieldKey.TITLE).takeIf { it.isNotBlank() },
                    "artist" to tag.getFirst(FieldKey.ARTIST).takeIf { it.isNotBlank() },
                    "album" to tag.getFirst(FieldKey.ALBUM).takeIf { it.isNotBlank() },
                    "genre" to tag.getFirst(FieldKey.GENRE).takeIf { it.isNotBlank() },
                    "bpm" to tag.getFirst(FieldKey.BPM).takeIf { it.isNotBlank() }
                )
            } catch (e: Exception) {
                Log.d(TAG, "Could not read tags from $uri", e)
                emptyMap()
            }
        }
    }
    
    /**
     * Read metadata from a file using MediaMetadataRetriever (works on all
     * Android versions). Fallback when the file cannot be parsed as a taggable
     * audio file.
     */
    private fun readCompleteMetadataWithRetriever(uri: Uri): Map<String, String?> {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            
            val result = mutableMapOf<String, String?>()
            
            result["title"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            result["artist"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            result["album"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            result["genre"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            result["bpm"] = null
            
            // Extract CD track number (returns strings like "3/12")
            try {
                result["trackNumber"] = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER
                )
            } catch (_: Exception) { }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "MediaMetadataRetriever metadata read failed for $uri", e)
            emptyMap()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release MediaMetadataRetriever", e)
            }
        }
    }
    
    /**
     * Read BPM using MediaMetadataRetriever (no BPM key exists in the
     * retriever, so this always returns null; kept for API compatibility)
     */
    private fun readBpmFallback(uri: Uri): Float? {
        return null
    }
    
    /**
     * Run a block against a local File for the given URI. For content://
     * URIs the file is copied to a temp file first; the temp file is deleted
     * afterwards.
     */
    private fun <T> withLocalFile(uri: Uri, block: (File) -> T): T {
        return if (uri.scheme == "file") {
            block(File(uri.path ?: throw IllegalArgumentException("No path in URI: $uri")))
        } else {
            val tempPath = UriUtils.copyUriToTempFile(
                context, uri,
                getExtension(uri) ?: "bin"
            ) ?: throw IllegalArgumentException("Could not copy URI to temp file: $uri")
            val tempFile = File(tempPath)
            try {
                block(tempFile)
            } finally {
                tempFile.delete()
            }
        }
    }
    
    /**
     * Get the file extension for a URI from its display name
     */
    private fun getExtension(uri: Uri): String? {
        val displayName = UriUtils.getDisplayName(context, uri) ?: return null
        return displayName.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
    }
    
    /**
     * Check if a file is writable for metadata modification
     */
    fun isFileWritable(uri: Uri): Boolean {
        return PermissionUtils.canWriteUriNow(context, uri)
    }
    
    /**
     * Check if the editor can handle the given file type
     */
    fun canHandleFile(uri: Uri): Boolean {
        val extension = getExtension(uri)
            ?: return uri.scheme == "file" && File(uri.path ?: return false).extension.isNotEmpty()
        return TAGGABLE_EXTENSIONS.contains(extension)
    }
    
    /**
     * Tag editing is available on all supported Android versions
     * (JAudiotagger is a plain Java library bundled with the app)
     */
    fun isTagEditingAvailable(): Boolean {
        return true
    }
}
