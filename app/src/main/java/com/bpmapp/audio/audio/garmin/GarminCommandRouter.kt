// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import androidx.media3.common.Player
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.audio.TrackRepository
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
    private val mediaKeyDispatcher: MediaKeyDispatcher,
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
     * True when RunBeat owns the "current track" context: playing,
     * buffering, or paused with a track loaded. In this state the watch
     * keeps displaying the current track (source "RunBeat", isPlaying
     * false — exactly as the phone app shows it while paused), and the
     * RunBeat-only commands are accepted: the target cadence and the
     * speed-correction toggle are settings, adjustable while paused.
     */
    private fun isRunBeatOwned(): Boolean {
        return isRunBeatActive() || playerRepository.currentTrack.value != null
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
        }
    }

    /**
     * Builds the state message pushed to the watch. [GarminWatchState.source]
     * is "RunBeat" while RunBeat owns the current-track context (playing,
     * buffering, or paused with a track loaded — the watch keeps showing
     * the track while paused, exactly as the app does), "none" otherwise
     * (external now-playing metadata is out of scope: no notification listener).
     * The RunBeat-only fields (trackId, title, artist, album, durationMs,
     * positionMs, bpm, targetCadence, speedCorrection, playbackSpeed) are
     * null unless RunBeat owns the context; the codec omits null fields from
     * the wire message. In particular the title is omitted otherwise so the
     * watch does not show a stale RunBeat title with source "none".
     * [GarminWatchState.durationMs] is the track's
     * `extras.durationMs`, falling back to
     * [PlayerRepository.PlayerState.duration];
     * [GarminWatchState.positionMs] is the current position rounded down to
     * whole seconds so the state push stays at ~1/s while playing.
     */
    fun currentState(): GarminWatchState {
        val owned = isRunBeatOwned()
        val track = playerRepository.currentTrack.value
        val extras = track?.mediaMetadata?.extras
        return GarminWatchState(
            source = if (owned) SOURCE_RUNBEAT else SOURCE_NONE,
            isPlaying = playerRepository.playerState.value.isPlaying,
            trackId = if (owned) extras?.getString("trackId") else null,
            title = if (owned) track?.mediaMetadata?.title?.toString() else null,
            artist = if (owned) track?.mediaMetadata?.artist?.toString() else null,
            album = if (owned) track?.mediaMetadata?.albumTitle?.toString() else null,
            durationMs = if (owned) {
                extras?.getLong("durationMs")?.takeIf { it > 0 }
                    ?: playerRepository.playerState.value.duration.takeIf { it > 0 }
            } else {
                null
            },
            // Rounded down to whole seconds so the manager's
            // distinctUntilChanged emits ~1/s while playing.
            positionMs = if (owned) {
                playerRepository.playerState.value.currentPosition / 1000L * 1000L
            } else {
                null
            },
            bpm = if (owned) extras?.getString("bpm")?.toFloatOrNull() else null,
            targetCadence = if (owned) playerRepository.targetCadence.value else null,
            speedCorrection = if (owned) playerRepository.speedCorrectionEnabled.value else null,
            playbackSpeed = if (owned) playerRepository.playbackSpeed.value else null,
            volume = mediaKeyDispatcher.currentVolume(),
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
        is GarminCommand.VolumeDelta -> "volume_delta"
        GarminCommand.MarkNotForRunning -> "mark_not_for_running"
        // RequestState never reaches the router (the manager intercepts it);
        // the mapping exists to keep this when exhaustive.
        GarminCommand.RequestState -> "request_state"
        is GarminCommand.Seek -> "seek"
    }

    private companion object {
        const val SOURCE_RUNBEAT = "RunBeat"
        const val SOURCE_NONE = "none"
    }
}
