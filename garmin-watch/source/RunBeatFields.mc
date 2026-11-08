import Toybox.Activity;
import Toybox.Application;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// RunBeat data fields — TWO full-screen data field apps.
//
// Field 1 "RunBeat Track":    play state, title, artist/album, transport |< >/|| >|
// Field 2 "RunBeat Controls": target cadence [- num +], AVG/60s, NO GOOD RUN
//
// Add each to a separate full-screen data screen in the run activity.
// Only the visible screen's field runs, so one process at a time.

// ============================================================
// Shared base
// ============================================================

class RunBeatFieldBase extends WatchUi.DataField {

    protected var _state;
    protected var _messages;
    protected var _cadenceBuffer;

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

    protected function connColor() as Number {
        if (!_state.hasConnected() || _state.isStale(System.getTimer())) {
            return Graphics.COLOR_YELLOW;
        }
        return _state.isRunBeatActive() ? Graphics.COLOR_GREEN : Graphics.COLOR_LT_GRAY;
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

    function draw(dc as Graphics.Dc, now as Number) as Void {}
    function onTap(x as Number, y as Number) as Boolean { return false; }
}

// ============================================================
// Field 1: TRACK — full screen
//   top: play state (connection color)
//   center: title (big) + artist - album
//   bottom: transport |<  >/||  >|  (tap zones, connection color)
// ============================================================

class RunBeatTrackField extends RunBeatFieldBase {

    private var _strPlaying as String;
    private var _strPaused as String;
    private var _zones as Array = [];

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
        var fhNum = dc.getFontHeight(Graphics.FONT_NUMBER_MEDIUM);
        var runBeat = _state.isRunBeatActive();
        var color = connColor();

