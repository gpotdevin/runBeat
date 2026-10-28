import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// Cadence page (RunBeat only; greyed out when source != "RunBeat").
// Big live 60-second average cadence, target cadence from phone state,
// and two drawn buttons: [TEMPO ON/OFF] toggles speed correction (the
// playback-tempo adjustment), [TARGET +/-] opens the pushed target-
// cadence adjuster (upper half +1 spm, lower half -1 spm).
// Select = "use current as target"; menu = +1 / -1 / use current /
// speed correction.
class CadenceView extends BaseView {

    private var _cadenceBuffer;   // CadenceRingBuffer

    // Drawn button rects [x, y, w, h] from the last onUpdate (tap hit-testing).
    private var _btnTempo;        // Array? or null
    private var _btnTarget;       // Array? or null

    function initialize(state, messages, cadenceBuffer) {
        BaseView.initialize(state, messages);
        _cadenceBuffer = cadenceBuffer;
    }

    function onLayout(dc as Dc) as Void {
        if (isRound()) {
            setLayout(Rez.Layouts.CadenceLayoutRound(dc));
        } else {
            setLayout(Rez.Layouts.CadenceLayout(dc));
        }
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var runBeat = _state.isRunBeatActive();
        var dim = Graphics.COLOR_DK_GRAY;

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        updateHeader(WatchUi.loadResource(Rez.Strings.HeaderCadence) as String, runBeat ? Graphics.COLOR_LT_GRAY : dim);

        // Big live 60-second average (greyed out unless RunBeat is playing).
        var mean = _cadenceBuffer.mean(System.getTimer());
        var meanText = mean == null ? WatchUi.loadResource(Rez.Strings.NoValue) as String : (mean as Float).format("%.0f");
        dc.setColor(runBeat ? Graphics.COLOR_WHITE : dim, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.36, Graphics.FONT_LARGE, meanText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.47, Graphics.FONT_TINY, WatchUi.loadResource(Rez.Strings.AvgCaption) as String,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Target cadence from the phone state.
        var target = _state.targetCadenceText();
        if (target.length() == 0) {
            target = WatchUi.loadResource(Rez.Strings.NoValue) as String;
        }
        dc.setColor(runBeat ? Graphics.COLOR_LT_GRAY : dim, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.58, Graphics.FONT_SMALL,
            WatchUi.loadResource(Rez.Strings.TargetPrefix) as String + " " + target,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Two drawn buttons: [TEMPO ON/OFF] and [TARGET +/-] (RunBeat
        // only — without RunBeat the hint takes their place; taps then
        // fall through to select, which shows the same hint).
        var bH = maxNum(16, minNum(30, h * 0.075)).toNumber();
        var bW = minNum(w * 0.34, bH * 3.2).toNumber();
        var bGap = (w * 0.06).toNumber();
        var bTotal = bW * 2 + bGap;
        var bLeft = (w - bTotal) / 2;
        var bTop = maxNum(h * 0.74 - bH / 2, h * 0.58 + 14).toNumber();
        var bCenterY = bTop + bH / 2;
        _btnTempo = [bLeft, bTop, bW, bH];
        _btnTarget = [bLeft + bW + bGap, bTop, bW, bH];

        if (runBeat) {
            dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
            dc.fillRoundedRectangle(bLeft, bTop, bW, bH, bH / 4);
            dc.fillRoundedRectangle(bLeft + bW + bGap, bTop, bW, bH, bH / 4);
            var tempoText = WatchUi.loadResource(Rez.Strings.TempoLabel) as String + " " +
                (_state.speedCorrection ? WatchUi.loadResource(Rez.Strings.On) as String : WatchUi.loadResource(Rez.Strings.Off) as String);
            dc.setColor(_state.speedCorrection ? Graphics.COLOR_GREEN : Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(bLeft + bW / 2, bCenterY, Graphics.FONT_TINY, tempoText,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(bLeft + bW + bGap + bW / 2, bCenterY, Graphics.FONT_TINY,
                WatchUi.loadResource(Rez.Strings.TargetPrefix) as String + " +/-",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            dc.setColor(dim, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, bCenterY, Graphics.FONT_TINY, WatchUi.loadResource(Rez.Strings.RunBeatOnlyHint) as String,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        drawAck(dc);
        drawPageIndicator(dc, 3, 3);
    }

    // Select: use the current 60 s average as the target cadence.
    function onSelectPressed() as Void {
        useCurrentAsTarget();
    }

    // Tap handler for RunBeatPageDelegate: the TEMPO button toggles speed
    // correction, the TARGET button opens the pushed target-cadence
    // adjuster. Both are RunBeat only (the buttons are only drawn when
    // RunBeat is active). Returns true when handled.
    function onTap(x as Number, y as Number) as Boolean {
        if (hitTest(_btnTempo, x, y)) {
            if (!_state.isRunBeatActive()) {
                _state.setAck(WatchUi.loadResource(Rez.Strings.RunBeatOnlyHint) as String, true);
                WatchUi.requestUpdate();
                return true;
            }
            _messages.sendSpeedCorrectionToggle();
            return true;
        }
        if (hitTest(_btnTarget, x, y)) {
            if (!_state.isRunBeatActive()) {
                _state.setAck(WatchUi.loadResource(Rez.Strings.RunBeatOnlyHint) as String, true);
                WatchUi.requestUpdate();
                return true;
            }
            var view = new CadenceSetView(_state, _cadenceBuffer);
            view.arm();
            _state.adjustScreen = view;
            WatchUi.pushView(view, new CadenceSetDelegate(view, _messages), WatchUi.SLIDE_IMMEDIATE);
            return true;
        }
        return false;
    }

    // Sends cadence_use_current with the ring-buffer mean. Guarded: RunBeat
    // only, and only when a usable mean exists.
    function useCurrentAsTarget() as Void {
        if (!_state.isRunBeatActive()) {
            _state.setAck(WatchUi.loadResource(Rez.Strings.RunBeatOnlyHint) as String, true);
            WatchUi.requestUpdate();
            return;
        }
        var mean = _cadenceBuffer.mean(System.getTimer());
        if (mean == null) {
            // No usable cadence in the last 60 s (not running / just started).
            _state.setAck(WatchUi.loadResource(Rez.Strings.NoCadenceHint) as String, true);
            WatchUi.requestUpdate();
            return;
        }
        _messages.sendCadenceUseCurrent(mean as Float);
    }

    private function maxNum(a, b) {
        return a > b ? a : b;
    }

    private function minNum(a, b) {
        return a < b ? a : b;
    }
}

class CadenceViewMenuDelegate extends WatchUi.MenuInputDelegate {

    private var _view;      // CadenceView
    private var _messages;  // MessageHandler
    private var _state;     // WatchState

    function initialize(view, messages, state) {
        MenuInputDelegate.initialize();
        _view = view;
        _messages = messages;
        _state = state;
    }

    function onMenuItem(item as Symbol) as Void {
        if (!_state.isRunBeatActive()) {
            _state.setAck(WatchUi.loadResource(Rez.Strings.RunBeatOnlyHint) as String, true);
            WatchUi.requestUpdate();
            return;
        }
        switch (item) {
            case :cadence_plus:
                _messages.sendCadenceDelta(1);
                break;
            case :cadence_minus:
                _messages.sendCadenceDelta(-1);
                break;
            case :cadence_use_current:
                _view.useCurrentAsTarget();
                break;
            case :speed_correction:
                _messages.sendSpeedCorrectionToggle();
                break;
        }
    }
}
