import Toybox.Application;
import Toybox.Communications;
import Toybox.FitContributor;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.WatchUi;

// RunBeat data field (Connect IQ / Monkey C).
//
// A data field runs ONLY during an activity (run, bike, ...) — the watch
// firmware owns its lifecycle, so it can never run in the background or
// keep the BLE radio awake outside a workout. This structurally fixes the
// battery drain of the old audio-content-provider companion app: when the
// activity stops, the data field stops, zero BLE cost.
//
// The field shows live cadence (big), the 60 s average, the target cadence,
// and the current RunBeat track. It talks to the RunBeat phone companion
// (GarminCompanionManager) via Connect IQ messages:
//   - on activity start it sends request_state (arms the phone's push window)
//   - SELECT/ENTER toggles play/pause, UP/DOWN adjust target cadence,
//     LAP skips to the next track
//   - the phone pushes state (~1/5 s while playing) which updates the display
//
// No own timers: the system calls onUpdate() at the data field's configured
// rate (typically 1 Hz). No idle-exit, no visibility tracking, no listener
// unregistration hacks — the system tears the field down when the activity
// ends, so a stopped field costs nothing.
//
// FIT contribution: live cadence and target cadence are written to the
// activity's FIT file so they appear in Garmin Connect after the run.

class RunBeatDataFieldApp extends Application.App {

    function initialize() {
        Application.App.initialize();
    }

    // Data fields: getInitialView returns [DataField, delegate].
    function getInitialView() {
        var field = new RunBeatDataField();
        return [field, new RunBeatDataFieldDelegate(field)];
    }
}

// The data field itself. onUpdate(dc) is called by the system at the
// configured rate (1 Hz) while the activity runs.
class RunBeatDataField extends WatchUi.DataField {

    private var _state;            // WatchState
    private var _messages;         // MessageHandler
    private var _cadenceBuffer;    // CadenceRingBuffer

    // FIT contributor fields (written each onUpdate, saved every 30 s).
    private var _fitCadence;       // FitContributor.Field?
    private var _fitTarget;        // FitContributor.Field?
    private var _fitSaveCounter as Number = 0;

    // Cached resources (loadResource hits the file system — cache once).
    private var _strAppName as String;
    private var _strNoPlayback as String;
    private var _strNoValue as String;
    private var _strAvgCaption as String;
    private var _strTargetPrefix as String;
    private var _strConnecting as String;
    private var _strPhoneNotConnected as String;

    function initialize() {
        WatchUi.DataField.initialize();
        _state = new WatchState();
        _cadenceBuffer = new CadenceRingBuffer();
        _messages = new MessageHandler(_state);

        _strAppName = WatchUi.loadResource(Rez.Strings.AppName) as String;
        _strNoPlayback = WatchUi.loadResource(Rez.Strings.NoPlayback) as String;
        _strNoValue = WatchUi.loadResource(Rez.Strings.NoValue) as String;
        _strAvgCaption = WatchUi.loadResource(Rez.Strings.AvgCaption) as String;
        _strTargetPrefix = WatchUi.loadResource(Rez.Strings.TargetPrefix) as String;
        _strConnecting = WatchUi.loadResource(Rez.Strings.Connecting) as String;
        _strPhoneNotConnected = WatchUi.loadResource(Rez.Strings.PhoneNotConnected) as String;
    }

    // Called when the data field is shown (activity started / screen added).
    function onShow() as Void {
        // Register for phone messages and handshake.
        Communications.registerForPhoneAppMessages(_messages.method(:onPhoneMessage));
        _messages.sendRequestState();

        // Set up FIT contributor fields.
        try {
            _fitCadence = FitContributor.createField("cadence", 0, FitContributor.DATA_TYPE_NUMBER, {:mesgType => FitContributor.MESG_TYPE_RECORD, :units => "spm"});
            _fitTarget = FitContributor.createField("target_cadence", 1, FitContributor.DATA_TYPE_NUMBER, {:mesgType => FitContributor.MESG_TYPE_RECORD, :units => "spm"});
        } catch (e) {
            _fitCadence = null;
            _fitTarget = null;
        }
    }

    // Called when the data field is hidden (activity stopped / screen removed).
    function onHide() as Void {
        // Best-effort farewell + unregister so no BLE traffic lingers.
        _messages.sendBye();
        Communications.registerForPhoneAppMessages(null);

        // Save any pending FIT data.
        saveFit();
    }

