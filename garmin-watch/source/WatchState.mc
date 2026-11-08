import Toybox.Lang;

import Toybox.System;

// Plain data holder for the latest phone state plus transient UI feedback.
// Updated by MessageHandler from incoming "state"/"ack" messages
// (protocol: docs/companion_garmin_app/architecture.md).
class WatchState {

    // How long a transient ack line stays on screen (drawn by the data field
    // clears it when it expires; the app's 1 Hz tick repaints once at
    // expiry).
    static const ACK_VISIBLE_MS = 2500;

    // --- playback state (from "state" messages) ---
    var source as String = "none";      // "RunBeat" | "none"
    var isPlaying as Boolean = false;
    var trackId as String = "";
    var title as String = "";
    var artist as String = "";          // track artist (RunBeat only)
    var album as String = "";           // album title (RunBeat only)
    var durationMs as Number? = null;   // track duration in ms (RunBeat only)
    var positionMs as Number? = null;   // playback position in ms, 5 s buckets (RunBeat only)
    // System.getTimer() when positionMs was last updated by a state push;
    // base for local extrapolation (see effectivePositionMs).
    var positionTimestampMs as Number = 0;
    var bpm as Float? = null;
    var targetCadence as Float? = null;
    var speedCorrection as Boolean = false;
    var shuffle as Boolean = false;    // shuffle mode on (RunBeat only; false for "none")
    var isFavorite as Boolean = false;  // current track favorited (RunBeat only; false for "none")
    var playbackSpeed as Float? = null;
    var volume as Float? = null;        // AudioManager STREAM_MUSIC index

    // --- transient confirmation (from "ack" messages) ---
    var ackText as String = "";
    var ackIsError as Boolean = false;
    var ackTimestamp as Number = 0;     // System.getTimer() when the ack arrived

    // --- library playlists (from the "playlists" push) ---
    // Flat indexed fields from the phone arrive as two parallel arrays here;
    // defaults are empty (nothing pushed yet / the phone has no playlists).
    var playlistIds as Array = [];      // String playlist ids
    var playlistNames as Array = [];    // String playlist names, same indexing
    var playlistCount as Number = 0;
    var selectedPlaylistIndex as Number = 0;   // highlighted row on the playlists page

    // --- connection tracking ---
    var lastMessageAt as Number = 0;    // System.getTimer() of the last valid incoming message
    var phoneReachable as Boolean = false;

    // Protocol API version reported by the phone in its state/ack pushes (0 = unknown).
    var phoneApiVersion as Number = 0;

    // --- deferred repaint (battery: coalesce redraws) ---
    // Set when a "state" push changed nothing but the playback position:
    // nothing else on screen changed, so instead of a second full-screen
    // repaint on top of the 1 Hz tick's, the tick consumes the flag and
    // paints the position together with its own update.
    private var _positionDirty as Boolean = false;

    // --- pushed adjust screen (volume / target cadence) ---
    // The screen that is currently pushed on top of the view stack, or
    // null. The pushing page sets it, the screen clears it in onHide, and
    // the app's 1 Hz cadence tick calls checkAutoPop() on it — the screen
    // must not start a Timer of its own (hosts allow only ~3 concurrent
    // timers and the app already runs two repeating ones).
    var adjustScreen = null;

    // True while a page menu is pushed on top of the view stack (set by the
    // (data field: no page menus; kept for protocol compatibility)
    //
    // the app's 1 Hz tick never repaints them.
    var menuOpen as Boolean = false;

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

    // Staleness thresholds (battery): while PLAYING the phone pushes state
    // about once per 5 s (position in 5 s buckets), so 10 s without a
    // message means the link is dead. While PAUSED the phone pushes
    // nothing — the watch only hears back when it asks (request_state) —
    // so a longer threshold (30 s) keeps that probe/answer ping-pong (a
    // BLE wakeup per round trip) at ~1/30 s instead of ~1/10 s. Cost:
    // dead-phone detection and the "Phone not connected" header flip are
    // slower while paused (30 s instead of 10 s); each answer still
    // refreshes the link, so a live phone never shows as stale for long.
    static const STALE_PLAYING_MS = 10000;
    static const STALE_IDLE_MS = 30000;

