// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bpmapp.audio.audio.PlayerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the excludeNotForRunningTracks setting in [SettingsViewModel]:
 * the default (ON) when unset, and persistence of the stored value across
 * ViewModel instances (e.g. after process death).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelNotForRunningTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var playerRepository: PlayerRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE).edit().clear().commit()
        playerRepository = mock {
            on { modeBeepSignal } doReturn MutableStateFlow(true)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): SettingsViewModel = SettingsViewModel(context, playerRepository)

    @Test
    fun excludeNotForRunningTracks_defaultsToTrueWhenUnset() {
        val viewModel = newViewModel()

        assertTrue("Exclusion must be ON by default", viewModel.excludeNotForRunningTracks.value)
    }

    @Test
    fun setExcludeNotForRunningTracks_falsePersistsToNewInstances() {
        val viewModel = newViewModel()

        viewModel.setExcludeNotForRunningTracks(false)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, viewModel.excludeNotForRunningTracks.value)

        // A new ViewModel instance (e.g. after process death) reads the stored value.
        val reloaded = newViewModel()
        assertEquals(false, reloaded.excludeNotForRunningTracks.value)
    }

    @Test
    fun setExcludeNotForRunningTracks_truePersistsToNewInstances() {
        context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("exclude_not_for_running_tracks", false)
            .commit()
        val viewModel = newViewModel()
        assertEquals(false, viewModel.excludeNotForRunningTracks.value)

        viewModel.setExcludeNotForRunningTracks(true)
        testDispatcher.scheduler.advanceUntilIdle()

        val reloaded = newViewModel()
        assertEquals(true, reloaded.excludeNotForRunningTracks.value)
    }
}