        // Top: play state.
        var st = runBeat ? (_state.isPlaying ? _strPlaying : _strPaused) : _strNoPlayback;
        dc.setColor(color, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, fhSmall / 2 + 4, Graphics.FONT_SMALL, st,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Center: title (big) + artist/album.
        var midY = fhSmall + 8 + (h - fhSmall - fhNum - 16) / 2;
        if (runBeat) {
            var title = _state.title.length() > 0 ? _state.title : _strNoValue;
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, midY, Graphics.FONT_LARGE,
                truncate(dc, title, Graphics.FONT_LARGE, (w * 0.94).toNumber()),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            var sub = _state.artist;
            if (_state.album.length() > 0) {
                sub = sub.length() > 0 ? sub + " - " + _state.album : _state.album;
            }
            if (sub.length() > 0) {
                dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
                dc.drawText(w / 2, midY + fhLarge / 2 + 4 + fhTiny / 2, Graphics.FONT_TINY,
                    truncate(dc, sub, Graphics.FONT_TINY, (w * 0.90).toNumber()),
                    Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            }
        } else {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, midY, Graphics.FONT_LARGE, _strNoPlayback,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        // Bottom: transport row.
        var tY = h - fhNum - 8;
        var zw = (w / 3).toNumber();
        dc.setColor(color, Graphics.COLOR_BLACK);
        dc.drawText((zw / 2).toNumber(), tY + fhNum / 2, Graphics.FONT_NUMBER_MEDIUM, "|<",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var pl = _state.isPlaying ? "||" : ">";
        dc.drawText(zw + (zw / 2).toNumber(), tY + fhNum / 2, Graphics.FONT_NUMBER_MEDIUM, pl,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.drawText(zw * 2 + (zw / 2).toNumber(), tY + fhNum / 2, Graphics.FONT_NUMBER_MEDIUM, ">|",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        _zones = [
            [0, tY, zw, fhNum + 8],
            [zw, tY, zw, fhNum + 8],
            [zw * 2, tY, w - zw * 2, fhNum + 8]
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

class RunBeatTrackApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() {
        var f = new RunBeatTrackField();
        return [f, new RunBeatFieldDelegate(f)];
    }
}

// ============================================================
// Field 2: CONTROLS — full screen
//   Row 1: TARGET  [-] [num] [+]   num tap = toggle speed corr (green/gray)
//   Row 2: AVG/60s (blue, tap=set target)  |  NO GOOD RUN (red, tap=mark)
// ============================================================

class RunBeatControlsField extends RunBeatFieldBase {

    private var _strTargetPrefix as String;
    private var _strAvgCaption as String;
    private var _strMarkLabel as String;
    private var _zonesT as Array = [];  // [minus, num, plus]
    private var _zonesB as Array = [];  // [avg, mark]

    function initialize() {
        RunBeatFieldBase.initialize();
        _strTargetPrefix = WatchUi.loadResource(Rez.Strings.TargetPrefix) as String;
        _strAvgCaption = WatchUi.loadResource(Rez.Strings.AvgCaption) as String;
        _strMarkLabel = WatchUi.loadResource(Rez.Strings.MarkNotForRunning) as String;
    }

    function draw(dc as Graphics.Dc, now as Number) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);
        var fhSmall = dc.getFontHeight(Graphics.FONT_SMALL);
        var fhNum = dc.getFontHeight(Graphics.FONT_NUMBER_MEDIUM);
        var runBeat = _state.isRunBeatActive();

        // Split into two rows.
        var rowH = (h / 2).toNumber();
        var row1Y = 0;
        var row2Y = rowH;

        // ---- Row 1: TARGET [-] [num] [+] ----
        var c1y = row1Y + rowH / 2;
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, row1Y + fhTiny / 2 + 2, Graphics.FONT_TINY, _strTargetPrefix,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        var zw = (w / 3).toNumber();
        var numY = row1Y + fhTiny + 4 + (rowH - fhTiny - 4 - fhNum) / 2 + fhNum / 2;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText((zw / 2).toNumber(), numY, Graphics.FONT_NUMBER_MEDIUM, "-",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        var target = _state.targetCadenceText();
        if (target.length() == 0) { target = _strNoValue; }
        var numColor = !runBeat ? Graphics.COLOR_DK_GRAY
            : (_state.speedCorrection ? Graphics.COLOR_GREEN : Graphics.COLOR_LT_GRAY);
        dc.setColor(numColor, Graphics.COLOR_BLACK);
        dc.drawText(zw + (zw / 2).toNumber(), numY, Graphics.FONT_NUMBER_MEDIUM, target,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(zw * 2 + (zw / 2).toNumber(), numY, Graphics.FONT_NUMBER_MEDIUM, "+",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        _zonesT = [
            [0, row1Y, zw, rowH],
            [zw, row1Y, zw, rowH],
            [zw * 2, row1Y, w - zw * 2, rowH]
        ];

        // ---- Row 2: AVG/60s | NO GOOD RUN ----
        var c2y = row2Y + rowH / 2;
        var halfW = (w / 2).toNumber();

        // Left half: AVG/60s (blue, tap = set as target).
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText((halfW / 2).toNumber(), row2Y + fhTiny / 2 + 2, Graphics.FONT_TINY, _strAvgCaption,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var mean = _cadenceBuffer.mean(now);
        var meanText = mean == null ? _strNoValue : (mean as Float).format("%.0f");
        dc.setColor(Graphics.COLOR_BLUE, Graphics.COLOR_BLACK);
        dc.drawText((halfW / 2).toNumber(), c2y + fhNum / 4, Graphics.FONT_NUMBER_MEDIUM, meanText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Right half: NO GOOD RUN (red, tap = mark).
        var markColor = runBeat ? Graphics.COLOR_RED : Graphics.COLOR_DK_GRAY;
        dc.setColor(markColor, Graphics.COLOR_BLACK);
        dc.drawText(halfW + (halfW / 2).toNumber(), c2y, Graphics.FONT_SMALL,
            truncate(dc, _strMarkLabel, Graphics.FONT_SMALL, (halfW * 0.92).toNumber()),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        _zonesB = [
            [0, row2Y, halfW, rowH],
            [halfW, row2Y, halfW, rowH]
        ];
    }

    function onTap(x as Number, y as Number) as Boolean {
        var h = 0;  // we don't have h here, use zones to determine row
        var zt = _zonesT;
        if (zt.size() >= 3) {
            if (hitTest(zt[0], x, y)) { _messages.sendCadenceDelta(-1); return true; }
            if (hitTest(zt[1], x, y)) { _messages.sendSpeedCorrectionToggle(); return true; }
            if (hitTest(zt[2], x, y)) { _messages.sendCadenceDelta(1); return true; }
        }
        var zb = _zonesB;
        if (zb.size() >= 2) {
            if (hitTest(zb[0], x, y)) {
                if (_state.isRunBeatActive()) {
                    var mean = _cadenceBuffer.mean(System.getTimer());
                    if (mean != null) { _messages.sendCadenceUseCurrent(mean as Float); }
                }
                return true;
            }
            if (hitTest(zb[1], x, y)) {
                if (_state.isRunBeatActive()) { _messages.sendMarkNotForRunning(); }
                return true;
            }
        }
        return false;
    }
}

class RunBeatControlsApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() {
        var f = new RunBeatControlsField();
        return [f, new RunBeatFieldDelegate(f)];
    }
}

// ============================================================
// Shared input delegate
// ============================================================

class RunBeatFieldDelegate extends WatchUi.BehaviorDelegate {

    private var _field;

    function initialize(field) {
        BehaviorDelegate.initialize();
        _field = field;
    }

    function onSelect() as Boolean {
        _field.onTap(-1, -1);
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
