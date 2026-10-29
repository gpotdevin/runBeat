// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.Context
import android.content.SharedPreferences
import com.bpmapp.audio.audio.PlayerRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [GarminMessageCodec].
 *
 * Round-trips every command type and the state/ack messages, and checks the
 * exact wire format against the strings the watch app sends
 * (garmin-watch/source/MessageHandler.mc).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GarminMessageCodecTest {

    // ---------------------------------------------------------------
    // Watch -> phone commands: decode the watch's exact wire strings
    // ---------------------------------------------------------------

    @Test
    fun decodesPlainTransportCommands() {
        assertEquals(GarminCommand.Play, GarminMessageCodec.decodeCommand("{\"type\":\"play\"}"))
        assertEquals(GarminCommand.Pause, GarminMessageCodec.decodeCommand("{\"type\":\"pause\"}"))
        assertEquals(GarminCommand.Toggle, GarminMessageCodec.decodeCommand("{\"type\":\"toggle\"}"))
        assertEquals(GarminCommand.Next, GarminMessageCodec.decodeCommand("{\"type\":\"next\"}"))
        assertEquals(GarminCommand.Prev, GarminMessageCodec.decodeCommand("{\"type\":\"prev\"}"))
    }

    @Test
    fun decodesCadenceCommands() {
        assertEquals(
            GarminCommand.CadenceSet(172f),
            GarminMessageCodec.decodeCommand("{\"type\":\"cadence_set\",\"cadence\":172}")
        )
        assertEquals(
            GarminCommand.CadenceDelta(1),
            GarminMessageCodec.decodeCommand("{\"type\":\"cadence_delta\",\"delta\":1}")
        )
        assertEquals(
            GarminCommand.CadenceDelta(-1),
            GarminMessageCodec.decodeCommand("{\"type\":\"cadence_delta\",\"delta\":-1}")
        )
        // The watch formats the 60 s mean as a whole number ("%.0f").
        assertEquals(
            GarminCommand.CadenceUseCurrent(168f),
            GarminMessageCodec.decodeCommand("{\"type\":\"cadence_use_current\",\"cadence\":168}")
        )
    }

    @Test
    fun decodesRemainingCommands() {
        assertEquals(
            GarminCommand.SpeedCorrectionToggle,
            GarminMessageCodec.decodeCommand("{\"type\":\"speed_correction_toggle\"}")
        )
        assertEquals(
            GarminCommand.VolumeDelta(1),
            GarminMessageCodec.decodeCommand("{\"type\":\"volume_delta\",\"delta\":1}")
        )
        assertEquals(
            GarminCommand.VolumeDelta(-1),
            GarminMessageCodec.decodeCommand("{\"type\":\"volume_delta\",\"delta\":-1}")
        )
        assertEquals(
            GarminCommand.MarkNotForRunning,
            GarminMessageCodec.decodeCommand("{\"type\":\"mark_not_for_running\"}")
        )
    }

    @Test
    fun decodesRequestStateCommand() {
        assertEquals(
            GarminCommand.RequestState,
            GarminMessageCodec.decodeCommand("{\"type\":\"request_state\"}")
        )
    }

    @Test
    fun decodesSeekCommand() {
        assertEquals(
            GarminCommand.Seek(42000L),
            GarminMessageCodec.decodeCommand("{\"type\":\"seek\",\"positionMs\":42000}")
        )
        // The boundaries of the 24 h sanity range are accepted.
        assertEquals(
            GarminCommand.Seek(0L),
            GarminMessageCodec.decodeCommand("{\"type\":\"seek\",\"positionMs\":0}")
        )
        assertEquals(
            GarminCommand.Seek(86400000L),
            GarminMessageCodec.decodeCommand("{\"type\":\"seek\",\"positionMs\":86400000}")
        )
    }

    @Test
    fun decodeCommandRejectsMissingInvalidOrAbsurdSeekPosition() {
        // Missing field: optDouble would yield NaN.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"seek\"}"))
        // JSON null.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"seek\",\"positionMs\":null}"))
        // Non-numeric field.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"seek\",\"positionMs\":\"abc\"}"))
        // Negative position.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"seek\",\"positionMs\":-1}"))
        // Non-integral position.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"seek\",\"positionMs\":42000.5}"))
        // Beyond the 24 h sanity bound.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"seek\",\"positionMs\":86400001}"))
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"seek\",\"positionMs\":100000000000}"))
    }

    @Test
    fun decodeCommandIsTolerant() {
        assertNull(GarminMessageCodec.decodeCommand("not json"))
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"unknown_command\"}"))
        assertNull(GarminMessageCodec.decodeCommand("{\"foo\":1}"))
        // A phone->watch message is not a command.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"state\",\"source\":\"RunBeat\"}"))
    }

    // ---------------------------------------------------------------
    // Malformed numeric fields must decode to null, never to NaN
    // ---------------------------------------------------------------

    @Test
    fun decodeCommandRejectsMissingOrInvalidCadence() {
        // Missing field: optDouble would yield NaN.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"cadence_set\"}"))
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"cadence_use_current\"}"))
        // Non-numeric field: optDouble would yield NaN.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"cadence_set\",\"cadence\":\"abc\"}"))
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"cadence_use_current\",\"cadence\":\"abc\"}"))
        // JSON null.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"cadence_set\",\"cadence\":null}"))
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"cadence_use_current\",\"cadence\":null}"))
    }

    @Test
    fun decodeCommandRejectsMissingInvalidOrAbsurdDelta() {
        // Missing field: optInt would yield 0, indistinguishable from a real
        // "no-op" delta.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"cadence_delta\"}"))
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"volume_delta\"}"))
        // Non-numeric field.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"cadence_delta\",\"delta\":\"abc\"}"))
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"volume_delta\",\"delta\":\"abc\"}"))
        // JSON null.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"cadence_delta\",\"delta\":null}"))
        // Non-integral and out-of-range deltas are rejected too.
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"cadence_delta\",\"delta\":1.5}"))
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"cadence_delta\",\"delta\":100000}"))
        assertNull(GarminMessageCodec.decodeCommand("{\"type\":\"volume_delta\",\"delta\":-100000}"))
        // In-range deltas at the sanity bound still decode.
        assertEquals(
            GarminCommand.CadenceDelta(100),
            GarminMessageCodec.decodeCommand("{\"type\":\"cadence_delta\",\"delta\":100}")
        )
        assertEquals(
            GarminCommand.VolumeDelta(-100),
            GarminMessageCodec.decodeCommand("{\"type\":\"volume_delta\",\"delta\":-100}")
        )
    }

    // ---------------------------------------------------------------
    // Command round-trips
    // ---------------------------------------------------------------

    @Test
    fun commandRoundTrip() {
        val commands = listOf(
            GarminCommand.Play,
            GarminCommand.Pause,
            GarminCommand.Toggle,
            GarminCommand.Next,
            GarminCommand.Prev,
            GarminCommand.CadenceSet(172f),
            GarminCommand.CadenceSet(172.5f),
            GarminCommand.CadenceDelta(1),
            GarminCommand.CadenceDelta(-1),
            GarminCommand.CadenceUseCurrent(168f),
            GarminCommand.SpeedCorrectionToggle,
            GarminCommand.VolumeDelta(1),
            GarminCommand.VolumeDelta(-1),
            GarminCommand.MarkNotForRunning,
            GarminCommand.RequestState,
            GarminCommand.Bye,
            GarminCommand.Seek(42000L),
        )
        for (command in commands) {
            val encoded = GarminMessageCodec.encodeCommand(command)
            assertEquals("round trip failed for $command", command, GarminMessageCodec.decodeCommand(encoded))
        }
    }

    @Test
    fun encodeCommandMatchesWatchWireFormat() {
        assertEquals("{\"type\":\"play\"}", GarminMessageCodec.encodeCommand(GarminCommand.Play))
        assertEquals("{\"type\":\"toggle\"}", GarminMessageCodec.encodeCommand(GarminCommand.Toggle))
        assertEquals(
            "{\"type\":\"cadence_set\",\"cadence\":172}",
            GarminMessageCodec.encodeCommand(GarminCommand.CadenceSet(172f))
        )
        assertEquals(
            "{\"type\":\"cadence_delta\",\"delta\":-1}",
            GarminMessageCodec.encodeCommand(GarminCommand.CadenceDelta(-1))
        )
        assertEquals(
            "{\"type\":\"volume_delta\",\"delta\":1}",
            GarminMessageCodec.encodeCommand(GarminCommand.VolumeDelta(1))
        )
        assertEquals(
            "{\"type\":\"request_state\"}",
            GarminMessageCodec.encodeCommand(GarminCommand.RequestState)
        )
        assertEquals(
            "{\"type\":\"bye\"}",
            GarminMessageCodec.encodeCommand(GarminCommand.Bye)
        )
        assertEquals(
            "{\"type\":\"seek\",\"positionMs\":42000}",
            GarminMessageCodec.encodeCommand(GarminCommand.Seek(42000L))
        )
    }

    // ---------------------------------------------------------------
    // Phone -> watch: wake (background re-activation)
    // ---------------------------------------------------------------

    @Test
    fun wakeWireFormatIsExact() {
        // The watch's background service matches the exact substring
        // "type":"wake" with no JSON parser (32 KB background heap), so
        // the wire format must stay whitespace-free.
        assertEquals("{\"type\":\"wake\"}", GarminMessageCodec.encodeWake())
    }

    @Test
    fun isWakeRecognizesWakeMessages() {
        assertTrue(GarminMessageCodec.isWake(GarminMessageCodec.encodeWake()))
        assertEquals(false, GarminMessageCodec.isWake(GarminMessageCodec.encodeAck(GarminAck("toggle", true))))
        assertEquals(false, GarminMessageCodec.isWake("not json"))
    }

    // ---------------------------------------------------------------
    // Phone -> watch: state
    // ---------------------------------------------------------------

    @Test
    fun decodesWatchExampleStateMessage() {
        // The example from architecture.md / MessageHandler.mc, verbatim.
        val json = "{\"type\":\"state\",\"source\":\"RunBeat\",\"isPlaying\":true," +
            "\"trackId\":\"content://media/...\",\"title\":\"Song name\"," +
            "\"artist\":\"Artist name\",\"album\":\"Album name\"," +
            "\"durationMs\":213000,\"positionMs\":42000," +
            "\"bpm\":140.0,\"targetCadence\":172,\"speedCorrection\":true," +
            "\"playbackSpeed\":1.03,\"volume\":12}"
        val state = GarminMessageCodec.decodeState(json)
        assertEquals(
            GarminWatchState(
                source = "RunBeat",
                isPlaying = true,
                trackId = "content://media/...",
                title = "Song name",
                artist = "Artist name",
                album = "Album name",
                durationMs = 213000L,
                positionMs = 42000L,
                bpm = 140f,
                targetCadence = 172f,
                speedCorrection = true,
                playbackSpeed = 1.03f,
                volume = 12,
            ),
            state
        )
    }

    @Test
    fun stateRoundTrip() {
        val state = GarminWatchState(
            source = "RunBeat",
            isPlaying = false,
            trackId = "/music/track.mp3",
            title = "Track",
            artist = "Track Artist",
            album = "Track Album",
            durationMs = 213000L,
            positionMs = 42000L,
            bpm = 86.5f,
            targetCadence = 173f,
            speedCorrection = false,
            playbackSpeed = 1.0f,
            volume = 9,
        )
        val decoded = GarminMessageCodec.decodeState(GarminMessageCodec.encodeState(state))
        assertEquals(state, decoded)
    }

    @Test
    fun stateRoundTripWithUtf8AndSpecialCharactersInTitle() {
        val state = GarminWatchState(
            source = "RunBeat",
            isPlaying = true,
            trackId = "track-1",
            title = "Björk — “Hyperballad” \\ 100% café \uD83C\uDFC3 newline\nand\ttab",
            bpm = 140f,
            targetCadence = 172f,
            speedCorrection = true,
            playbackSpeed = 1.03f,
            volume = 12,
        )
        val encoded = GarminMessageCodec.encodeState(state)
        assertEquals(state, GarminMessageCodec.decodeState(encoded))
    }

    @Test
    fun stateRoundTripWithUtf8AndSpecialCharactersInArtistAndAlbum() {
        val state = GarminWatchState(
            source = "RunBeat",
            isPlaying = true,
            trackId = "track-1",
            title = "Track",
            artist = "Björk — “Hyperballad” \\ 100% café \uD83C\uDFB8",
            album = "Homogénéité \uD83D\uDCE0 newline\nand\ttab",
            durationMs = 213000L,
            positionMs = 42000L,
            volume = 12,
        )
        val encoded = GarminMessageCodec.encodeState(state)
        assertEquals(state, GarminMessageCodec.decodeState(encoded))
    }

    @Test
    fun encodeStateOmitsUnknownRunBeatOnlyFields() {
        // source != "RunBeat": RunBeat-only fields are omitted, per the protocol.
        val encoded = GarminMessageCodec.encodeState(
            GarminWatchState(source = "external", isPlaying = true, volume = 7)
        )
        val obj = JSONObject(encoded)
        assertEquals("state", obj.getString("type"))
        assertEquals("external", obj.getString("source"))
        assertTrue(obj.getBoolean("isPlaying"))
        assertEquals(7, obj.getInt("volume"))
        for (key in listOf("trackId", "title", "artist", "album", "durationMs", "positionMs", "bpm", "targetCadence", "speedCorrection", "playbackSpeed")) {
            assertFalse("$key should be omitted: $encoded", obj.has(key))
        }
        // Decoding the sparse message yields nulls for the omitted fields.
        assertEquals(
            GarminWatchState(source = "external", isPlaying = true, volume = 7),
            GarminMessageCodec.decodeState(encoded)
        )
    }

    @Test
    fun encodeStateEmitsArtistAlbumDurationAndPosition() {
        val encoded = GarminMessageCodec.encodeState(
            GarminWatchState(
                source = "RunBeat",
                isPlaying = true,
                trackId = "track-1",
                title = "Song",
                artist = "Song Artist",
                album = "Song Album",
                durationMs = 213000L,
                positionMs = 42000L,
                volume = 12,
            )
        )
        val obj = JSONObject(encoded)
        assertEquals("state", obj.getString("type"))
        assertEquals("Song Artist", obj.getString("artist"))
        assertEquals("Song Album", obj.getString("album"))
        assertEquals(213000L, obj.getLong("durationMs"))
        assertEquals(42000L, obj.getLong("positionMs"))
    }

    @Test
    fun decodeStateIsTolerant() {
        assertNull(GarminMessageCodec.decodeState("not json"))
        assertNull(GarminMessageCodec.decodeState("{\"type\":\"ack\",\"command\":\"play\",\"ok\":true}"))
    }

    @Test
    fun encodeStateSkipsNonFiniteNumericFields() {
        // org.json rejects NaN/Infinity ("Forbidden numeric value"), which
        // used to kill the state-push coroutine. Non-finite fields must be
        // omitted instead.
        val state = GarminWatchState(
            source = "RunBeat",
            isPlaying = true,
            bpm = Float.NaN,
            targetCadence = Float.POSITIVE_INFINITY,
            playbackSpeed = Float.NaN,
            volume = 5,
        )
        val encoded = GarminMessageCodec.encodeState(state)
        val obj = JSONObject(encoded)
        assertFalse("bpm should be omitted: $encoded", obj.has("bpm"))
        assertFalse("targetCadence should be omitted: $encoded", obj.has("targetCadence"))
        assertFalse("playbackSpeed should be omitted: $encoded", obj.has("playbackSpeed"))
        assertEquals(5, obj.getInt("volume"))
    }

    // ---------------------------------------------------------------
    // Phone -> watch: ack
    // ---------------------------------------------------------------

    @Test
    fun decodesAckMessage() {
        assertEquals(
            GarminAck(command = "mark_not_for_running", ok = true),
            GarminMessageCodec.decodeAck("{\"type\":\"ack\",\"command\":\"mark_not_for_running\",\"ok\":true}")
        )
        assertEquals(
            GarminAck(command = "cadence_set", ok = false),
            GarminMessageCodec.decodeAck("{\"type\":\"ack\",\"command\":\"cadence_set\",\"ok\":false}")
        )
    }

    @Test
    fun ackRoundTrip() {
        val ack = GarminAck(command = "speed_correction_toggle", ok = true)
        assertEquals(ack, GarminMessageCodec.decodeAck(GarminMessageCodec.encodeAck(ack)))
        assertEquals(
            "{\"type\":\"ack\",\"command\":\"mark_not_for_running\",\"ok\":true}",
            GarminMessageCodec.encodeAck(GarminAck("mark_not_for_running", ok = true))
        )
    }

    @Test
    fun decodeAckIsTolerant() {
        assertNull(GarminMessageCodec.decodeAck("not json"))
        assertNull(GarminMessageCodec.decodeAck("{\"type\":\"state\"}"))
        assertNull(GarminMessageCodec.decodeAck("{\"type\":\"ack\",\"ok\":true}")) // no command
    }

    // ---------------------------------------------------------------
    // PlayerRepository NaN guard (defense in depth behind the codec)
    // ---------------------------------------------------------------

    @Test
    fun setTargetCadenceIgnoresNonFiniteValues() {
        // Same mock style as PlayerRepositoryModeBeepDuckTest: nothing stored
        // in prefs, mocked ExoPlayer, no current track so the speed-correction
        // re-apply is a no-op.
        val context = mock<Context>()
        val prefs = mock<SharedPreferences>()
        whenever(prefs.getStringSet(any(), any())).thenReturn(null)
        whenever(prefs.getBoolean(any(), any())).thenReturn(true)
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        val repository = PlayerRepository(context, mock(), mock(), mock())

        val before = repository.targetCadence.value
        repository.setTargetCadence(Float.NaN)
        repository.setTargetCadence(Float.POSITIVE_INFINITY)
        repository.setTargetCadence(Float.NEGATIVE_INFINITY)
        assertEquals(before, repository.targetCadence.value, 0f)

        // Finite values still apply (clamped to the 150-195 range).
        repository.setTargetCadence(180f)
        assertEquals(180f, repository.targetCadence.value, 0f)
    }
}
