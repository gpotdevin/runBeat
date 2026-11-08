// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.media3.common.Player
import com.bpmapp.audio.audio.MediaItemFactory
import com.bpmapp.audio.audio.PlaylistRepository
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.audio.TrackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes commands coming from the Garmin watch to RunBeat only, per the
 * routing table in docs/companion_garmin_app/architecture.md:
 *
 *  - transport commands (toggle/play/pause/next/prev), volume, cadence /
 *    speed-correction / shuffle / mark-not-for-running and seek all target
 *    [PlayerRepository] (or, for volume, the STREAM_MUSIC volume) and only
 *    while RunBeat is the active player — playing, buffering, or paused
 *    for less than [PAUSE_RESUME_GRACE_MS] (the pause resume grace: the
 *    watch remote stays usable across a pause and can resume it; after the
 *    grace the remote goes inert). When RunBeat is not active, those
 *    commands are no-ops (ack ok=false) — the phone never reads or drives
 *    other apps' media sessions or notifications for the watch;
 *
 *  - volume is the global STREAM_MUSIC volume via [AudioManager], which
 *    applies to RunBeat like to any other player;
 *
 *  - playlist_play is NOT gated on the active check: picking a playlist is
 *    an explicit "start this in RunBeat" action, the one way the watch can
 *    start RunBeat playback from any state;
 *
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
 * PlayerRepository is main-thread confined and the volume path only touches
 * AudioManager.
 */
