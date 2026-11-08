import Toybox.Application;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Activity;
import Toybox.System;
import Toybox.WatchUi;

// RunBeat data field (Connect IQ / Monkey C) — single view.
//
// A data field occupies ONE rectangle on ONE activity screen. The
// activity owns screen swiping and most buttons; the field reliably
// gets onTimerLap (LAP button) and possibly tap (full-screen field).
//
// Display: play state, track title, artist, target cadence, 60 s avg.
// Input: LAP = next track. Tap zones on the field for prev / play-pause /
// next / speed-corr toggle / set-avg-as-target / mark-not-for-running.
//
// No FitContributor (was crashing on load). No screen switching.

class RunBeatDataFieldApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function getInitialView() {
        var field = new RunBeatDataField();
        return [field, new RunBeatDataFieldDelegate(field)];
    }
}

class RunBeatDataField extends WatchUi.DataField {

    private var _state;            // WatchState
    private var _messages;         // MessageHandler
    private var _cadenceBuffer;    // CadenceRingBuffer

    // Cached resources.
    private var _strAppName as String;
    private var _strNoPlayback as String;
    private var _strNoValue as String;
    private var _strAvgCaption as String;
    private var _strTargetPrefix as String;
    private var _strMarkLabel as String;
    private var _strPlaying as String;
    private var _strPaused as String;

