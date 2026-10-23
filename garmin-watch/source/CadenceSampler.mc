using Toybox.ActivityMonitor;
using Toybox.System;
using Toybox.Timer;

// Samples cadence once per second into a CadenceRingBuffer.
//
// Preferred source: ActivityMonitor.getLiveCadence() (live spm), guarded by
// a method-existence check. Fallback: delta of ActivityMonitor.getInfo().steps
// between two ticks, converted to steps/minute.
//
// Battery: 1 Hz sampling only; no phone polling (state is pushed phone->watch
// on change, never requested). Starts/stops with the app lifecycle.
class CadenceSampler {

    private var _buffer;              // CadenceRingBuffer
    private var _onSample;            // Method? invoked after each tick
    private var _timer as Timer.Timer? = null;
    private var _lastSteps as Number = -1;
    private var _liveCadenceAvailable as Boolean = false;

    function initialize(buffer, onSample) {
        _buffer = buffer;
        _onSample = onSample;
        _timer = null;
    }

    function start() as Void {
        if (_timer != null) {
            return;
        }
        _lastSteps = -1;
        _liveCadenceAvailable = liveCadenceAvailable();
        _timer = new Timer.Timer();
        _timer.start(method(:onTick), 1000, true);
    }

    function stop() as Void {
        var t = _timer;
        if (t != null) {
            (t as Timer.Timer).stop();
            _timer = null;
        }
    }

    function onTick() as Void {
        if (_liveCadenceAvailable) {
            var live = safeLiveCadence();
            if (live != null) {
                _buffer.add(live, System.getTimer());
            } else {
                storeStepDeltaSample();
            }
        } else {
            storeStepDeltaSample();
        }
        var cb = _onSample;
        if (cb != null) {
            (cb as Method).invoke();
        }
    }

    private function liveCadenceAvailable() as Boolean {
        return (Toybox has :ActivityMonitor) && (Toybox.ActivityMonitor has :getLiveCadence);
    }

    private function safeLiveCadence() {
        try {
            return ActivityMonitor.getLiveCadence();
        } catch (ex as Lang.Exception) {
            return null;
        }
    }

    private function storeStepDeltaSample() as Void {
        if (!(Toybox has :ActivityMonitor)) {
            return;
        }
        var info = ActivityMonitor.getInfo();
        if (info == null) {
            return;
        }
        var steps = info.steps;
        if (_lastSteps >= 0) {
            var delta = steps - _lastSteps;
            // One tick = 1 s, so steps/min = delta * 60.
            _buffer.add(delta > 0 ? delta * 60 : 0, System.getTimer());
        }
        _lastSteps = steps;
    }
}
