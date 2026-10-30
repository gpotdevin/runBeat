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
import com.garmin.android.connectiq.adb.AdbConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.ServerSocket
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
 *    not a repository flow), and right after volume commands — but ONLY while
 *    the RunBeat watch app is active (open; see [watchAppActiveUntilMs], armed
 *    by any incoming watch message and dropped by "bye"): a closed watch app
 *    must not be pushed at, the BLE wakeups drained its battery;
 *  - send one {"type":"wake"} when playback starts while no watch app is
 *    active: the watch's background service turns it into a launch dialog
 *    (re-activation without the app running in between);
 *  - answer a watch's request_state with one state push to that device (the
 *    watch sends it on app start and every ~5 s while it has heard nothing
 *    for ~10 s, so a watch launched after the connect-time push still gets
 *    recognized);
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
 *
 * Debug builds only: [setSimulatorAdbMode] switches the ConnectIQ instance
 * between WIRELESS (real watch via Garmin Connect Mobile) and TETHERED (the
 * SDK's BLE simulation over ADB, for the Connect IQ simulator on a PC).
 */
@Singleton
class GarminCompanionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepository: PlayerRepository,
    private val router: GarminCommandRouter,
    sendDispatcher: CoroutineDispatcher,
) : ConnectIQ.ConnectIQListener,
    ConnectIQ.IQDeviceEventListener,
    ConnectIQ.IQApplicationEventListener {

    /** Watch connection state, mirrored to the main source set for the UI. */
    val connectionState: StateFlow<GarminConnectionState> = GarminWatchConnection.state

    // Debug-only developer setting: talk to the Connect IQ simulator on a PC
    // over ADB instead of to a real watch via Garmin Connect Mobile.
    private var simulatorAdbMode = false
    private var connectIQInstance: ConnectIQ? = null

    private val connectIQ: ConnectIQ
        get() = connectIQInstance ?: createConnectIQ().also { connectIQInstance = it }

    private fun createConnectIQ(): ConnectIQ = ConnectIQ.getInstance(
        context,
        // TETHERED is the SDK's BLE-simulation-over-ADB strategy (AdbConnection,
        // default port 7381) used to talk to the Connect IQ simulator on a PC;
        // WIRELESS talks to a real watch through Garmin Connect Mobile.
        if (simulatorAdbMode && tetherPortBindable()) ConnectIQ.IQConnectType.TETHERED else ConnectIQ.IQConnectType.WIRELESS,
    )

    /**
     * Pre-flight check for simulator (TETHERED) mode: the SDK AdbConnection
     * opens its server socket (AdbConnection.DEFAULT_PORT) on a background
     * thread and, when the bind fails, its cleanup path throws an NPE that
     * kills the whole process. Probe the bind ourselves first and fall back
     * to WIRELESS when the tether port cannot be bound (for example when the
     * OS firewall denies this app the network), so a broken tether
     * environment can never take playback down with it.
     */
    private fun tetherPortBindable(): Boolean = try {
        ServerSocket(AdbConnection.DEFAULT_PORT).use { true }
    } catch (e: Exception) {
        Log.w(TAG, "Simulator tether port ${AdbConnection.DEFAULT_PORT} not bindable: ${e.message}; staying WIRELESS")
        false
    }

    private val iqApp: IQApp by lazy { IQApp(IQ_APP_ID) }

    /**
     * Incoming tether messages carry a hardcoded empty app UUID: the SDK's
     * AdbDataHandler re-broadcasts every simulator message as
     * IQApp("", "Simulator App"), and IQMessageReceiver dispatches by app id,
     * so a listener registered under [IQ_APP_ID] never matches and every
     * watch command is silently dropped. In simulator (ADB) mode the same
     * [onMessageReceived] is therefore also registered under the empty id.
     */
    private val simulatorIqApp: IQApp by lazy { IQApp("") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Serializes SDK sends and keeps them off the main thread:
     * ConnectIQ.sendMessage performs a blocking socket write, and the SDK's
     * StrictMode policy throws NetworkOnMainThreadException when it runs on
     * the main thread, where this manager's scope and the SDK callbacks run.
     * A single-threaded dispatcher also preserves message order (a stale
     * state push must not overtake a newer one).
     */
    private val sendScope = CoroutineScope(SupervisorJob() + sendDispatcher)

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
    private var wakeJob: Job? = null

    /**
     * Wall-clock time until which the RunBeat watch app is considered
     * active (open on a watch). Armed by ANY incoming watch message
     * (request_state, commands — the watch app sends request_state on
     * start, as a keepalive every 5 s while playing, and as a stale
     * retry); cleared immediately by a "bye" (the watch's onStop farewell)
     * and by expiry. State is pushed to the watch ONLY inside this window:
     * IQDevice CONNECTED means the watch is reachable via Garmin Connect
     * Mobile, NOT that the app is open — pushing at ~1 Hz to a closed app
     * kept the watch's BLE radio busy all day and drained its battery.
     */
    private var watchAppActiveUntilMs = 0L

    private fun isWatchAppActive(): Boolean =
        System.currentTimeMillis() < watchAppActiveUntilMs

    /** Arms the watch-app active window ([WATCH_APP_ACTIVE_WINDOW_MS] from now). */
    private fun armWatchAppActivity() {
        watchAppActiveUntilMs = System.currentTimeMillis() + WATCH_APP_ACTIVE_WINDOW_MS
    }

    private fun dropWatchAppActivity() {
        watchAppActiveUntilMs = 0L
    }

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
        startWakeWatch()
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
        wakeJob?.cancel()
        wakeJob = null
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

    /**
     * Switches the Connect IQ connection between a real watch (WIRELESS, via
     * Garmin Connect Mobile) and the Connect IQ simulator on a PC (TETHERED —
     * the SDK's BLE simulation over ADB; needs USB debugging and
     * `adb forward tcp:7381 tcp:7381`, no GCM). Debug builds only.
     *
     * Re-creates the ConnectIQ instance with the new connect type and
     * re-initializes it when the companion was running; the SDK's
     * getInstance shuts the previous instance down when the type changes.
     */
    fun setSimulatorAdbMode(enabled: Boolean) {
        if (simulatorAdbMode == enabled) return
        simulatorAdbMode = enabled
        Log.i(TAG, "Connect IQ simulator (ADB) mode ${if (enabled) "enabled" else "disabled"}; re-creating ConnectIQ instance")
        val wasInitialized = initializeCalled
        if (wasInitialized) shutdown()
        connectIQInstance = null
        if (wasInitialized) initialize()
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
        if (simulatorAdbMode) {
            Log.i(TAG, "Simulator (ADB) mode: init error — in the simulator use Connection > Start (CTRL-F1); on the phone run 'adb forward tcp:7381 tcp:7381'")
        }
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
            // ANY watch message means the app is open: arm the active
            // window so the state-push stream may flow.
            armWatchAppActivity()
            // Link-layer farewell, not a playback command: the watch app
            // is closing — drop the active window immediately so pushes
            // stop now instead of at window expiry. No answer.
            if (command is GarminCommand.Bye) {
                Log.i(TAG, "Watch app bye: dropping watch-app active window")
                dropWatchAppActivity()
                continue
            }
            // Link-layer request, not a playback command: answer the sender
            // with one state push. No ack and no command mutex — it only
            // reads flows and sends, so it must not be serialized behind an
            // unrelated command's Room write.
            if (command is GarminCommand.RequestState) {
                scope.launch {
                    recomputeConnection()
                    pushStateTo(device)
                }
                continue
            }
            // Link-layer request, not a playback command: answer the sender
            // with one playlists push. No ack and no command mutex, exactly
            // like request_state.
            if (command is GarminCommand.PlaylistList) {
                scope.launch {
                    pushPlaylistsTo(device)
                }
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
                if (simulatorAdbMode) {
                    Log.i(TAG, "Simulator (ADB) mode: no known devices — in the simulator use Connection > Start (CTRL-F1); on the phone run 'adb forward tcp:7381 tcp:7381'")
                }
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
            if (simulatorAdbMode) {
                // Simulator (ADB) mode: incoming messages are re-broadcast
                // with an empty app UUID (see [simulatorIqApp]); without this
                // second registration the SDK drops every watch command.
                connectIQ.registerForAppEvents(device, simulatorIqApp, this)
            }
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
                // Gated on watch-app activity (see [watchAppActiveUntilMs]):
                // a closed watch app must not be pushed at — the BLE wakeups
                // drained the watch battery. sendToWatch filters connection.
                if (isWatchAppActive()) {
                    sendToWatch(GarminMessageCodec.encodeState(state))
                }
            }
        }
    }

    private fun stopStatePush() {
        statePushJob?.cancel()
        statePushJob = null
    }

    /**
     * Watches for playback start (isPlaying false -> true) and offers the
     * watch app a wake: when no watch app is active (the remote is closed)
     * and a watch is connected, one {"type":"wake"} message is sent. The
     * watch's background service (registered via the CIQ
     * registerForPhoneAppMessageEvent) shows a launch-confirmation dialog —
     * re-activation without the app running, or polling, in between.
     */
    private fun startWakeWatch() {
        wakeJob?.cancel()
        wakeJob = scope.launch {
            var wasPlaying: Boolean? = null
            playerRepository.playerState
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { playing ->
                    if (playing && wasPlaying == false) maybeSendWake()
                    wasPlaying = playing
                }
        }
    }

    private fun maybeSendWake() {
        if (connectionState.value != GarminConnectionState.CONNECTED) return
        if (isWatchAppActive()) return
        Log.i(TAG, "Playback started with no watch app active: sending wake")
        sendToWatch(GarminMessageCodec.encodeWake())
    }

    /**
     * Re-evaluates the watch state whenever any source flow emits, plus a ~1 Hz
     * tick that picks up volume changes. [distinctUntilChanged] keeps the BLE
     * traffic to actual changes: playerState emits ~10x/s with the position,
     * but the position is rounded to whole seconds first, so while playing the
     * state (and the push) changes about once per second — a heartbeat that
     * also proves to the watch that the link is alive.
     */
    private fun watchStateFlow(): Flow<GarminWatchState> =
        combine(
            // Rounded before combining (the router rounds it too, for direct
            // consumers of currentState): without the rounding the per-second
            // position changes would dedupe to nothing and starve the push.
            playerRepository.playerState
                .map { it.copy(currentPosition = it.currentPosition / 1000L) }
                .distinctUntilChanged(),
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

    /**
     * Pushes the current state to the watch. No-op while disconnected OR
     * while no watch app is active — the stream is only for an open remote.
     */
    private fun pushState() {
        if (connectionState.value != GarminConnectionState.CONNECTED) return
        if (!isWatchAppActive()) return
        sendToWatch(GarminMessageCodec.encodeState(router.currentState()))
    }

    /**
     * Answers a watch's request_state: pushes the current state to that one
     * device when it is connected. Independent of the aggregate
     * [connectionState] gate used by [pushState] — a watch that just (re-)sent
     * the request must get an answer even if the aggregate state has not
     * caught up yet.
     */
    private fun pushStateTo(device: IQDevice) {
        if (deviceStatuses[device.deviceIdentifier] != IQDevice.IQDeviceStatus.CONNECTED) {
            Log.i(TAG, "request_state from ${device.friendlyName}: device not connected, not answering")
            return
        }
        sendTo(device, GarminMessageCodec.encodeState(router.currentState()))
    }

    /**
     * Answers a watch's playlist_list: pushes the library playlists (flat
     * indexed fields) to that one device when it is connected — same
     * device-local path as [pushStateTo], independent of the aggregate
     * [connectionState] gate and of the watch-app active window.
     */
    private suspend fun pushPlaylistsTo(device: IQDevice) {
        if (deviceStatuses[device.deviceIdentifier] != IQDevice.IQDeviceStatus.CONNECTED) {
            Log.i(TAG, "playlist_list from ${device.friendlyName}: device not connected, not answering")
            return
        }
        sendTo(device, GarminMessageCodec.encodePlaylists(router.currentPlaylists()))
    }

    /** Sends a JSON message to every connected, registered watch. */
    private fun sendToWatch(json: String) {
        val targets = devices.values.filter {
            deviceStatuses[it.deviceIdentifier] == IQDevice.IQDeviceStatus.CONNECTED
        }
        for (device in targets) {
            sendTo(device, json)
        }
    }

    /**
     * Performs one SDK send on [sendScope]. ConnectIQ.sendMessage blocks on a
     * socket write, so it must never run on the main thread (see [sendScope]);
     * failures are logged, never thrown, so a dead link cannot take the
     * caller (playback, a watch command) down with it.
     */
    private fun sendTo(device: IQDevice, json: String) {
        sendScope.launch {
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
        is GarminCommand.PlaylistPlay,
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

        /**
         * How long after the last incoming watch message the watch app is
         * considered active (open). The watch sends request_state on start
         * and every 5 s while playing (keepalive), and commands on every
         * interaction, so 30 s is far longer than any legitimate gap while
         * the app is open — and short enough that a closed app stops the
         * ~1 Hz state stream (and its BLE cost) within half a minute.
         */
        private const val WATCH_APP_ACTIVE_WINDOW_MS = 30_000L

        /** Device-refresh retries after a transient failure (exponential backoff). */
        private const val MAX_DEVICE_REFRESH_RETRIES = 5
        private const val DEVICE_REFRESH_RETRY_BASE_MS = 2000L
    }
}
