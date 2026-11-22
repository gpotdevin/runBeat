// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Utility functions for handling URIs, particularly for extracting display names
 */
object UriUtils {
    private const val TAG = "UriUtils"

    /**
     * Get display name from a content:// URI
     */
    fun getDisplayName(context: Context, uri: Uri): String? {
        return try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting display name", e)
            null
        }
    }
    
    /**
     * Get actual file path from a content:// URI using MediaStore
     * Returns the file path, or null if not found
     */
    fun getFilePath(context: Context, uri: Uri): String? {
        return try {
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    cursor.getString(columnIndex)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file path from URI", e)
            null
        }
    }

    /**
     * Resolve an absolute file path to its MediaStore audio content URI by
     * matching the DATA column. Returns null when the path is not in the
     * media database (e.g. app-private storage).
     */
    fun getAudioUriForFilePath(context: Context, filePath: String): Uri? {
        return try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                MediaStore.Audio.Media.DATA + " = ?",
                arrayOf(filePath),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving file path to audio URI: $filePath", e)
            null
        }
    }

    /**
     * Map a track URI to the MediaStore audio content URI when possible.
     * Tracks imported from CSV carry file:// URIs; MediaStore write requests
     * only accept content:// URIs, and scoped storage (Android 11+) blocks
     * direct file-path writes anyway, so those must go through the content
     * URI. URIs that are already content:// (or cannot be resolved) are
     * returned unchanged.
     */
    fun toMediaStoreAudioUri(context: Context, uri: Uri): Uri {
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return uri
        return getAudioUriForFilePath(context, path) ?: uri
    }

    /**
     * Check whether a content URI still resolves in the MediaStore audio
     * database. Stale URIs (file deleted from storage but still referenced by
     * the app database) make MediaStore.createWriteRequest reject the whole
     * request with "Invalid Uri", so they must be filtered out beforehand.
     */
    fun existsInMediaStore(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return true
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Copy a content:// URI to a temporary file for processing
     * Returns the path to the temporary file, or null if copy failed
     */
    fun copyUriToTempFile(context: Context, uri: Uri, suffix: String = "mp3"): String? {
        return try {
            val tempFile = File.createTempFile("bpm_temp_", ".$suffix", context.cacheDir)
            tempFile.deleteOnExit()
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            if (tempFile.exists() && tempFile.length() > 0) {
                tempFile.absolutePath
            } else {
                Log.e(TAG, "Failed to copy URI to temp file")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying URI to temp file", e)
            null
        }
    }
}