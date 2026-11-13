// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connection state of the Garmin watch companion link, as surfaced by the
 * Connect IQ Mobile SDK (garmin flavor only).
 */
enum class GarminConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

/**
 * Process-wide holder for the watch connection state.
 *
 * The writer ([GarminCompanionManager], garmin source set) and the reader
 * (Settings screen, shared by all flavors) live in different source sets, so
 * the state crosses that boundary through this object instead of a Hilt
 * binding. In the fdroid flavor nothing ever writes it and it stays
 * [GarminConnectionState.DISCONNECTED] (the Settings UI hides the Garmin
 * section there anyway).
 */
object GarminWatchConnection {

    private val _state = MutableStateFlow(GarminConnectionState.DISCONNECTED)
    val state: StateFlow<GarminConnectionState> = _state.asStateFlow()

    fun update(state: GarminConnectionState) {
        _state.value = state
    }
}
