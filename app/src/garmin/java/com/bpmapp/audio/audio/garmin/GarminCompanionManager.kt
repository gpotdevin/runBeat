// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *    [GarminCommandRouter] (serialized through a mutex), then push an ack
 *    back to the watch;
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
 * Lifecycle: [initialize] is idempotent and called at process scope (BpmApp,
 * plus AudioService for service-started processes), so the companion survives
 * activity destruction and keeps working while the app is backgrounded during
 * a run. There is deliberately no explicit shutdown wired to any lifecycle
 * callback: the OS reclaims the SDK's receivers and service bindings when the
 * process dies. [shutdown] remains as the release path (it also cancels
 * pending device-refresh retries) but nothing invokes it.
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

    /**
     * Serializes watch-command handling. The mark-not-for-running path
     * suspends on a Room write, so without this mutex two commands from the
     * same BLE batch (or a fast double-press) could interleave: both would
     * read the same current track and both would call playNext(), skipping
     * two tracks.
     */
    private val commandMutex = Mutex()

    // Main-thread confined (SDK callbacks arrive on the main thread).
    private val devices = LinkedHashMap<Long, IQDevice>()
    private val deviceStatuses = HashMap<Long, IQDevice.IQDeviceStatus>()
    private var statePushJob: Job? = null
    private var initializeCalled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var deviceRefreshRetryCount = 0
    private val deviceRefreshRetryRunnable = Runnable {
        Log.i(TAG, "Retrying device refresh (attempt $deviceRefreshRetryCount)")
        refreshDevices()
    }

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
     * Unregisters the SDK listeners and shuts ConnectIQ down, cancelling any
     * pending device-refresh retry. Not wired to any lifecycle callback — see
     * the class KDoc for the shutdown policy. Playback is never affected.
     */
    fun shutdown() {
        stopStatePush()
        mainHandler.removeCallbacks(deviceRefreshRetryRunnable)
        deviceRefreshRetryCount = 0
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
        // A new SDK event re-arms the device-refresh retry cycle.
        deviceRefreshRetryCount = 0
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
        mainHandler.removeCallbacks(deviceRefreshRetryRunnable)
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
            scope.launch {
                try {
                    // Serialized: two commands must never run handleCommand
                    // concurrently (see [commandMutex]).
                    val ack = commandMutex.withLock { router.handleCommand(command) }
                    // Ack only the state-changing RunBeat commands; transport and
                    // volume are confirmed by the pushed state message.
                    if (command.needsAck()) {
                        sendToWatch(GarminMessageCodec.encodeAck(ack))
                    }
                    // Volume is not a repository flow: push the new state right away
                    // instead of waiting for the next 1 Hz tick.
                    if (command is GarminCommand.VolumeDelta) pushState()
                } catch (e: Exception) {
                    // A failing watch command must never crash playback.
                    Log.e(TAG, "Failed to handle watch command: $json", e)
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Device registration / connection state
    // ---------------------------------------------------------------

    private fun refreshDevices() {
        // A fresh refresh supersedes any pending retry.
        mainHandler.removeCallbacks(deviceRefreshRetryRunnable)
        try {
            val known = connectIQ.getKnownDevices()
            if (known.isEmpty()) {
                // GCM is reachable but lists no paired devices (yet).
                Log.i(TAG, "No known Garmin devices; will retry")
                scheduleDeviceRefreshRetry()
                return
            }
            var allRegistered = true
            for (device in known) {
                allRegistered = registerDevice(device) && allRegistered
            }
            // A (backoff-retry) refresh that registers a CONNECTED device must
            // update the connection state even when no further SDK event
            // arrives; only onSdkReady/onDeviceStatusChanged recompute it
            // otherwise.
            recomputeConnection()
            if (allRegistered) {
                deviceRefreshRetryCount = 0
            } else {
                scheduleDeviceRefreshRetry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list known Garmin devices", e)
            scheduleDeviceRefreshRetry()
        }
    }

    /** @return true when the device is registered for events. */
    private fun registerDevice(device: IQDevice): Boolean {
        if (devices.containsKey(device.deviceIdentifier)) return true
        try {
            // Device status events + messages from our IQ app on this device.
            connectIQ.registerForEvents(device, this, iqApp, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register for events on ${device.friendlyName}", e)
            return false
        }
        devices[device.deviceIdentifier] = device
        try {
            deviceStatuses[device.deviceIdentifier] = connectIQ.getDeviceStatus(device)
        } catch (e: Exception) {
            Log.w(TAG, "Device status unavailable for ${device.friendlyName}", e)
        }
        return true
    }

    /**
     * Retries a failed or empty device refresh with exponential backoff on the
     * main looper (2s, 4s, 8s, 16s, 32s — about a minute in total), then stops
     * until the next SDK event re-arms the cycle (see [onSdkReady]).
     */
    private fun scheduleDeviceRefreshRetry() {
        if (deviceRefreshRetryCount >= MAX_DEVICE_REFRESH_RETRIES) {
            Log.i(TAG, "Device refresh retries exhausted; waiting for the next SDK event")
            return
        }
        deviceRefreshRetryCount++
        val delayMs = DEVICE_REFRESH_RETRY_BASE_MS * (1L shl (deviceRefreshRetryCount - 1))
        Log.i(TAG, "Scheduling device refresh retry $deviceRefreshRetryCount/$MAX_DEVICE_REFRESH_RETRIES in ${delayMs}ms")
        mainHandler.postDelayed(deviceRefreshRetryRunnable, delayMs)
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

    /** Commands the watch expects an ack for (state-changing RunBeat commands). */
    private fun GarminCommand.needsAck(): Boolean = when (this) {
        is GarminCommand.CadenceSet,
        is GarminCommand.CadenceDelta,
        is GarminCommand.CadenceUseCurrent,
        is GarminCommand.SpeedCorrectionToggle,
        is GarminCommand.MarkNotForRunning,
        -> true
        else -> false
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

        /** Device-refresh retries after a transient failure (exponential backoff). */
        private const val MAX_DEVICE_REFRESH_RETRIES = 5
        private const val DEVICE_REFRESH_RETRY_BASE_MS = 2000L
    }
}
