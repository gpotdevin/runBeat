import Toybox.Communications;
import Toybox.Lang;
import Toybox.WatchUi;

// Message protocol (normative): docs/companion_garmin_app/architecture.md
// and docs/companion_garmin_app/implementation_plan_v2_2026-10-24.md (section 3).
//
// Watch -> phone commands are small JSON strings:
//   {"type":"play"} {"type":"pause"} {"type":"toggle"} {"type":"next"} {"type":"prev"}
//   {"type":"cadence_set","cadence":172}
//   {"type":"cadence_delta","delta":1} or {"delta":-1}
//   {"type":"cadence_use_current","cadence":172}
//   {"type":"speed_correction_toggle"}
//   {"type":"volume_delta","delta":1} or {"delta":-1}
//   {"type":"mark_not_for_running"}
//   {"type":"request_state"}            (no payload, no ack; the phone answers
//                                       the sender with one state push)
//   {"type":"seek","positionMs":42000}  (no ack; positionMs is an integer)
//   {"type":"playlist_list"}             (no ack; the phone answers the sender
//                                       with one playlists push)
//   {"type":"playlist_play","playlistId":"..."}  (acked)
//
// Phone -> watch:
//   {"type":"state","source":"RunBeat","isPlaying":true,"trackId":"...","title":"...",
//    "artist":"...","album":"...","durationMs":213000,"positionMs":42000,
//    "bpm":140.0,"targetCadence":172,"speedCorrection":true,"playbackSpeed":1.03,"volume":12}
//     - artist/album: track metadata (required when source == "RunBeat");
//     - durationMs/positionMs: track length and playback position in ms
//       (positionMs rounded down to whole seconds by the phone);
//     - both are null/omitted when source != "RunBeat" or unknown.
//   {"type":"ack","command":"mark_not_for_running","ok":true}
//   {"type":"playlists","count":N,"id0":"...","name0":"...",...}
//     - flat indexed fields (the parser skips nested arrays/objects);
//       sanity bound MAX_PLAYLISTS.
//
// Monkey C has no org.json, so the flat JSON objects are built/parsed by hand.
// The parser is deliberately tolerant: unknown fields, nested structures and
// malformed input are ignored instead of failing.
class MessageHandler {

    // Sanity bound for the "playlists" push: the phone sends at most this
    // many idN/nameN indexed pairs; a larger count is truncated.
    static const MAX_PLAYLISTS = 50;

    private var _state;   // WatchState

    function initialize(state) {
        _state = state;
    }

    // ---------------------------------------------------------------
    // Outgoing commands (watch -> phone)
    // ---------------------------------------------------------------

    function sendToggle() as Void {
        sendJson("{\"type\":\"toggle\"}");
    }

    function sendNext() as Void {
        sendJson("{\"type\":\"next\"}");
    }

    function sendPrev() as Void {
        sendJson("{\"type\":\"prev\"}");
    }

    function sendCadenceDelta(delta as Number) as Void {
        sendJson("{\"type\":\"cadence_delta\",\"delta\":" + delta.format("%d") + "}");
    }

    function sendCadenceUseCurrent(cadence as Float) as Void {
        // Whole spm: the protocol examples use integer cadence, and an integer
        // payload parses correctly whether the phone reads it as Int or Double.
        sendJson("{\"type\":\"cadence_use_current\",\"cadence\":" + cadence.format("%.0f") + "}");
    }

    function sendSpeedCorrectionToggle() as Void {
        sendJson("{\"type\":\"speed_correction_toggle\"}");
    }

    function sendVolumeDelta(delta as Number) as Void {
        sendJson("{\"type\":\"volume_delta\",\"delta\":" + delta.format("%d") + "}");
    }

    function sendMarkNotForRunning() as Void {
        sendJson("{\"type\":\"mark_not_for_running\"}");
    }

    // Playlist list request (link-layer: the phone intercepts it like
    // request_state and answers the sender with one playlists push, no ack).
    function sendPlaylistList() as Void {
        sendJson("{\"type\":\"playlist_list\"}");
    }

