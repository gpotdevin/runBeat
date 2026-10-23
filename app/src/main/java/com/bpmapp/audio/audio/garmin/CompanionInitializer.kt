// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context

/**
 * Flavor hook for the watch companion integration.
 *
 * The main source set ships [NoopCompanionInitializer] (used by the fdroid
 * flavor, which has no companion SDK). The garmin flavor provides the real
 * implementation backed by the Connect IQ Mobile SDK.
 */
interface CompanionInitializer {
    fun initialize(context: Context)

    /**
     * Releases companion resources (the Connect IQ SDK requires unregistering
     * its listeners on app termination). No-op by default.
     */
    fun shutdown() {
        // Nothing to release by default.
    }
}

/**
 * No-op initializer used in builds without the Garmin Connect IQ SDK
 * (the fdroid flavor).
 */
object NoopCompanionInitializer : CompanionInitializer {
    override fun initialize(context: Context) {
        // Nothing to initialize: no companion SDK in this flavor.
    }
}