    // Tap zones: [prev, play, next, speedCorr, avgTarget, mark]
    private var _tapZones as Array = [];

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
        _strMarkLabel = WatchUi.loadResource(Rez.Strings.MarkNotForRunning) as String;
        _strPlaying = WatchUi.loadResource(Rez.Strings.Playing) as String;
        _strPaused = WatchUi.loadResource(Rez.Strings.Paused) as String;
    }

    function onShow() as Void {
        try {
            Communications.registerForPhoneAppMessages(_messages.method(:onPhoneMessage));
            _messages.sendRequestState();
        } catch (e) {}
    }

    function onHide() as Void {
        try {
            _messages.sendBye();
            Communications.registerForPhoneAppMessages(null);
        } catch (e) {}
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var now = System.getTimer();
        sampleCadence(now);
        draw(dc, now);
    }

    function onTimerStart() as Void { safeRequestState(); }
    function onTimerResume() as Void { safeRequestState(); }
    function onTimerLap() as Void { _messages.sendNext(); }
    function onTimerStop() as Void {
        try { _messages.sendBye(); Communications.registerForPhoneAppMessages(null); } catch (e) {}
    }
    function onTimerPause() as Void { try { _messages.sendBye(); } catch (e) {} }

    private function safeRequestState() as Void {
        try { _messages.sendRequestState(); } catch (e) {}
    }

    // --- Input ---

    function onSelectPressed() as Void { _messages.sendToggle(); }

    function onTap(x as Number, y as Number) as Boolean {
        var z = _tapZones;
        if (z.size() >= 6) {
            if (hitTest(z[0], x, y)) { _messages.sendPrev(); return true; }
            if (hitTest(z[1], x, y)) { _messages.sendToggle(); return true; }
            if (hitTest(z[2], x, y)) { _messages.sendNext(); return true; }
            if (hitTest(z[3], x, y)) { _messages.sendSpeedCorrectionToggle(); return true; }
            if (hitTest(z[4], x, y)) { useAvgAsTarget(); return true; }
            if (hitTest(z[5], x, y)) { _messages.sendMarkNotForRunning(); return true; }
        }
        return false;
    }

    private function useAvgAsTarget() as Void {
        if (!_state.isRunBeatActive()) { return; }
        var mean = _cadenceBuffer.mean(System.getTimer());
        if (mean != null) { _messages.sendCadenceUseCurrent(mean as Float); }
    }

    // --- Cadence sampling (feeds ring buffer for the 60 s avg) ---

    private function sampleCadence(now as Number) as Void {
        // Data fields get cadence from Activity.getActivityInfo(), not Sensor.
        try {
            var info = Activity.getActivityInfo();
            if (info != null) {
                var i = info as Activity.Info;
                if (i.currentCadence != null) { _cadenceBuffer.add(i.currentCadence, now); }
            }
        } catch (ex) {}
    }

    // --- Drawing: single view, all controls visible ---

    private function draw(dc as Graphics.Dc, now as Number) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);
        var fhSmall = dc.getFontHeight(Graphics.FONT_SMALL);
        var fhLarge = dc.getFontHeight(Graphics.FONT_LARGE);

        var runBeat = _state.isRunBeatActive();

        // Play state (top).
        var stateText = runBeat ? (_state.isPlaying ? _strPlaying : _strPaused) : _strNoPlayback;
        dc.setColor(runBeat ? Graphics.COLOR_WHITE : Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, 2 + fhSmall / 2, Graphics.FONT_SMALL, stateText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Track title (big, centered).
        var yTitle = fhSmall + 4 + (h - fhSmall - fhTiny * 3 - 8) / 2;
        if (runBeat) {
            var title = _state.title.length() > 0 ? _state.title : _strNoValue;
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yTitle, Graphics.FONT_LARGE,
                truncateToWidth(dc, title, Graphics.FONT_LARGE, (w * 0.92).toNumber()),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            var artist = _state.artist.length() > 0 ? _state.artist : "";
            if (artist.length() > 0) {
                dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
                dc.drawText(w / 2, yTitle + fhLarge / 2 + 2 + fhTiny / 2, Graphics.FONT_TINY,
                    truncateToWidth(dc, artist, Graphics.FONT_TINY, (w * 0.84).toNumber()),
                    Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            }
        } else {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yTitle, Graphics.FONT_LARGE, _strNoPlayback,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        // Bottom row: TARGET (green/gray) | AVG (blue) — tappable.
        var rowY = h - fhTiny * 2 - 6;
        var halfW = (w / 2).toNumber();
        var target = _state.targetCadenceText();
        if (target.length() == 0) { target = _strNoValue; }
        var tgtColor = !runBeat ? Graphics.COLOR_DK_GRAY
            : (_state.speedCorrection ? Graphics.COLOR_GREEN : Graphics.COLOR_LT_GRAY);
        dc.setColor(tgtColor, Graphics.COLOR_BLACK);
        dc.drawText((halfW / 2).toNumber(), rowY + fhTiny / 2, Graphics.FONT_TINY,
            _strTargetPrefix + " " + target,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        var mean = _cadenceBuffer.mean(now);
        var meanText = mean == null ? _strNoValue : (mean as Float).format("%.0f");
        dc.setColor(Graphics.COLOR_BLUE, Graphics.COLOR_BLACK);
        dc.drawText(halfW + (halfW / 2).toNumber(), rowY + fhTiny / 2, Graphics.FONT_TINY,
            _strAvgCaption + " " + meanText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Bottom row: transport hint.
        var hintY = h - fhTiny - 2;
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, hintY, Graphics.FONT_TINY, "|<  >/||  >|  " + _strMarkLabel,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Tap zones (6 zones across the lower half of the screen):
        // Row 1 (y ~ rowY): [target=speedCorr, avg=setTarget]
        // Row 2 (y ~ hintY area): [prev, play, next, mark]
        var zoneTop = (h / 2).toNumber();
        var zoneH = h - zoneTop;
        var qW = (w / 4).toNumber();
        _tapZones = [
            [0, zoneTop, qW, zoneH / 2],                    // prev (bottom-left)
            [qW, zoneTop, qW, zoneH / 2],                   // play
            [qW * 2, zoneTop, qW, zoneH / 2],               // next
            [qW * 3, zoneTop, w - qW * 3, zoneH / 2],       // mark (bottom-right)
            [0, zoneTop + zoneH / 2, halfW, zoneH / 2],     // speedCorr (top-left)
            [halfW, zoneTop + zoneH / 2, halfW, zoneH / 2]  // avgTarget (top-right)
        ];
    }

    // --- Helpers ---

    private function hitTest(rect, x as Number, y as Number) as Boolean {
        if (rect == null) { return false; }
        var r = rect as Array;
        return x >= (r[0] as Number) && x < (r[0] as Number) + (r[2] as Number) &&
               y >= (r[1] as Number) && y < (r[1] as Number) + (r[3] as Number);
    }

    private function truncateToWidth(dc as Graphics.Dc, text as String, font, maxWidth as Number) as String {
        if (dc.getTextWidthInPixels(text, font) <= maxWidth) { return text; }
        var ellipsis = "...";
        var trimmed = text;
        while (trimmed.length() > 0 && dc.getTextWidthInPixels(trimmed + ellipsis, font) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + ellipsis;
    }
}

// Input delegate.
class RunBeatDataFieldDelegate extends WatchUi.BehaviorDelegate {

    private var _field;

    function initialize(field) {
        BehaviorDelegate.initialize();
        _field = field;
    }

    function onSelect() as Boolean {
        _field.onSelectPressed();
        return true;
    }

    function onKey(keyEvent as WatchUi.KeyEvent) as Boolean {
        if (keyEvent.getKey() == WatchUi.KEY_ENTER) {
            _field.onSelectPressed();
            return true;
        }
        return false;
    }

    function onTap(clickEvent as WatchUi.ClickEvent) as Boolean {
        var coords = clickEvent.getCoordinates();
        return _field.onTap(coords[0] as Number, coords[1] as Number);
    }

    function onBack() as Boolean { return false; }
}
