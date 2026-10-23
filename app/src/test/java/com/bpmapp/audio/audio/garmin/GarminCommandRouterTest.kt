// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.audio.TrackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [GarminCommandRouter]: command routing in both modes
 * (RunBeat active / not active), volume always via the dispatcher, the
 * mark-not-for-running orchestration, and [GarminCommandRouter.currentState]
 * field mapping. Repositories and the dispatcher are mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GarminCommandRouterTest {

    private lateinit var playerRepository: PlayerRepository
    private lateinit var trackRepository: TrackRepository
    private lateinit var mediaKeyDispatcher: MediaKeyDispatcher
    private lateinit var router: GarminCommandRouter

    private lateinit var playerState: MutableStateFlow<PlayerRepository.PlayerState>
    private lateinit var currentTrack: MutableStateFlow<MediaItem?>
    private lateinit var targetCadence: MutableStateFlow<Float>
    private lateinit var speedCorrectionEnabled: MutableStateFlow<Boolean>
    private lateinit var playbackSpeed: MutableStateFlow<Float>

    @Before
    fun setUp() {
        playerRepository = mock()
        trackRepository = mock()
        mediaKeyDispatcher = mock()
        playerState = MutableStateFlow(PlayerRepository.PlayerState())
        currentTrack = MutableStateFlow(null)
        targetCadence = MutableStateFlow(172f)
        speedCorrectionEnabled = MutableStateFlow(true)
        playbackSpeed = MutableStateFlow(1.0f)
        whenever(playerRepository.playerState).thenReturn(playerState)
        whenever(playerRepository.currentTrack).thenReturn(currentTrack)
        whenever(playerRepository.targetCadence).thenReturn(targetCadence)
        whenever(playerRepository.speedCorrectionEnabled).thenReturn(speedCorrectionEnabled)
        whenever(playerRepository.playbackSpeed).thenReturn(playbackSpeed)
        whenever(mediaKeyDispatcher.currentVolume()).thenReturn(7)
        router = GarminCommandRouter(playerRepository, trackRepository, mediaKeyDispatcher)
    }

    private fun setRunBeatActive(active: Boolean) {
        playerState.value = PlayerRepository.PlayerState(
            isPlaying = active,
            playbackState = if (active) Player.STATE_READY else Player.STATE_IDLE,
        )
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

    // ---------------------------------------------------------------
    // isRunBeatActive
    // ---------------------------------------------------------------

    @Test
    fun isRunBeatActive_trueWhenPlaying() {
        setRunBeatActive(true)
        assertTrue(router.isRunBeatActive())
    }

    @Test
    fun isRunBeatActive_trueWhenBuffering() {
        playerState.value = PlayerRepository.PlayerState(
            isPlaying = false,
            playbackState = Player.STATE_BUFFERING,
        )
        assertTrue(router.isRunBeatActive())
    }

    @Test
    fun isRunBeatActive_falseWhenIdle() {
        setRunBeatActive(false)
        assertFalse(router.isRunBeatActive())
    }

    // ---------------------------------------------------------------
    // Transport commands — RunBeat active
    // ---------------------------------------------------------------

    @Test
    fun toggle_routesToPlayerRepositoryWhenActive() {
        setRunBeatActive(true)
        val ack = router.handleCommand(GarminCommand.Toggle)
        verify(playerRepository).togglePlayPause()
        verify(mediaKeyDispatcher, never()).toggle()
        assertTrue(ack.ok)
        assertEquals("toggle", ack.command)
    }

    @Test
    fun play_routesToPlayerRepositoryWhenActive() {
        setRunBeatActive(true)
        val ack = router.handleCommand(GarminCommand.Play)
        verify(playerRepository).play()
        verify(mediaKeyDispatcher, never()).play()
        assertTrue(ack.ok)
        assertEquals("play", ack.command)
    }

    @Test
    fun pause_routesToPlayerRepositoryWhenActive() {
        setRunBeatActive(true)
        val ack = router.handleCommand(GarminCommand.Pause)
        verify(playerRepository).pause()
        verify(mediaKeyDispatcher, never()).pause()
        assertTrue(ack.ok)
        assertEquals("pause", ack.command)
    }

    @Test
    fun next_routesToPlayerRepositoryWhenActive() {
        setRunBeatActive(true)
        val ack = router.handleCommand(GarminCommand.Next)
        verify(playerRepository).playNext()
        verify(mediaKeyDispatcher, never()).next()
        assertTrue(ack.ok)
        assertEquals("next", ack.command)
    }

    @Test
    fun prev_routesToPlayerRepositoryWhenActive() {
        setRunBeatActive(true)
        val ack = router.handleCommand(GarminCommand.Prev)
        verify(playerRepository).playPrevious()
        verify(mediaKeyDispatcher, never()).previous()
        assertTrue(ack.ok)
        assertEquals("prev", ack.command)
    }

    // ---------------------------------------------------------------
    // Transport commands — RunBeat not active (media keys)
    // ---------------------------------------------------------------

    @Test
    fun toggle_routesToMediaKeyDispatcherWhenInactive() {
        setRunBeatActive(false)
        val ack = router.handleCommand(GarminCommand.Toggle)
        verify(mediaKeyDispatcher).toggle()
        verify(playerRepository, never()).togglePlayPause()
        assertTrue(ack.ok)
    }

    @Test
    fun play_routesToMediaKeyDispatcherWhenInactive() {
        setRunBeatActive(false)
        val ack = router.handleCommand(GarminCommand.Play)
        verify(mediaKeyDispatcher).play()
        verify(playerRepository, never()).play()
        assertTrue(ack.ok)
    }

    @Test
    fun pause_routesToMediaKeyDispatcherWhenInactive() {
        setRunBeatActive(false)
        val ack = router.handleCommand(GarminCommand.Pause)
        verify(mediaKeyDispatcher).pause()
        verify(playerRepository, never()).pause()
        assertTrue(ack.ok)
    }

    @Test
    fun next_routesToMediaKeyDispatcherWhenInactive() {
        setRunBeatActive(false)
        val ack = router.handleCommand(GarminCommand.Next)
        verify(mediaKeyDispatcher).next()
        verify(playerRepository, never()).playNext()
        assertTrue(ack.ok)
    }

    @Test
    fun prev_routesToMediaKeyDispatcherWhenInactive() {
        setRunBeatActive(false)
        val ack = router.handleCommand(GarminCommand.Prev)
        verify(mediaKeyDispatcher).previous()
        verify(playerRepository, never()).playPrevious()
        assertTrue(ack.ok)
    }

    // ---------------------------------------------------------------
    // Volume — always via the dispatcher (STREAM_MUSIC)
    // ---------------------------------------------------------------

    @Test
    fun volumeDelta_usesDispatcherWhenActive() {
        setRunBeatActive(true)
        val ack = router.handleCommand(GarminCommand.VolumeDelta(1))
        verify(mediaKeyDispatcher).adjustStreamVolume(1)
        assertTrue(ack.ok)
        assertEquals("volume_delta", ack.command)
    }

    @Test
    fun volumeDelta_usesDispatcherWhenInactive() {
        setRunBeatActive(false)
        val ack = router.handleCommand(GarminCommand.VolumeDelta(-1))
        verify(mediaKeyDispatcher).adjustStreamVolume(-1)
        assertTrue(ack.ok)
    }

    // ---------------------------------------------------------------
    // Cadence commands — RunBeat only
    // ---------------------------------------------------------------

    @Test
    fun cadenceSet_appliesTargetCadenceWhenActive() {
        setRunBeatActive(true)
        val ack = router.handleCommand(GarminCommand.CadenceSet(180f))
        verify(playerRepository).setTargetCadence(180f)
        assertTrue(ack.ok)
        assertEquals("cadence_set", ack.command)
    }

    @Test
    fun cadenceSet_rejectedWhenInactive() {
        setRunBeatActive(false)
        val ack = router.handleCommand(GarminCommand.CadenceSet(180f))
        verify(playerRepository, never()).setTargetCadence(any())
        assertFalse(ack.ok)
        assertEquals("cadence_set", ack.command)
    }

    @Test
    fun cadenceUseCurrent_appliesTargetCadenceWhenActive() {
        setRunBeatActive(true)
        val ack = router.handleCommand(GarminCommand.CadenceUseCurrent(171f))
        verify(playerRepository).setTargetCadence(171f)
        assertTrue(ack.ok)
        assertEquals("cadence_use_current", ack.command)
    }

    @Test
    fun cadenceUseCurrent_rejectedWhenInactive() {
        setRunBeatActive(false)
        val ack = router.handleCommand(GarminCommand.CadenceUseCurrent(171f))
        verify(playerRepository, never()).setTargetCadence(any())
        assertFalse(ack.ok)
    }

    @Test
    fun cadenceDelta_incrementsTargetCadenceWhenActive() {
        setRunBeatActive(true)
        val ack = router.handleCommand(GarminCommand.CadenceDelta(1))
        verify(playerRepository).incrementTargetCadence()
        verify(playerRepository, never()).decrementTargetCadence()
        assertTrue(ack.ok)
        assertEquals("cadence_delta", ack.command)
    }

    @Test
    fun cadenceDelta_decrementsTargetCadenceWhenActive() {
        setRunBeatActive(true)
        val ack = router.handleCommand(GarminCommand.CadenceDelta(-1))
        verify(playerRepository).decrementTargetCadence()
        verify(playerRepository, never()).incrementTargetCadence()
        assertTrue(ack.ok)
    }

    @Test
    fun cadenceDelta_rejectedWhenInactive() {
        setRunBeatActive(false)
        val ack = router.handleCommand(GarminCommand.CadenceDelta(1))
        verify(playerRepository, never()).incrementTargetCadence()
        verify(playerRepository, never()).decrementTargetCadence()
        assertFalse(ack.ok)
    }

    // ---------------------------------------------------------------
    // Speed correction toggle — RunBeat only
    // ---------------------------------------------------------------

    @Test
    fun speedCorrectionToggle_togglesWhenActive() {
        setRunBeatActive(true)
        val ack = router.handleCommand(GarminCommand.SpeedCorrectionToggle)
        verify(playerRepository).toggleSpeedCorrection()
        assertTrue(ack.ok)
        assertEquals("speed_correction_toggle", ack.command)
    }

    @Test
    fun speedCorrectionToggle_rejectedWhenInactive() {
        setRunBeatActive(false)
        val ack = router.handleCommand(GarminCommand.SpeedCorrectionToggle)
        verify(playerRepository, never()).toggleSpeedCorrection()
        assertFalse(ack.ok)
    }

    // ---------------------------------------------------------------
    // Mark not for running — RunBeat only, skips immediately
    // ---------------------------------------------------------------

    @Test
    fun markNotForRunning_flagsTrackAndSkipsWhenActive() {
        setRunBeatActive(true)
        currentTrack.value = track("track-1", "Song Title", "140")
        runBlocking { whenever(trackRepository.markTrackNotForRunning("track-1")).thenReturn(1) }

        val ack = router.handleCommand(GarminCommand.MarkNotForRunning)

        assertTrue(ack.ok)
        assertEquals("mark_not_for_running", ack.command)
        runBlocking { verify(trackRepository).markTrackNotForRunning("track-1") }
        verify(playerRepository).playNext()
    }

    @Test
    fun markNotForRunning_withoutCurrentTrack_returnsNotOk() {
        setRunBeatActive(true)
        currentTrack.value = null

        val ack = router.handleCommand(GarminCommand.MarkNotForRunning)

        assertFalse(ack.ok)
        verifyNoInteractions(trackRepository)
        verify(playerRepository, never()).playNext()
    }

    @Test
    fun markNotForRunning_withoutTrackIdExtra_returnsNotOk() {
        setRunBeatActive(true)
        currentTrack.value = MediaItem.Builder().setMediaId("no-extras").build()

        val ack = router.handleCommand(GarminCommand.MarkNotForRunning)

        assertFalse(ack.ok)
        verifyNoInteractions(trackRepository)
        verify(playerRepository, never()).playNext()
    }

    @Test
    fun markNotForRunning_rejectedWhenInactive() {
        setRunBeatActive(false)
        currentTrack.value = track("track-1", "Song Title", "140")

        val ack = router.handleCommand(GarminCommand.MarkNotForRunning)

        assertFalse(ack.ok)
        verifyNoInteractions(trackRepository)
        verify(playerRepository, never()).playNext()
    }

    // ---------------------------------------------------------------
    // currentState
    // ---------------------------------------------------------------

    @Test
    fun currentState_mapsAllFieldsWhenRunBeatActive() {
        setRunBeatActive(true)
        currentTrack.value = track("track-1", "Song Title", "140.5")
        targetCadence.value = 180f
        speedCorrectionEnabled.value = false
        playbackSpeed.value = 1.03f

        val state = router.currentState()

        assertEquals("RunBeat", state.source)
        assertTrue(state.isPlaying)
        assertEquals("track-1", state.trackId)
        assertEquals("Song Title", state.title)
        assertEquals(140.5f, state.bpm!!, 0.001f)
        assertEquals(180f, state.targetCadence!!, 0.001f)
        assertFalse(state.speedCorrection!!)
        assertEquals(1.03f, state.playbackSpeed!!, 0.001f)
        assertEquals(7, state.volume)
    }

    @Test
    fun currentState_omitsRunBeatOnlyFieldsWhenInactive() {
        setRunBeatActive(false)
        currentTrack.value = track("track-1", "Song Title", "140.5")

        val state = router.currentState()

        assertEquals("none", state.source)
        assertFalse(state.isPlaying)
        assertNull(state.trackId)
        assertNull(state.bpm)
        assertNull(state.targetCadence)
        assertNull(state.speedCorrection)
        assertNull(state.playbackSpeed)
        // The title is not a RunBeat-only field: it is reported whenever a
        // track is loaded.
        assertEquals("Song Title", state.title)
        assertEquals(7, state.volume)
    }

    @Test
    fun currentState_reportsRunBeatSourceWhileBuffering() {
        playerState.value = PlayerRepository.PlayerState(
            isPlaying = false,
            playbackState = Player.STATE_BUFFERING,
        )
        currentTrack.value = track("track-1", "Song Title", "140")

        val state = router.currentState()

        assertEquals("RunBeat", state.source)
        assertFalse(state.isPlaying)
        assertEquals("track-1", state.trackId)
    }

    @Test
    fun currentState_withoutTrack_reportsNoTrackFields() {
        setRunBeatActive(true)

        val state = router.currentState()

        assertEquals("RunBeat", state.source)
        assertNull(state.trackId)
        assertNull(state.title)
        assertNull(state.bpm)
        assertEquals(7, state.volume)
    }
}