    // Play one library playlist on the phone (routed, acked).
    function sendPlaylistPlay(playlistId as String) as Void {
        sendJson("{\"type\":\"playlist_play\",\"playlistId\":\"" + jsonEscape(playlistId) + "\"}");
    }

    // Handshake: ask the phone for one state push. Sent on app start, every
    // 5 s while playing (keepalive — the phone only pushes state to an
    // "active" watch app, and while playing the app itself sends nothing),
    // and while the state is stale with exponential backoff (see
    // RunBeatCompanionApp.onStateTimerTick — an unreachable phone must
    // not trigger a radio attempt every 5 s forever). No ack — the answer
    // is the state push itself.
    function sendRequestState() as Void {
        sendJson("{\"type\":\"request_state\"}");
    }

    // Best-effort farewell, sent in onStop: lets the phone drop its
    // watch-app active window immediately (and stop pushing state) instead
    // of waiting for the window to expire.
    function sendBye() as Void {
        sendJson("{\"type\":\"bye\"}");
    }

    // ---------------------------------------------------------------
    // Incoming messages (phone -> watch)
    // ---------------------------------------------------------------

    // Callback registered with Communications.registerForPhoneAppMessages().
    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (!(data instanceof String)) {
            return;
        }
        var payload = data as String;
        var dict = parseFlatObject(payload);
        if (dict == null) {
            return;
        }
        handleParsed(dict as Dictionary);
    }

    private function handleParsed(dict as Dictionary) as Void {
        var type = dict["type"];
        if (!(type instanceof String)) {
            return;
        }
        var t = type as String;
        if (t.equals("state")) {
            applyState(dict);
        } else if (t.equals("ack")) {
            applyAck(dict);
        } else if (t.equals("playlists")) {
            applyPlaylists(dict);
        }
        // Unknown message types are ignored (forward compatibility).
    }

    private function applyState(dict as Dictionary) as Void {
        var s = _state;
        var now = System.getTimer();
        // The header flips back from "Phone not connected" to the source
        // label when this message un-stales the link — repaint even if the
        // state content is unchanged.
        var wasStale = s.isStale(now);
        // Snapshot of every rendered field except positionMs: while playing
        // the phone pushes ~1/s and only positionMs advances, and painting
        // those pushes immediately would double the repaint rate on top of
        // the 1 Hz tick. A position-only push is deferred to the tick.
        var before = [s.source, s.isPlaying, s.trackId, s.title, s.artist,
            s.album, s.durationMs, s.bpm, s.targetCadence, s.speedCorrection,
            s.playbackSpeed, s.volume];
        var source = dict["source"];
        if (source instanceof String) {
            s.source = source as String;
        }
        var isPlaying = dict["isPlaying"];
        if (isPlaying instanceof Boolean) {
            s.isPlaying = isPlaying as Boolean;
        }
        var trackId = dict["trackId"];
        if (trackId instanceof String) {
            s.trackId = trackId as String;
        }
        var title = dict["title"];
        if (title instanceof String) {
            s.title = title as String;
        }
        var artist = dict["artist"];
        if (artist instanceof String) {
            s.artist = artist as String;
        }
        var album = dict["album"];
        if (album instanceof String) {
            s.album = album as String;
        }
        s.durationMs = optionalNumber(dict, "durationMs");
        s.positionMs = optionalNumber(dict, "positionMs");
        s.bpm = optionalFloat(dict, "bpm");
        s.targetCadence = optionalFloat(dict, "targetCadence");
        var speedCorrection = dict["speedCorrection"];
        if (speedCorrection instanceof Boolean) {
            s.speedCorrection = speedCorrection as Boolean;
        }
        s.playbackSpeed = optionalFloat(dict, "playbackSpeed");
        s.volume = optionalFloat(dict, "volume");

        if (!s.source.equals("RunBeat")) {
            // RunBeat-only fields are omitted or null for other sources; drop
            // stale values so nothing RunBeat-specific lingers on screen.
            s.trackId = "";
            s.title = "";
            s.artist = "";
            s.album = "";
            s.durationMs = null;
            s.positionMs = null;
            s.bpm = null;
            s.targetCadence = null;
            s.speedCorrection = false;
            s.playbackSpeed = null;
        }

        s.markMessageReceived();
        var after = [s.source, s.isPlaying, s.trackId, s.title, s.artist,
            s.album, s.durationMs, s.bpm, s.targetCadence, s.speedCorrection,
            s.playbackSpeed, s.volume];
        if (wasStale || !statesEqual(before, after)) {
            WatchUi.requestUpdate();
        } else {
            // Position-only push (the common case while playing): let the
            // app's 1 Hz tick paint it in the same repaint as the cadence
            // tick, instead of a second full-screen render.
            s.markPositionDirty();
        }
    }

    // {"type":"playlists","count":N,"id0":"...","name0":"...",...} — the
    // library playlist list pushed in answer to playlist_list. Flat indexed
    // fields; tolerant: a missing count means 0, a missing idN/nameN entry
    // becomes an empty string, and the count is sanity-bound to
    // MAX_PLAYLISTS.
    private function applyPlaylists(dict as Dictionary) as Void {
        var countValue = optionalNumber(dict, "count");
        var count = countValue == null ? 0 : countValue as Number;
        if (count < 0) {
            count = 0;
        }
        if (count > MAX_PLAYLISTS) {
            count = MAX_PLAYLISTS;
        }
        var ids = [];
        var names = [];
        for (var i = 0; i < count; i++) {
            var idValue = dict["id" + i.format("%d")];
            var nameValue = dict["name" + i.format("%d")];
            ids.add(idValue instanceof String ? idValue as String : "");
            names.add(nameValue instanceof String ? nameValue as String : "");
        }
        _state.setPlaylists(ids, names);
        _state.markMessageReceived();
        WatchUi.requestUpdate();
    }

    // Element-wise equality of the two applyState field snapshots.
    private function statesEqual(a as Array, b as Array) as Boolean {
        var n = a.size();
        for (var i = 0; i < n; i++) {
            if (valueDiffers(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    // True when two parsed field values differ. Monkey C compares strings
    // by reference with ==/!= (see e2e_connection_test_report.md rev. 4),
    // so strings go through .equals(); null is compared explicitly.
    // Numbers/booleans arrive type-consistently from the parser
    // (optionalFloat always yields Float, optionalNumber always Number),
    // so == compares values there.
    private function valueDiffers(a, b) as Boolean {
        if (a == null || b == null) {
            return !(a == null && b == null);
        }
        if (a instanceof String && b instanceof String) {
            return !(a as String).equals(b as String);
        }
        return !(a == b);
    }

    private function applyAck(dict as Dictionary) as Void {
        var ok = false;
        var okValue = dict["ok"];
        if (okValue instanceof Boolean) {
            ok = okValue as Boolean;
        }

        var text = "";
        var command = dict["command"];
        if (command instanceof String) {
            var cmd = command as String;
            if (cmd.equals("mark_not_for_running")) {
                text = ok ? WatchUi.loadResource(Rez.Strings.AckMarked) as String : WatchUi.loadResource(Rez.Strings.AckMarkFailed) as String;
            } else if (cmd.equals("cadence_use_current") || cmd.equals("cadence_set") || cmd.equals("cadence_delta")) {
                text = ok ? WatchUi.loadResource(Rez.Strings.AckCadenceApplied) as String : WatchUi.loadResource(Rez.Strings.AckCadenceFailed) as String;
            } else if (cmd.equals("speed_correction_toggle")) {
                text = ok ? WatchUi.loadResource(Rez.Strings.AckSpeedCorrection) as String : WatchUi.loadResource(Rez.Strings.AckFailed) as String;
            } else if (cmd.equals("playlist_play")) {
                text = ok ? WatchUi.loadResource(Rez.Strings.AckPlaylistStarted) as String : WatchUi.loadResource(Rez.Strings.AckPlaylistFailed) as String;
            } else {
                text = ok ? WatchUi.loadResource(Rez.Strings.AckOk) as String : WatchUi.loadResource(Rez.Strings.AckFailed) as String;
            }
        } else {
            text = ok ? WatchUi.loadResource(Rez.Strings.AckOk) as String : WatchUi.loadResource(Rez.Strings.AckFailed) as String;
        }

        _state.setAck(text, !ok);
        _state.markMessageReceived();
        WatchUi.requestUpdate();
    }

    private function sendJson(json as String) as Void {
        // transmit() reports failure through the ConnectionListener's
        // onError() callback.
        Communications.transmit(json, null, new RunBeatConnectionListener(_state));
    }

    // Escapes a string for embedding as a JSON string value (used for the
    // playlist id in playlist_play).
    private function jsonEscape(s as String) as String {
        var result = "";
        var n = s.length();
        for (var i = 0; i < n; i++) {
            var c = s.substring(i, i + 1);
            if (c.equals("\\")) {
                result += "\\\\";
            } else if (c.equals("\"")) {
                result += "\\\"";
            } else if (c.equals("\n")) {
                result += "\\n";
            } else if (c.equals("\r")) {
                result += "\\r";
            } else if (c.equals("\t")) {
                result += "\\t";
            } else {
                result += c;
            }
        }
        return result;
    }

    private function optionalFloat(dict as Dictionary, key as String) as Float? {
        var v = dict[key];
        if (v instanceof Float) {
            return v as Float;
        }
        if (v instanceof Number) {
            return (v as Number) + 0.0;
        }
        return null;
    }

    // Integer-valued field (durations/positions in ms). The flat parser
    // yields a Number for numeric literals; a Float is tolerated too.
    private function optionalNumber(dict as Dictionary, key as String) as Number? {
        var v = dict[key];
        if (v instanceof Number) {
            return v as Number;
        }
        if (v instanceof Float) {
            return (v as Float).toNumber();
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Minimal flat-JSON-object parser (tolerant)
    // ---------------------------------------------------------------

    // Parses a flat JSON object into a Dictionary with String keys.
    // Returns null on malformed input. Nested objects/arrays are skipped
    // (the protocol has none, but stay tolerant).
    private function parseFlatObject(text as String) as Dictionary? {
        var result = {};
        var n = text.length();
        var i = skipWs(text, 0);
        if (i >= n || !text.substring(i, i + 1).equals("{")) {
            return null;
        }
        i = skipWs(text, i + 1);
        if (i < n && text.substring(i, i + 1).equals("}")) {
            return result;
        }
        while (i < n) {
            i = skipWs(text, i);
            if (i >= n) {
                return null;
            }
            var c = text.substring(i, i + 1);
            if (c.equals("}")) {
                return result;
            }
            if (c.equals(",")) {
                i++;
                continue;
            }
            if (!c.equals("\"")) {
                return null;
            }
            var keyEnd = findStringEnd(text, i);
            if (keyEnd < 0) {
                return null;
            }
            var key = parseStringValue(text, i, keyEnd);
            i = skipWs(text, keyEnd + 1);
            if (i >= n || !text.substring(i, i + 1).equals(":")) {
                return null;
            }
            i = skipWs(text, i + 1);
            if (i >= n) {
                return null;
            }
            c = text.substring(i, i + 1);
            if (c.equals("\"")) {
                var valEnd = findStringEnd(text, i);
                if (valEnd < 0) {
                    return null;
                }
                result[key] = parseStringValue(text, i, valEnd);
                i = valEnd + 1;
            } else if (c.equals("{") || c.equals("[")) {
                var after = skipNested(text, i);
                if (after < 0) {
                    return null;
                }
                result[key] = null;
                i = after;
            } else {
                var j = i;
                while (j < n) {
                    var ch = text.substring(j, j + 1);
                    if (ch.equals(",") || ch.equals("}") || ch.equals(" ") || ch.equals("\t") || ch.equals("\n") || ch.equals("\r")) {
                        break;
                    }
                    j++;
                }
                var raw = text.substring(i, j);
                i = j;
                if (raw.equals("true")) {
                    result[key] = true;
                } else if (raw.equals("false")) {
                    result[key] = false;
                } else if (raw.equals("null") || raw.length() == 0) {
                    result[key] = null;
                } else {
                    var num = raw.toNumber();
                    if (num == null) {
                        return null;
                    }
                    result[key] = num;
                }
            }
        }
        return null; // ran off the end without a closing brace
    }

    private function skipWs(text as String, i as Number) as Number {
        var n = text.length();
        while (i < n) {
            var c = text.substring(i, i + 1);
            if (c.equals(" ") || c.equals("\t") || c.equals("\n") || c.equals("\r")) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    // Index of the closing quote of the JSON string starting at text[start]
    // (text[start] must be '"'), or -1 if unterminated.
    private function findStringEnd(text as String, start as Number) as Number {
        var i = start + 1;
        var n = text.length();
        while (i < n) {
            var c = text.substring(i, i + 1);
            if (c.equals("\\")) {
                i += 2;
                continue;
            }
            if (c.equals("\"")) {
                return i;
            }
            i++;
        }
        return -1;
    }

    // Unescapes the JSON string between text[start] (opening quote) and
    // text[end] (closing quote). Unknown escapes are dropped (tolerant).
    private function parseStringValue(text as String, start as Number, end as Number) as String {
        var raw = text.substring(start + 1, end);
        if (raw.find("\\") == null) {
            return raw;
        }
        var result = "";
        var i = 0;
        var n = raw.length();
        while (i < n) {
            var c = raw.substring(i, i + 1);
            if (c.equals("\\") && i + 1 < n) {
                var e = raw.substring(i + 1, i + 2);
                var skip = 2;
                if (e.equals("n")) {
                    result += "\n";
                } else if (e.equals("t")) {
                    result += "\t";
                } else if (e.equals("r")) {
                    result += "\r";
                } else if (e.equals("\"")) {
                    result += "\"";
                } else if (e.equals("\\")) {
                    result += "\\";
                } else if (e.equals("/")) {
                    result += "/";
                } else if (e.equals("u")) {
                    // \uXXXX: skip the whole escape (control characters only
                    // in practice — org.json emits raw UTF-8 for printable
                    // text, which passes through untouched above).
                    skip = 6;
                }
                // Any other escape: dropped (tolerant).
                i += skip;
            } else {
                result += c;
                i++;
            }
        }
        return result;
    }

    // Skips a nested {...} or [...] starting at text[start]; returns the index
    // just past the matching close, or -1 if unbalanced.
    private function skipNested(text as String, start as Number) as Number {
        var depth = 0;
        var inString = false;
        var i = start;
        var n = text.length();
        while (i < n) {
            var c = text.substring(i, i + 1);
            if (inString) {
                if (c.equals("\\")) {
                    i += 2;
                    continue;
                }
                if (c.equals("\"")) {
                    inString = false;
                }
            } else {
                if (c.equals("\"")) {
                    inString = true;
                } else if (c.equals("{") || c.equals("[")) {
                    depth++;
                } else if (c.equals("}") || c.equals("]")) {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
            }
            i++;
        }
        return -1;
    }
}

// Transmit result listener (reference: samples/Comm CommListener). A failed
// send means the phone is unreachable (no Garmin Connect Mobile link,
// companion not running, ...). Surfaced as "Phone not connected"; the next
// incoming message marks the phone reachable again.
class RunBeatConnectionListener extends Communications.ConnectionListener {

    private var _state;   // WatchState

    function initialize(state) {
        Communications.ConnectionListener.initialize();
        _state = state;
    }

    function onComplete() as Void {
    }

    function onError() as Void {
        _state.markPhoneUnreachable();
        _state.setAck(WatchUi.loadResource(Rez.Strings.PhoneNotConnected) as String, true);
        WatchUi.requestUpdate();
    }
}
