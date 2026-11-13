// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

/**
 * Keys of the shared "AppSettings" SharedPreferences file, owned here so the
 * library UI (LibraryViewModel / Settings screen) and the Garmin companion
 * (command router) read and write the same definitions.
 */
object SettingsKeys {

    const val PREFS_NAME = "AppSettings"

    /**
     * Exclude tracks marked "not for running" from the play queue
     * (default ON).
     */
    const val KEY_EXCLUDE_NOT_FOR_RUNNING_TRACKS = "exclude_not_for_running_tracks"
    const val DEFAULT_EXCLUDE_NOT_FOR_RUNNING_TRACKS = true
}
