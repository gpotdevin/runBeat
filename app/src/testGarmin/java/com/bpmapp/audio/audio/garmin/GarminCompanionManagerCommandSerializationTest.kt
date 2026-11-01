// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.bpmapp.audio.audio.MediaItemFactory
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.audio.TrackRepository
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Unit test for the command serialization in [GarminCompanionManager]
 * (review finding N2): the mark-not-for-running path suspends on a Room
 * write, so two commands delivered in one BLE batch (or a fast
 * double-press of the mark button) must not run
 * [GarminCommandRouter.handleCommand] concurrently — otherwise both read
 * the same current track and both call playNext(), skipping two tracks.
 *
 * The manager lives in the garmin source set, so this test lives in the
 * garmin flavor's unit-test source set (app/src/testGarmin). The Room write
 * is stubbed with a suspending answer so the interleaving the mutex
 * prevents is actually reachable without the fix.
 *
 * Also covers the request_state handshake (the "phone not recognized" fix):
 * a watch's request_state is answered with exactly one state push to that
 * device — no ack — which is what lets a watch launched after the link was
 * already up still get recognized.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GarminCompanionManagerCommandSerializationTest {

    private lateinit var playerRepository: PlayerRepository
    private lateinit var trackRepository: TrackRepository
    private lateinit var mediaKeyDispatcher: MediaKeyDispatcher
    private lateinit var manager: GarminCompanionManager

    private lateinit var playerState: MutableStateFlow<PlayerRepository.PlayerState>
    private lateinit var currentTrack: MutableStateFlow<MediaItem?>

    private val testDispatcher = StandardTestDispatcher()

    // Concurrency observability for the stubbed Room write.
    private val roomWriteInFlight = AtomicInteger(0)
    private val maxRoomWriteInFlight = AtomicInteger(0)
    private val markedTrackIds = mutableListOf<String>()

    @Before
    fun setUp() {
        // The manager's command scope uses Dispatchers.Main.immediate; route
        // it through the test scheduler so the test is deterministic. The send
        // dispatcher is passed explicitly for the same reason.
        Dispatchers.setMain(testDispatcher)
        playerRepository = mock()
        trackRepository = mock()
        mediaKeyDispatcher = mock()
        playerState = MutableStateFlow(
            PlayerRepository.PlayerState(isPlaying = true, playbackState = Player.STATE_READY)
        )
        currentTrack = MutableStateFlow(null)
        whenever(playerRepository.playerState).thenReturn(playerState)
        whenever(playerRepository.currentTrack).thenReturn(currentTrack)
        whenever(playerRepository.targetCadence).thenReturn(MutableStateFlow(172f))
        whenever(playerRepository.speedCorrectionEnabled).thenReturn(MutableStateFlow(true))
        whenever(playerRepository.playbackSpeed).thenReturn(MutableStateFlow(1.0f))
        whenever(mediaKeyDispatcher.currentVolume()).thenReturn(7)
        // playNext advances the track, like the real player does.
        doAnswer {
            currentTrack.value = track("track-2", "Second Song", "140")
        }.whenever(playerRepository).playNext()
        val router = GarminCommandRouter(
            playerRepository,
            trackRepository,
            mock(),
            MediaItemFactory(),
            mediaKeyDispatcher,
            mock(),
            mock(),
        )
        manager = GarminCompanionManager(mock<Context>(), playerRepository, router, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun track(
        trackId: String,
        title: String,
        bpm: String,
        artist: String? = null,
        album: String? = null,
        durationMs: Long? = null,
    ): MediaItem {
        val extras = Bundle().apply {
            putString("trackId", trackId)
            putString("bpm", bpm)
            if (durationMs != null) putLong("durationMs", durationMs)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setExtras(extras)
        if (artist != null) metadata.setArtist(artist)
        if (album != null) metadata.setAlbumTitle(album)
        return MediaItem.Builder()
            .setMediaId(trackId)
            .setMediaMetadata(metadata.build())
            .build()
    }

    /** Stubs the Room write to genuinely suspend, tracking overlap. */
    private fun stubSuspendingRoomWrite() {
        runBlocking {
            whenever(trackRepository.markTrackNotForRunning(any())).doSuspendableAnswer {
                roomWriteInFlight.incrementAndGet()
                maxRoomWriteInFlight.accumulateAndGet(roomWriteInFlight.get()) { a, b -> max(a, b) }
                delay(ROOM_WRITE_MILLIS)
                roomWriteInFlight.decrementAndGet()
                markedTrackIds.add(it.getArgument<String>(0))
                1
            }
        }
    }

    @Test
    fun twoMarkCommandsInOneBatch_areSerialized() {
        currentTrack.value = track("track-1", "First Song", "140")
        stubSuspendingRoomWrite()

        val device = IQDevice(1L, "Forerunner")
        val app = IQApp(GarminCompanionManager.IQ_APP_ID)
        val json = "{\"type\":\"mark_not_for_running\"}"
        manager.onMessageReceived(device, app, listOf(json, json), ConnectIQ.IQMessageStatus.SUCCESS)

        testDispatcher.scheduler.advanceUntilIdle()

        // The Room write never runs concurrently...
        assertEquals(1, maxRoomWriteInFlight.get())
        // ...and the second command sees the track skipped by the first one
        // instead of flagging the same track twice (double skip).
        assertEquals(listOf("track-1", "track-2"), markedTrackIds)
        verify(playerRepository, times(2)).playNext()
    }

    @Test
    fun requestState_isAnsweredWithOneStatePushToThatDevice() {
        val connectIQ = mock<ConnectIQ>()
        val sentMessages = mutableListOf<Pair<IQDevice, String>>()
        val device = IQDevice(1L, "Forerunner")
        val notConnectedDevice = IQDevice(2L, "NotConnected")
        val app = IQApp(GarminCompanionManager.IQ_APP_ID)

        Mockito.mockStatic(ConnectIQ::class.java).use { mocked ->
            mocked.`when`<ConnectIQ> { ConnectIQ.getInstance(any(), any()) }.thenReturn(connectIQ)
            doAnswer { invocation ->
                sentMessages.add(
                    invocation.getArgument<IQDevice>(0) to invocation.getArgument<String>(2)
                )
                null
            }.whenever(connectIQ).sendMessage(any(), any(), any(), any())

            // The SDK reports the watch connected, as it would on a real link.
            manager.onDeviceStatusChanged(device, IQDevice.IQDeviceStatus.CONNECTED)
            manager.onDeviceStatusChanged(notConnectedDevice, IQDevice.IQDeviceStatus.NOT_CONNECTED)
            // Run the state-push collector's first (no-op) emission. The 1 Hz
            // volume ticker never goes idle, so the scheduler is advanced with
            // runCurrent, not advanceUntilIdle.
            testDispatcher.scheduler.runCurrent()

            currentTrack.value = track(
                trackId = "track-1",
                title = "First Song",
                bpm = "140",
                artist = "Song Artist",
                album = "Song Album",
                durationMs = 213000L,
            )
            playerState.value = PlayerRepository.PlayerState(
                isPlaying = true,
                playbackState = Player.STATE_READY,
                currentPosition = 42500L,
                duration = 213000L,
            )

            manager.onMessageReceived(
                device,
                app,
                listOf("{\"type\":\"request_state\"}"),
                ConnectIQ.IQMessageStatus.SUCCESS,
            )
            testDispatcher.scheduler.runCurrent()

            // Exactly one push, to the requesting device only, carrying the
            // current state with the extended track metadata.
            assertEquals(1, sentMessages.size)
            assertEquals(1L, sentMessages[0].first.deviceIdentifier)
            val payload = sentMessages[0].second
            assertEquals("state", JSONObject(payload).getString("type"))
            val state = GarminMessageCodec.decodeState(payload)
            assertEquals("RunBeat", state?.source)
            assertTrue(state!!.isPlaying)
            assertEquals("Song Artist", state.artist)
            assertEquals("Song Album", state.album)
            assertEquals(213000L, state.durationMs)
            // Rounded down to 5 s buckets by the phone.
            assertEquals(40000L, state.positionMs)

            // A request_state from a device that is not connected is not answered.
            manager.onMessageReceived(
                notConnectedDevice,
                app,
                listOf("{\"type\":\"request_state\"}"),
                ConnectIQ.IQMessageStatus.SUCCESS,
            )
            testDispatcher.scheduler.runCurrent()
            assertEquals(1, sentMessages.size)

            // Stop the state-push collector and reset the global connection state.
            manager.shutdown()
        }
    }

    companion object {
        private const val ROOM_WRITE_MILLIS = 100L
    }
}
