// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Garmin-flavor companion wiring: binds the real, SDK-backed
 * [GarminCompanionInitializer] as the [GarminCompanionStarter], which the main
 * source set's [CompanionModule] prefers over [NoopCompanionInitializer].
 *
 * The initializer is bound under the [GarminCompanionStarter] marker key
 * rather than re-binding [CompanionInitializer] directly, because Hilt has no
 * binding override and a second binding of [CompanionInitializer] in
 * SingletonComponent would be a Dagger duplicate-binding error.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GarminCompanionModule {

    @Binds
    abstract fun bindGarminCompanionStarter(impl: GarminCompanionInitializer): GarminCompanionStarter
}
