// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BpmResolverTest {

    private lateinit var context: Context
    private lateinit var trackRepository: TrackRepository
    private lateinit var metadataEditor: MetadataEditor
    private lateinit var resolver: BpmResolver

    private fun track(bpm: Float? = null): Track {
        return Track(
            id = "file:///music/test.mp3",
            relativePath = ".",
            fileName = "test.mp3",
            bpm = bpm,
            fileSizeBytes = 1000L,
            durationMs = 180000L,
            source = TrackSource.APP
        )
    }

    @Before
    fun setup() {
        context = mock()
        trackRepository = mock()
        metadataEditor = mock()
        resolver = BpmResolver(context, trackRepository, metadataEditor)
    }

    @Test
    fun resolveBpmForTrack_knownDbBpmReturnsImmediately() = runTest {
        val t = track(bpm = 128.0f)

        val result = resolver.resolveBpmForTrack(t)

        assertEquals(128.0f, result!!, 0.01f)
        verifyBlocking(trackRepository, never()) {
            updateBpm(any(), any())
        }
    }

    @Test
    fun resolveBpmForTrack_id3BpmUsedAndPersisted() = runTest {
        val t = track(bpm = null)
        wheneverBlocking {
            metadataEditor.readBpmFromFile(any())
        }.thenReturn(140.0f)

        val result = resolver.resolveBpmForTrack(t)

        assertEquals(140.0f, result!!, 0.01f)
        verifyBlocking(trackRepository, times(1)) {
            updateBpm(t.id, 140.0f)
        }
    }

    @Test
    fun resolveBpmForTrack_unknownBpmReturnsNull() = runTest {
        val t = track(bpm = null)
        wheneverBlocking {
            metadataEditor.readBpmFromFile(any())
        }.thenReturn(null)

        val result = resolver.resolveBpmForTrack(t)

        assertNull(result)
    }

    @Test
    fun resolveBpmForTracks_returnsMapForAll() = runTest {
        val t1 = track(bpm = 100.0f).copy(id = "file:///music/test1.mp3")
        val t2 = track(bpm = null).copy(id = "file:///music/test2.mp3")
        val tracks = listOf(t1, t2)
        wheneverBlocking {
            metadataEditor.readBpmFromFile(any())
        }.thenReturn(null)

        val result = resolver.resolveBpmForTracks(tracks)

        assertNotNull(result)
        assertEquals(2, result.size)
        assertEquals(100.0f, result[t1.id]!!, 0.01f)
        assertNull(result[t2.id])
    }
}