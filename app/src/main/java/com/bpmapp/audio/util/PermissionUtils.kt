// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Utility class for handling runtime permissions, especially for file access
 * needed for modifying audio file metadata (ID3 tags).
 *
 * Storage model:
 * - Android 11+ (API 30+): no broad storage permission. Write access to individual
 *   files is obtained per-URI via [MediaStore.createWriteRequest], which shows a
 *   system dialog asking the user to grant write access to the listed files.
 * - Android 10 and below: WRITE_EXTERNAL_STORAGE runtime permission.
 */
object PermissionUtils {
    
    // Permission constants
    const val WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
    const val READ_MEDIA_AUDIO = Manifest.permission.READ_MEDIA_AUDIO
    
    // Request codes
    const val REQUEST_CODE_WRITE_STORAGE = 1002
    const val REQUEST_CODE_READ_MEDIA = 1003
    
    /**
     * Check if the app has the legacy storage permission used for file
     * modification on Android 10 and below.
     */
    fun hasLegacyStoragePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if the app has the read permission needed to scan the audio library.
     */
    fun hasReadMediaAudioPermission(context: Context): Boolean {
        // READ_MEDIA_AUDIO only exists on Android 13+ (API 33); older devices use READ_EXTERNAL_STORAGE
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if a URI can be written right now, by probing it.
     *
     * On Android 11+ this is true when the app owns the file or holds a write
     * grant for it. On Android 10 and below it requires the legacy storage
     * permission (or app-internal storage).
     */
    fun canWriteUriNow(context: Context, uri: Uri): Boolean {
        return try {
            when {
                uri.scheme == "file" -> {
                    val path = uri.path ?: return false
                    if (path.startsWith(context.filesDir.absolutePath) ||
                        path.startsWith(context.cacheDir.absolutePath)) return true
                    File(path).canWrite()
                }
                uri.scheme == "content" -> {
                    context.contentResolver.openFileDescriptor(uri, "rw")?.use {
                        it.fileDescriptor.valid()
                    } ?: false
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check whether writing to the given URIs requires an explicit user grant:
     * a MediaStore write request on Android 11+, or the legacy storage
     * permission on Android 10 and below.
     *
     * @return true when a grant should be requested before writing
     */
    fun needsWriteGrant(context: Context, uris: Collection<Uri>): Boolean {
        if (uris.isEmpty()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            uris.any { !canWriteUriNow(context, it) }
        } else {
            uris.any { uri ->
                val path = uri.path
                val isAppInternal = path != null && (
                    path.startsWith(context.filesDir.absolutePath) ||
                    path.startsWith(context.cacheDir.absolutePath))
                !isAppInternal && !hasLegacyStoragePermission(context)
            }
        }
    }
    
    /**
     * Create an IntentSender for a MediaStore write request covering the given
     * URIs (Android 11+ only). Launching it shows a system dialog asking the
     * user to grant write access to those files.
     *
     * @return the IntentSender to launch, or null when unavailable (all URIs
     *         writable, or Android 10 and below — use the legacy permission)
     */
    fun createWriteRequestIntentSender(
        context: Context,
        uris: Collection<Uri>
    ): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return try {
            MediaStore.createWriteRequest(context.contentResolver, uris).intentSender
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if the app can modify files at the given URI
     */
    fun canModifyFile(context: Context, uri: Uri): Boolean {
        return when {
            // File URIs in app storage can always be modified
            uri.scheme == "file" && uri.path?.startsWith(context.filesDir.absolutePath) == true -> true
            uri.scheme == "file" && uri.path?.startsWith(context.cacheDir.absolutePath) == true -> true
            uri.scheme == "file" -> File(uri.path ?: return false).canWrite()
            uri.scheme == "content" -> canWriteUriNow(context, uri)
            else -> false
        }
    }
    
    /**
     * Request WRITE_EXTERNAL_STORAGE permission (Android 10 and below)
     */
    fun requestLegacyStoragePermission(activity: Activity, requestCode: Int = REQUEST_CODE_WRITE_STORAGE) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(activity, WRITE_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                    arrayOf(WRITE_EXTERNAL_STORAGE),
                    requestCode
                )
            }
        }
    }
    
    /**
     * Request READ_MEDIA_AUDIO permission (Android 13+)
     */
    fun requestReadMediaAudioPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(activity, permission)
                != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                    arrayOf(permission),
                    REQUEST_CODE_READ_MEDIA
                )
            }
        }
    }
    
    /**
     * Check if external storage is available for writing
     */
    fun isExternalStorageWritable(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }
    
    /**
     * Create a permission request launcher for use with Activity Result API
     */
    fun createPermissionLauncher(
        activity: AppCompatActivity,
        onPermissionGranted: () -> Unit,
        onPermissionDenied: () -> Unit = {}
    ) = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onPermissionGranted()
        } else {
            onPermissionDenied()
        }
    }
    
    /**
     * Create a multiple permission request launcher
     */
    fun createMultiplePermissionLauncher(
        activity: AppCompatActivity,
        onAllGranted: () -> Unit,
        onSomeDenied: (List<String>) -> Unit = {}
    ) = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val deniedPermissions = permissions.filter { !it.value }.keys.toList()
        if (deniedPermissions.isEmpty()) {
            onAllGranted()
        } else {
            onSomeDenied(deniedPermissions)
        }
    }
}

/**
 * Result of checking write access for a set of track URIs.
 */
sealed class WriteAccess {
    /** All URIs are already writable */
    object Granted : WriteAccess()
    
    /** Android 10 and below: the WRITE_EXTERNAL_STORAGE runtime permission must be requested */
    object LegacyPermissionNeeded : WriteAccess()
    
    /** Android 11+: a MediaStore write request should be launched for the URIs */
    data class WriteRequest(val intentSender: IntentSender) : WriteAccess()
    
    /** Write access cannot be obtained */
    object Denied : WriteAccess()
}
