// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import com.bpmapp.audio.data.TrackDao
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for the "not for running" flag updates in [TrackRepository]:
 * markTrackNotForRunning / unmarkTrackNotForRunning must call through to the
 * DAO with the right flag value and pass the DAO result back to the caller.
 */
class TrackRepositoryNotForRunningTest {

    private lateinit var trackDao: TrackDao
    private lateinit var repository: TrackRepository

    @Before
    fun setUp() {
        trackDao = mock {
            on { getAllTracks() } doReturn flowOf(emptyList())
        }
        repository = TrackRepository(trackDao, mock<Context>())
    }

    @Test
    fun markTrackNotForRunning_setsTheFlagThroughTheDao() = runBlocking<Unit> {
        whenever(trackDao.updateNotForRunning("track-1", true)).thenReturn(1)

        val updatedRows = repository.markTrackNotForRunning("track-1")

        assertEquals("The DAO result must be returned to the caller", 1, updatedRows)
        verify(trackDao).updateNotForRunning("track-1", true)
    }

    @Test
    fun unmarkTrackNotForRunning_clearsTheFlagThroughTheDao() = runBlocking<Unit> {
        whenever(trackDao.updateNotForRunning("track-1", false)).thenReturn(1)

        val updatedRows = repository.unmarkTrackNotForRunning("track-1")

        assertEquals("The DAO result must be returned to the caller", 1, updatedRows)
        verify(trackDao).updateNotForRunning("track-1", false)
    }
}
