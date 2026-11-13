// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the mode-beep ducking restore guard in [PlayerRepository].
 *
 * The beep callbacks are captured from a mocked [ModeBeepPlayer] and invoked
 * directly, so the duck/restore logic runs against a mocked [ExoPlayer]
 * without any audio hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerRepositoryModeBeepDuckTest {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var modeBeepPlayer: ModeBeepPlayer
    private lateinit var repository: PlayerRepository

    private val onStartCaptor = argumentCaptor<() -> Unit>()
    private val onFinishCaptor = argumentCaptor<() -> Unit>()

    @Before
    fun setUp() {
        val context = mock<Context>()
        // Minimal SharedPreferences stub: nothing stored, so the rhythm
        // patterns fall back to their defaults and the mode-beep signal
        // (default on) stays enabled.
        val prefs = mock<SharedPreferences>()
        whenever(prefs.getStringSet(any(), any())).thenReturn(null)
        whenever(prefs.getBoolean(any(), any())).thenReturn(true)
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        exoPlayer = mock()
        modeBeepPlayer = mock()
        // Binary rhythm factor -> 2 beeps, so the signal actually plays.
        whenever(modeBeepPlayer.beepCountForFactor(any())).thenReturn(2)
        repository = PlayerRepository(context, exoPlayer, mock(), modeBeepPlayer)
    }

    // BPM 86 at the default 172 cadence matches the binary (factor 2.0)
    // pattern, so the signal plays 2 beeps and the music is ducked to 30%.
    private fun track(id: String): MediaItem {
        val extras = Bundle().apply {
            putString("trackId", id)
            putString("bpm", "86")
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
            .build()
    }

    /** Plays [trackId] and returns the captured beep callbacks. */
    private fun startBeep(trackId: String): Pair<() -> Unit, () -> Unit> {
        repository.playMediaItem(track(trackId))
        verify(modeBeepPlayer, atLeastOnce()).beep(
            eq(2),
            onStartCaptor.capture(),
            onFinishCaptor.capture()
        )
        return onStartCaptor.allValues.last() to onFinishCaptor.allValues.last()
    }

    @Test
    fun onFinishRestoresVolumeWhenUnchangedDuringBeep() {
        whenever(exoPlayer.volume).thenReturn(0.8f)
        val (onStart, onFinish) = startBeep("track-1")

        onStart()
        val ducked = (0.8f * 0.3f).coerceIn(0f, 1f)
        assertEquals(0.24f, ducked, 0.0001f)
        verify(exoPlayer).setVolume(ducked)

        // Nothing else touches the volume while the beeps play.
        whenever(exoPlayer.volume).thenReturn(ducked)
        onFinish()

        verify(exoPlayer).setVolume(0.8f)
    }

    @Test
    fun onFinishLeavesVolumeAloneWhenChangedDuringBeep() {
        whenever(exoPlayer.volume).thenReturn(0.8f)
        val (onStart, onFinish) = startBeep("track-1")

        onStart()
        val ducked = (0.8f * 0.3f).coerceIn(0f, 1f)
        verify(exoPlayer).setVolume(ducked)

        // The volume changes while the beeps play (e.g. the user moves the
        // volume slider); the restore must not clobber that change.
        whenever(exoPlayer.volume).thenReturn(0.5f)
        onFinish()

        verify(exoPlayer, never()).setVolume(0.8f)
        verify(exoPlayer, times(1)).setVolume(ducked)
    }

    @Test
    fun nextBeepSequenceCapturesVolumeAgainAfterRestore() {
        whenever(exoPlayer.volume).thenReturn(0.8f)
        val (onStart1, onFinish1) = startBeep("track-1")
        onStart1()
        val ducked1 = (0.8f * 0.3f).coerceIn(0f, 1f)
        whenever(exoPlayer.volume).thenReturn(ducked1)
        onFinish1()
        verify(exoPlayer).setVolume(0.8f)

        // volumeBeforeDuck was reset to null: a later beep sequence must
        // capture the then-current volume as the new original.
        whenever(exoPlayer.volume).thenReturn(0.6f)
        val (onStart2, onFinish2) = startBeep("track-2")
        onStart2()
        val ducked2 = (0.6f * 0.3f).coerceIn(0f, 1f)
        verify(exoPlayer).setVolume(ducked2)
        whenever(exoPlayer.volume).thenReturn(ducked2)
        onFinish2()
        verify(exoPlayer).setVolume(0.6f)
    }
}
