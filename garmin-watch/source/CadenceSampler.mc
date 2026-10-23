import Toybox.ActivityMonitor;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.Timer;

// Samples cadence once per second into a CadenceRingBuffer.
//
// Primary source: Toybox.Sensor (accelerometer-based cadence; the Sensor
// permission is declared in manifest.xml). The onboard accelerometer needs
// NO enable call: each 1 Hz tick just reads Sensor.getInfo().cadence (spm;
// Sensor.Info.cadence exists since API 1.0.0 — see the AccelMag sample,
// which reads Sensor.getInfo() on a timer with only the Sensor permission).
//
// Last-resort fallback: steps-per-minute from the step-counter delta
// between two ticks (ActivityMonitor.getInfo().steps). Coarse — with a
// 1 s tick it quantizes cadence to multiples of 60 spm (120/180/240 ...),
// so it is only used when Toybox.Sensor is unavailable or returns no
// cadence.
//
// Battery: 1 Hz sampling only; no phone polling (state is pushed
// phone->watch on change, never requested).
class CadenceSampler {

    private var _buffer;               // CadenceRingBuffer
    private var _onSample;             // Method? invoked after each tick
    private var _timer as Timer.Timer? = null;
    private var _lastSteps as Number = -1;
    private var _lastStepsAt as Number = 0;

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
        _lastStepsAt = 0;
        var timer = new Timer.Timer();
        timer.start(method(:onTick), 1000, true);
        _timer = timer;
    }

    function stop() as Void {
        var t = _timer;
        if (t != null) {
            (t as Timer.Timer).stop();
            _timer = null;
        }
    }

    function onTick() as Void {
        var now = System.getTimer();
        var sample = readCadence(now);
        if (sample != null) {
            _buffer.add(sample, now);
        }
        var cb = _onSample;
        if (cb != null) {
            (cb as Method).invoke();
        }
    }

    // Current cadence in spm from the best available source, or null when
    // no source produced a value this tick.
    private function readCadence(now as Number) {
        if (Toybox has :Sensor) {
            var cadence = sensorCadence();
            if (cadence != null) {
                return cadence;
            }
        }
        return stepDeltaCadence(now);
    }

    // Accelerometer-based cadence in spm (Toybox.Sensor), or null when
    // there is no cadence data (e.g. standing still).
    private function sensorCadence() {
        var info = Sensor.getInfo();
        if (info != null) {
            var i = info as Sensor.Info;
            return i.cadence;
        }
        return null;
    }

    // Last-resort fallback: steps-per-minute from the step-counter delta
    // between two ticks (ActivityMonitor.getInfo().steps). Coarse: with a
    // 1 s tick this quantizes cadence to multiples of 60 spm
    // (120/180/240 ...), so it is only used when Toybox.Sensor is
    // unavailable or returns no cadence. The delta is only computed while
    // the baseline is fresh (previous tick, at most 1.5 s old); otherwise
    // this tick just re-baselines and produces no sample, so a stale
    // baseline never yields a garbage value.
    private function stepDeltaCadence(now as Number) {
        if (!(Toybox has :ActivityMonitor)) {
            return null;
        }
        var info = ActivityMonitor.getInfo();
        if (info == null) {
            return null;
        }
        var i = info as ActivityMonitor.Info;
        var steps = i.steps;
        var sample = freshBaselineDelta(steps, now);
        _lastSteps = steps;
        _lastStepsAt = now;
        return sample;
    }

    // Step-counter delta over the last tick in steps/minute, or null when
    // the baseline is stale (this tick only re-baselines).
    private function freshBaselineDelta(steps as Number, now as Number) {
        if (_lastSteps < 0 || now - _lastStepsAt > 1500) {
            return null;
        }
        var delta = steps - _lastSteps;
        return delta > 0 ? delta * 60 : 0;
    }
}
