// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context
import androidx.media3.common.Player
import com.bpmapp.audio.audio.MediaItemFactory
import com.bpmapp.audio.audio.PlaylistRepository
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.audio.TrackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes commands coming from the Garmin watch, per the routing table in
 * docs/companion_garmin_app/architecture.md:
 *
 *  - transport commands (toggle/play/pause/next/prev) go to [PlayerRepository]
 *    when RunBeat is the active playback app, otherwise to [MediaKeyDispatcher]
 *    (media key events reach the app currently holding media focus);
 *  - volume always goes to [MediaKeyDispatcher] (STREAM_MUSIC works for RunBeat
 *    and external apps alike);
 *  - cadence / speed-correction / mark-not-for-running / seek are
 *    RunBeat-only and are rejected (ack ok=false) when RunBeat is not
 *    active;
 *  - request_state is not routed at all: the manager intercepts it as a
 *    link-layer request and answers it with a state push.
 *
 * This class is deliberately free of Garmin SDK imports so it compiles and is
 * unit-tested in every flavor; the garmin flavor's GarminCompanionManager is
 * the thin SDK shell that feeds it.
 *
 * Threading: the manager invokes [handleCommand] (a suspend function) and
 * [currentState] from the Connect IQ SDK callbacks, which are delivered on the
 * main thread. The suspending mark path calls Room's suspend API directly, so
 * the UPDATE runs on Room's query executor without blocking any thread;
 * PlayerRepository is main-thread confined and MediaKeyDispatcher only
 * touches AudioManager.
 */
