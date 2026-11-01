// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Singleton

/**
 * Hilt wiring for the SDK-free companion core (compiled into every flavor).
 *
 * Hilt has no binding override (a second [CompanionInitializer] binding in
 * SingletonComponent would be a Dagger duplicate-binding error), so the
 * default binding lives here and returns [NoopCompanionInitializer] unless the
 * garmin flavor binds a [GarminCompanionStarter] — in which case that real,
 * SDK-backed initializer is used instead.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CompanionModule {

    /** Present only in the garmin flavor (bound by GarminCompanionModule). */
    @BindsOptionalOf
    abstract fun bindGarminCompanionStarter(): GarminCompanionStarter

    companion object {

        @Provides
        @Singleton
        @JvmStatic
        fun provideCompanionInitializer(
            starter: Optional<GarminCompanionStarter>,
        ): CompanionInitializer =
            if (starter.isPresent) starter.get() else NoopCompanionInitializer
    }
}

/**
 * Entry point so the Application class can start the companion at process
 * scope (Hilt does not inject into the @HiltAndroidApp Application itself).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CompanionInitializerEntryPoint {
    fun companionInitializer(): CompanionInitializer
}
