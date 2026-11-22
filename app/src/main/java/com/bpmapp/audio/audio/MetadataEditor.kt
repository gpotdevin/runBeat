// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.bpmapp.audio.R
import com.bpmapp.audio.util.PermissionUtils
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.CannotWriteException
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
     * @return the detailed [TagWriteResult] describing success or the failure reason
     */
    suspend fun saveBpmToFile(uri: Uri, bpm: Float): TagWriteResult {
        if (!isUriWritableNow(uri)) {
            Log.w(TAG, "No write access to file: $uri")
            return TagWriteResult.NoWritePermission(uri)
        }
        return try {
            writeTagsToUri(uri, bpm = bpm, flags = null)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while saving BPM to file: $uri", e)
            TagWriteResult.SecurityError(e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save BPM to file: $uri", e)
            TagWriteResult.Other("${e.javaClass.simpleName}: ${e.message}")
        }
    }
    
    /**
     * Save the notForRunning/isFavorite flags to an audio file's metadata
     *
     * @param uri The URI of the audio file (file:// or content://)
     * @param notForRunning The "not for running" flag to save
     * @param isFavorite The favorite flag to save
     * @return the detailed [TagWriteResult] describing success or the failure reason
     */
    suspend fun saveFlagsToFile(uri: Uri, notForRunning: Boolean, isFavorite: Boolean): TagWriteResult {
        if (!isUriWritableNow(uri)) {
            Log.w(TAG, "No write access to file: $uri")
            return TagWriteResult.NoWritePermission(uri)
        }
        return try {
            writeTagsToUri(uri, bpm = null, flags = notForRunning to isFavorite)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while saving flags to file: $uri", e)
            TagWriteResult.SecurityError(e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save flags to file: $uri", e)
            TagWriteResult.Other("${e.javaClass.simpleName}: ${e.message}")
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
     * @return the detailed [TagWriteResult] describing success or the failure reason
     */
    suspend fun saveBpmAndFlagsToFile(
        uri: Uri,
        bpm: Float,
        notForRunning: Boolean,
        isFavorite: Boolean
    ): TagWriteResult {
        if (!isUriWritableNow(uri)) {
            Log.w(TAG, "No write access to file: $uri")
            return TagWriteResult.NoWritePermission(uri)
        }
        return try {
            writeTagsToUri(uri, bpm = bpm, flags = notForRunning to isFavorite)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while saving BPM+flags to file: $uri", e)
            TagWriteResult.SecurityError(e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save BPM+flags to file: $uri", e)
            TagWriteResult.Other("${e.javaClass.simpleName}: ${e.message}")
        }
    }
    
    /**
     * Write the BPM tag using JAudiotagger.
     *
     * For file:// URIs the file is tagged in place. For content:// URIs the
     * absolute file path is first resolved via MediaStore.Audio.Media.DATA;
     * when a writable path is found the file is tagged IN PLACE, avoiding the
     * fragile temp-copy + writeback entirely. Only content:// URIs that
     * cannot be resolved to a writable file path fall back to copying the
     * file to a temp file, tagging it, and writing it back through the
     * ContentResolver (truncating write).
     */
    private fun writeTagsToUri(uri: Uri, bpm: Float?, flags: Pair<Boolean, Boolean>?): TagWriteResult {
        // On Android 11+ scoped storage blocks direct writes to file:// paths
        // in shared storage (File.canWrite() is false there even for media
        // files), and MediaStore write-request grants only apply to content
        // URIs. Route file:// URIs through their MediaStore content URI so the
        // grant + write-back path is used. Below Android 11 the legacy
        // storage permission makes the raw path writable, so tag in place.
        if (uri.scheme == "file" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val contentUri = UriUtils.toMediaStoreAudioUri(context, uri)
            if (contentUri != uri) {
                Log.d(TAG, "Routing file URI $uri through content URI $contentUri (scoped storage)")
                return writeTagsToUri(contentUri, bpm, flags)
            }
        }
        return if (uri.scheme == "file") {
            val file = File(uri.path ?: return TagWriteResult.Other("No path in URI: $uri"))
            if (!file.exists() || !file.canWrite()) {
                Log.e(TAG, "File not writable: $uri")
                return TagWriteResult.NoWritePermission(uri)
            }
            writeTagTo(file, bpm, flags)
        } else {
            // Preferred strategy: resolve the content URI to an absolute file
            // path and tag the real file in place when it is writable.
            val filePath = UriUtils.getFilePath(context, uri)
            if (filePath != null) {
                val file = File(filePath)
                if (file.exists() && file.canWrite()) {
                    Log.d(TAG, "Resolved $uri to file path $filePath; tagging in place")
                    return writeTagTo(file, bpm, flags)
                }
                Log.d(TAG, "Resolved $uri to $filePath but it is missing or not writable; " +
                    "falling back to temp-copy write-back")
            } else {
                Log.d(TAG, "Could not resolve $uri to a file path; using temp-copy write-back")
            }
            // Fallback: temp copy + write-back through the ContentResolver
            val extension = getExtension(uri)
            if (extension == null) {
                Log.e(TAG, "Cannot determine file extension for $uri (display name and MIME type unavailable); cannot tag")
            }
            val tempPath = UriUtils.copyUriToTempFile(
                context, uri,
                extension ?: "bin"
            ) ?: run {
                Log.e(TAG, "Could not copy file for tagging: $uri")
                return TagWriteResult.Other("Could not copy URI to temp file: $uri")
            }
            val tempFile = File(tempPath)
            Log.d(TAG, "Tagging temp copy ${tempFile.name} (${tempFile.length()} bytes, ext=${extension ?: "bin"}) for $uri")
            try {
                val tagResult = writeTagTo(tempFile, bpm, flags)
                if (tagResult !is TagWriteResult.Success) {
                    Log.e(TAG, "Tagging temp copy failed ($tagResult), aborting write-back for $uri")
                    return tagResult
                }
                writeTempFileBackToUri(tempFile, uri)
            } finally {
                tempFile.delete()
            }
        }
    }
    
    /**
     * Apply the BPM and/or RunBeat flags to a local file with JAudiotagger.
     * The returned [TagWriteResult] distinguishes a blocked write
     * (SecurityException), a JAudiotagger commit failure, and other errors.
     */
    private fun writeTagTo(file: File, bpm: Float?, flags: Pair<Boolean, Boolean>?): TagWriteResult {
        return try {
            val sizeBefore = file.length()
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            if (bpm != null) {
                tag.setField(FieldKey.BPM, formatBpmValue(bpm))
            }
            if (flags != null) {
                writeFlagsToTag(tag, flags.first, flags.second)
            }
            audioFile.commit()
            Log.d(TAG, "Saved tags (bpm=$bpm, flags=$flags) to file: ${file.name} " +
                "(${sizeBefore} -> ${file.length()} bytes, tag=${tag.javaClass.simpleName})")
            TagWriteResult.Success
        } catch (e: SecurityException) {
            Log.e(TAG, "Write blocked by system for ${file.name}: ${e.message}", e)
            TagWriteResult.SecurityError(e.message)
        } catch (e: CannotWriteException) {
            Log.e(TAG, "JAudiotagger commit failed for ${file.name} (${file.length()} bytes): " +
                "${e.javaClass.name}: ${e.message}", e)
            TagWriteResult.CommitFailed(e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Tag write failed for ${file.name} (${file.length()} bytes): " +
                "${e.javaClass.name}: ${e.message}", e)
            TagWriteResult.Other("${e.javaClass.simpleName}: ${e.message}")
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
     * Write the tagged temp file back over the content URI.
     *
     * The stream is opened with an explicit truncating mode ("wt"/"rwt",
     * falling back to "w"): some providers do not truncate on plain "w",
     * which would leave stale trailing bytes and corrupt the audio file
     * whenever the tagged copy is shorter than the original. After writing,
     * the resulting size is verified against the temp file size.
     */
    private fun writeTempFileBackToUri(tempFile: File, uri: Uri): TagWriteResult {
        val expectedSize = tempFile.length()
        var lastError: Exception? = null
        for (mode in listOf("wt", "rwt", "w")) {
            try {
                val output = context.contentResolver.openOutputStream(uri, mode) ?: run {
                    Log.e(TAG, "openOutputStream($mode) returned null for $uri")
                    return TagWriteResult.NoWritePermission(uri)
                }
                output.use { out ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                    out.flush()
                }
                Log.d(TAG, "Wrote $expectedSize bytes back to $uri (mode=$mode)")
                return when (val actual = queryContentSize(uri)) {
                    null -> {
                        Log.d(TAG, "Could not query SIZE for $uri after write-back; skipping size verification")
                        TagWriteResult.Success
                    }
                    expectedSize -> TagWriteResult.Success
                    else -> {
                        Log.e(TAG, "Size mismatch after write-back to $uri: expected $expectedSize, got $actual " +
                            "(stale bytes — the provider did not truncate)")
                        TagWriteResult.SizeMismatch(expectedSize, actual)
                    }
                }
            } catch (e: SecurityException) {
                // The grant check is identical for every mode; no point retrying.
                Log.e(TAG, "Write-back to $uri blocked by system (mode=$mode): ${e.message}", e)
                return TagWriteResult.SecurityError(e.message)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Write-back to $uri with mode=$mode failed: " +
                    "${e.javaClass.name}: ${e.message}")
            }
        }
        Log.e(TAG, "Failed to write back to URI: $uri (all modes failed); " +
            "last error: ${lastError?.javaClass?.name}: ${lastError?.message}", lastError)
        return TagWriteResult.Other("${lastError?.javaClass?.simpleName}: ${lastError?.message}")
    }

    /**
     * Query the size of the content behind [uri] after a write-back.
     * Returns null when the provider does not expose SIZE.
     */
    private fun queryContentSize(uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Size query for $uri failed: ${e.message}")
            null
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
     * Get the file extension for a URI, first from its display name, then
     * from the URI path, then from the MIME type. JAudiotagger picks the
     * tag format from the file extension, so a wrong/missing extension
     * ("bin") makes AudioFileIO.read() fail — this fallback prevents that.
     */
    private fun getExtension(uri: Uri): String? {
        UriUtils.getDisplayName(context, uri)
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= 5 }
            ?.let { return it }
        uri.path?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= 5 }
            ?.let { return it }
        return extensionFromMimeType(context.contentResolver.getType(uri))
    }

    /**
     * Map a content MIME type to a file extension JAudiotagger can handle
     */
    private fun extensionFromMimeType(mimeType: String?): String? {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when (mime) {
            "audio/mpeg", "audio/mp3", "audio/x-mpeg", "audio/x-mp3" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg", "application/ogg", "audio/vorbis", "audio/x-ogg" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "audio/vnd.wav" -> "wav"
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/mp4a-latm" -> "m4a"
            "audio/aac", "audio/x-aac", "audio/aacp" -> "aac"
            else -> null
        }
    }
    
    /**
     * Check if a URI can be written right now, either through the
     * ContentResolver (URI grant) or via its resolved absolute file path
     * (MediaStore.Audio.Media.DATA) when that file is directly writable.
     */
    private fun isUriWritableNow(uri: Uri): Boolean {
        if (PermissionUtils.canWriteUriNow(context, uri)) return true
        if (uri.scheme == "content") {
            val path = UriUtils.getFilePath(context, uri)
            if (path != null && File(path).canWrite()) return true
        }
        // file:// URIs (CSV-imported tracks): on Android 11+ the raw path is
        // not writable under scoped storage, but a write grant on the
        // equivalent MediaStore content URI covers the file.
        if (uri.scheme == "file") {
            val contentUri = UriUtils.toMediaStoreAudioUri(context, uri)
            if (contentUri != uri && PermissionUtils.canWriteUriNow(context, contentUri)) return true
        }
        return false
    }

    /**
     * Check if a file is writable for metadata modification
     */
    fun isFileWritable(uri: Uri): Boolean {
        return isUriWritableNow(uri)
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

/**
 * Detailed outcome of a tag write ([MetadataEditor.saveBpmToFile] and
 * friends), so callers can surface the actual failure reason to the user
 * instead of a silent boolean.
 */
sealed class TagWriteResult {
    /** The tag was written successfully */
    object Success : TagWriteResult()

    /** No write permission: a MediaStore write-request dialog grant is needed */
    data class NoWritePermission(val uri: Uri) : TagWriteResult()

    /** The system blocked the write (SecurityException) */
    data class SecurityError(val message: String?) : TagWriteResult()

    /** The content size after write-back did not match the tagged temp file */
    data class SizeMismatch(val expectedBytes: Long, val actualBytes: Long) : TagWriteResult()

    /** JAudiotagger could not commit the tag to the file */
    data class CommitFailed(val message: String?) : TagWriteResult()

    /** Any other failure */
    data class Other(val message: String?) : TagWriteResult()

    val isSuccess: Boolean get() = this is Success
}

/**
 * Map a [TagWriteResult] to a user-facing message that distinguishes the
 * failure reason (no write permission, SecurityException, size mismatch,
 * commit failure, other).
 */
fun TagWriteResult.userMessage(context: Context): String {
    return when (this) {
        is TagWriteResult.Success -> context.getString(R.string.info_bpm_saved_to_file)
        is TagWriteResult.NoWritePermission -> context.getString(R.string.error_tag_write_no_permission)
        is TagWriteResult.SecurityError -> context.getString(R.string.error_tag_write_security)
        is TagWriteResult.SizeMismatch -> context.getString(R.string.error_tag_write_size_mismatch)
        is TagWriteResult.CommitFailed -> context.getString(R.string.error_tag_write_commit_failed)
        is TagWriteResult.Other -> context.getString(R.string.error_tag_write_failed, message ?: "")
    }
}
