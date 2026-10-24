// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import org.json.JSONException
import org.json.JSONObject

/**
 * Commands sent by the Garmin watch to the phone.
 *
 * Wire format (normative: docs/companion_garmin_app/architecture.md, matching
 * garmin-watch/source/MessageHandler.mc):
 *   {"type":"play"} {"type":"pause"} {"type":"toggle"} {"type":"next"} {"type":"prev"}
 *   {"type":"cadence_set","cadence":172}
 *   {"type":"cadence_delta","delta":1}
 *   {"type":"cadence_use_current","cadence":172}
 *   {"type":"speed_correction_toggle"}
 *   {"type":"volume_delta","delta":1}
 *   {"type":"mark_not_for_running"}
 */
sealed class GarminCommand {
    data object Play : GarminCommand()
    data object Pause : GarminCommand()
    data object Toggle : GarminCommand()
    data object Next : GarminCommand()
    data object Prev : GarminCommand()
    data class CadenceSet(val cadence: Float) : GarminCommand()
    data class CadenceDelta(val delta: Int) : GarminCommand()
    data class CadenceUseCurrent(val cadence: Float) : GarminCommand()
    data object SpeedCorrectionToggle : GarminCommand()
    data class VolumeDelta(val delta: Int) : GarminCommand()
    data object MarkNotForRunning : GarminCommand()
}

/**
 * Playback state pushed from the phone to the watch:
 *   {"type":"state","source":"RunBeat","isPlaying":true,"trackId":"...","title":"...",
 *    "bpm":140.0,"targetCadence":172,"speedCorrection":true,"playbackSpeed":1.03,"volume":12}
 *
 * [source] is "RunBeat" when RunBeat is the active playback app, otherwise
 * "none". "external" (or the external app's name) is a reserved protocol
 * value, not currently emitted: the phone ships without the optional
 * ExternalPlaybackMonitor notification listener. The RunBeat-only fields
 * ([trackId], [bpm], [targetCadence], [speedCorrection], [playbackSpeed]) are
 * null when unknown / not applicable and are omitted from the wire message.
 * [volume] is the current STREAM_MUSIC media volume (AudioManager index).
 */
data class GarminWatchState(
    val source: String,
    val isPlaying: Boolean,
    val trackId: String? = null,
    val title: String? = null,
    val bpm: Float? = null,
    val targetCadence: Float? = null,
    val speedCorrection: Boolean? = null,
    val playbackSpeed: Float? = null,
    val volume: Int = 0,
)

/**
 * Acknowledgement pushed from the phone to the watch:
 *   {"type":"ack","command":"mark_not_for_running","ok":true}
 */
data class GarminAck(val command: String, val ok: Boolean)

/**
 * Pure Kotlin + org.json (de)serialization of the watch <-> phone message
 * protocol. No Garmin SDK imports — compiles and tests in every flavor.
 *
 * Decoding is deliberately tolerant, mirroring the watch-side parser:
 * malformed JSON, unknown message types, unknown fields and missing or
 * non-finite numeric fields yield null instead of throwing.
 */
object GarminMessageCodec {

    private const val KEY_TYPE = "type"
    private const val KEY_CADENCE = "cadence"
    private const val KEY_DELTA = "delta"
    private const val KEY_COMMAND = "command"
    private const val KEY_OK = "ok"
    private const val KEY_SOURCE = "source"
    private const val KEY_IS_PLAYING = "isPlaying"
    private const val KEY_TRACK_ID = "trackId"
    private const val KEY_TITLE = "title"
    private const val KEY_BPM = "bpm"
    private const val KEY_TARGET_CADENCE = "targetCadence"
    private const val KEY_SPEED_CORRECTION = "speedCorrection"
    private const val KEY_PLAYBACK_SPEED = "playbackSpeed"
    private const val KEY_VOLUME = "volume"

