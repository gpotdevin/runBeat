// Fixed-size (60-slot) circular buffer of cadence samples.
// Each sample is { "value" => spm, "timestamp" => System.getTimer() ms }.
// One sample per second, so 60 slots cover a rolling 60-second window.

class CadenceRingBuffer {

    private var _slots as Array = new [60];
    private var _index as Number = 0;   // next write position
    private var _count as Number = 0;   // number of valid slots (<= 60)

    // Adds one sample. `value` is steps-per-minute; 0 means "not running".
    // (Untyped on purpose: Monkey C Number and Float are distinct types and
    // both live-cadence sources and the step-delta fallback produce either.)
    function add(value, timestamp as Number) as Void {
        _slots[_index] = { "value" => value, "timestamp" => timestamp };
        _index = (_index + 1) % 60;
        if (_count < 60) {
            _count++;
        }
    }

    // Mean of the samples not older than 60 s, ignoring 0 ("not running")
    // samples. Returns null when there is no usable sample (buffer empty, or
    // every recent sample is 0) — callers must not offer "use current" then.
    function mean(now as Number) as Float? {
        var sum = 0.0;
        var n = 0;
        for (var i = 0; i < _count; i++) {
            var s = _slots[i];
            if (s == null) {
                continue;
            }
            var d = s as Dictionary;
            var ts = d["timestamp"];
            if (!(ts instanceof Number)) {
                continue;
            }
            if (now - (ts as Number) > 60000) {
                continue; // older than 60 s
            }
            var v = d["value"];
            if (v instanceof Float) {
                var f = v as Float;
                if (f > 0) {
                    sum += f;
                    n++;
                }
            } else if (v instanceof Number) {
                var nv = v as Number;
                if (nv > 0) {
                    sum += nv;
                    n++;
                }
            }
        }
        if (n == 0) {
            return null;
        }
        return sum / n;
    }

    function size() as Number {
        return _count;
    }

    function reset() as Void {
        _index = 0;
        _count = 0;
    }
}
