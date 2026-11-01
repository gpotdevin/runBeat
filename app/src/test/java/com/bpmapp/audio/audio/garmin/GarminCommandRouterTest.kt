// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.bpmapp.audio.audio.MediaItemFactory
import com.bpmapp.audio.audio.PlaylistRepository
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.audio.TrackRepository
import com.bpmapp.audio.data.Playlist
import com.bpmapp.audio.data.PlaylistTrack
import com.bpmapp.audio.data.Track
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
import org.mockito.kotlin.argumentCaptor
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
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var mediaKeyDispatcher: MediaKeyDispatcher
    private lateinit var externalPlaybackMonitor: ExternalPlaybackMonitor
    private lateinit var context: Context
    private lateinit var router: GarminCommandRouter

    private lateinit var playerState: MutableStateFlow<PlayerRepository.PlayerState>
    private lateinit var currentTrack: MutableStateFlow<MediaItem?>
    private lateinit var targetCadence: MutableStateFlow<Float>
    private lateinit var speedCorrectionEnabled: MutableStateFlow<Boolean>
    private lateinit var playbackSpeed: MutableStateFlow<Float>
    private lateinit var shuffleEnabled: MutableStateFlow<Boolean>

    @Before
    fun setUp() {
        playerRepository = mock()
        trackRepository = mock()
        playlistRepository = mock()
        mediaKeyDispatcher = mock()
        externalPlaybackMonitor = mock()
        whenever(externalPlaybackMonitor.currentExternalPlayback()).thenReturn(null)
        context = ApplicationProvider.getApplicationContext<Context>()
        // Start each test with a clean "AppSettings" prefs file; the
        // exclude-not-for-running default is ON.
        context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        playerState = MutableStateFlow(PlayerRepository.PlayerState())
        currentTrack = MutableStateFlow(null)
        targetCadence = MutableStateFlow(172f)
        speedCorrectionEnabled = MutableStateFlow(true)
        playbackSpeed = MutableStateFlow(1.0f)
        shuffleEnabled = MutableStateFlow(false)
        whenever(playerRepository.playerState).thenReturn(playerState)
        whenever(playerRepository.currentTrack).thenReturn(currentTrack)
        whenever(playerRepository.targetCadence).thenReturn(targetCadence)
        whenever(playerRepository.speedCorrectionEnabled).thenReturn(speedCorrectionEnabled)
        whenever(playerRepository.playbackSpeed).thenReturn(playbackSpeed)
        whenever(playerRepository.shuffleEnabled).thenReturn(shuffleEnabled)
        whenever(mediaKeyDispatcher.currentVolume()).thenReturn(7)
        router = GarminCommandRouter(
            playerRepository,
            trackRepository,
            playlistRepository,
            MediaItemFactory(),
            mediaKeyDispatcher,
            externalPlaybackMonitor,
            context,
        )
    }

    private fun setExcludeNotForRunning(value: Boolean) {
        context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SettingsKeys.KEY_EXCLUDE_NOT_FOR_RUNNING_TRACKS, value)
            .commit()
    }

    private fun libraryTrack(trackId: String, notForRunning: Boolean = false): Track =
        Track(
            id = trackId,
            relativePath = "Music",
            fileName = "$trackId.mp3",
            bpm = 140f,
            fileSizeBytes = null,
            durationMs = null,
            notForRunning = notForRunning,
        )

    private fun setRunBeatActive(active: Boolean) {
        playerState.value = PlayerRepository.PlayerState(
            isPlaying = active,
            playbackState = if (active) Player.STATE_READY else Player.STATE_IDLE,
        )
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

    // Paused with a loaded track: RunBeat owns the track context (the watch
    // keeps showing it) but does NOT hold the media stream — transport must
    // route to media keys so other players stay controllable from the watch.
    @Test
    fun isRunBeatActive_falseWhenPausedWithTrack() {
        setRunBeatActive(false)
        currentTrack.value = track("t1", "Title", "140")
        assertFalse(router.isRunBeatActive())
    }

    @Test
    fun toggle_routesToMediaKeysWhenPausedWithTrack() {
        setRunBeatActive(false)
        currentTrack.value = track("t1", "Title", "140")

        runBlocking { router.handleCommand(GarminCommand.Toggle) }

        verify(mediaKeyDispatcher).toggle()
        verify(playerRepository, never()).togglePlayPause()
    }

    // RunBeat-only settings are accepted while paused with a loaded track:
    // the target cadence is adjustable without playing.
    @Test
    fun cadenceDelta_appliedWhenPausedWithTrack() {
        setRunBeatActive(false)
        currentTrack.value = track("t1", "Title", "140")

        val ack = runBlocking { router.handleCommand(GarminCommand.CadenceDelta(1)) }

        assertTrue(ack.ok)
        verify(playerRepository).incrementTargetCadence()
    }

    // ---------------------------------------------------------------
    // Transport commands — RunBeat active
    // ---------------------------------------------------------------

    @Test
    fun toggle_routesToPlayerRepositoryWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.Toggle) }
        verify(playerRepository).togglePlayPause()
        verify(mediaKeyDispatcher, never()).toggle()
        assertTrue(ack.ok)
        assertEquals("toggle", ack.command)
    }

    @Test
    fun play_routesToPlayerRepositoryWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.Play) }
        verify(playerRepository).play()
        verify(mediaKeyDispatcher, never()).play()
        assertTrue(ack.ok)
        assertEquals("play", ack.command)
    }

    @Test
    fun pause_routesToPlayerRepositoryWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.Pause) }
        verify(playerRepository).pause()
        verify(mediaKeyDispatcher, never()).pause()
        assertTrue(ack.ok)
        assertEquals("pause", ack.command)
    }

    @Test
    fun next_routesToPlayerRepositoryWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.Next) }
        verify(playerRepository).playNext()
        verify(mediaKeyDispatcher, never()).next()
        assertTrue(ack.ok)
        assertEquals("next", ack.command)
    }

    @Test
    fun prev_routesToPlayerRepositoryWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.Prev) }
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
        val ack = runBlocking { router.handleCommand(GarminCommand.Toggle) }
        verify(mediaKeyDispatcher).toggle()
        verify(playerRepository, never()).togglePlayPause()
        assertTrue(ack.ok)
    }

    @Test
    fun play_routesToMediaKeyDispatcherWhenInactive() {
        setRunBeatActive(false)
        val ack = runBlocking { router.handleCommand(GarminCommand.Play) }
        verify(mediaKeyDispatcher).play()
        verify(playerRepository, never()).play()
        assertTrue(ack.ok)
    }

    @Test
    fun pause_routesToMediaKeyDispatcherWhenInactive() {
        setRunBeatActive(false)
        val ack = runBlocking { router.handleCommand(GarminCommand.Pause) }
        verify(mediaKeyDispatcher).pause()
        verify(playerRepository, never()).pause()
        assertTrue(ack.ok)
    }

    @Test
    fun next_routesToMediaKeyDispatcherWhenInactive() {
        setRunBeatActive(false)
        val ack = runBlocking { router.handleCommand(GarminCommand.Next) }
        verify(mediaKeyDispatcher).next()
        verify(playerRepository, never()).playNext()
        assertTrue(ack.ok)
    }

    @Test
    fun prev_routesToMediaKeyDispatcherWhenInactive() {
        setRunBeatActive(false)
        val ack = runBlocking { router.handleCommand(GarminCommand.Prev) }
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
        val ack = runBlocking { router.handleCommand(GarminCommand.VolumeDelta(1)) }
        verify(mediaKeyDispatcher).adjustStreamVolume(1)
        assertTrue(ack.ok)
        assertEquals("volume_delta", ack.command)
    }

    @Test
    fun volumeDelta_usesDispatcherWhenInactive() {
        setRunBeatActive(false)
        val ack = runBlocking { router.handleCommand(GarminCommand.VolumeDelta(-1)) }
        verify(mediaKeyDispatcher).adjustStreamVolume(-1)
        assertTrue(ack.ok)
    }

    // ---------------------------------------------------------------
    // Cadence commands — RunBeat only
    // ---------------------------------------------------------------

    @Test
    fun cadenceSet_appliesTargetCadenceWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.CadenceSet(180f)) }
        verify(playerRepository).setTargetCadence(180f)
        assertTrue(ack.ok)
        assertEquals("cadence_set", ack.command)
    }

    @Test
    fun cadenceSet_rejectedWhenInactive() {
        setRunBeatActive(false)
        val ack = runBlocking { router.handleCommand(GarminCommand.CadenceSet(180f)) }
        verify(playerRepository, never()).setTargetCadence(any())
        assertFalse(ack.ok)
        assertEquals("cadence_set", ack.command)
    }

    @Test
    fun cadenceSet_rejectsNonFiniteCadenceWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.CadenceSet(Float.NaN)) }
        verify(playerRepository, never()).setTargetCadence(any())
        assertFalse(ack.ok)
        assertEquals("cadence_set", ack.command)
    }

    @Test
    fun cadenceUseCurrent_appliesTargetCadenceWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.CadenceUseCurrent(171f)) }
        verify(playerRepository).setTargetCadence(171f)
        assertTrue(ack.ok)
        assertEquals("cadence_use_current", ack.command)
    }

    @Test
    fun cadenceUseCurrent_rejectedWhenInactive() {
        setRunBeatActive(false)
        val ack = runBlocking { router.handleCommand(GarminCommand.CadenceUseCurrent(171f)) }
        verify(playerRepository, never()).setTargetCadence(any())
        assertFalse(ack.ok)
    }

    @Test
    fun cadenceUseCurrent_rejectsNonFiniteCadenceWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking {
            router.handleCommand(GarminCommand.CadenceUseCurrent(Float.POSITIVE_INFINITY))
        }
        verify(playerRepository, never()).setTargetCadence(any())
        assertFalse(ack.ok)
        assertEquals("cadence_use_current", ack.command)
    }

    @Test
    fun cadenceDelta_incrementsTargetCadenceWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.CadenceDelta(1)) }
        verify(playerRepository).incrementTargetCadence()
        verify(playerRepository, never()).decrementTargetCadence()
        assertTrue(ack.ok)
        assertEquals("cadence_delta", ack.command)
    }

    @Test
    fun cadenceDelta_decrementsTargetCadenceWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.CadenceDelta(-1)) }
        verify(playerRepository).decrementTargetCadence()
        verify(playerRepository, never()).incrementTargetCadence()
        assertTrue(ack.ok)
    }

    @Test
    fun cadenceDelta_rejectedWhenInactive() {
        setRunBeatActive(false)
        val ack = runBlocking { router.handleCommand(GarminCommand.CadenceDelta(1)) }
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
        val ack = runBlocking { router.handleCommand(GarminCommand.SpeedCorrectionToggle) }
        verify(playerRepository).toggleSpeedCorrection()
        assertTrue(ack.ok)
        assertEquals("speed_correction_toggle", ack.command)
    }

    @Test
    fun speedCorrectionToggle_rejectedWhenInactive() {
        setRunBeatActive(false)
        val ack = runBlocking { router.handleCommand(GarminCommand.SpeedCorrectionToggle) }
        verify(playerRepository, never()).toggleSpeedCorrection()
        assertFalse(ack.ok)
    }

    // ---------------------------------------------------------------
    // Shuffle toggle — RunBeat only
    // ---------------------------------------------------------------

    @Test
    fun shuffleToggle_togglesShuffleWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.ShuffleToggle) }
        verify(playerRepository).toggleShuffle()
        assertTrue(ack.ok)
        assertEquals("shuffle_toggle", ack.command)
    }

    @Test
    fun shuffleToggle_rejectedWhenInactive() {
        setRunBeatActive(false)
        val ack = runBlocking { router.handleCommand(GarminCommand.ShuffleToggle) }
        verify(playerRepository, never()).toggleShuffle()
        assertFalse(ack.ok)
        assertEquals("shuffle_toggle", ack.command)
    }

    // ---------------------------------------------------------------
    // Mark not for running — RunBeat only, skips immediately
    // ---------------------------------------------------------------

    @Test
    fun markNotForRunning_flagsTrackAndSkipsWhenActive() {
        setRunBeatActive(true)
        currentTrack.value = track("track-1", "Song Title", "140")
        runBlocking { whenever(trackRepository.markTrackNotForRunning("track-1")).thenReturn(1) }

        val ack = runBlocking { router.handleCommand(GarminCommand.MarkNotForRunning) }

        assertTrue(ack.ok)
        assertEquals("mark_not_for_running", ack.command)
        runBlocking { verify(trackRepository).markTrackNotForRunning("track-1") }
        verify(playerRepository).playNext()
    }

    @Test
    fun markNotForRunning_withoutCurrentTrack_returnsNotOk() {
        setRunBeatActive(true)
        currentTrack.value = null

        val ack = runBlocking { router.handleCommand(GarminCommand.MarkNotForRunning) }

        assertFalse(ack.ok)
        verifyNoInteractions(trackRepository)
        verify(playerRepository, never()).playNext()
    }

    @Test
    fun markNotForRunning_withoutTrackIdExtra_returnsNotOk() {
        setRunBeatActive(true)
        currentTrack.value = MediaItem.Builder().setMediaId("no-extras").build()

        val ack = runBlocking { router.handleCommand(GarminCommand.MarkNotForRunning) }

        assertFalse(ack.ok)
        verifyNoInteractions(trackRepository)
        verify(playerRepository, never()).playNext()
    }

    @Test
    fun markNotForRunning_rejectedWhenInactive() {
        setRunBeatActive(false)

        val ack = runBlocking { router.handleCommand(GarminCommand.MarkNotForRunning) }

        assertFalse(ack.ok)
        verifyNoInteractions(trackRepository)
        verify(playerRepository, never()).playNext()
    }

    // ---------------------------------------------------------------
    // Seek — RunBeat-only absolute position
    // ---------------------------------------------------------------

    @Test
    fun seek_routesToPlayerRepositorySeekToWhenActive() {
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.Seek(42000L)) }
        verify(playerRepository).seekTo(42000L)
        // An absolute seek has no media-key equivalent: nothing is dispatched.
        verify(mediaKeyDispatcher, never()).toggle()
        verify(mediaKeyDispatcher, never()).play()
        verify(mediaKeyDispatcher, never()).pause()
        verify(mediaKeyDispatcher, never()).next()
        verify(mediaKeyDispatcher, never()).previous()
        verify(mediaKeyDispatcher, never()).adjustStreamVolume(any())
        assertTrue(ack.ok)
        assertEquals("seek", ack.command)
    }

    @Test
    fun seek_ignoredWhenInactive() {
        setRunBeatActive(false)
        val ack = runBlocking { router.handleCommand(GarminCommand.Seek(42000L)) }
        verify(playerRepository, never()).seekTo(any())
        verify(mediaKeyDispatcher, never()).toggle()
        verify(mediaKeyDispatcher, never()).play()
        verify(mediaKeyDispatcher, never()).pause()
        verify(mediaKeyDispatcher, never()).next()
        verify(mediaKeyDispatcher, never()).previous()
        verify(mediaKeyDispatcher, never()).adjustStreamVolume(any())
        assertFalse(ack.ok)
        assertEquals("seek", ack.command)
    }

    @Test
    fun requestState_isNotRoutedAsAPlaybackCommand() {
        // The manager intercepts request_state before the router; if one ever
        // slipped through, it must not touch the player or the media keys.
        setRunBeatActive(true)
        val ack = runBlocking { router.handleCommand(GarminCommand.RequestState) }
        verify(playerRepository, never()).seekTo(any())
        verify(playerRepository, never()).togglePlayPause()
        verify(playerRepository, never()).play()
        verify(playerRepository, never()).pause()
        verify(playerRepository, never()).playNext()
        verify(playerRepository, never()).playPrevious()
        verify(mediaKeyDispatcher, never()).toggle()
        verify(mediaKeyDispatcher, never()).adjustStreamVolume(any())
        assertEquals("request_state", ack.command)
    }

    // ---------------------------------------------------------------
    // Playlist play — load + play the playlist's playable tracks
    // ---------------------------------------------------------------

    private fun stubPlaylist(playlistId: String, tracks: List<Track>) {
        runBlocking {
            whenever(playlistRepository.tracksForPlaylist(playlistId)).thenReturn(
                tracks.mapIndexed { index, track ->
                    PlaylistTrack(playlistId = playlistId, trackId = track.id, position = index)
                }
            )
            tracks.forEach { track ->
                whenever(playlistRepository.trackById(track.id)).thenReturn(track)
            }
        }
    }

    private fun loadedTrackIds(): List<String> {
        val captor = argumentCaptor<List<MediaItem>>()
        verify(playerRepository).loadPlaylist(captor.capture())
        return captor.firstValue.mapNotNull { it.mediaMetadata.extras?.getString("trackId") }
    }

    @Test
    fun playlistPlay_loadsAndPlaysPlaylistTracksInOrder() {
        stubPlaylist(
            "pl-1",
            listOf(libraryTrack("t1"), libraryTrack("t2"), libraryTrack("t3"))
        )

        val ack = runBlocking { router.handleCommand(GarminCommand.PlaylistPlay("pl-1")) }

        assertTrue(ack.ok)
        assertEquals("playlist_play", ack.command)
        assertEquals(listOf("t1", "t2", "t3"), loadedTrackIds())
        verify(playerRepository).play()
    }

    @Test
    fun playlistPlay_appliesExclusionFilterWhenEnabled() {
        // The exclude-not-for-running setting defaults to ON.
        stubPlaylist(
            "pl-1",
            listOf(libraryTrack("t1"), libraryTrack("t2", notForRunning = true), libraryTrack("t3"))
        )

        val ack = runBlocking { router.handleCommand(GarminCommand.PlaylistPlay("pl-1")) }

        assertTrue(ack.ok)
        assertEquals(listOf("t1", "t3"), loadedTrackIds())
        verify(playerRepository).play()
    }

    @Test
    fun playlistPlay_includesFlaggedTracksWhenExclusionDisabled() {
        setExcludeNotForRunning(false)
        stubPlaylist(
            "pl-1",
            listOf(libraryTrack("t1"), libraryTrack("t2", notForRunning = true))
        )

        val ack = runBlocking { router.handleCommand(GarminCommand.PlaylistPlay("pl-1")) }

        assertTrue(ack.ok)
        assertEquals(listOf("t1", "t2"), loadedTrackIds())
        verify(playerRepository).play()
    }

    @Test
    fun playlistPlay_missingPlaylist_returnsNotOk() {
        runBlocking {
            whenever(playlistRepository.tracksForPlaylist("no-such-playlist")).thenReturn(emptyList())
        }

        val ack = runBlocking { router.handleCommand(GarminCommand.PlaylistPlay("no-such-playlist")) }

        assertFalse(ack.ok)
        assertEquals("playlist_play", ack.command)
        verify(playerRepository, never()).loadPlaylist(any())
        verify(playerRepository, never()).play()
    }

    @Test
    fun playlistPlay_emptyAfterExclusionFilter_returnsNotOk() {
        // Every track of the playlist is marked "not for running" and the
        // exclusion setting is ON: nothing playable, ack ok=false.
        stubPlaylist(
            "pl-1",
            listOf(libraryTrack("t1", notForRunning = true), libraryTrack("t2", notForRunning = true))
        )

        val ack = runBlocking { router.handleCommand(GarminCommand.PlaylistPlay("pl-1")) }

        assertFalse(ack.ok)
        assertEquals("playlist_play", ack.command)
        verify(playerRepository, never()).loadPlaylist(any())
        verify(playerRepository, never()).play()
    }

    @Test
    fun playlistPlay_missingTrackEntitiesAreSkipped() {
        // A playlist relation whose track is no longer in the library is
        // dropped from the play queue.
        runBlocking {
            whenever(playlistRepository.tracksForPlaylist("pl-1")).thenReturn(
                listOf(
                    PlaylistTrack(playlistId = "pl-1", trackId = "gone", position = 0),
                    PlaylistTrack(playlistId = "pl-1", trackId = "t1", position = 1),
                )
            )
            whenever(playlistRepository.trackById("gone")).thenReturn(null)
            whenever(playlistRepository.trackById("t1")).thenReturn(libraryTrack("t1"))
        }

        val ack = runBlocking { router.handleCommand(GarminCommand.PlaylistPlay("pl-1")) }

        assertTrue(ack.ok)
        assertEquals(listOf("t1"), loadedTrackIds())
        verify(playerRepository).play()
    }

    @Test
    fun playlistList_isNotRoutedAsAPlaybackCommand() {
        // The manager intercepts playlist_list before the router; if one ever
        // slipped through, it must not touch the player or the media keys.
        val ack = runBlocking { router.handleCommand(GarminCommand.PlaylistList) }
        verify(playerRepository, never()).loadPlaylist(any())
        verify(playerRepository, never()).play()
        verify(mediaKeyDispatcher, never()).toggle()
        assertEquals("playlist_list", ack.command)
    }

    @Test
    fun currentPlaylists_returnsIdNamePairs() {
        runBlocking {
            whenever(playlistRepository.playlistsOnce()).thenReturn(
                listOf(Playlist(id = "id-1", name = "Chill"), Playlist(id = "id-2", name = "Run"))
            )
        }

        val playlists = runBlocking { router.currentPlaylists() }

        assertEquals(listOf("id-1" to "Chill", "id-2" to "Run"), playlists)
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
        shuffleEnabled.value = true

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
        assertTrue(state.shuffle)
    }

    @Test
    fun currentState_includesArtistAlbumDurationPositionWhenActive() {
        playerState.value = PlayerRepository.PlayerState(
            isPlaying = true,
            playbackState = Player.STATE_READY,
            currentPosition = 42500L,
            duration = 213000L,
        )
        currentTrack.value = track(
            trackId = "track-1",
            title = "Song Title",
            bpm = "140.5",
            artist = "Song Artist",
            album = "Song Album",
            durationMs = 213000L,
        )

        val state = router.currentState()

        assertEquals("RunBeat", state.source)
        // artist / album come from the track's MediaMetadata.
        assertEquals("Song Artist", state.artist)
        assertEquals("Song Album", state.album)
        // durationMs comes from the track's extras.durationMs.
        assertEquals(213000L, state.durationMs)
        // positionMs is the player position rounded down to 5 s buckets.
        assertEquals(40000L, state.positionMs)
    }

    @Test
    fun currentState_fallsBackToPlayerStateDurationWhenExtraMissing() {
        playerState.value = PlayerRepository.PlayerState(
            isPlaying = true,
            playbackState = Player.STATE_READY,
            currentPosition = 1500L,
            duration = 180000L,
        )
        currentTrack.value = track(
            trackId = "track-1",
            title = "Song Title",
            bpm = "140.5",
            artist = "Song Artist",
            album = "Song Album",
        )

        val state = router.currentState()

        // No extras.durationMs: fall back to PlayerState.duration.
        assertEquals(180000L, state.durationMs)
        // 1500 ms rounds down to the 0 s bucket.
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun currentState_omitsRunBeatOnlyFieldsWhenInactive() {
        setRunBeatActive(false)

        val state = router.currentState()

        assertEquals("none", state.source)
        assertFalse(state.isPlaying)
        assertNull(state.trackId)
        assertNull(state.artist)
        assertNull(state.album)
        assertNull(state.durationMs)
        assertNull(state.positionMs)
        assertNull(state.bpm)
        assertNull(state.targetCadence)
        assertNull(state.speedCorrection)
        assertNull(state.playbackSpeed)
        // The title is RunBeat-only too: it is omitted when RunBeat is not
        // active so the watch does not show a stale RunBeat title with
        // source "none".
        assertNull(state.title)
        assertEquals(7, state.volume)
        // Shuffle is a RunBeat-only field: false when not owned.
        assertFalse(state.shuffle)
    }

    @Test
    fun currentState_omitsTitleWhenRunBeatInactive() {
        setRunBeatActive(false)

        val state = router.currentState()

        assertEquals("none", state.source)
        assertNull(state.title)
    }

    // ---------------------------------------------------------------
    // currentState — external app playing (source "external")
    // ---------------------------------------------------------------

    @Test
    fun currentState_reportsExternalTrackWhenExternalAppPlaying() {
        setRunBeatActive(false)
        whenever(externalPlaybackMonitor.currentExternalPlayback()).thenReturn(
            ExternalPlayback(
                packageName = "com.spotify.music",
                title = "External Song",
                artist = "External Artist",
                album = "External Album",
                isPlaying = true,
            )
        )

        val state = router.currentState()

        assertEquals("external", state.source)
        assertTrue(state.isPlaying)
        assertEquals("External Song", state.title)
        assertEquals("External Artist", state.artist)
        assertEquals("External Album", state.album)
        // RunBeat-only fields stay null for an external source.
        assertNull(state.trackId)
        assertNull(state.durationMs)
        assertNull(state.positionMs)
        assertNull(state.bpm)
        assertNull(state.targetCadence)
        assertNull(state.speedCorrection)
        assertNull(state.playbackSpeed)
        assertEquals(7, state.volume)
    }

    @Test
    fun currentState_reportsExternalPausedWithoutLosingTrackName() {
        // Paused external app: still source "external" with the track name,
        // isPlaying false — the watch shows the track with a play icon.
        setRunBeatActive(false)
        whenever(externalPlaybackMonitor.currentExternalPlayback()).thenReturn(
            ExternalPlayback(
                packageName = "com.qobuz.app",
                title = "Paused Song",
                artist = null,
                album = null,
                isPlaying = false,
            )
        )

        val state = router.currentState()

        assertEquals("external", state.source)
        assertFalse(state.isPlaying)
        assertEquals("Paused Song", state.title)
        assertNull(state.artist)
        assertNull(state.album)
    }

    @Test
    fun currentState_externalWithoutTitle_stillReportsExternalSource() {
        // An external session without metadata (e.g. a podcast app): the
        // watch falls back to the plain "External app" label.
        setRunBeatActive(false)
        whenever(externalPlaybackMonitor.currentExternalPlayback()).thenReturn(
            ExternalPlayback(
                packageName = "com.podcast.app",
                title = null,
                artist = null,
                album = null,
                isPlaying = true,
            )
        )

        val state = router.currentState()

        assertEquals("external", state.source)
        assertTrue(state.isPlaying)
        assertNull(state.title)
    }

    @Test
    fun currentState_externalWinsOverStaleRunBeatTrack() {
        // RunBeat paused with a loaded track is "owned" and keeps source
        // "RunBeat"; only when RunBeat has no track at all does the external
        // session get reported. Here RunBeat has nothing loaded.
        setRunBeatActive(false)
        currentTrack.value = null
        whenever(externalPlaybackMonitor.currentExternalPlayback()).thenReturn(
            ExternalPlayback("com.spotify.music", "Other Song", null, null, true)
        )

        val state = router.currentState()

        assertEquals("external", state.source)
        assertEquals("Other Song", state.title)
        assertNull(state.trackId)
    }

    // A paused player with a loaded track stays source "RunBeat" — the
    // watch keeps displaying the current track, exactly as the app does.
    @Test
    fun currentState_keepsTrackWhilePausedWithTrack() {
        setRunBeatActive(false)
        currentTrack.value = track("track-1", "Song Title", "140.5")

        val state = router.currentState()

        assertEquals("RunBeat", state.source)
        assertFalse(state.isPlaying)
        assertEquals("track-1", state.trackId)
        assertEquals("Song Title", state.title)
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
