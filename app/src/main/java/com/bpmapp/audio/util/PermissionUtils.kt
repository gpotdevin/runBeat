// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.util

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Utility class for handling runtime permissions, especially for file access
 * needed for modifying audio file metadata (ID3 tags).
 */
// These permission constants are plain string literals. They reference constants that only
// exist on API 30/API 33+, but every usage of them is guarded by Build.VERSION.SDK_INT checks.
@SuppressLint("InlinedApi")
object PermissionUtils {
    
    // Permission constants
    const val MANAGE_EXTERNAL_STORAGE = Manifest.permission.MANAGE_EXTERNAL_STORAGE
    const val WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
    const val READ_MEDIA_AUDIO = Manifest.permission.READ_MEDIA_AUDIO
    
    // Request codes
    const val REQUEST_CODE_MANAGE_STORAGE = 1001
    const val REQUEST_CODE_WRITE_STORAGE = 1002
    const val REQUEST_CODE_READ_MEDIA = 1003
    
    /**
     * Check if the app has permission to manage external storage (Android 11+)
     */
    fun hasManageExternalStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if the app has permission to read media audio
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
     * Check if the app can modify files at the given URI
     * For content:// URIs, this checks if we have the necessary permissions
     */
    fun canModifyFile(context: Context, uri: Uri): Boolean {
        return when {
            // File URIs in app storage can always be modified
            uri.scheme == "file" && uri.path?.startsWith(context.filesDir.absolutePath) == true -> true
            uri.scheme == "file" && uri.path?.startsWith(context.cacheDir.absolutePath) == true -> true
            
            // For external storage, need MANAGE_EXTERNAL_STORAGE on Android 11+
            uri.scheme == "content" -> hasManageExternalStoragePermission(context)
            
            // For file URIs in external storage
            uri.scheme == "file" -> hasManageExternalStoragePermission(context)
            
            else -> false
        }
    }
    
    /**
     * Request MANAGE_EXTERNAL_STORAGE permission (Android 11+)
     * This will open the system settings page where user must manually enable it
     */
    fun requestManageExternalStoragePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE)
            }
        } else {
            // For Android 10 and below, request WRITE_EXTERNAL_STORAGE
            requestWriteExternalStoragePermission(activity)
        }
    }
    
    /**
     * Request WRITE_EXTERNAL_STORAGE permission (Android 10 and below)
     */
    private fun requestWriteExternalStoragePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            val permission = WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(activity, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                    arrayOf(permission),
                    REQUEST_CODE_WRITE_STORAGE
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
     * Check if we need to request permissions for file modification
     */
    fun needsFileModificationPermission(context: Context): Boolean {
        return !hasManageExternalStoragePermission(context)
    }
    
    /**
     * Get the explanation message for why file access permission is needed
     */
    fun getFileAccessExplanation(): String {
        return "To save BPM information directly to your music files, the app needs " +
               "access to modify files on your device. This allows BPM data to be " +
               "available in other music players that support ID3 tags."
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
    
    /**
     * Check if the app can handle the given URI for metadata modification
     */
    fun canHandleUri(context: Context, uri: Uri): Boolean {
        return when (uri.scheme) {
            "file" -> true // Can always try to modify file URIs
            "content" -> {
                // For content URIs, we need MANAGE_EXTERNAL_STORAGE on Android 11+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    hasManageExternalStoragePermission(context)
                } else {
                    true // On older Android, content URIs might be accessible
                }
            }
            else -> false
        }
    }
}
