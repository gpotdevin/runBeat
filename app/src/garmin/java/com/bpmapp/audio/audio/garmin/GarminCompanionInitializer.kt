// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Garmin-flavor companion initializer: starts and stops the Connect IQ
 * companion manager. Bound as the [GarminCompanionStarter], which the main
 * source set's [CompanionModule] prefers over [NoopCompanionInitializer].
 */
@Singleton
class GarminCompanionInitializer @Inject constructor(
    private val manager: GarminCompanionManager,
) : GarminCompanionStarter {

    override fun initialize(context: Context) {
        manager.initialize()
    }

    override fun shutdown() {
        manager.shutdown()
    }
}
