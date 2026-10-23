// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context
import android.util.Log
import com.bpmapp.audio.audio.PlayerRepository
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin Connect IQ Mobile SDK shell for the Garmin watch companion. This is
 * the only class in the app that imports `com.garmin.*`; everything else
 * (command routing, message codec, connection-state holder) lives in the main
 * source set and is unit-testable without the SDK.
 *
 * Responsibilities:
 *  - initialize ConnectIQ and register/listen for the RunBeat IQ app
 *    ([IQ_APP_ID]) on every known (paired) Garmin device;
 *  - decode watch messages with [GarminMessageCodec] and delegate them to
 *    [GarminCommandRouter], then push an ack back to the watch;
 *  - push [GarminWatchState] to the watch whenever any underlying flow
 *    changes (playerState, currentTrack, targetCadence, speedCorrectionEnabled,
 *    playbackSpeed), plus a ~1 Hz tick that picks up volume changes (volume is
 *    not a repository flow), and right after volume commands;
 *  - expose the connection state via [connectionState].
 *
 * Threading: the Connect IQ SDK delivers its callbacks (onSdkReady,
 * onDeviceStatusChanged, onMessageReceived, ...) on the main thread, so all
 * mutable state below is main-thread confined. SDK failures — including a
 * missing or outdated Garmin Connect Mobile — are logged and surfaced as
 * DISCONNECTED; they never crash or block playback.
 *
 * Lifecycle: [initialize] is idempotent and called on app start (via
 * GarminCompanionInitializer); [shutdown] unregisters the SDK listeners as the
 * SDK requires and is called when the app terminates.
 */
