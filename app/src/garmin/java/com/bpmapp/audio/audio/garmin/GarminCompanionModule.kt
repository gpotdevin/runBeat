// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

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

    companion object {

        /**
         * Serializes the companion manager's SDK sends on a single background
         * thread: ConnectIQ.sendMessage blocks on a socket write and must not
         * run on the main thread, and a stale state push must not overtake a
         * newer one.
         */
        @Provides
        @Singleton
        fun provideGarminSendDispatcher(): CoroutineDispatcher =
            Dispatchers.IO.limitedParallelism(1)
    }
}
