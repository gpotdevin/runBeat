import Toybox.Application;
import Toybox.Communications;
import Toybox.FitContributor;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.WatchUi;

// RunBeat data field (Connect IQ / Monkey C) — two screens.
//
// A data field runs ONLY during an activity; the firmware owns its
// lifecycle, so it can never drain the battery outside a workout.
//
// Screen 0 — Track: title, artist, play state. Tap zones: left=prev,
//   center=play/pause, right=next. SELECT=play/pause, LAP=next.
// Screen 1 — Controls: 2x2 grid.
//   top-left   TGT xxx  (green=speed-corr ON, gray=OFF)  tap=toggle speed corr
//   top-right  AVG xxx  (blue)                            tap=set avg as target
//   bottom-left MARK    (red)                             tap=mark not-for-running
//   bottom-right        connection status (gray)
// Switch screens: UP/DOWN key, or vertical swipe.
//
// No live cadence display — the stock running app already shows it.
// FIT contribution: cadence + target cadence written to the FIT file.

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

    // FIT contributor fields.
    private var _fitCadence;       // FitContributor.Field?
    private var _fitTarget;        // FitContributor.Field?
    private var _fitSaveCounter as Number = 0;

    // Current screen: 0 = track, 1 = controls.
    private var _screen as Number = 0;

    // Cached resources.
    private var _strAppName as String;
    private var _strNoPlayback as String;
    private var _strNoValue as String;
    private var _strAvgCaption as String;
    private var _strTargetPrefix as String;
    private var _strConnecting as String;
    private var _strPhoneNotConnected as String;
    private var _strMarkLabel as String;
    private var _strPlaying as String;
    private var _strPaused as String;

    // Tap-zone rects [x, y, w, h] from the last onUpdate, per screen.
    private var _tapZones as Array = [];   // screen 0: [prev, play, next]
    private var _tapZones1 as Array = [];  // screen 1: [target, avg, mark, conn]

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
        _strMarkLabel = WatchUi.loadResource(Rez.Strings.MarkNotForRunning) as String;
        _strPlaying = WatchUi.loadResource(Rez.Strings.Playing) as String;
        _strPaused = WatchUi.loadResource(Rez.Strings.Paused) as String;
    }

    function onShow() as Void {
        Communications.registerForPhoneAppMessages(_messages.method(:onPhoneMessage));
        _messages.sendRequestState();
        try {
            _fitCadence = createField("cadence", 0, FitContributor.DATA_TYPE_UINT16, {:mesgType => FitContributor.MESG_TYPE_RECORD, :units => "spm"});
            _fitTarget = createField("target_cadence", 1, FitContributor.DATA_TYPE_UINT16, {:mesgType => FitContributor.MESG_TYPE_RECORD, :units => "spm"});
        } catch (e) {
            _fitCadence = null;
            _fitTarget = null;
        }
    }

    function onHide() as Void {
        _messages.sendBye();
        Communications.registerForPhoneAppMessages(null);
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var now = System.getTimer();
        sampleCadence(now);
        writeFit(now);
        if (_screen == 0) {
            drawTrackScreen(dc, now);
        } else {
            drawControlsScreen(dc, now);
        }
    }

    function onTimerStart() as Void { _messages.sendRequestState(); }
    function onTimerResume() as Void { _messages.sendRequestState(); }
    function onTimerLap() as Void { _messages.sendNext(); }
    function onTimerStop() as Void {
        _messages.sendBye();
        Communications.registerForPhoneAppMessages(null);
    }
    function onTimerPause() as Void { _messages.sendBye(); }

    // --- Input handlers (called by the delegate) ---

    function onSelectPressed() as Void {
        if (_screen == 0) {
            _messages.sendToggle();
        }
    }

    // UP/DOWN toggle between the two screens.
    function onUpPressed() as Void {
        _screen = (_screen == 0) ? 1 : 0;
        WatchUi.requestUpdate();
    }

    function onDownPressed() as Void {
        _screen = (_screen == 0) ? 1 : 0;
        WatchUi.requestUpdate();
    }

    // Tap routing: screen 0 = prev/play/next zones; screen 1 = 2x2 grid.
    function onTap(x as Number, y as Number) as Boolean {
        if (_screen == 0) {
            var zones = _tapZones;
            if (zones.size() >= 3) {
                if (hitTest(zones[0], x, y)) { _messages.sendPrev(); return true; }
                if (hitTest(zones[1], x, y)) { _messages.sendToggle(); return true; }
                if (hitTest(zones[2], x, y)) { _messages.sendNext(); return true; }
            }
            // Tap outside zones on screen 0 = play/pause.
            _messages.sendToggle();
            return true;
        } else {
            var z = _tapZones1;
            if (z.size() >= 4) {
                if (hitTest(z[0], x, y)) { _messages.sendSpeedCorrectionToggle(); return true; }
                if (hitTest(z[1], x, y)) { useAvgAsTarget(); return true; }
                if (hitTest(z[2], x, y)) { _messages.sendMarkNotForRunning(); return true; }
            }
            return true;
        }
    }

    // Vertical swipe switches screens.
    function onVerticalSwipe(direction as Symbol) as Void {
        _screen = (_screen == 0) ? 1 : 0;
        WatchUi.requestUpdate();
    }

    // Sends cadence_use_current with the 60 s ring-buffer mean.
    private function useAvgAsTarget() as Void {
        if (!_state.isRunBeatActive()) { return; }
        var mean = _cadenceBuffer.mean(System.getTimer());
        if (mean != null) {
            _messages.sendCadenceUseCurrent(mean as Float);
        }
    }

    // --- Cadence sampling (feeds the ring buffer; no live display) ---

    private function sampleCadence(now as Number) as Void {
        if (Toybox has :Sensor) {
            try {
                var info = Sensor.getInfo();
                if (info != null) {
                    var i = info as Sensor.Info;
                    if (i.cadence != null) {
                        _cadenceBuffer.add(i.cadence, now);
                    }
                }
            } catch (ex) {}
        }
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
        _fitSaveCounter++;
        if (_fitSaveCounter >= 30) {
            _fitSaveCounter = 0;
            }
    }

    // --- Drawing: Screen 0 (Track) ---

    private function drawTrackScreen(dc as Graphics.Dc, now as Number) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);
        var fhSmall = dc.getFontHeight(Graphics.FONT_SMALL);
        var fhLarge = dc.getFontHeight(Graphics.FONT_LARGE);

        var runBeat = _state.isRunBeatActive();

        // Play/pause state line (top of screen, no header).
        var stateText = runBeat ? (_state.isPlaying ? _strPlaying : _strPaused) : _strNoPlayback;
        dc.setColor(runBeat ? Graphics.COLOR_WHITE : Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, 2 + fhSmall / 2, Graphics.FONT_SMALL, stateText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Track title (big, centered in remaining space).
        var yTitle = fhSmall + 6 + (h - fhSmall - fhTiny - 6) / 2;
        if (runBeat) {
            var title = _state.title.length() > 0 ? _state.title : _strNoValue;
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yTitle, Graphics.FONT_LARGE,
                truncateToWidth(dc, title, Graphics.FONT_LARGE, (w * 0.92).toNumber()),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            // Artist below title.
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

        // Transport hint at the bottom: |<  >/||  >|
        var hintY = h - fhTiny - 4;
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, hintY, Graphics.FONT_TINY, "|<   >/||   >|",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Tap zones: left 1/3 = prev, center 1/3 = play/pause, right 1/3 = next.
        var zoneY = 0;
        var zoneH = h - fhTiny - 4;
        var zw = (w / 3).toNumber();
        _tapZones = [
            [0, zoneY, zw, zoneH],
            [zw, zoneY, zw, zoneH],
            [zw * 2, zoneY, w - zw * 2, zoneH]
        ];

        // Screen indicator.
        drawScreenIndicator(dc, 1, 2);
    }

    // --- Drawing: Screen 1 (Controls, 2x2 grid) ---

    private function drawControlsScreen(dc as Graphics.Dc, now as Number) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);
        var fhSmall = dc.getFontHeight(Graphics.FONT_SMALL);
        var fhMedium = dc.getFontHeight(Graphics.FONT_NUMBER_MEDIUM);

        var runBeat = _state.isRunBeatActive();

        // Header: connection status.
        dc.setColor(headerColor(runBeat), Graphics.COLOR_BLACK);
        dc.drawText(w / 2, fhTiny / 2 + 2, Graphics.FONT_TINY, headerText(),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // 2x2 grid below the header.
        var gridTop = fhTiny + 6;
        var gridH = h - gridTop - fhTiny - 6;
        var cellW = (w / 2).toNumber();
        var cellH = (gridH / 2).toNumber();

        // --- Top-left: Target cadence (green=speed-corr ON, gray=OFF) ---
        var tlX = 0;
        var tlY = gridTop;
        var target = _state.targetCadenceText();
        if (target.length() == 0) { target = _strNoValue; }
        var tgtColor = !runBeat ? Graphics.COLOR_DK_GRAY
            : (_state.speedCorrection ? Graphics.COLOR_GREEN : Graphics.COLOR_LT_GRAY);
        drawCellLabel(dc, (cellW / 2).toNumber(), (tlY + cellH * 0.30).toNumber(), _strTargetPrefix,
            Graphics.COLOR_LT_GRAY, Graphics.FONT_TINY);
        drawCellValue(dc, (cellW / 2).toNumber(), (tlY + cellH * 0.62).toNumber(), target, tgtColor, Graphics.FONT_NUMBER_MEDIUM);
        drawCellBorder(dc, tlX, tlY, cellW, cellH, tgtColor);

        // --- Top-right: 60s avg cadence (blue) ---
        var trX = cellW;
        var mean = _cadenceBuffer.mean(now);
        var meanText = mean == null ? _strNoValue : (mean as Float).format("%.0f");
        drawCellLabel(dc, trX + (cellW / 2).toNumber(), (tlY + cellH * 0.30).toNumber(), _strAvgCaption,
            Graphics.COLOR_LT_GRAY, Graphics.FONT_TINY);
        drawCellValue(dc, trX + (cellW / 2).toNumber(), (tlY + cellH * 0.62).toNumber(), meanText,
            Graphics.COLOR_BLUE, Graphics.FONT_NUMBER_MEDIUM);
        drawCellBorder(dc, trX, tlY, cellW, cellH, Graphics.COLOR_BLUE);

        // --- Bottom-left: Mark not for running (red) ---
        var blY = gridTop + cellH;
        drawCellLabel(dc, (cellW / 2).toNumber(), (blY + cellH * 0.30).toNumber(), _strMarkLabel,
            Graphics.COLOR_RED, Graphics.FONT_SMALL);
        drawCellBorder(dc, 0, blY, cellW, cellH, Graphics.COLOR_RED);

        // --- Bottom-right: Connection status (gray) ---
        var brX = cellW;
        var connText = headerText();
        drawCellValue(dc, brX + (cellW / 2).toNumber(), (blY + cellH * 0.50).toNumber(), connText,
            Graphics.COLOR_LT_GRAY, Graphics.FONT_TINY);
        drawCellBorder(dc, brX, blY, cellW, cellH, Graphics.COLOR_DK_GRAY);

        // Tap zones for the 2x2 grid.
        _tapZones1 = [
            [tlX, tlY, cellW, cellH],   // target -> toggle speed correction
            [trX, tlY, cellW, cellH],   // avg -> set as target
            [0, blY, cellW, cellH],     // mark -> not for running
            [brX, blY, cellW, cellH]    // connection (no action)
        ];

        drawScreenIndicator(dc, 2, 2);
    }

    // Small label inside a grid cell.
    private function drawCellLabel(dc as Graphics.Dc, cx as Number, cy as Number,
                                   text as String, color as Number, font) as Void {
        dc.setColor(color, Graphics.COLOR_BLACK);
        dc.drawText(cx, cy, font, text,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // Big value inside a grid cell.
    private function drawCellValue(dc as Graphics.Dc, cx as Number, cy as Number,
                                   text as String, color as Number, font) as Void {
        dc.setColor(color, Graphics.COLOR_BLACK);
        dc.drawText(cx, cy, font, text,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // Thin border around a grid cell (visual tap target hint).
    private function drawCellBorder(dc as Graphics.Dc, x as Number, y as Number,
                                    cw as Number, ch as Number, color as Number) as Void {
        dc.setColor(color, Graphics.COLOR_BLACK);
        dc.drawRectangle(x + 2, y + 2, cw - 4, ch - 4);
    }

    // --- Shared helpers ---

    private function headerText() as String {
        if (!_state.hasConnected()) { return _strConnecting; }
        if (_state.isStale(System.getTimer())) { return _strPhoneNotConnected; }
        return _state.isRunBeatActive() ? _strAppName : _strNoPlayback;
    }

    private function headerColor(runBeat as Boolean) as Number {
        if (!_state.hasConnected() || _state.isStale(System.getTimer())) {
            return Graphics.COLOR_YELLOW;
        }
        return runBeat ? Graphics.COLOR_GREEN : Graphics.COLOR_LT_GRAY;
    }

    private function drawScreenIndicator(dc as Graphics.Dc, page as Number, total as Number) as Void {
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(dc.getWidth() - 4, dc.getHeight() - 4, Graphics.FONT_TINY,
            page.format("%d") + "/" + total.format("%d"),
            Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);
    }

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

// Input delegate: routes keys and taps to the field.
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

    function onTap(clickEvent as WatchUi.ClickEvent) as Boolean {
        var coords = clickEvent.getCoordinates();
        _field.onTap(coords[0] as Number, coords[1] as Number);
        return true;
    }

    function onSwipe(swipeEvent as WatchUi.SwipeEvent) as Boolean {
        var dir = swipeEvent.getDirection();
        if (dir == WatchUi.SWIPE_UP || dir == WatchUi.SWIPE_DOWN) {
            _field.onVerticalSwipe(dir);
            return true;
        }
        return false;
    }

    function onBack() as Boolean {
        return false;
    }
}
