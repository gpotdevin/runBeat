// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
        // it through the test scheduler so the test is deterministic.
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
        val router = GarminCommandRouter(playerRepository, trackRepository, mediaKeyDispatcher)
        manager = GarminCompanionManager(mock<Context>(), playerRepository, router)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun track(trackId: String, title: String, bpm: String): MediaItem {
        val extras = Bundle().apply {
            putString("trackId", trackId)
            putString("bpm", bpm)
        }
        return MediaItem.Builder()
            .setMediaId(trackId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setExtras(extras).build())
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

    companion object {
        private const val ROOM_WRITE_MILLIS = 100L
    }
}
