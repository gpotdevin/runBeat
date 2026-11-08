import Toybox.Activity;
import Toybox.Application;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// RunBeat data fields — 5 separate data field apps in one .prg.
// Each app = one rectangle on one activity screen.
//
// Shared base handles Communications + WatchState + cadence sampling.
// Each subclass overrides draw() and onTap() for its specific function.

class RunBeatFieldBase extends WatchUi.DataField {

    protected var _state;            // WatchState
    protected var _messages;         // MessageHandler
    protected var _cadenceBuffer;    // CadenceRingBuffer

    // Cached resources.
    protected var _strAppName as String;
    protected var _strNoPlayback as String;
    protected var _strNoValue as String;
    protected var _strConnecting as String;
    protected var _strPhoneNotConnected as String;

    function initialize() {
        WatchUi.DataField.initialize();
        _state = new WatchState();
        _cadenceBuffer = new CadenceRingBuffer();
        _messages = new MessageHandler(_state);
        _strAppName = WatchUi.loadResource(Rez.Strings.AppName) as String;
        _strNoPlayback = WatchUi.loadResource(Rez.Strings.NoPlayback) as String;
        _strNoValue = WatchUi.loadResource(Rez.Strings.NoValue) as String;
        _strConnecting = WatchUi.loadResource(Rez.Strings.Connecting) as String;
        _strPhoneNotConnected = WatchUi.loadResource(Rez.Strings.PhoneNotConnected) as String;
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

    function onTimerStart() as Void { safeReq(); }
    function onTimerResume() as Void { safeReq(); }
    function onTimerLap() as Void { _messages.sendNext(); }
    function onTimerStop() as Void {
        try { _messages.sendBye(); Communications.registerForPhoneAppMessages(null); } catch (e) {}
    }
    function onTimerPause() as Void { try { _messages.sendBye(); } catch (e) {} }

    protected function safeReq() as Void {
        try { _messages.sendRequestState(); } catch (e) {}
    }

    protected function sampleCadence(now as Number) as Void {
        try {
            var info = Activity.getActivityInfo();
            if (info != null) {
                var i = info as Activity.Info;
                if (i.currentCadence != null) { _cadenceBuffer.add(i.currentCadence, now); }
            }
        } catch (ex) {}
    }

    // Connection status color: yellow=connecting/stale, green=RunBeat, gray=none.
    protected function connColor() as Number {
        if (!_state.hasConnected() || _state.isStale(System.getTimer())) {
            return Graphics.COLOR_YELLOW;
        }
        return _state.isRunBeatActive() ? Graphics.COLOR_GREEN : Graphics.COLOR_LT_GRAY;
    }

    protected function connText() as String {
        if (!_state.hasConnected()) { return _strConnecting; }
        if (_state.isStale(System.getTimer())) { return _strPhoneNotConnected; }
        return _state.isRunBeatActive() ? _strAppName : _strNoPlayback;
    }

    protected function hitTest(rect, x as Number, y as Number) as Boolean {
        if (rect == null) { return false; }
        var r = rect as Array;
        return x >= (r[0] as Number) && x < (r[0] as Number) + (r[2] as Number) &&
               y >= (r[1] as Number) && y < (r[1] as Number) + (r[3] as Number);
    }

    protected function truncate(dc as Graphics.Dc, text as String, font, maxW as Number) as String {
        if (dc.getTextWidthInPixels(text, font) <= maxW) { return text; }
        var t = text;
        while (t.length() > 0 && dc.getTextWidthInPixels(t + "...", font) > maxW) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "...";
    }

    // Overridden by subclasses.
    function draw(dc as Graphics.Dc, now as Number) as Void {}
    function onTap(x as Number, y as Number) as Boolean { return false; }
}

// ============================================================
// Field 1: Track — Artist / Album / Title (display only)
// ============================================================

class RunBeatTrackField extends RunBeatFieldBase {

    private var _strPlaying as String;
    private var _strPaused as String;

    function initialize() {
        RunBeatFieldBase.initialize();
        _strPlaying = WatchUi.loadResource(Rez.Strings.Playing) as String;
        _strPaused = WatchUi.loadResource(Rez.Strings.Paused) as String;
    }

    function draw(dc as Graphics.Dc, now as Number) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);
        var fhSmall = dc.getFontHeight(Graphics.FONT_SMALL);
        var fhLarge = dc.getFontHeight(Graphics.FONT_LARGE);
        var runBeat = _state.isRunBeatActive();