    // Called by the system at the configured rate (1 Hz) while the activity runs.
    function onUpdate(dc as Graphics.Dc) as Void {
        var now = System.getTimer();

        // Sample cadence from the sensor (the system drives this callback,
        // so no own timer is needed).
        sampleCadence(now);

        // Write FIT fields.
        writeFit(now);

        // Draw.
        draw(dc, now);
    }

    // Activity lifecycle callbacks.
    function onTimerStart() as Void {
        _messages.sendRequestState();
    }

    function onTimerResume() as Void {
        _messages.sendRequestState();
    }

    function onTimerLap() as Void {
        // LAP button = next track.
        _messages.sendNext();
    }

    function onTimerStop() as Void {
        _messages.sendBye();
        Communications.registerForPhoneAppMessages(null);
        saveFit();
    }

    function onTimerPause() as Void {
        // Paused: send bye so the phone drops the push window.
        _messages.sendBye();
    }

    // --- Input handlers (called by the delegate) ---

    function onSelectPressed() as Void {
        _messages.sendToggle();
    }

    function onUpPressed() as Void {
        _messages.sendCadenceDelta(1);
    }

    function onDownPressed() as Void {
        _messages.sendCadenceDelta(-1);
    }

    // --- Cadence sampling ---

    private function sampleCadence(now as Number) as Void {
        var cadence = readSensorCadence();
        if (cadence != null) {
            _cadenceBuffer.add(cadence, now);
        }
    }

    private function readSensorCadence() as Float? {
        if (Toybox has :Sensor) {
            try {
                var info = Sensor.getInfo();
                if (info != null) {
                    var i = info as Sensor.Info;
                    if (i.cadence != null) {
                        return i.cadence;
                    }
                }
            } catch (ex) {
                // fall through
            }
        }
        return null;
    }

    // --- FIT contribution ---

    private function writeFit(now as Number) as Void {
        var live = _cadenceBuffer.last(now);
        if (live != null && _fitCadence != null) {
            (_fitCadence as FitContributor.Field).setData(live);
        }
        var target = _state.targetCadence;
        if (target != null && _fitTarget != null) {
            (_fitTarget as FitContributor.Field).setData(target);
        }
        // Save every 30 updates (~30 s at 1 Hz) to balance FIT write cost.
        _fitSaveCounter++;
        if (_fitSaveCounter >= 30) {
            _fitSaveCounter = 0;
            saveFit();
        }
    }

    private function saveFit() as Void {
        if (_fitCadence != null || _fitTarget != null) {
            try {
                FitContributor.save();
            } catch (e) {
                // FIT save failure is non-fatal.
            }
        }
    }

    // --- Drawing ---

