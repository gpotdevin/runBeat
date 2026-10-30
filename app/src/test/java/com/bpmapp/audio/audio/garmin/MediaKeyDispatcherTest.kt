// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.media.AudioManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [MediaKeyDispatcher] with a mocked [AudioManager].
 *
 * Notification access is not granted in the Robolectric environment, so the
 * transport commands exercise the [AudioManager.dispatchMediaKeyEvent]
 * fallback path; the session-selection logic is covered separately through
 * [MediaKeyDispatcher.selectExternalController].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaKeyDispatcherTest {

    private lateinit var audioManager: AudioManager
    private lateinit var dispatcher: MediaKeyDispatcher

    @Before
    fun setUp() {
        audioManager = mock()
        dispatcher = MediaKeyDispatcher(ApplicationProvider.getApplicationContext(), audioManager)
    }

    /** Dispatches one transport command and returns the captured key events. */
    private fun captureKeyEvents(action: () -> Unit): List<KeyEvent> {
        action()
        val captor = argumentCaptor<KeyEvent>()
        verify(audioManager, times(2)).dispatchMediaKeyEvent(captor.capture())
        return captor.allValues
    }

    private fun assertKeyEventPair(events: List<KeyEvent>, keyCode: Int) {
        assertEquals(2, events.size)
        assertEquals(keyCode, events[0].keyCode)
        assertEquals(KeyEvent.ACTION_DOWN, events[0].action)
        assertEquals(keyCode, events[1].keyCode)
        assertEquals(KeyEvent.ACTION_UP, events[1].action)
    }

    @Test
    fun toggleDispatchesPlayPauseDownThenUp() {
        assertKeyEventPair(captureKeyEvents { dispatcher.toggle() }, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    @Test
    fun playDispatchesMediaPlayDownThenUp() {
        assertKeyEventPair(captureKeyEvents { dispatcher.play() }, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    @Test
    fun pauseDispatchesMediaPauseDownThenUp() {
        assertKeyEventPair(captureKeyEvents { dispatcher.pause() }, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    @Test
    fun nextDispatchesMediaNextDownThenUp() {
        assertKeyEventPair(captureKeyEvents { dispatcher.next() }, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    @Test
    fun previousDispatchesMediaPreviousDownThenUp() {
        assertKeyEventPair(captureKeyEvents { dispatcher.previous() }, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    @Test
    fun adjustStreamVolumeUsesMusicStream() {
        dispatcher.adjustStreamVolume(1)
        verify(audioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)

        dispatcher.adjustStreamVolume(-1)
        verify(audioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, -1, 0)
    }

    @Test
    fun volumeGettersUseMusicStream() {
        whenever(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(7)
        whenever(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(15)

        assertEquals(7, dispatcher.currentVolume())
        assertEquals(15, dispatcher.maxVolume())
    }

    // --- selectExternalController (pure session-selection logic) ---

    /** Sessions as (packageName, playbackState) pairs, like MediaController exposes. */
    private fun select(sessions: List<Pair<String, Int>>, ownPackage: String = "com.RunBeat.run.audio") =
        MediaKeyDispatcher.selectExternalController(
            sessions,
            ownPackageName = ownPackage,
            packageNameOf = { it.first },
            playbackStateOf = { it.second },
        )

    @Test
    fun selectionReturnsNullWhenNoSessions() {
        assertNull(select(emptyList()))
    }

    @Test
    fun selectionIgnoresOwnPackage() {
        val own = "com.RunBeat.run.audio" to PlaybackState.STATE_PLAYING
        assertNull(select(listOf(own)))
    }

    @Test
    fun selectionPrefersPlayingSession() {
        val paused = "com.qobuz.music" to PlaybackState.STATE_PAUSED
        val playing = "com.spotify.music" to PlaybackState.STATE_PLAYING
        assertSame(playing, select(listOf(paused, playing)))
    }

    @Test
    fun selectionFallsBackToFirstExternalWhenNonePlaying() {
        val first = "com.qobuz.music" to PlaybackState.STATE_PAUSED
        val second = "com.spotify.music" to PlaybackState.STATE_BUFFERING
        assertSame(first, select(listOf(first, second)))
    }

    @Test
    fun selectionSkipsOwnPackageEvenWhenPlaying() {
        val own = "com.RunBeat.run.audio" to PlaybackState.STATE_PLAYING
        val external = "com.qobuz.music" to PlaybackState.STATE_PAUSED
        assertSame(external, select(listOf(own, external)))
    }
}
