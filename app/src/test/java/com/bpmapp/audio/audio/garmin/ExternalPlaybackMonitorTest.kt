// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.media.MediaMetadata
import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ExternalPlaybackMonitor.playbackFromSession], the pure
 * mapping from a media session's metadata + transport state to the
 * [ExternalPlayback] snapshot pushed to the watch. The session query itself
 * needs notification access and is covered on-device (device_test_plan.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalPlaybackMonitorTest {

    private fun metadata(
        title: String? = null,
        artist: String? = null,
        album: String? = null,
    ): MediaMetadata? {
        if (title == null && artist == null && album == null) return null
        val builder = MediaMetadata.Builder()
        title?.let { builder.putString(MediaMetadata.METADATA_KEY_TITLE, it) }
        artist?.let { builder.putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
        album?.let { builder.putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
        return builder.build()
    }

    @Test
    fun mapsTitleArtistAlbumAndPlayingState() {
        val playback = ExternalPlaybackMonitor.playbackFromSession(
            packageName = "com.spotify.music",
            metadata = metadata(title = "Song", artist = "Artist", album = "Album"),
            playbackState = PlaybackState.STATE_PLAYING,
        )

        assertEquals("com.spotify.music", playback.packageName)
        assertEquals("Song", playback.title)
        assertEquals("Artist", playback.artist)
        assertEquals("Album", playback.album)
        assertTrue(playback.isPlaying)
    }

    @Test
    fun pausedSessionIsNotPlayingButKeepsMetadata() {
        val playback = ExternalPlaybackMonitor.playbackFromSession(
            packageName = "com.qobuz.app",
            metadata = metadata(title = "Paused Song", artist = "Artist"),
            playbackState = PlaybackState.STATE_PAUSED,
        )

        assertFalse(playback.isPlaying)
        assertEquals("Paused Song", playback.title)
        assertEquals("Artist", playback.artist)
        assertNull(playback.album)
    }

    @Test
    fun blankTitleCountsAsNoTitle() {
        val playback = ExternalPlaybackMonitor.playbackFromSession(
            packageName = "com.podcast.app",
            metadata = metadata(title = "   "),
            playbackState = PlaybackState.STATE_PLAYING,
        )

        assertNull(playback.title)
        assertTrue(playback.isPlaying)
    }

    @Test
    fun nullMetadataYieldsNullFields() {
        val playback = ExternalPlaybackMonitor.playbackFromSession(
            packageName = "com.podcast.app",
            metadata = null,
            playbackState = PlaybackState.STATE_PLAYING,
        )

        assertNull(playback.title)
        assertNull(playback.artist)
        assertNull(playback.album)
        assertTrue(playback.isPlaying)
    }

    @Test
    fun missingPlaybackStateIsNotPlaying() {
        val playback = ExternalPlaybackMonitor.playbackFromSession(
            packageName = "com.example.app",
            metadata = metadata(title = "Song"),
            playbackState = null,
        )

        assertFalse(playback.isPlaying)
        assertEquals("Song", playback.title)
    }
}