@Singleton
class GarminCommandRouter @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val trackRepository: TrackRepository,
    private val playlistRepository: PlaylistRepository,
    private val mediaItemFactory: MediaItemFactory,
    @ApplicationContext private val context: Context,
) {

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Monotonic clock for the pause resume grace ([isRunBeatActive]).
     * Overridden by unit tests to advance time deterministically;
     * production uses [SystemClock.elapsedRealtime] (immune to wall-clock
     * changes).
     */
    internal var nowMs: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * [nowMs] of the last poll that observed RunBeat actively playing or
     * buffering (null before the first poll); the anchor the pause resume
     * grace counts from. Main-thread confined (see class KDoc).
     */
    private var lastActiveAtMs: Long? = null

    /** Cached favorite flag of the current track (avoids a Room read in the
     * non-suspend currentState()); updated by toggleCurrentTrackFavorite */
     private var _lastKnownFavorite: Boolean = false

    /**
     * True when RunBeat is the app holding the phone's media stream right
     * now: playing, or buffering a track (buffering counts so a watch
     * "next" during a gap still lands in RunBeat), or PAUSED with a track
     * loaded for less than [PAUSE_RESUME_GRACE_MS] since the last active
     * poll — the pause resume grace: a pause (from the watch or anywhere)
     * keeps the watch remote usable for one minute, so the pushed state
     * shows the paused track and toggle/play resume it; if nothing
     * resumes playback within the grace, RunBeat goes inactive and the
     * pushed source becomes "none". Only a real pause (STATE_READY) counts:
     * playback that ended (STATE_ENDED) or a player that never played
     * (STATE_IDLE) is inactive immediately. While this is false, every
     * watch command except playlist_play is a no-op: the watch remote
     * controls RunBeat only, never another app.
     */
    fun isRunBeatActive(): Boolean {
        val state = playerRepository.playerState.value
        if (state.isPlaying || state.playbackState == Player.STATE_BUFFERING) {
            lastActiveAtMs = nowMs()
            return true
        }
        return isPausedWithinResumeGrace(state)
    }

    /**
     * True while a pause is still inside the resume grace: the player is
     * READY with a loaded track (a real pause — pausing keeps STATE_READY,
     * stopping/ending does not) and the last poll that observed active
     * playback is within [PAUSE_RESUME_GRACE_MS].
     */
    private fun isPausedWithinResumeGrace(state: PlayerRepository.PlayerState): Boolean {
        if (state.playbackState != Player.STATE_READY) return false
        if (playerRepository.currentTrack.value == null) return false
        val lastActiveAt = lastActiveAtMs ?: return false
        return nowMs() - lastActiveAt < PAUSE_RESUME_GRACE_MS
    }

    /**
     * Routes one watch command and returns the ack to push back to the watch.
     * Commands are no-ops (ack ok=false) when RunBeat is not the active
     * player.
     */
    suspend fun handleCommand(cmd: GarminCommand): GarminAck {
        val active = isRunBeatActive()
        return when (cmd) {
            is GarminCommand.Toggle -> {
                if (active) playerRepository.togglePlayPause()
                GarminAck(cmd.wireType(), ok = active)
            }
            is GarminCommand.Play -> {
                if (active) playerRepository.play()
                GarminAck(cmd.wireType(), ok = active)
            }
            is GarminCommand.Pause -> {
                if (active) playerRepository.pause()
                GarminAck(cmd.wireType(), ok = active)
            }
            is GarminCommand.Next -> {
                if (active) playerRepository.playNext()
                GarminAck(cmd.wireType(), ok = active)
            }
            is GarminCommand.Prev -> {
                if (active) playerRepository.playPrevious()
                GarminAck(cmd.wireType(), ok = active)
            }
            // Volume is the global STREAM_MUSIC volume; it only applies while
            // RunBeat is the active player (the watch controls RunBeat only).
            is GarminCommand.VolumeDelta -> {
                if (active) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, cmd.delta, 0)
                }
                GarminAck(cmd.wireType(), ok = active)
            }
            is GarminCommand.CadenceSet -> {
                // Defensive: the codec filters non-finite values, but never
                // forward NaN/Infinity to PlayerRepository.
                if (!cmd.cadence.isFinite()) {
                    GarminAck(cmd.wireType(), ok = false)
                } else if (active) {
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
                } else if (active) {
                    playerRepository.setTargetCadence(cmd.cadence)
                    GarminAck(cmd.wireType(), ok = true)
                } else {
                    GarminAck(cmd.wireType(), ok = false)
                }
            }
            is GarminCommand.CadenceAvg -> {
                if (!cmd.cadence.isFinite() || cmd.cadence <= 0f) {
                    GarminAck(cmd.wireType(), ok = false)
                } else if (active) {
                    playerRepository.correctBpmFromCadence(cmd.cadence)
                    GarminAck(cmd.wireType(), ok = true)
                } else {
                    GarminAck(cmd.wireType(), ok = false)
                }
            }
            is GarminCommand.CadenceDelta -> {
                if (active) {
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
                if (active) {
                    playerRepository.toggleSpeedCorrection()
                    GarminAck(cmd.wireType(), ok = true)
                } else {
                    GarminAck(cmd.wireType(), ok = false)
                }
            }
            is GarminCommand.ShuffleToggle -> {
                if (active) {
                    playerRepository.toggleShuffle()
                    GarminAck(cmd.wireType(), ok = true)
                } else {
                    GarminAck(cmd.wireType(), ok = false)
                }
            }
            is GarminCommand.MarkNotForRunning -> markCurrentTrackNotForRunning(active, cmd)
            is GarminCommand.FavoriteToggle -> toggleCurrentTrackFavorite(active, cmd)
            // Absolute seek is RunBeat-only. The ack is returned for
            // symmetry, but the manager does not send it — the following
            // state push (with the new positionMs) confirms the seek.
            is GarminCommand.Seek -> {
                if (active) {
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
     * is "RunBeat" only while RunBeat is the active player (playing,
     * buffering, or paused within the resume grace, see [isRunBeatActive]);
     * otherwise it is "none" and the
     * title/artist/album and all RunBeat-only fields (trackId, durationMs,
     * positionMs, bpm, targetCadence, speedCorrection, playbackSpeed) are
     * omitted (null), so the watch never shows a stale track name. The source
     * is never "external": the phone does not read other apps' media sessions
     * for the watch.
     *
     * For "RunBeat", [GarminWatchState.durationMs] is the track's
     * `extras.durationMs`, falling back to
     * [PlayerRepository.PlayerState.duration];
     * [GarminWatchState.positionMs] is the current position rounded down to
     * 5-second buckets so the state push stays at ~1/5s while playing (the
     * watch extrapolates between pushes; must match the rounding in
     * GarminCompanionManager.watchStateFlow).
     */
    fun currentState(): GarminWatchState {
        if (!isRunBeatActive()) {
            return GarminWatchState(
                source = SOURCE_NONE,
                isPlaying = false,
                volume = currentVolume(),
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
            volume = currentVolume(),
            shuffle = playerRepository.shuffleEnabled.value,
            isFavorite = extras?.getBoolean("isFavorite") ?: _lastKnownFavorite,
        )
    }

    /** Current STREAM_MUSIC media volume (AudioManager index). */
    private fun currentVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

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

    /**
     * Toggles the favorite flag of the current track (Room suspend call, so
     * the UPDATE runs on Room's query executor). Returns a not-ok ack when
     * RunBeat is not active or when no track is loaded.
     */
    private suspend fun toggleCurrentTrackFavorite(
        active: Boolean,
        cmd: GarminCommand.FavoriteToggle,
    ): GarminAck {
        if (!active) return GarminAck(cmd.wireType(), ok = false)
        val trackId = playerRepository.currentTrack.value
            ?.mediaMetadata?.extras?.getString("trackId")
            ?: return GarminAck(cmd.wireType(), ok = false)
        val current = trackRepository.getTrackById(trackId).first()?.isFavorite == true
        val newValue = !current
        trackRepository.updateFavorite(trackId, newValue)
        _lastKnownFavorite = newValue
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
        is GarminCommand.CadenceAvg -> "cadence_avg"
        GarminCommand.SpeedCorrectionToggle -> "speed_correction_toggle"
        GarminCommand.ShuffleToggle -> "shuffle_toggle"
        is GarminCommand.VolumeDelta -> "volume_delta"
        GarminCommand.MarkNotForRunning -> "mark_not_for_running"
        GarminCommand.FavoriteToggle -> "favorite_toggle"
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

    companion object {
        const val SOURCE_RUNBEAT = "RunBeat"
        const val SOURCE_NONE = "none"

        /**
         * How long after a pause the watch can still resume RunBeat
         * (see [isRunBeatActive]); after the grace the pushed source becomes
         * "none" and watch commands are no-ops again.
         */
        const val PAUSE_RESUME_GRACE_MS = 60_000L
    }
}