    // True while the phone link looks dead: no message ever received, or
    // the last one is older than the threshold for the current play state
    // (10 s playing / 30 s paused — see above). While playing, the phone
    // pushes state about once per 5 s and answers the watch's
    // request_state keepalive, so a live link never goes stale.
    function isStale(now as Number) as Boolean {
        if (lastMessageAt == 0) {
            return true;
        }
        var threshold = isPlaying ? STALE_PLAYING_MS : STALE_IDLE_MS;
        return now - lastMessageAt > threshold;
    }

    // True once any message has arrived; distinguishes "Connecting..."
    // (no message yet) from "Phone not connected" (stale) in the header.
    function hasConnected() as Boolean {
        return lastMessageAt > 0;
    }

    // Replaces the playlist list (called by MessageHandler.applyPlaylists).
    // The selection is clamped into the new range, not reset.
    function setPlaylists(ids as Array, names as Array) as Void {
        playlistIds = ids;
        playlistNames = names;
        playlistCount = ids.size();
        if (selectedPlaylistIndex >= playlistCount) {
            selectedPlaylistIndex = playlistCount > 0 ? playlistCount - 1 : 0;
        }
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

    // True while the ack line is inside its display window (the same
    // constant drawAck uses to expire it). The app's 1 Hz tick watches
    // the visible/not-visible transition to repaint exactly once when
    // the line disappears.
    function isAckVisible(now as Number) as Boolean {
        return ackText.length() > 0 && now - ackTimestamp <= ACK_VISIBLE_MS;
    }

    // Deferred-repaint flag: set by MessageHandler on a position-only
    // state push, consumed by the app's 1 Hz tick.
    function markPositionDirty() as Void {
        _positionDirty = true;
    }

    function consumePositionDirty() as Boolean {
        var dirty = _positionDirty;
        _positionDirty = false;
        return dirty;
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

    // Optimistic bump of the displayed target cadence (adjust screen):
    // applied on each +/- tap so the number moves immediately instead
    // of waiting for the phone's next state push. The push stays the
    // source of truth - the next "state" message overwrites this field
    // with the authoritative value. Clamped to the same 150..195 spm
    // bounds the phone applies (PlayerRepository coerceIn/coerceAtMost/
    // coerceAtLeast); at a boundary the number settles on reconcile.
    function bumpTargetCadence(delta as Number) as Void {
        var v = targetCadence;
        if (v == null) {
            return;
        }
        var next = (v as Float) + delta;
        if (next < 150.0) {
            next = 150.0;
        }
        if (next > 195.0) {
            next = 195.0;
        }
        targetCadence = next;
    }

    function playbackSpeedText() as String {
        var v = playbackSpeed;
        return v == null ? "" : (v as Float).format("%.2f");
    }

    function volumeText() as String {
        var v = volume;
        return v == null ? "" : (v as Float).format("%.0f");
    }

    // Locally extrapolated playback position: the phone pushes positionMs
    // only every 5 s (5 s buckets) to keep BLE traffic low, so between
    // pushes the watch advances it itself while playing:
    // at the actual playback speed and clamps to the track duration. This keeps the progress bar
    // and the m:ss caption smooth at the 1 Hz repaint rate; the next push
    // re-anchors any drift. Null when the position is unknown; never
    // negative, never past the duration.
    function effectivePositionMs(now as Number) as Number? {
        var v = positionMs;
        if (v == null) {
            return null;
        }
        var pos = v as Number;
        if (isPlaying && now > positionTimestampMs) {
            // Advance at the actual playback speed: ExoPlayer currentPosition
            // moves at playbackSpeed x real time in media ms.
            var speed = playbackSpeed;
            var rate = (speed == null ? 1.0 : speed) as Float;
            pos += ((now - positionTimestampMs) * rate).toNumber();
        }
        if (pos < 0) {
            pos = 0;
        }
        // Clamp to the track duration so the bar and caption never overshoot.
        var d = durationMs;
        if (d != null && pos > (d as Number)) {
            pos = d as Number;
        }
        return pos;
    }

    // Playback position as m:ss, extrapolated to `now` ("" when unknown).
    function positionTextAt(now as Number) as String {
        var v = effectivePositionMs(now);
        return v == null ? "" : formatMs(v as Number);
    }

    // Track duration as m:ss ("" when unknown).
    function durationText() as String {
        var v = durationMs;
        return v == null ? "" : formatMs(v as Number);
    }

    // "1:23 / 3:45" with the position extrapolated to `now`
    // ("" when the duration is unknown).
    function progressTextAt(now as Number) as String {
        var d = durationMs;
        if (d == null || (d as Number) <= 0) {
            return "";
        }
        var p = positionTextAt(now);
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