        // Play state (top, small).
        var st = runBeat ? (_state.isPlaying ? _strPlaying : _strPaused) : _strNoPlayback;
        dc.setColor(connColor(), Graphics.COLOR_BLACK);
        dc.drawText(w / 2, fhTiny / 2 + 1, Graphics.FONT_TINY, st,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        if (runBeat) {
            // Title (big, center).
            var title = _state.title.length() > 0 ? _state.title : _strNoValue;
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, h / 2, Graphics.FONT_LARGE,
                truncate(dc, title, Graphics.FONT_LARGE, (w * 0.94).toNumber()),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            // Artist / Album (bottom).
            var artist = _state.artist.length() > 0 ? _state.artist : "";
            var album = _state.album.length() > 0 ? _state.album : "";
            var sub = artist;
            if (album.length() > 0) {
                sub = sub.length() > 0 ? sub + " - " + album : album;
            }
            if (sub.length() > 0) {
                dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
                dc.drawText(w / 2, h - fhSmall / 2 - 1, Graphics.FONT_SMALL,
                    truncate(dc, sub, Graphics.FONT_SMALL, (w * 0.90).toNumber()),
                    Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            }
        } else {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, h / 2, Graphics.FONT_LARGE, _strNoPlayback,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }
}

class RunBeatTrackApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() {
        var f = new RunBeatTrackField();
        return [f, new RunBeatFieldDelegate(f)];
    }
}

// ============================================================
// Field 2: Transport — |<  >/||  >|  (colors = connection status)
// ============================================================

class RunBeatTransportField extends RunBeatFieldBase {

    private var _zones as Array = [];  // [prev, play, next]

    function draw(dc as Graphics.Dc, now as Number) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var fhNum = dc.getFontHeight(Graphics.FONT_NUMBER_MEDIUM);
        var color = connColor();

        // Three equal zones: prev | play-pause | next.
        var zw = (w / 3).toNumber();
        var cy = (h / 2).toNumber();
        var fs = fhNum > (h * 0.5).toNumber() ? (h * 0.5).toNumber() : fhNum;

        dc.setColor(color, Graphics.COLOR_BLACK);
        dc.drawText((zw / 2).toNumber(), cy, Graphics.FONT_NUMBER_MEDIUM, "|<",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var playLabel = _state.isPlaying ? "||" : ">";
        dc.drawText(zw + (zw / 2).toNumber(), cy, Graphics.FONT_NUMBER_MEDIUM, playLabel,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.drawText(zw * 2 + (zw / 2).toNumber(), cy, Graphics.FONT_NUMBER_MEDIUM, ">|",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        _zones = [
            [0, 0, zw, h],
            [zw, 0, zw, h],
            [zw * 2, 0, w - zw * 2, h]
        ];
    }

    function onTap(x as Number, y as Number) as Boolean {
        var z = _zones;
        if (z.size() >= 3) {
            if (hitTest(z[0], x, y)) { _messages.sendPrev(); return true; }
            if (hitTest(z[1], x, y)) { _messages.sendToggle(); return true; }
            if (hitTest(z[2], x, y)) { _messages.sendNext(); return true; }
        }
        return false;
    }
}

class RunBeatTransportApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() {
        var f = new RunBeatTransportField();
        return [f, new RunBeatFieldDelegate(f)];
    }
}

// ============================================================
// Field 3: Target cadence — [-1] [number] [+1]
//   number tap = toggle speed correction; color = green(ON)/gray(OFF)
// ============================================================

class RunBeatTargetField extends RunBeatFieldBase {

    private var _strTargetPrefix as String;
    private var _zones as Array = [];  // [minus, number, plus]

    function initialize() {
        RunBeatFieldBase.initialize();
        _strTargetPrefix = WatchUi.loadResource(Rez.Strings.TargetPrefix) as String;
    }

    function draw(dc as Graphics.Dc, now as Number) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);
        var fhNum = dc.getFontHeight(Graphics.FONT_NUMBER_MEDIUM);
        var runBeat = _state.isRunBeatActive();

        // Label (top).
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, fhTiny / 2 + 1, Graphics.FONT_TINY, _strTargetPrefix,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Three zones: -1 | number | +1
        var zw = (w / 3).toNumber();
        var cy = (h + fhTiny) / 2;

