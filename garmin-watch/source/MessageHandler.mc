using Toybox.Communications;
using Toybox.WatchUi;

// Message protocol (normative): docs/companion_garmin_app/architecture.md.
//
// Watch -> phone commands are small JSON strings:
//   {"type":"play"} {"type":"pause"} {"type":"toggle"} {"type":"next"} {"type":"prev"}
//   {"type":"cadence_set","cadence":172}
//   {"type":"cadence_delta","delta":1} or {"delta":-1}
//   {"type":"cadence_use_current","cadence":172}
//   {"type":"speed_correction_toggle"}
//   {"type":"volume_delta","delta":1} or {"delta":-1}
//   {"type":"mark_not_for_running"}
//
// Phone -> watch:
//   {"type":"state","source":"RunBeat","isPlaying":true,"trackId":"...","title":"...",
//    "bpm":140.0,"targetCadence":172,"speedCorrection":true,"playbackSpeed":1.03,"volume":12}
//   {"type":"ack","command":"mark_not_for_running","ok":true}
//
// Monkey C has no org.json, so the flat JSON objects are built/parsed by hand.
// The parser is deliberately tolerant: unknown fields, nested structures and
// malformed input are ignored instead of failing.
class MessageHandler {

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

    // ---------------------------------------------------------------
    // Incoming messages (phone -> watch)
    // ---------------------------------------------------------------

    // Callback registered with Communications.registerForPhoneAppMessages().
    function onPhoneMessage(msg as Communications.Message) as Void {
        var payload = msg.payload;
        if (!(payload instanceof String)) {
            return;
        }
        var dict = parseFlatObject(payload as String);
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
        if (t == "state") {
            applyState(dict);
        } else if (t == "ack") {
            applyAck(dict);
        }
        // Unknown message types are ignored (forward compatibility).
    }

    private function applyState(dict as Dictionary) as Void {
        var s = _state;
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
        s.bpm = optionalFloat(dict, "bpm");
        s.targetCadence = optionalFloat(dict, "targetCadence");
        var speedCorrection = dict["speedCorrection"];
        if (speedCorrection instanceof Boolean) {
            s.speedCorrection = speedCorrection as Boolean;
        }
        s.playbackSpeed = optionalFloat(dict, "playbackSpeed");
        s.volume = optionalFloat(dict, "volume");

        if (s.source != "RunBeat") {
            // RunBeat-only fields are omitted or null for other sources; drop
            // stale values so nothing RunBeat-specific lingers on screen.
            s.trackId = "";
            s.bpm = null;
            s.targetCadence = null;
            s.speedCorrection = false;
            s.playbackSpeed = null;
        }

        s.markMessageReceived();
        WatchUi.requestUpdate();
    }

    private function applyAck(dict as Dictionary) as Void {
        var ok = false;
        var okValue = dict["ok"];
        if (okValue instanceof Boolean) {
            ok = okValue as Boolean;
        }

        var text as String = "";
        var command = dict["command"];
        if (command instanceof String) {
            var cmd = command as String;
            if (cmd == "mark_not_for_running") {
                text = ok ? Rez.Strings.AckMarked : Rez.Strings.AckMarkFailed;
            } else if (cmd == "cadence_use_current" || cmd == "cadence_set" || cmd == "cadence_delta") {
                text = ok ? Rez.Strings.AckCadenceApplied : Rez.Strings.AckCadenceFailed;
            } else if (cmd == "speed_correction_toggle") {
                text = ok ? Rez.Strings.AckSpeedCorrection : Rez.Strings.AckFailed;
            } else {
                text = ok ? Rez.Strings.AckOk : Rez.Strings.AckFailed;
            }
        } else {
            text = ok ? Rez.Strings.AckOk : Rez.Strings.AckFailed;
        }

        _state.setAck(text, !ok);
        _state.markMessageReceived();
        WatchUi.requestUpdate();
    }

    private function sendJson(json as String) as Void {
        try {
            Communications.transmit(json);
        } catch (ex as Lang.Exception) {
            // Phone unreachable (no Garmin Connect Mobile link, companion not
            // running, ...). Surfaced as "Phone not connected"; the next
            // incoming message marks the phone reachable again.
            _state.markPhoneUnreachable();
            _state.setAck(Rez.Strings.PhoneNotConnected, true);
            WatchUi.requestUpdate();
        }
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
        if (i >= n || text.substring(i, i + 1) != "{") {
            return null;
        }
        i = skipWs(text, i + 1);
        if (i < n && text.substring(i, i + 1) == "}") {
            return result;
        }
        while (i < n) {
            i = skipWs(text, i);
            if (i >= n) {
                return null;
            }
            var c = text.substring(i, i + 1);
            if (c == "}") {
                return result;
            }
            if (c == ",") {
                i++;
                continue;
            }
            if (c != "\"") {
                return null;
            }
            var keyEnd = findStringEnd(text, i);
            if (keyEnd < 0) {
                return null;
            }
            var key = parseStringValue(text, i, keyEnd);
            i = skipWs(text, keyEnd + 1);
            if (i >= n || text.substring(i, i + 1) != ":") {
                return null;
            }
            i = skipWs(text, i + 1);
            if (i >= n) {
                return null;
            }
            c = text.substring(i, i + 1);
            if (c == "\"") {
                var valEnd = findStringEnd(text, i);
                if (valEnd < 0) {
                    return null;
                }
                result[key] = parseStringValue(text, i, valEnd);
                i = valEnd + 1;
            } else if (c == "{" || c == "[") {
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
                    if (ch == "," || ch == "}" || ch == " " || ch == "\t" || ch == "\n" || ch == "\r") {
                        break;
                    }
                    j++;
                }
                var raw = text.substring(i, j);
                i = j;
                if (raw == "true") {
                    result[key] = true;
                } else if (raw == "false") {
                    result[key] = false;
                } else if (raw == "null" || raw.length() == 0) {
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
            if (c == " " || c == "\t" || c == "\n" || c == "\r") {
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
            if (c == "\\") {
                i += 2;
                continue;
            }
            if (c == "\"") {
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
            if (c == "\\" && i + 1 < n) {
                var e = raw.substring(i + 1, i + 2);
                var skip = 2;
                if (e == "n") {
                    result += "\n";
                } else if (e == "t") {
                    result += "\t";
                } else if (e == "r") {
                    result += "\r";
                } else if (e == "\"") {
                    result += "\"";
                } else if (e == "\\") {
                    result += "\\";
                } else if (e == "/") {
                    result += "/";
                } else if (e == "u") {
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
                if (c == "\\") {
                    i += 2;
                    continue;
                }
                if (c == "\"") {
                    inString = false;
                }
            } else {
                if (c == "\"") {
                    inString = true;
                } else if (c == "{" || c == "[") {
                    depth++;
                } else if (c == "}" || c == "]") {
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
