import Toybox.ActivityMonitor;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.Timer;

// Samples cadence into a CadenceRingBuffer on an ADAPTIVE tick — 1 Hz
// while music is playing, 5 s while paused (battery: a repeating 1 s
// Timer wakes the CPU 60x/min even when the tick has nothing to do; while
// paused nothing time-critical happens at 1 Hz, so the app lowers the
// period via setPeriodMs — see RunBeatCompanionApp.reconcileTickPeriod).
// The timer runs ONLY while the app is visible (the app stops this
// sampler's timer on hide; see RunBeatCompanionApp.onAppVisible, which
// routes through stop() / start() below).
//
// Primary source: Toybox.Sensor (accelerometer-based cadence; the Sensor
// permission is declared in manifest.xml); each 1 Hz tick reads
// Sensor.getInfo().cadence (spm).
//
// Note on Sensor.setEnabledSensors: an out-of-repo older revision called
// Sensor.setEnabledSensors([Sensor.SENSOR_ACCELEROMETER]) on show and
// setEnabledSensors([]) on hide. That constant does NOT exist in the
// Connect IQ API (verified against SDK 9.3.0 and 8.1.0 api.debug.xml —
// the accelerometer is not a toggleable SensorType), so it never
// compiled. The battery win is instead achieved by stopping the 1 Hz
// timer entirely while the app is hidden: zero Sensor.getInfo() /
// ActivityMonitor.getInfo() calls and zero CPU wakeups at 1 Hz.
//
// Last-resort fallback: steps-per-minute from the step-counter delta
// between two ticks (ActivityMonitor.getInfo().steps). Coarse — with a
// 1 s tick it quantizes cadence to multiples of 60 spm (120/180/240 ...),
// so it is only used when Toybox.Sensor is unavailable or returns no
// cadence.
//
// Battery: the tick runs only while visible, the SENSOR READ itself only
// while the gate method returns true (the app gates on "music playing" —
// the cadence average is only meaningful during a run), and the tick
// PERIOD is adaptive (1 Hz playing / 5 s paused, setPeriodMs). While
// paused the tick still fires — it drives the app's idle-exit and repaint
// checks — but at 5 s instead of 1 s, performs zero
// Sensor/ActivityMonitor calls, so a paused app costs almost nothing.
// No phone polling (state is pushed phone->watch on change, never
// requested).
class CadenceSampler {

    private var _buffer;               // CadenceRingBuffer
    private var _onSample;             // Method? invoked after each tick
    private var _gate;                 // Method? -> Boolean, sensor read gate
    private var _timer as Timer.Timer? = null;
    private var _periodMs as Number = 1000;  // current tick period (adaptive)
    private var _lastSteps as Number = -1;
    private var _lastStepsAt as Number = 0;
    private var _sampling as Boolean = false;  // false while hidden

    function initialize(buffer, onSample, gate) {
        _buffer = buffer;
        _onSample = onSample;
        _gate = gate;
        _timer = null;
        _periodMs = 1000;
    }

    function start() as Void {
        if (_timer != null) {
            return;
        }
        _lastSteps = -1;
        _lastStepsAt = 0;
        onShow();
        var timer = new Timer.Timer();
        timer.start(method(:onTick), _periodMs, true);
        _timer = timer;
    }

    // Changes the tick period of the RUNNING timer (Monkey C Timers have
    // no setPeriod — stop + restart). No-op while stopped (hidden) or when
    // the period is unchanged; the app reconciles playing(1 s)/paused(5 s).
    function setPeriodMs(periodMs as Number) as Void {
        if (_timer == null || periodMs == _periodMs) {
            return;
        }
        var t = _timer;
        (t as Timer.Timer).stop();
        _timer = null;
        _periodMs = periodMs;
        var timer = new Timer.Timer();
        timer.start(method(:onTick), _periodMs, true);
        _timer = timer;
    }

    function stop() as Void {
        onHide();
        var t = _timer;
        if (t != null) {
            (t as Timer.Timer).stop();
            _timer = null;
        }
    }

    // Visibility hooks: the app's pause/resume path (RunBeatCompanionApp
    // onHide/onShow) routes through stop()/start(), which route through
    // these. While hidden the timer is stopped AND the tick gate below
    // stays closed, so no sensor reads happen at all.
    function onShow() as Void {
        _sampling = true;
    }

    function onHide() as Void {
        _sampling = false;
    }

    function onTick() as Void {
        // Hard visibility gate: never touch the sensor hub while the app
        // is not visible (the timer is also stopped in that state; this
        // guards against a missed onHide).
        if (!_sampling) {
            return;
        }
        var now = System.getTimer();
        // Battery gate: the sensor hub is read only while the app's gate
        // says the cadence is meaningful (music playing). Paused = no
        // Sensor.getInfo()/ActivityMonitor.getInfo() call at all.
        var gate = _gate;
        if (gate == null || (gate as Method).invoke()) {
            var sample = readCadence(now);
            if (sample != null) {
                _buffer.add(sample, now);
            }
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
        try {
            var info = Sensor.getInfo();
            if (info != null) {
                var i = info as Sensor.Info;
                return i.cadence;
            }
        } catch (ex) {
            // Fall through — the step-delta fallback runs instead.
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
