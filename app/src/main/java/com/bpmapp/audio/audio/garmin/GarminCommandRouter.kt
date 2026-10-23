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
 *  - cadence / speed-correction / mark-not-for-running are RunBeat-only and
 *    are rejected (ack ok=false) when RunBeat is not active.
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
     * True when RunBeat is the active playback app: playing, or buffering a
     * track (buffering counts so a watch "next" during a gap still lands in
     * RunBeat rather than in another app).
     */
    fun isRunBeatActive(): Boolean {
        val state = playerRepository.playerState.value
        return state.isPlaying || state.playbackState == Player.STATE_BUFFERING
    }

    /**
     * Routes one watch command and returns the ack to push back to the watch.
     */
    suspend fun handleCommand(cmd: GarminCommand): GarminAck {
        val active = isRunBeatActive()
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
            is GarminCommand.MarkNotForRunning -> markCurrentTrackNotForRunning(active, cmd)
        }
    }

    /**
     * Builds the state message pushed to the watch. [GarminWatchState.source]
     * is "RunBeat" while RunBeat is the active playback app, "none" otherwise
     * (external now-playing metadata is out of scope: no notification listener).
     * The RunBeat-only fields (trackId, title, bpm, targetCadence,
     * speedCorrection, playbackSpeed) are null unless RunBeat is active; the
     * codec omits null fields from the wire message. In particular the title
     * is omitted when RunBeat is not active so the watch does not show a
     * stale RunBeat title with source "none".
     */
    fun currentState(): GarminWatchState {
        val active = isRunBeatActive()
        val track = playerRepository.currentTrack.value
        val extras = track?.mediaMetadata?.extras
        return GarminWatchState(
            source = if (active) SOURCE_RUNBEAT else SOURCE_NONE,
            isPlaying = playerRepository.playerState.value.isPlaying,
            trackId = if (active) extras?.getString("trackId") else null,
            title = if (active) track?.mediaMetadata?.title?.toString() else null,
            bpm = if (active) extras?.getString("bpm")?.toFloatOrNull() else null,
            targetCadence = if (active) playerRepository.targetCadence.value else null,
            speedCorrection = if (active) playerRepository.speedCorrectionEnabled.value else null,
            playbackSpeed = if (active) playerRepository.playbackSpeed.value else null,
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
    }

    private companion object {
        const val SOURCE_RUNBEAT = "RunBeat"
        const val SOURCE_NONE = "none"
    }
}
