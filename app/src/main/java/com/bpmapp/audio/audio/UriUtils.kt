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