    private const val TYPE_PLAY = "play"
    private const val TYPE_PAUSE = "pause"
    private const val TYPE_TOGGLE = "toggle"
    private const val TYPE_NEXT = "next"
    private const val TYPE_PREV = "prev"
    private const val TYPE_CADENCE_SET = "cadence_set"
    private const val TYPE_CADENCE_DELTA = "cadence_delta"
    private const val TYPE_CADENCE_USE_CURRENT = "cadence_use_current"
    private const val TYPE_SPEED_CORRECTION_TOGGLE = "speed_correction_toggle"
    private const val TYPE_VOLUME_DELTA = "volume_delta"
    private const val TYPE_MARK_NOT_FOR_RUNNING = "mark_not_for_running"
    private const val TYPE_STATE = "state"
    private const val TYPE_ACK = "ack"

    // Sanity bound for cadence_delta / volume_delta: the watch sends ±1 per
    // button press, so anything beyond this is a corrupt or hostile message.
    private const val MAX_DELTA = 100

    // ---------------------------------------------------------------
    // Watch -> phone (commands)
    // ---------------------------------------------------------------

    /** Decodes a watch command message; null when malformed or of unknown type. */
    fun decodeCommand(json: String): GarminCommand? {
        val obj = parse(json) ?: return null
        return when (obj.optString(KEY_TYPE)) {
            TYPE_PLAY -> GarminCommand.Play
            TYPE_PAUSE -> GarminCommand.Pause
            TYPE_TOGGLE -> GarminCommand.Toggle
            TYPE_NEXT -> GarminCommand.Next
            TYPE_PREV -> GarminCommand.Prev
            TYPE_CADENCE_SET -> obj.optFiniteFloatOrNull(KEY_CADENCE)
                ?.let { GarminCommand.CadenceSet(it) }
            TYPE_CADENCE_DELTA -> obj.optDeltaOrNull(KEY_DELTA)
                ?.let { GarminCommand.CadenceDelta(it) }
            TYPE_CADENCE_USE_CURRENT -> obj.optFiniteFloatOrNull(KEY_CADENCE)
                ?.let { GarminCommand.CadenceUseCurrent(it) }
            TYPE_SPEED_CORRECTION_TOGGLE -> GarminCommand.SpeedCorrectionToggle
            TYPE_VOLUME_DELTA -> obj.optDeltaOrNull(KEY_DELTA)
                ?.let { GarminCommand.VolumeDelta(it) }
            TYPE_MARK_NOT_FOR_RUNNING -> GarminCommand.MarkNotForRunning
            else -> null
        }
    }

    /** Encodes a command. The phone does not send commands to the watch, but
     *  encoding is provided for symmetry and round-trip tests. */
    fun encodeCommand(command: GarminCommand): String = when (command) {
        is GarminCommand.Play -> typed(TYPE_PLAY)
        is GarminCommand.Pause -> typed(TYPE_PAUSE)
        is GarminCommand.Toggle -> typed(TYPE_TOGGLE)
        is GarminCommand.Next -> typed(TYPE_NEXT)
        is GarminCommand.Prev -> typed(TYPE_PREV)
        is GarminCommand.CadenceSet -> JSONObject()
            .put(KEY_TYPE, TYPE_CADENCE_SET)
            .putCadence(KEY_CADENCE, command.cadence)
            .toString()
        is GarminCommand.CadenceDelta -> JSONObject()
            .put(KEY_TYPE, TYPE_CADENCE_DELTA)
            .put(KEY_DELTA, command.delta)
            .toString()
        is GarminCommand.CadenceUseCurrent -> JSONObject()
            .put(KEY_TYPE, TYPE_CADENCE_USE_CURRENT)
            .putCadence(KEY_CADENCE, command.cadence)
            .toString()
        is GarminCommand.SpeedCorrectionToggle -> typed(TYPE_SPEED_CORRECTION_TOGGLE)
        is GarminCommand.VolumeDelta -> JSONObject()
            .put(KEY_TYPE, TYPE_VOLUME_DELTA)
            .put(KEY_DELTA, command.delta)
            .toString()
        is GarminCommand.MarkNotForRunning -> typed(TYPE_MARK_NOT_FOR_RUNNING)
    }

    // ---------------------------------------------------------------
    // Phone -> watch (state, ack)
    // ---------------------------------------------------------------

