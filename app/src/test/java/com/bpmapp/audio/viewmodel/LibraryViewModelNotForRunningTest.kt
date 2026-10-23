// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bpmapp.audio.audio.BpmResolver
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.audio.TrackRepository
import com.bpmapp.audio.data.AppDatabase
import com.bpmapp.audio.data.PlaylistDao
import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the "not for running" play-queue filtering in LibraryViewModel:
 * the persisted excludeNotForRunningTracks setting (default ON) and the
 * playableTracks / getPlayableMediaItems queue builders honoring it.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelNotForRunningTest {

    private lateinit var context: Context
    private lateinit var trackDao: TrackDao
    private lateinit var appDatabase: AppDatabase
    private lateinit var trackRepository: TrackRepository
    private lateinit var playerRepository: PlayerRepository
    private lateinit var viewModel: LibraryViewModel

    private val tracks = listOf(
        Track(
            id = "normal",
            relativePath = ".",
            fileName = "normal.mp3",
            bpm = 120.0f,
            fileSizeBytes = null,
            durationMs = null
        ),
        Track(
            id = "flagged",
            relativePath = ".",
            fileName = "flagged.mp3",
            bpm = 130.0f,
            fileSizeBytes = null,
            durationMs = null,
            notForRunning = true
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE).edit().clear().commit()

        trackDao = mock {
            on { getAllTracks() } doReturn flowOf(tracks)
            on { getFavoriteTracks() } doReturn flowOf(emptyList())
        }
        val playlistDao: PlaylistDao = mock {
            on { getAllPlaylists() } doReturn flowOf(emptyList())
            on { getAllPlaylistTracks() } doReturn flowOf(emptyList())
        }
        appDatabase = mock {
            on { trackDao() } doReturn trackDao
            on { playlistDao() } doReturn playlistDao
        }
        trackRepository = mock {
            on { allTracks } doReturn flowOf(tracks)
        }
        playerRepository = mock {
            on { targetCadence } doReturn MutableStateFlow(172f)
        }
        val bpmResolver: BpmResolver = mock()

        viewModel = LibraryViewModel(trackRepository, bpmResolver, playerRepository, appDatabase, context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun excludeNotForRunningTracks_defaultsToTrue() {
        assertTrue("Exclusion must be ON by default", viewModel.excludeNotForRunningTracks.value)
    }

    @Test
    fun playableTracks_excludesFlaggedTracksByDefault() {
        val playable = viewModel.playableTracks(tracks)
        assertEquals(listOf("normal"), playable.map { it.id })
    }

    @Test
    fun playableTracks_includesFlaggedTracksWhenSettingDisabled() {
        viewModel.setExcludeNotForRunningTracks(false)
        val playable = viewModel.playableTracks(tracks)
        assertEquals(listOf("normal", "flagged"), playable.map { it.id })
    }

    @Test
    fun playableTracks_readsSettingChangedOutsideTheViewModel() {
        // The Settings screen writes the same SharedPreferences key; the queue
        // builder must pick the change up even in this Activity-scoped instance.
        context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("exclude_not_for_running_tracks", false)
            .commit()
        val playable = viewModel.playableTracks(tracks)
        assertEquals(listOf("normal", "flagged"), playable.map { it.id })
    }

    @Test
    fun getPlayableMediaItems_buildsQueueWithoutFlaggedTracks() {
        val mediaItems = viewModel.getPlayableMediaItems(tracks)
        assertEquals(1, mediaItems.size)
        assertEquals("normal", mediaItems[0].mediaMetadata.extras?.getString("trackId"))
    }

    @Test
    fun setExcludeNotForRunningTracks_persistsToSharedPreferences() {
        viewModel.setExcludeNotForRunningTracks(false)

        // A new ViewModel instance (e.g. after process death) reads the stored value
        val reloaded = LibraryViewModel(
            trackRepository,
            mock(),
            playerRepository,
            appDatabase,
            context
        )
        assertEquals(false, reloaded.excludeNotForRunningTracks.value)
        assertEquals(listOf("normal", "flagged"), reloaded.playableTracks(tracks).map { it.id })
    }

    @Test
    fun toggleNotForRunning_flipsTheFlagInTheDatabase() = runBlocking<Unit> {
        val track = Track(
            id = "1",
            relativePath = ".",
            fileName = "one.mp3",
            bpm = null,
            fileSizeBytes = null,
            durationMs = null,
            notForRunning = false
        )
        whenever(trackDao.getTrackById("1")).thenReturn(flowOf(track))

        viewModel.toggleNotForRunning("1")

        verify(trackDao, timeout(5000)).updateNotForRunning("1", true)
    }

    @Test
    fun toggleNotForRunning_unmarksFlaggedTrack() = runBlocking<Unit> {
        val track = Track(
            id = "2",
            relativePath = ".",
            fileName = "two.mp3",
            bpm = null,
            fileSizeBytes = null,
            durationMs = null,
            notForRunning = true
        )
        whenever(trackDao.getTrackById("2")).thenReturn(flowOf(track))

        viewModel.toggleNotForRunning("2")

        verify(trackDao, timeout(5000)).updateNotForRunning("2", false)
    }
}
