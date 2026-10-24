// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Garmin-flavor companion initializer: starts the Connect IQ companion
 * manager. Bound as the [GarminCompanionStarter], which the main source set's
 * [CompanionModule] prefers over [NoopCompanionInitializer].
 *
 * [shutdown] is kept for completeness but is not wired to any lifecycle
 * callback: the companion lives for the whole process (see
 * [GarminCompanionManager]).
 */
@Singleton
class GarminCompanionInitializer @Inject constructor(
    private val manager: GarminCompanionManager,
) : GarminCompanionStarter {

    override fun initialize(context: Context) {
        // Apply the persisted developer setting before the first initialize:
        // the manager creates its ConnectIQ instance lazily with this type.
        manager.setSimulatorAdbMode(SimulatorAdbMode.isEnabled(context))
        manager.initialize()
    }

    override fun shutdown() {
        manager.shutdown()
    }

    override fun onSimulatorAdbModeChanged(enabled: Boolean) {
        manager.setSimulatorAdbMode(enabled)
    }
}
