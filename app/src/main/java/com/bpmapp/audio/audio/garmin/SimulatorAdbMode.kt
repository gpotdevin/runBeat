// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context

/**
 * Developer setting (debug builds, garmin flavor): talk to the Connect IQ
 * simulator on a PC over ADB instead of to a real watch via Garmin Connect
 * Mobile. Stored in the shared "AppSettings" preferences, like the other
 * app settings; owned here so the Settings screen and the companion manager
 * share one key definition.
 */
object SimulatorAdbMode {

    const val PREFS_NAME = "AppSettings"
    const val KEY = "ciq_simulator_adb_mode"
    const val DEFAULT = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY, DEFAULT)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }
}
