// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Forwards transport commands to external apps via
 * [AudioManager.dispatchMediaKeyEvent] and adjusts/reads the STREAM_MUSIC
 * volume via [AudioManager].
 *
 * Used when RunBeat is not the app holding the phone's media stream, so the
 * watch remote behaves like Garmin's built-in music app. No permissions are
 * required for either operation.
 *
 * The [AudioManager] is passed in (constructor injection) so the class is
 * unit-testable with a mock.
 */
class MediaKeyDispatcher(private val audioManager: AudioManager) {

    /** Toggles play/pause on the app currently holding media focus. */
    fun toggle() = dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    /** Starts playback on the app currently holding media focus. */
    fun play() = dispatch(KeyEvent.KEYCODE_MEDIA_PLAY)

    /** Pauses playback on the app currently holding media focus. */
    fun pause() = dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE)

    /** Skips to the next track on the app currently holding media focus. */
    fun next() = dispatch(KeyEvent.KEYCODE_MEDIA_NEXT)

    /** Skips to the previous track on the app currently holding media focus. */
    fun previous() = dispatch(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /**
     * Adjusts the global media volume (STREAM_MUSIC) by [delta] steps
     * (+1 / -1), for RunBeat and external apps alike.
     */
    fun adjustStreamVolume(delta: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, delta, 0)
    }

    /** Current STREAM_MUSIC media volume (AudioManager index). */
    fun currentVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    /** Maximum STREAM_MUSIC media volume (AudioManager index). */
    fun maxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    // Media key events must be delivered as a DOWN followed by an UP; a bare
    // UP is ignored by most media apps.
    private fun dispatch(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }
}