    private function draw(dc as Graphics.Dc, now as Number) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);
        var fhSmall = dc.getFontHeight(Graphics.FONT_SMALL);
        var fhLarge = dc.getFontHeight(Graphics.FONT_LARGE);
        var fhNumber = dc.getFontHeight(Graphics.FONT_NUMBER_MEDIUM);

        var runBeat = _state.isRunBeatActive();

        // --- Header line (connection status / source) ---
        dc.setColor(headerColor(runBeat), Graphics.COLOR_BLACK);
        dc.drawText(w / 2, fhTiny / 2 + 2, Graphics.FONT_TINY, headerText(),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Wide screens (>= 240 px): two columns. Narrow: stacked.
        if (w < 240) {
            drawNarrowLayout(dc, w, h, now, fhTiny, fhLarge, runBeat);
        } else {
            drawWideLayout(dc, w, h, now, fhTiny, fhNumber, fhSmall, runBeat);
        }
    }

    // Wide layout (>= 240 px): two columns.
    private function drawWideLayout(dc as Graphics.Dc, w as Number, h as Number, now as Number,
                                    fhTiny as Number, fhNumber as Number, fhSmall as Number,
                                    runBeat as Boolean) as Void {
        var colW = w / 2;
        var y = fhTiny + 4;

        // --- Left column: cadence ---
        var live = _cadenceBuffer.last(now);
        var liveText = live == null ? _strNoValue : (live as Float).format("%.0f");
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.drawText(colW / 2, y + fhNumber / 2, Graphics.FONT_NUMBER_MEDIUM, liveText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        y += fhNumber + 2;

        var mean = _cadenceBuffer.mean(now);
        var meanText = mean == null ? _strNoValue : (mean as Float).format("%.0f");
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(colW / 2, y + fhTiny / 2, Graphics.FONT_TINY,
            _strAvgCaption + " " + meanText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // --- Right column: target cadence + track ---
        y = fhTiny + 4;
        var target = _state.targetCadenceText();
        if (target.length() == 0) { target = _strNoValue; }
        dc.setColor(runBeat ? Graphics.COLOR_GREEN : Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(colW + colW / 2, y + fhSmall / 2, Graphics.FONT_SMALL,
            _strTargetPrefix + " " + target,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        y += fhSmall + 2;

        if (runBeat) {
            var title = _state.title.length() > 0 ? _state.title : _strNoValue;
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            var maxW = (colW * 0.92).toNumber();
            dc.drawText(colW + colW / 2, y + fhTiny / 2, Graphics.FONT_TINY,
                truncateToWidth(dc, title, Graphics.FONT_TINY, maxW),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            y += fhTiny + 2;
            var artist = _state.artist.length() > 0 ? _state.artist : "";
            if (artist.length() > 0) {
                dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
                dc.drawText(colW + colW / 2, y + fhTiny / 2, Graphics.FONT_TINY,
                    truncateToWidth(dc, artist, Graphics.FONT_TINY, maxW),
                    Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            }
        } else {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(colW + colW / 2, y + fhTiny / 2, Graphics.FONT_TINY,
                _strNoPlayback,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    // Narrow layout (< 240 px): stacked vertically.
    private function drawNarrowLayout(dc as Graphics.Dc, w as Number, h as Number, now as Number,
                                      fhTiny as Number, fhLarge as Number,
                                      runBeat as Boolean) as Void {
        var y = fhTiny + 4;

        // Live cadence (big, centered).
        var live = _cadenceBuffer.last(now);
        var liveText = live == null ? _strNoValue : (live as Float).format("%.0f");
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, y + fhLarge / 2, Graphics.FONT_LARGE, liveText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        y += fhLarge + 2;

        // Avg + target on one line.
        var mean = _cadenceBuffer.mean(now);
        var meanText = mean == null ? _strNoValue : (mean as Float).format("%.0f");
        var target = _state.targetCadenceText();
        if (target.length() == 0) { target = _strNoValue; }
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, y + fhTiny / 2, Graphics.FONT_TINY,
            _strAvgCaption + " " + meanText + "  " + _strTargetPrefix + " " + target,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        y += fhTiny + 2;

        // Track title.
        if (runBeat) {
            var title = _state.title.length() > 0 ? _state.title : _strNoValue;
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, y + fhTiny / 2, Graphics.FONT_TINY,
                truncateToWidth(dc, title, Graphics.FONT_TINY, (w * 0.92).toNumber()),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, y + fhTiny / 2, Graphics.FONT_TINY, _strNoPlayback,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    // Header: three connection states.
    private function headerText() as String {
        if (!_state.hasConnected()) {
            return _strConnecting;
        }
        if (_state.isStale(System.getTimer())) {
            return _strPhoneNotConnected;
        }
        return _state.isRunBeatActive() ? _strAppName : _strNoPlayback;
    }

    private function headerColor(runBeat as Boolean) as Number {
        if (!_state.hasConnected() || _state.isStale(System.getTimer())) {
            return Graphics.COLOR_YELLOW;
        }
        return runBeat ? Graphics.COLOR_GREEN : Graphics.COLOR_LT_GRAY;
    }

    private function truncateToWidth(dc as Graphics.Dc, text as String, font, maxWidth as Number) as String {
        if (dc.getTextWidthInPixels(text, font) <= maxWidth) {
            return text;
        }
        var ellipsis = "...";
        var trimmed = text;
        while (trimmed.length() > 0 && dc.getTextWidthInPixels(trimmed + ellipsis, font) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + ellipsis;
    }
}

// Input delegate for the data field. Maps hardware keys to field actions:
//   SELECT/ENTER = play/pause toggle
//   UP           = target cadence +1
//   DOWN         = target cadence -1
//   LAP          = next track (handled in onTimerLap)
class RunBeatDataFieldDelegate extends WatchUi.BehaviorDelegate {

    private var _field;   // RunBeatDataField

    function initialize(field) {
        BehaviorDelegate.initialize();
        _field = field;
    }

    function onSelect() as Boolean {
        _field.onSelectPressed();
        return true;
    }

    function onKey(keyEvent as WatchUi.KeyEvent) as Boolean {
        var key = keyEvent.getKey();
        if (key == WatchUi.KEY_UP) {
            _field.onUpPressed();
            return true;
        }
        if (key == WatchUi.KEY_DOWN) {
            _field.onDownPressed();
            return true;
        }
        if (key == WatchUi.KEY_ENTER) {
            _field.onSelectPressed();
            return true;
        }
        return false;
    }

    // Back exits the data field configuration (system handles the pop).
    function onBack() as Boolean {
        return false;
    }
}
