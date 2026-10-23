// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

/**
 * Marker for the real, SDK-backed [CompanionInitializer]. Bound only by the
 * garmin flavor (see GarminCompanionModule in the garmin source set).
 *
 * Hilt has no binding override, so the garmin flavor cannot simply re-bind
 * [CompanionInitializer] (that would be a Dagger duplicate-binding error).
 * Instead it binds this marker key, and the main source set's [CompanionModule]
 * binds [CompanionInitializer] to the [GarminCompanionStarter] when one is
 * present (garmin flavor) and to [NoopCompanionInitializer] otherwise (fdroid
 * flavor).
 */
interface GarminCompanionStarter : CompanionInitializer
