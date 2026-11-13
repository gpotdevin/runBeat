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
     * Releases companion resources. No-op by default; not wired to any
     * lifecycle callback — the companion lives for the whole process and the
     * OS reclaims the SDK receivers when it dies (see GarminCompanionManager).
     */
    fun shutdown() {
        // Nothing to release by default.
    }

    /**
     * Called when the developer-only "Connect IQ simulator (ADB)" setting
     * changes (debug builds only). The default no-op covers builds without
     * the companion SDK; the garmin flavor recreates the ConnectIQ instance
     * with the new connect type.
     */
    fun onSimulatorAdbModeChanged(enabled: Boolean) {
        // Nothing to do by default.
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