    /** Decodes a state message; null when malformed or not a state message. */
    fun decodeState(json: String): GarminWatchState? {
        val obj = parse(json) ?: return null
        if (obj.optString(KEY_TYPE) != TYPE_STATE) return null
        return GarminWatchState(
            source = obj.optString(KEY_SOURCE, "none"),
            isPlaying = obj.optBoolean(KEY_IS_PLAYING, false),
            trackId = obj.optStringOrNull(KEY_TRACK_ID),
            title = obj.optStringOrNull(KEY_TITLE),
            bpm = obj.optFloatOrNull(KEY_BPM),
            targetCadence = obj.optFloatOrNull(KEY_TARGET_CADENCE),
            speedCorrection = obj.optNullableBoolean(KEY_SPEED_CORRECTION),
            playbackSpeed = obj.optFloatOrNull(KEY_PLAYBACK_SPEED),
            volume = obj.optInt(KEY_VOLUME, 0),
        )
    }

    /** Encodes a state message. Null (unknown / not-applicable) fields are omitted. */
    fun encodeState(state: GarminWatchState): String {
        val obj = JSONObject()
            .put(KEY_TYPE, TYPE_STATE)
            .put(KEY_SOURCE, state.source)
            .put(KEY_IS_PLAYING, state.isPlaying)
        state.trackId?.let { obj.put(KEY_TRACK_ID, it) }
        state.title?.let { obj.put(KEY_TITLE, it) }
        state.bpm?.let { obj.putCadence(KEY_BPM, it) }
        state.targetCadence?.let { obj.putCadence(KEY_TARGET_CADENCE, it) }
        state.speedCorrection?.let { obj.put(KEY_SPEED_CORRECTION, it) }
        state.playbackSpeed?.takeIf { it.isFinite() }
            ?.let { obj.put(KEY_PLAYBACK_SPEED, it.toDouble()) }
        obj.put(KEY_VOLUME, state.volume)
        return obj.toString()
    }

    /** Decodes an ack message; null when malformed or not an ack message. */
    fun decodeAck(json: String): GarminAck? {
        val obj = parse(json) ?: return null
        if (obj.optString(KEY_TYPE) != TYPE_ACK) return null
        val command = obj.optStringOrNull(KEY_COMMAND) ?: return null
        return GarminAck(command = command, ok = obj.optBoolean(KEY_OK, false))
    }

    /** Encodes an ack message. */
    fun encodeAck(ack: GarminAck): String = JSONObject()
        .put(KEY_TYPE, TYPE_ACK)
        .put(KEY_COMMAND, ack.command)
        .put(KEY_OK, ack.ok)
        .toString()

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun typed(type: String): String = JSONObject().put(KEY_TYPE, type).toString()

    private fun parse(json: String): JSONObject? = try {
        JSONObject(json)
    } catch (e: JSONException) {
        null
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.optFloatOrNull(key: String): Float? =
        if (has(key) && !isNull(key)) getDouble(key).toFloat() else null

    // optDouble returns NaN for missing or non-numeric fields; only accept a
    // present, finite value so a malformed command decodes to null instead of
    // poisoning downstream state with NaN.
    private fun JSONObject.optFiniteFloatOrNull(key: String): Float? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key).toFloat()
        return if (value.isFinite()) value else null
    }

    // Same treatment for integer deltas: the field must be present, numeric,
    // integral and within a sane range.
    private fun JSONObject.optDeltaOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key)
        if (!value.isFinite() || value % 1.0 != 0.0) return null
        val delta = value.toInt()
        return if (delta in -MAX_DELTA..MAX_DELTA) delta else null
    }

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    // The watch formats cadence as a whole number when possible; write whole
    // floats as integers so encoded messages match the watch's own format.
    // Non-finite values (NaN/Infinity) are skipped: org.json rejects them on
    // the wire, and they must never reach the watch.
    private fun JSONObject.putCadence(key: String, value: Float): JSONObject =
        if (!value.isFinite()) {
            this
        } else if (value % 1f == 0f) {
            put(key, value.toInt())
        } else {
            put(key, value.toDouble())
        }
}
