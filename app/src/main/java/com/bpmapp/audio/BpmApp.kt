// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Main Application class for RunBeat
 * Initializes Hilt dependency injection
 */
@HiltAndroidApp
class BpmApp : Application() {
    
    override fun onCreate() {
        super.onCreate()
        // Initialize app-wide components here
    }
}
