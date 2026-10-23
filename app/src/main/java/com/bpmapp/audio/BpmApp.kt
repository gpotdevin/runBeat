// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio

import android.app.Application
import com.bpmapp.audio.audio.garmin.CompanionInitializerEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp

/**
 * Main Application class for RunBeat
 * Initializes Hilt dependency injection
 */
@HiltAndroidApp
class BpmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Start the watch companion for the whole process lifetime (no-op in
        // the fdroid flavor). It is deliberately not tied to the activity or
        // service lifecycle: the companion must keep working while the app is
        // backgrounded during a run, and the OS reclaims the SDK receivers
        // when the process dies — so there is no explicit shutdown either.
        EntryPointAccessors.fromApplication(this, CompanionInitializerEntryPoint::class.java)
            .companionInitializer()
            .initialize(this)
    }
}
