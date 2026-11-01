// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of the playback an EXTERNAL app is currently holding (Spotify,
 * Qobuz, ...), read from its media session so the watch can display the
 * current track name even while RunBeat is not the player.
 *
 * With notification access granted ([GarminMediaNotificationListener]),
 * [MediaSessionManager.getActiveSessions] exposes other apps' sessions; the
 * selected session's [MediaController] carries both the transport state
 * (isPlaying) and the metadata (title / artist / album) — no notification
 * parsing needed, the same permission unlocks both.
 *
 * Returns null when notification access is not granted or no external
 * session exists: the router then reports source "none" and the watch shows
 * "Nothing playing".
 *
 * Lives in the SDK-free main source set so both flavors compile it.
 */
data class ExternalPlayback(
    val packageName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val isPlaying: Boolean,
)

@Singleton
class ExternalPlaybackMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The external app's current playback, or null when no external media
     * session is available (notification access not granted, or no other
     * app holds a session). Never our own package — RunBeat's own session
     * is reported through [PlayerRepository] instead.
     */
    fun currentExternalPlayback(): ExternalPlayback? {
        if (!GarminMediaNotificationListener.isEnabled(context)) return null
        val sessionManager =
            context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return null
        val controllers = try {
            sessionManager.getActiveSessions(
                ComponentName(context, GarminMediaNotificationListener::class.java)
            )
        } catch (e: SecurityException) {
            Log.d(TAG, "getActiveSessions denied: ${e.message}")
            return null
        }
        val controller = MediaKeyDispatcher.selectExternalController(
            controllers,
            ownPackageName = context.packageName,
            packageNameOf = { it.packageName },
            playbackStateOf = { it.playbackState?.state ?: PlaybackState.STATE_NONE },
        ) ?: return null
        return playbackFromSession(
            packageName = controller.packageName,
            metadata = controller.metadata,
            playbackState = controller.playbackState?.state,
        )
    }

    companion object {
        private const val TAG = "ExternalPlaybackMonitor"

        /**
         * Maps one session's metadata + transport state to an
         * [ExternalPlayback]. Pure function so unit tests can cover it
         * without Android framework classes ([PlaybackState] states are
         * compile-time constants). A blank title counts as no title: the
         * watch falls back to the plain "External app" label.
         */
        fun playbackFromSession(
            packageName: String,
            metadata: android.media.MediaMetadata?,
            playbackState: Int?,
        ): ExternalPlayback {
            fun text(key: String): String? =
                metadata?.getString(key)?.takeIf { it.isNotBlank() }
            return ExternalPlayback(
                packageName = packageName,
                title = text(android.media.MediaMetadata.METADATA_KEY_TITLE),
                artist = text(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                album = text(android.media.MediaMetadata.METADATA_KEY_ALBUM),
                isPlaying = playbackState == PlaybackState.STATE_PLAYING,
            )
        }
    }
}
