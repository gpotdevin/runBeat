using Toybox.System;

// Plain data holder for the latest phone state plus transient UI feedback.
// Updated by MessageHandler from incoming "state"/"ack" messages
// (protocol: docs/companion_garmin_app/architecture.md).
class WatchState {

    // --- playback state (from "state" messages) ---
    var source as String = "none";      // "RunBeat" | "external" | external app name | "none"
    var isPlaying as Boolean = false;
    var trackId as String = "";
    var title as String = "";
    var bpm as Float? = null;
    var targetCadence as Float? = null;
    var speedCorrection as Boolean = false;
    var playbackSpeed as Float? = null;
    var volume as Float? = null;        // AudioManager STREAM_MUSIC index

    // --- transient confirmation (from "ack" messages) ---
    var ackText as String = "";
    var ackIsError as Boolean = false;
    var ackTimestamp as Number = 0;     // System.getTimer() when the ack arrived

    // --- connection tracking ---
    var lastMessageAt as Number = 0;    // System.getTimer() of the last valid incoming message
    var phoneReachable as Boolean = false;

    function isRunBeatActive() as Boolean {
        return source == "RunBeat";
    }

    function markMessageReceived() as Void {
        lastMessageAt = System.getTimer();
        phoneReachable = true;
    }

    function markPhoneUnreachable() as Void {
        phoneReachable = false;
    }

    function setAck(text as String, isError as Boolean) as Void {
        ackText = text;
        ackIsError = isError;
        ackTimestamp = System.getTimer();
    }

    function clearExpiredAck(now as Number, timeoutMs as Number) as Void {
        if (ackText.length() > 0 && now - ackTimestamp > timeoutMs) {
            ackText = "";
        }
    }

    // Formatted accessors (empty string when the field is absent).
    function bpmText() as String {
        var v = bpm;
        return v == null ? "" : (v as Float).format("%.0f");
    }

    function targetCadenceText() as String {
        var v = targetCadence;
        return v == null ? "" : (v as Float).format("%.0f");
    }

    function playbackSpeedText() as String {
        var v = playbackSpeed;
        return v == null ? "" : (v as Float).format("%.2f");
    }

    function volumeText() as String {
        var v = volume;
        return v == null ? "" : (v as Float).format("%.0f");
    }
}
