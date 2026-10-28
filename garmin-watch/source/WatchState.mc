import Toybox.Lang;

import Toybox.System;

// Plain data holder for the latest phone state plus transient UI feedback.
// Updated by MessageHandler from incoming "state"/"ack" messages
// (protocol: docs/companion_garmin_app/architecture.md).
class WatchState {

    // --- playback state (from "state" messages) ---
    var source as String = "none";      // "RunBeat" | "external" | external app name | "none"
    var isPlaying as Boolean = false;
    var trackId as String = "";
    var title as String = "";
    var artist as String = "";          // track artist (RunBeat only)
    var album as String = "";           // album title (RunBeat only)
    var durationMs as Number? = null;   // track duration in ms (RunBeat only)
    var positionMs as Number? = null;   // playback position in ms, whole seconds (RunBeat only)
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

    // --- pushed adjust screen (volume / target cadence) ---
    // The screen that is currently pushed on top of the view stack, or
    // null. The pushing page sets it, the screen clears it in onHide, and
    // the app's 1 Hz cadence tick calls checkAutoPop() on it — the screen
    // must not start a Timer of its own (hosts allow only ~3 concurrent
    // timers and the app already runs two repeating ones).
    var adjustScreen = null;

    function isRunBeatActive() as Boolean {
        return source.equals("RunBeat");
    }

    function markMessageReceived() as Void {
        lastMessageAt = System.getTimer();
        phoneReachable = true;
    }

    function markPhoneUnreachable() as Void {
        phoneReachable = false;
    }

    // True while the phone link looks dead: no message ever received, or
    // the last one is older than 10 s. While playing, the phone pushes
    // state about once per second and answers the watch's request_state,
    // so a live link never goes stale.
    function isStale(now as Number) as Boolean {
        return lastMessageAt == 0 || now - lastMessageAt > 10000;
    }

    // True once any message has arrived; distinguishes "Connecting..."
    // (no message yet) from "Phone not connected" (stale) in the header.
    function hasConnected() as Boolean {
        return lastMessageAt > 0;
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

    // Playback position as m:ss ("" when unknown).
    function positionText() as String {
        var v = positionMs;
        return v == null ? "" : formatMs(v as Number);
    }

    // Track duration as m:ss ("" when unknown).
    function durationText() as String {
        var v = durationMs;
        return v == null ? "" : formatMs(v as Number);
    }

    // "1:23 / 3:45" ("" when the duration is unknown).
    function progressText() as String {
        var d = durationMs;
        if (d == null || (d as Number) <= 0) {
            return "";
        }
        var p = positionText();
        if (p.length() == 0) {
            p = formatMs(0);
        }
        return p + " / " + durationText();
    }

    // m:ss for a millisecond value (whole seconds; ms >= 0).
    private function formatMs(ms as Number) as String {
        var totalSeconds = ms / 1000;
        var minutes = totalSeconds / 60;
        var seconds = totalSeconds % 60;
        return minutes.format("%d") + ":" + seconds.format("%02d");
    }
}