        // -1 button (left).
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText((zw / 2).toNumber(), cy, Graphics.FONT_NUMBER_MEDIUM, "-",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Number (center) — green if speed corr ON, gray if OFF.
        var target = _state.targetCadenceText();
        if (target.length() == 0) { target = _strNoValue; }
        var numColor = !runBeat ? Graphics.COLOR_DK_GRAY
            : (_state.speedCorrection ? Graphics.COLOR_GREEN : Graphics.COLOR_LT_GRAY);
        dc.setColor(numColor, Graphics.COLOR_BLACK);
        dc.drawText(zw + (zw / 2).toNumber(), cy, Graphics.FONT_NUMBER_MEDIUM, target,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // +1 button (right).
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(zw * 2 + (zw / 2).toNumber(), cy, Graphics.FONT_NUMBER_MEDIUM, "+",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        _zones = [
            [0, fhTiny, zw, h - fhTiny],           // -1
            [zw, fhTiny, zw, h - fhTiny],          // number (toggle corr)
            [zw * 2, fhTiny, w - zw * 2, h - fhTiny] // +1
        ];
    }

    function onTap(x as Number, y as Number) as Boolean {
        var z = _zones;
        if (z.size() >= 3) {
            if (hitTest(z[0], x, y)) { _messages.sendCadenceDelta(-1); return true; }
            if (hitTest(z[1], x, y)) { _messages.sendSpeedCorrectionToggle(); return true; }
            if (hitTest(z[2], x, y)) { _messages.sendCadenceDelta(1); return true; }
        }
        return false;
    }
}

class RunBeatTargetApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() {
        var f = new RunBeatTargetField();
        return [f, new RunBeatFieldDelegate(f)];
    }
}

// ============================================================
// Field 4: No Good Run — red button, tap = mark not-for-running
// ============================================================

class RunBeatMarkField extends RunBeatFieldBase {

    private var _strMarkLabel as String;

    function initialize() {
        RunBeatFieldBase.initialize();
        _strMarkLabel = WatchUi.loadResource(Rez.Strings.MarkNotForRunning) as String;
    }

    function draw(dc as Graphics.Dc, now as Number) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var fhSmall = dc.getFontHeight(Graphics.FONT_SMALL);
        var runBeat = _state.isRunBeatActive();
        var color = runBeat ? Graphics.COLOR_RED : Graphics.COLOR_DK_GRAY;

        dc.setColor(color, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h / 2, Graphics.FONT_SMALL,
            truncate(dc, _strMarkLabel, Graphics.FONT_SMALL, (w * 0.92).toNumber()),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function onTap(x as Number, y as Number) as Boolean {
        if (_state.isRunBeatActive()) {
            _messages.sendMarkNotForRunning();
        }
        return true;
    }
}

class RunBeatMarkApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() {
        var f = new RunBeatMarkField();
        return [f, new RunBeatFieldDelegate(f)];
    }
}

// ============================================================
// Field 5: Avg/60s cadence — blue, tap = set as target cadence
// ============================================================

class RunBeatAvgField extends RunBeatFieldBase {

    private var _strAvgCaption as String;

    function initialize() {
        RunBeatFieldBase.initialize();
        _strAvgCaption = WatchUi.loadResource(Rez.Strings.AvgCaption) as String;
    }

    function draw(dc as Graphics.Dc, now as Number) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);
        var fhNum = dc.getFontHeight(Graphics.FONT_NUMBER_MEDIUM);

        // Label (top).
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, fhTiny / 2 + 1, Graphics.FONT_TINY, _strAvgCaption,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Big blue number (center).
        var mean = _cadenceBuffer.mean(now);
        var txt = mean == null ? _strNoValue : (mean as Float).format("%.0f");
        dc.setColor(Graphics.COLOR_BLUE, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, (h + fhTiny) / 2, Graphics.FONT_NUMBER_MEDIUM, txt,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function onTap(x as Number, y as Number) as Boolean {
        if (_state.isRunBeatActive()) {
            var mean = _cadenceBuffer.mean(System.getTimer());
            if (mean != null) { _messages.sendCadenceUseCurrent(mean as Float); }
        }
        return true;
    }
}

class RunBeatAvgApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() {
        var f = new RunBeatAvgField();
        return [f, new RunBeatFieldDelegate(f)];
    }
}

// ============================================================
// Shared input delegate
// ============================================================

class RunBeatFieldDelegate extends WatchUi.BehaviorDelegate {

    private var _field;   // RunBeatFieldBase

    function initialize(field) {
        BehaviorDelegate.initialize();
        _field = field;
    }

    function onSelect() as Boolean {
        _field.onTap(-1, -1);  // no zone hit = no-op for most fields
        return true;
    }

    function onKey(keyEvent as WatchUi.KeyEvent) as Boolean {
        if (keyEvent.getKey() == WatchUi.KEY_ENTER) {
            _field.onTap(-1, -1);
            return true;
        }
        return false;
    }

    function onTap(clickEvent as WatchUi.ClickEvent) as Boolean {
        var c = clickEvent.getCoordinates();
        return _field.onTap(c[0] as Number, c[1] as Number);
    }

    function onBack() as Boolean { return false; }
}
