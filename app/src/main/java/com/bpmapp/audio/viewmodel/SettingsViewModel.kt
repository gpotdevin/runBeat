// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bpmapp.audio.audio.PlayerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for SettingsScreen
 * Manages app settings and preferences using SharedPreferences
 */
@HiltViewModel
@SuppressLint("StaticFieldLeak") // @ApplicationContext field is application-scoped
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepository: PlayerRepository
) : ViewModel() {
    
    companion object {
        private const val PREFS_NAME = "AppSettings"
        
        // Playback settings keys
        private const val KEY_DEFAULT_CADENCE = "default_cadence"
        private const val KEY_AUTO_APPLY_CADENCE_MATCH = "auto_apply_cadence_match"
        
        // Library settings keys
        private const val KEY_AUTO_SCAN_ON_STARTUP = "auto_scan_on_startup"
        
        // Appearance settings keys
        private const val KEY_DYNAMIC_COLORS = "dynamic_colors"
        
        // Default values
        private const val DEFAULT_DEFAULT_CADENCE = 172
        private const val DEFAULT_AUTO_APPLY_CADENCE_MATCH = true
        private const val DEFAULT_AUTO_SCAN_ON_STARTUP = true
        private const val DEFAULT_DYNAMIC_COLORS = true
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Playback settings
    private val _defaultCadence = MutableStateFlow(getDefaultCadence())
    val defaultCadence: StateFlow<Int> = _defaultCadence.asStateFlow()
    
    private val _autoApplyCadenceMatch = MutableStateFlow(getAutoApplyCadenceMatch())
    val autoApplyCadenceMatch: StateFlow<Boolean> = _autoApplyCadenceMatch.asStateFlow()
    
    // Library settings
    private val _autoScanOnStartup = MutableStateFlow(getAutoScanOnStartup())
    val autoScanOnStartup: StateFlow<Boolean> = _autoScanOnStartup.asStateFlow()
    
    // Appearance settings
    private val _dynamicColors = MutableStateFlow(getDynamicColors())
    val dynamicColors: StateFlow<Boolean> = _dynamicColors.asStateFlow()

    // Playback: rhythm-mode beep signal at track start (delegated to the repository,
    // which is the single source of truth at playback time).
    val modeBeepSignal: StateFlow<Boolean> = playerRepository.modeBeepSignal
    
    // Getters for SharedPreferences
    private fun getDefaultCadence(): Int = prefs.getInt(KEY_DEFAULT_CADENCE, DEFAULT_DEFAULT_CADENCE)
    private fun getAutoApplyCadenceMatch(): Boolean = prefs.getBoolean(KEY_AUTO_APPLY_CADENCE_MATCH, DEFAULT_AUTO_APPLY_CADENCE_MATCH)
    private fun getAutoScanOnStartup(): Boolean = prefs.getBoolean(KEY_AUTO_SCAN_ON_STARTUP, DEFAULT_AUTO_SCAN_ON_STARTUP)
    private fun getDynamicColors(): Boolean = prefs.getBoolean(KEY_DYNAMIC_COLORS, DEFAULT_DYNAMIC_COLORS)
    
    // Setters for SharedPreferences
    fun setDefaultCadence(value: Int) {
        viewModelScope.launch {
            _defaultCadence.value = value.coerceIn(150, 195)
            prefs.edit().putInt(KEY_DEFAULT_CADENCE, _defaultCadence.value).apply()
        }
    }
    
    fun setAutoApplyCadenceMatch(value: Boolean) {
        viewModelScope.launch {
            _autoApplyCadenceMatch.value = value
            prefs.edit().putBoolean(KEY_AUTO_APPLY_CADENCE_MATCH, value).apply()
        }
    }
    
    fun setAutoScanOnStartup(value: Boolean) {
        viewModelScope.launch {
            _autoScanOnStartup.value = value
            prefs.edit().putBoolean(KEY_AUTO_SCAN_ON_STARTUP, value).apply()
        }
    }
    
    fun setDynamicColors(value: Boolean) {
        viewModelScope.launch {
            _dynamicColors.value = value
            prefs.edit().putBoolean(KEY_DYNAMIC_COLORS, value).apply()
        }
    }

    fun setModeBeepSignal(value: Boolean) {
        playerRepository.setModeBeepSignal(value)
    }
    
    // Get version info
    fun getVersionInfo(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    // Get app license info
    fun getAppLicense(): String = "GPLv3"

    // Get third-party library license info
    fun getLibraries(): List<LibraryInfo> = listOf(
        LibraryInfo("SoundTouch", "LGPL v2.1", "https://github.com/hipxel/soundtouch-android"),
        LibraryInfo("Aubio", "GPL v3", "https://github.com/aubio/aubio"),
        LibraryInfo("AndroidX Media3 / ExoPlayer", "Apache-2.0", "https://github.com/androidx/media"),
        LibraryInfo("Room", "Apache-2.0", "https://github.com/androidx/androidx"),
        LibraryInfo("Hilt / Dagger", "Apache-2.0", "https://github.com/google/dagger"),
        LibraryInfo("Jetpack Compose", "Apache-2.0", "https://github.com/androidx/androidx"),
        LibraryInfo("Kotlin Coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
        LibraryInfo("Material Components for Android", "Apache-2.0", "https://github.com/material-components/material-components-android")
    )

    /**
     * Information about a third-party library used by the app.
     */
    data class LibraryInfo(
        val name: String,
        val license: String,
        val url: String
    )
}