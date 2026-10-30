// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Forwards transport commands to external apps and adjusts/reads the
 * STREAM_MUSIC volume via [AudioManager].
 *
 * Used when RunBeat is not the app holding the phone's media stream, so the
 * watch remote behaves like Garmin's built-in music app.
 *
 * Transport commands prefer the media-session route: with notification access
 * granted ([GarminMediaNotificationListener]), [MediaSessionManager] exposes
 * other apps' sessions and we drive the selected one through its
 * [MediaController.TransportControls]. [AudioManager.dispatchMediaKeyEvent]
 * does NOT reliably reach the focus holder on some devices, so it is only the
 * fallback when no external session is available.
 *
 * The [Context] and [AudioManager] are passed in (constructor injection) so
 * the class is unit-testable with mocks.
 */
class MediaKeyDispatcher(
    private val context: Context,
    private val audioManager: AudioManager,
) {

    /** Toggles play/pause on the external app currently holding a media session. */
    fun toggle() = dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { controller ->
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    /** Starts playback on the external app currently holding a media session. */
    fun play() = dispatch(KeyEvent.KEYCODE_MEDIA_PLAY) { it.transportControls.play() }

    /** Pauses playback on the external app currently holding a media session. */
    fun pause() = dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE) { it.transportControls.pause() }

    /** Skips to the next track on the external app currently holding a media session. */
    fun next() = dispatch(KeyEvent.KEYCODE_MEDIA_NEXT) { it.transportControls.skipToNext() }

    /** Skips to the previous track on the external app currently holding a media session. */
    fun previous() = dispatch(KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.transportControls.skipToPrevious() }

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

    private fun dispatch(keyCode: Int, viaSession: (MediaController) -> Unit) {
        if (dispatchToExternalSession(viaSession)) return
        dispatchKeyEvent(keyCode)
    }

    /**
     * Routes the command to an external media session, if any is available.
     * Returns true when a session accepted the command.
     */
    private fun dispatchToExternalSession(viaSession: (MediaController) -> Unit): Boolean {
        if (!GarminMediaNotificationListener.isEnabled(context)) return false
        val sessionManager =
            context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return false
        val controllers = try {
            sessionManager.getActiveSessions(
                ComponentName(context, GarminMediaNotificationListener::class.java)
            )
        } catch (e: SecurityException) {
            Log.d(TAG, "getActiveSessions denied, falling back to media key event: ${e.message}")
            return false
        }
        val target = selectExternalController(
            controllers,
            ownPackageName = context.packageName,
            packageNameOf = { it.packageName },
            playbackStateOf = { it.playbackState?.state ?: PlaybackState.STATE_NONE },
        ) ?: return false
        Log.d(TAG, "Dispatching transport control to external session ${target.packageName}")
        viaSession(target)
        return true
    }

    // Media key events must be delivered as a DOWN followed by an UP; a bare
    // UP is ignored by most media apps.
    private fun dispatchKeyEvent(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    companion object {
        private const val TAG = "MediaKeyDispatcher"

        /**
         * Picks the external session to control: never our own package, the
         * PLAYING one if there is one, otherwise the first external session.
         * Pure function so unit tests can cover it without Android framework
         * classes ([PlaybackState.STATE_PLAYING] is a compile-time constant).
         */
        fun <T> selectExternalController(
            sessions: List<T>,
            ownPackageName: String,
            packageNameOf: (T) -> String,
            playbackStateOf: (T) -> Int,
        ): T? {
            val external = sessions.filter { packageNameOf(it) != ownPackageName }
            return external.firstOrNull { playbackStateOf(it) == PlaybackState.STATE_PLAYING }
                ?: external.firstOrNull()
        }
    }
}