@Singleton
class GarminCompanionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepository: PlayerRepository,
    private val router: GarminCommandRouter,
) : ConnectIQ.ConnectIQListener,
    ConnectIQ.IQDeviceEventListener,
    ConnectIQ.IQApplicationEventListener {

    /** Watch connection state, mirrored to the main source set for the UI. */
    val connectionState: StateFlow<GarminConnectionState> = GarminWatchConnection.state

    private val connectIQ: ConnectIQ by lazy {
        ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
    }
    private val iqApp: IQApp by lazy { IQApp(IQ_APP_ID) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Main-thread confined (SDK callbacks arrive on the main thread).
    private val devices = LinkedHashMap<Long, IQDevice>()
    private val deviceStatuses = HashMap<Long, IQDevice.IQDeviceStatus>()
    private var statePushJob: Job? = null
    private var initializeCalled = false

    private val sendMessageListener = object : ConnectIQ.IQSendMessageListener {
        override fun onMessageStatus(device: IQDevice, app: IQApp, status: ConnectIQ.IQMessageStatus) {
            if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                Log.w(TAG, "Message to ${device.friendlyName} failed: $status")
            }
        }
    }

    /**
     * Initializes the Connect IQ SDK (idempotent). Garmin Connect Mobile
     * absence or an outdated version is reported through
     * [onInitializeError] and leaves the companion DISCONNECTED.
     */
    fun initialize() {
        if (initializeCalled) return
        initializeCalled = true
        GarminWatchConnection.update(GarminConnectionState.CONNECTING)
        try {
            // showGcmDialogs = false: GCM problems are reported via
            // onInitializeError and surfaced as DISCONNECTED in Settings.
            connectIQ.initialize(context, false, this)
        } catch (e: Exception) {
            Log.e(TAG, "ConnectIQ initialization failed", e)
            GarminWatchConnection.update(GarminConnectionState.DISCONNECTED)
        }
    }

    /**
     * Unregisters the SDK listeners (the SDK requires this on termination) and
     * shuts ConnectIQ down. Playback is never affected.
     */
    fun shutdown() {
        stopStatePush()
        try {
            connectIQ.unregisterAllForEvents()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister ConnectIQ events", e)
        }
        try {
            connectIQ.shutdown(context)
        } catch (e: Exception) {
            Log.w(TAG, "ConnectIQ shutdown failed", e)
        }
        initializeCalled = false
        devices.clear()
        deviceStatuses.clear()
        GarminWatchConnection.update(GarminConnectionState.DISCONNECTED)
    }

    // ---------------------------------------------------------------
    // ConnectIQ.ConnectIQListener
    // ---------------------------------------------------------------

    override fun onSdkReady() {
        Log.i(TAG, "ConnectIQ SDK ready")
        refreshDevices()
        recomputeConnection(finalizeConnecting = true)
    }

    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
        Log.w(TAG, "ConnectIQ init error: $status (Garmin Connect Mobile missing or outdated)")
        GarminWatchConnection.update(GarminConnectionState.DISCONNECTED)
    }

    override fun onSdkShutDown() {
        Log.i(TAG, "ConnectIQ SDK shut down")
        stopStatePush()
        GarminWatchConnection.update(GarminConnectionState.DISCONNECTED)
    }

    // ---------------------------------------------------------------
    // ConnectIQ.IQDeviceEventListener
    // ---------------------------------------------------------------

    override fun onDeviceStatusChanged(device: IQDevice, status: IQDevice.IQDeviceStatus) {
        deviceStatuses[device.deviceIdentifier] = status
        recomputeConnection()
    }

    // ---------------------------------------------------------------
    // ConnectIQ.IQApplicationEventListener
    // ---------------------------------------------------------------

    override fun onMessageReceived(
        device: IQDevice,
        app: IQApp,
        messages: List<Any>,
        status: ConnectIQ.IQMessageStatus,
    ) {
        for (message in messages) {
            val json = message as? String ?: continue
            val command = GarminMessageCodec.decodeCommand(json)
            if (command == null) {
                Log.w(TAG, "Ignoring undecodable watch message: $json")
                continue
            }
            Log.d(TAG, "Watch command from ${device.friendlyName}: $json")
            val ack = router.handleCommand(command)
            sendToWatch(GarminMessageCodec.encodeAck(ack))
            // Volume is not a repository flow: push the new state right away
            // instead of waiting for the next 1 Hz tick.
            if (command is GarminCommand.VolumeDelta) pushState()
        }
    }

    // ---------------------------------------------------------------
    // Device registration / connection state
    // ---------------------------------------------------------------

    private fun refreshDevices() {
        try {
            for (device in connectIQ.getKnownDevices()) {
                registerDevice(device)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list known Garmin devices", e)
        }
    }

    private fun registerDevice(device: IQDevice) {
        if (devices.containsKey(device.deviceIdentifier)) return
        devices[device.deviceIdentifier] = device
        try {
            // Device status events + messages from our IQ app on this device.
            connectIQ.registerForEvents(device, this, iqApp, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register for events on ${device.friendlyName}", e)
            return
        }
        try {
            deviceStatuses[device.deviceIdentifier] = connectIQ.getDeviceStatus(device)
        } catch (e: Exception) {
            Log.w(TAG, "Device status unavailable for ${device.friendlyName}", e)
        }
    }

    private fun recomputeConnection(finalizeConnecting: Boolean = false) {
        val anyConnected = deviceStatuses.values.any { it == IQDevice.IQDeviceStatus.CONNECTED }
        when (connectionState.value) {
            GarminConnectionState.CONNECTED -> if (!anyConnected) setDisconnected()
            GarminConnectionState.DISCONNECTED -> if (anyConnected) setConnected()
            GarminConnectionState.CONNECTING -> {
                if (anyConnected) {
                    setConnected()
                } else if (finalizeConnecting) {
                    // SDK is up but no paired watch is in range.
                    setDisconnected()
                }
            }
        }
    }

    private fun setConnected() {
        Log.i(TAG, "Garmin watch connected")
        GarminWatchConnection.update(GarminConnectionState.CONNECTED)
        startStatePush()
        pushState()
    }

    private fun setDisconnected() {
        Log.i(TAG, "Garmin watch disconnected")
        GarminWatchConnection.update(GarminConnectionState.DISCONNECTED)
        stopStatePush()
    }

    // ---------------------------------------------------------------
    // State push
    // ---------------------------------------------------------------

    private fun startStatePush() {
        statePushJob?.cancel()
        statePushJob = scope.launch {
            watchStateFlow().collect { state ->
                sendToWatch(GarminMessageCodec.encodeState(state))
            }
        }
    }

    private fun stopStatePush() {
        statePushJob?.cancel()
        statePushJob = null
    }

    /**
     * Re-evaluates the watch state whenever any source flow emits, plus a ~1 Hz
     * tick that picks up volume changes. [distinctUntilChanged] keeps the BLE
     * traffic to actual changes (playerState emits ~10x/s with the position).
     */
    private fun watchStateFlow(): Flow<GarminWatchState> =
        combine(
            playerRepository.playerState,
            playerRepository.currentTrack,
            playerRepository.targetCadence,
            playerRepository.speedCorrectionEnabled,
            playerRepository.playbackSpeed,
        ) { _, _, _, _, _ -> router.currentState() }
            .combine(volumeTicker()) { state, _ -> state }
            .distinctUntilChanged()

    private fun volumeTicker(): Flow<Unit> = flow {
        // Cancelled collectors terminate the delay with a CancellationException.
        while (true) {
            emit(Unit)
            delay(VOLUME_POLL_INTERVAL_MS)
        }
    }

    /** Pushes the current state to the watch (no-op while disconnected). */
    private fun pushState() {
        if (connectionState.value != GarminConnectionState.CONNECTED) return
        sendToWatch(GarminMessageCodec.encodeState(router.currentState()))
    }

    /** Sends a JSON message to every connected, registered watch. */
    private fun sendToWatch(json: String) {
        val targets = devices.values.filter {
            deviceStatuses[it.deviceIdentifier] == IQDevice.IQDeviceStatus.CONNECTED
        }
        for (device in targets) {
            try {
                connectIQ.sendMessage(device, iqApp, json, sendMessageListener)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send message to ${device.friendlyName}", e)
            }
        }
    }

    companion object {
        private const val TAG = "GarminCompanionManager"

        /**
         * Connect IQ app id of the RunBeat watch app. Must match the id in
         * garmin-watch/manifest.xml.
         */
        const val IQ_APP_ID = "06c14376-0c60-4e4c-a977-e93364ace3eb"

        /** Volume poll interval while a watch is connected (~1 Hz). */
        private const val VOLUME_POLL_INTERVAL_MS = 1000L
    }
}