@Singleton
class GarminCommandRouter @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val trackRepository: TrackRepository,
    private val playlistRepository: PlaylistRepository,
    private val mediaItemFactory: MediaItemFactory,
    private val mediaKeyDispatcher: MediaKeyDispatcher,
    private val externalPlaybackMonitor: ExternalPlaybackMonitor,
    @ApplicationContext private val context: Context,
) {

    /**
     * True when RunBeat is the app holding the phone's media stream right
     * now: playing, or buffering a track (buffering counts so a watch
     * "next" during a gap still lands in RunBeat rather than in another
     * app). Transport commands route to [PlayerRepository] only in this
     * state — a loaded-but-PAUSED track must NOT capture them, or the
     * watch could no longer control other players (Spotify, podcasts, ...).
     */
    fun isRunBeatActive(): Boolean {
        val state = playerRepository.playerState.value
        return state.isPlaying || state.playbackState == Player.STATE_BUFFERING
    }

    /**
     * True when RunBeat owns the "current track" context: only while it is
     * the ACTIVE player (playing or buffering — see [isRunBeatActive]).
     * A merely loaded-but-PAUSED track does NOT count: when another app
     * (VLC, a podcast player, ...) is playing, the watch must show that
     * app's track (source "external"), not RunBeat's stale title.
     */
    private fun isRunBeatOwned(): Boolean {
        return isRunBeatActive()
    }

    /**
     * Routes one watch command and returns the ack to push back to the watch.
     */
    suspend fun handleCommand(cmd: GarminCommand): GarminAck {
        val active = isRunBeatActive()
        val owned = isRunBeatOwned()
        return when (cmd) {
            is GarminCommand.Toggle -> {
                if (active) playerRepository.togglePlayPause() else mediaKeyDispatcher.toggle()
                GarminAck(cmd.wireType(), ok = true)
            }
            is GarminCommand.Play -> {
                if (active) playerRepository.play() else mediaKeyDispatcher.play()
                GarminAck(cmd.wireType(), ok = true)
            }
            is GarminCommand.Pause -> {
                if (active) playerRepository.pause() else mediaKeyDispatcher.pause()
                GarminAck(cmd.wireType(), ok = true)
            }
            is GarminCommand.Next -> {
                if (active) playerRepository.playNext() else mediaKeyDispatcher.next()
                GarminAck(cmd.wireType(), ok = true)
            }
            is GarminCommand.Prev -> {
                if (active) playerRepository.playPrevious() else mediaKeyDispatcher.previous()
                GarminAck(cmd.wireType(), ok = true)
            }
            // Volume is the global STREAM_MUSIC volume: it applies to RunBeat
            // and to external apps alike, so it is never routed to the player.
            is GarminCommand.VolumeDelta -> {
                mediaKeyDispatcher.adjustStreamVolume(cmd.delta)
                GarminAck(cmd.wireType(), ok = true)
            }
            is GarminCommand.CadenceSet -> {
                // Defensive: the codec filters non-finite values, but never
                // forward NaN/Infinity to PlayerRepository.
                if (!cmd.cadence.isFinite()) {
                    GarminAck(cmd.wireType(), ok = false)
                } else if (owned) {
                    playerRepository.setTargetCadence(cmd.cadence)
                    GarminAck(cmd.wireType(), ok = true)
                } else {
                    GarminAck(cmd.wireType(), ok = false)
                }
            }
            // The watch computed the 60 s average itself; the phone just applies it.
            is GarminCommand.CadenceUseCurrent -> {
                if (!cmd.cadence.isFinite()) {
                    GarminAck(cmd.wireType(), ok = false)
                } else if (owned) {
                    playerRepository.setTargetCadence(cmd.cadence)
                    GarminAck(cmd.wireType(), ok = true)
                } else {
                    GarminAck(cmd.wireType(), ok = false)
                }
            }
            is GarminCommand.CadenceDelta -> {
                if (owned) {
                    when {
                        cmd.delta > 0 -> playerRepository.incrementTargetCadence()
                        cmd.delta < 0 -> playerRepository.decrementTargetCadence()
                    }
                    GarminAck(cmd.wireType(), ok = true)
                } else {
                    GarminAck(cmd.wireType(), ok = false)
                }
            }
            is GarminCommand.SpeedCorrectionToggle -> {
                if (owned) {
                    playerRepository.toggleSpeedCorrection()
                    GarminAck(cmd.wireType(), ok = true)
                } else {
                    GarminAck(cmd.wireType(), ok = false)
                }
            }
            // Shuffle is a RunBeat-only setting: there is no media-key
            // equivalent for an app-local shuffle mode.
            is GarminCommand.ShuffleToggle -> {
                if (owned) {
                    playerRepository.toggleShuffle()
                    GarminAck(cmd.wireType(), ok = true)
                } else {
                    GarminAck(cmd.wireType(), ok = false)
                }
            }
            is GarminCommand.MarkNotForRunning -> markCurrentTrackNotForRunning(owned, cmd)
            // Absolute seek is RunBeat-only: there is no media-key
            // equivalent for an absolute position. The ack is returned for
            // symmetry, but the manager does not send it — the following
            // state push (with the new positionMs) confirms the seek.
            is GarminCommand.Seek -> {
                if (owned) {
                    playerRepository.seekTo(cmd.positionMs)
                    GarminAck(cmd.wireType(), ok = true)
                } else {
                    GarminAck(cmd.wireType(), ok = false)
                }
            }
            // Never reached: the manager intercepts RequestState as a
            // link-layer request before routing (see GarminCompanionManager).
            is GarminCommand.RequestState -> GarminAck(cmd.wireType(), ok = true)
            // Never reached: the manager intercepts Bye as a link-layer
            // farewell before routing (see GarminCompanionManager).
            is GarminCommand.Bye -> GarminAck(cmd.wireType(), ok = true)
            is GarminCommand.PlaylistPlay -> playPlaylist(cmd)
            // Never reached: the manager intercepts PlaylistList as a
            // link-layer request before routing (see GarminCompanionManager).
            is GarminCommand.PlaylistList -> GarminAck(cmd.wireType(), ok = true)
        }
    }

    /**
     * Plays a library playlist in RunBeat: loads its tracks (ordered by
     * position) as the play queue and starts playback. Tracks flagged "not
     * for running" are left out when the shared "exclude not for running"
     * setting is on (the same filter the library UI applies). Ack ok=false
     * when the playlist is missing or has no playable track after filtering.
     */
    private suspend fun playPlaylist(cmd: GarminCommand.PlaylistPlay): GarminAck {
        val relations = playlistRepository.tracksForPlaylist(cmd.playlistId)
        val tracks = relations.mapNotNull { playlistRepository.trackById(it.trackId) }
        val playable = if (excludeNotForRunningTracks()) tracks.filterNot { it.notForRunning } else tracks
        if (playable.isEmpty()) return GarminAck(cmd.wireType(), ok = false)
        val items = playable.map { mediaItemFactory.mediaItemFor(it) }
        playerRepository.loadPlaylist(items)
        playerRepository.play()
        return GarminAck(cmd.wireType(), ok = true)
    }

    /**
     * The library playlists as (id, name) pairs, for the playlists push that
     * answers a watch's playlist_list request.
     */
    suspend fun currentPlaylists(): List<Pair<String, String>> =
        playlistRepository.playlistsOnce().map { it.id to it.name }

    /**
     * The shared "exclude not for running" setting, read fresh from the
     * "AppSettings" SharedPreferences (same key/default as the library UI).
     */
    private fun excludeNotForRunningTracks(): Boolean =
        context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(
                SettingsKeys.KEY_EXCLUDE_NOT_FOR_RUNNING_TRACKS,
                SettingsKeys.DEFAULT_EXCLUDE_NOT_FOR_RUNNING_TRACKS
            )

    /**
     * Builds the state message pushed to the watch. [GarminWatchState.source]
     * is "RunBeat" only while RunBeat is the active player (playing or
     * buffering). When RunBeat is not actively playing — even if a track
     * is loaded but paused — the external media session is consulted (via
     * [ExternalPlaybackMonitor], needs notification access): source is
     * "external" with the other app's title/artist/album and isPlaying when
     * an external session exists, "none" otherwise.
     * The RunBeat-only fields (trackId, durationMs, positionMs, bpm,
     * targetCadence, speedCorrection, playbackSpeed) are null unless RunBeat
     * owns the context; the codec omits null fields from the wire message.
     * title/artist/album are sent for "RunBeat" and "external" alike and are
     * omitted for "none", so the watch never shows a stale track name.
     * [GarminWatchState.durationMs] is the track's
     * `extras.durationMs`, falling back to
     * [PlayerRepository.PlayerState.duration];
     * [GarminWatchState.positionMs] is the current position rounded down to
     * 5-second buckets so the state push stays at ~1/5s while playing (the
     * watch extrapolates between pushes; must match the rounding in
     * GarminCompanionManager.watchStateFlow).
     */
    fun currentState(): GarminWatchState {
        val owned = isRunBeatOwned()
        if (!owned) {
            // RunBeat is not the player: report what an external app is
            // playing (track name included), or "none" when no external
            // media session is available.
            val external = externalPlaybackMonitor.currentExternalPlayback()
            return GarminWatchState(
                source = if (external != null) SOURCE_EXTERNAL else SOURCE_NONE,
                isPlaying = external?.isPlaying ?: false,
                title = external?.title,
                artist = external?.artist,
                album = external?.album,
                volume = mediaKeyDispatcher.currentVolume(),
            )
        }
        val track = playerRepository.currentTrack.value
        val extras = track?.mediaMetadata?.extras
        return GarminWatchState(
            source = SOURCE_RUNBEAT,
            isPlaying = playerRepository.playerState.value.isPlaying,
            trackId = extras?.getString("trackId"),
            title = track?.mediaMetadata?.title?.toString(),
            artist = track?.mediaMetadata?.artist?.toString(),
            album = track?.mediaMetadata?.albumTitle?.toString(),
            durationMs = extras?.getLong("durationMs")?.takeIf { it > 0 }
                ?: playerRepository.playerState.value.duration.takeIf { it > 0 },
            // Rounded down to 5 s buckets so the manager's
            // distinctUntilChanged emits ~1/5s while playing; the watch
            // extrapolates the position locally between pushes.
            positionMs = playerRepository.playerState.value.currentPosition / 5000L * 5000L,
            bpm = extras?.getString("bpm")?.toFloatOrNull(),
            targetCadence = playerRepository.targetCadence.value,
            speedCorrection = playerRepository.speedCorrectionEnabled.value,
            playbackSpeed = playerRepository.playbackSpeed.value,
            volume = mediaKeyDispatcher.currentVolume(),
            shuffle = playerRepository.shuffleEnabled.value,
        )
    }

    /**
     * Flags the current track "not for running" and skips it immediately
     * (confirmed decision). Returns a not-ok ack when RunBeat is not active
     * or when no track is loaded.
     */
    private suspend fun markCurrentTrackNotForRunning(
        active: Boolean,
        cmd: GarminCommand.MarkNotForRunning,
    ): GarminAck {
        if (!active) return GarminAck(cmd.wireType(), ok = false)
        val trackId = playerRepository.currentTrack.value
            ?.mediaMetadata?.extras?.getString("trackId")
            ?: return GarminAck(cmd.wireType(), ok = false)
        // Room suspend call: the UPDATE runs on Room's query executor.
        trackRepository.markTrackNotForRunning(trackId)
        playerRepository.playNext()
        return GarminAck(cmd.wireType(), ok = true)
    }

    /** Wire type name of a command, used as the ack's "command" field. */
    private fun GarminCommand.wireType(): String = when (this) {
        GarminCommand.Play -> "play"
        GarminCommand.Pause -> "pause"
        GarminCommand.Toggle -> "toggle"
        GarminCommand.Next -> "next"
        GarminCommand.Prev -> "prev"
        is GarminCommand.CadenceSet -> "cadence_set"
        is GarminCommand.CadenceDelta -> "cadence_delta"
        is GarminCommand.CadenceUseCurrent -> "cadence_use_current"
        GarminCommand.SpeedCorrectionToggle -> "speed_correction_toggle"
        GarminCommand.ShuffleToggle -> "shuffle_toggle"
        is GarminCommand.VolumeDelta -> "volume_delta"
        GarminCommand.MarkNotForRunning -> "mark_not_for_running"
        // RequestState never reaches the router (the manager intercepts it);
        // the mapping exists to keep this when exhaustive.
        GarminCommand.RequestState -> "request_state"
        // Same for Bye (link-layer farewell, intercepted by the manager).
        GarminCommand.Bye -> "bye"
        is GarminCommand.Seek -> "seek"
        // PlaylistList never reaches the router (the manager intercepts it);
        // the mapping exists to keep this when exhaustive.
        GarminCommand.PlaylistList -> "playlist_list"
        is GarminCommand.PlaylistPlay -> "playlist_play"
    }

    private companion object {
        const val SOURCE_RUNBEAT = "RunBeat"
        const val SOURCE_EXTERNAL = "external"
        const val SOURCE_NONE = "none"
    }
}
