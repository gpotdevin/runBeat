import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// Cadence page (RunBeat only; greyed out when source != "RunBeat").
// Big live 60-second average cadence, target cadence from phone state,
// and three stacked action buttons — one per action, so it is obvious
// where to tap:
//   [SET TARGET]         opens the pushed target-cadence adjuster
//                         (upper half +1 spm, lower half -1 spm)
//   [USE AVG AS TARGET]  sends the live 60 s average as the target
//   [TEMPO ON/OFF]       toggles speed correction (playback-tempo
//                         adjustment)
// Select also uses the current average as target; the menu keeps +1/-1,
// use current and speed correction for button devices.
class CadenceView extends BaseView {

    private var _cadenceBuffer;   // CadenceRingBuffer

    // Drawn button rects [x, y, w, h] from the last onUpdate (tap hit-testing).
    private var _btnSetTarget;     // Array? or null
    private var _btnUseAvg;        // Array? or null
    private var _btnTempo;         // Array? or null
    private var _btnFixBpm;       // Array? or null

    // Cached resources (battery/CPU: loadResource() hits the file system
    // and must not run in the onUpdate draw path — loaded once here).
    private var _strHeaderCadence as String;
    private var _strNoValue as String;
    private var _strAvgCaption as String;
    private var _strTargetPrefix as String;
    private var _strSetTargetLabel as String;
    private var _strUseAvgAsTargetLabel as String;
    private var _strTempoLabel as String;
    private var _strFixBpmLabel as String;
    private var _strOn as String;
    private var _strOff as String;
    private var _strRunBeatOnlyHint as String;
    private var _strNoCadenceHint as String;

    function initialize(state, messages, cadenceBuffer) {
        BaseView.initialize(state, messages);
        _cadenceBuffer = cadenceBuffer;
        _strHeaderCadence = WatchUi.loadResource(Rez.Strings.HeaderCadence) as String;
        _strNoValue = WatchUi.loadResource(Rez.Strings.NoValue) as String;
        _strAvgCaption = WatchUi.loadResource(Rez.Strings.AvgCaption) as String;
        _strTargetPrefix = WatchUi.loadResource(Rez.Strings.TargetPrefix) as String;
        _strSetTargetLabel = WatchUi.loadResource(Rez.Strings.SetTargetLabel) as String;
        _strUseAvgAsTargetLabel = WatchUi.loadResource(Rez.Strings.UseAvgAsTargetLabel) as String;
        _strTempoLabel = WatchUi.loadResource(Rez.Strings.TempoLabel) as String;
        _strFixBpmLabel = WatchUi.loadResource(Rez.Strings.FixBpmLabel) as String;
        _strOn = WatchUi.loadResource(Rez.Strings.On) as String;
        _strOff = WatchUi.loadResource(Rez.Strings.Off) as String;
        _strRunBeatOnlyHint = WatchUi.loadResource(Rez.Strings.RunBeatOnlyHint) as String;
        _strNoCadenceHint = WatchUi.loadResource(Rez.Strings.NoCadenceHint) as String;
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

        updateHeader(_strHeaderCadence, runBeat ? Graphics.COLOR_LT_GRAY : dim);

        // Big live 60-second average (greyed out unless RunBeat is playing).
        var mean = _cadenceBuffer.mean(System.getTimer());
        var meanText = mean == null ? _strNoValue : (mean as Float).format("%.0f");
        dc.setColor(runBeat ? Graphics.COLOR_WHITE : dim, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.28, Graphics.FONT_LARGE, meanText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.37, Graphics.FONT_TINY, _strAvgCaption,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Target cadence from the phone state.
        var target = _state.targetCadenceText();
        if (target.length() == 0) {
            target = _strNoValue;
        }
        dc.setColor(runBeat ? Graphics.COLOR_LT_GRAY : dim, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.46, Graphics.FONT_SMALL,
            _strTargetPrefix + " " + target,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // --- Three stacked action buttons (RunBeat only): one visible
        // button per action, so it is obvious where to tap. The stack is
        // sized to the space between the target line and the page
        // indicator; without RunBeat the hint takes its place and taps
        // fall through to select (which shows the same hint).
        var fhSmall = dc.getFontHeight(Graphics.FONT_SMALL);
        var gap = maxNum(4, minNum(8, h * 0.02)).toNumber();
        var regionTop = maxNum(h * 0.57, h * 0.46 + fhSmall / 2 + 6).toNumber();
        var regionBottom = h - 32;
        var regionH = regionBottom - regionTop;
        var bH = (regionH - gap * 3) / 4;
        if (bH > 34) {
            bH = 34;
        }
        bH = bH.toNumber();
        var bW = minNum(w * 0.64, 300).toNumber();
        var bLeft = (w - bW) / 2;
        var totalH = bH * 4 + gap * 3;
        var bTop = (regionTop + regionBottom - totalH) / 2;
        bTop = bTop.toNumber();

        if (runBeat) {
            _btnSetTarget = [bLeft, bTop, bW, bH];
            _btnUseAvg = [bLeft, bTop + bH + gap, bW, bH];
            _btnTempo = [bLeft, bTop + (bH + gap) * 2, bW, bH];
            _btnFixBpm = [bLeft, bTop + (bH + gap) * 3, bW, bH];
            drawActionButton(dc, _btnSetTarget, _strSetTargetLabel, Graphics.COLOR_LT_GRAY);
            drawActionButton(dc, _btnUseAvg, _strUseAvgAsTargetLabel, Graphics.COLOR_LT_GRAY);
            var tempoText = _strTempoLabel + " " +
                (_state.speedCorrection ? _strOn : _strOff);
            drawActionButton(dc, _btnTempo, tempoText,
                _state.speedCorrection ? Graphics.COLOR_GREEN : Graphics.COLOR_LT_GRAY);
            drawActionButton(dc, _btnFixBpm, _strFixBpmLabel, Graphics.COLOR_LT_GRAY);
        } else {
            _btnSetTarget = null;
            _btnUseAvg = null;
            _btnTempo = null;
            _btnFixBpm = null;
            dc.setColor(dim, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, bTop + totalH / 2, Graphics.FONT_TINY,
                _strRunBeatOnlyHint,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        drawAck(dc);
        drawPageIndicator(dc, 3, 4);
    }

    // One rounded action button with a centered label (the label is
    // truncated to the button width as a safety net).
    private function drawActionButton(dc as Dc, rect as Array, text as String, textColor as Number) as Void {
        var x = rect[0] as Number;
        var y = rect[1] as Number;
        var bw = rect[2] as Number;
        var bh = rect[3] as Number;
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
        dc.fillRoundedRectangle(x, y, bw, bh, bh / 4);
        dc.setColor(textColor, Graphics.COLOR_BLACK);
        dc.drawText(x + bw / 2, y + bh / 2, Graphics.FONT_TINY,
            truncateToWidth(dc, text, Graphics.FONT_TINY, bw - 8),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // Select: use the current 60 s average as the target cadence.
    function onSelectPressed() as Void {
        useCurrentAsTarget();
    }

    // Tap handler for RunBeatPageDelegate: each of the three drawn buttons
    // performs its named action (SET TARGET opens the pushed target-cadence
    // adjuster, USE AVG AS TARGET sends the 60 s average as the target,
    // TEMPO toggles speed correction). All are RunBeat only — the buttons
    // are only drawn when RunBeat is active. Returns true when handled.
    function onTap(x as Number, y as Number) as Boolean {
        if (hitTest(_btnSetTarget, x, y)) {
            if (!runBeatGuard()) {
                return true;
            }
            var view = new CadenceSetView(_state, _cadenceBuffer);
            view.arm();
            _state.adjustScreen = view;
            WatchUi.pushView(view, new CadenceSetDelegate(view, _messages), WatchUi.SLIDE_IMMEDIATE);
            return true;
        }
        if (hitTest(_btnUseAvg, x, y)) {
            if (!runBeatGuard()) {
                return true;
            }
            useCurrentAsTarget();
            return true;
        }
        if (hitTest(_btnTempo, x, y)) {
            if (!runBeatGuard()) {
                return true;
            }
            _messages.sendSpeedCorrectionToggle();
            return true;
        }
        if (hitTest(_btnFixBpm, x, y)) {
            if (!runBeatGuard()) {
                return true;
            }
            fixBpmFromCadence();
            return true;
        }
        return false;
    }

    // Sends cadence_avg with the 60 s mean so the phone can back-calculate
    // the original track BPM (correctedBpm = cadence / (rhythmFactor x speed)).
    function fixBpmFromCadence() as Void {
        var mean = _cadenceBuffer.mean(System.getTimer());
        if (mean == null) {
            _state.setAck(_strNoCadenceHint, true);
            WatchUi.requestUpdate();
            return;
        }
        _messages.sendCadenceAvg(mean as Float);
    }

    // Guard for taps on the cadence-page actions while another source is
    // playing: shows the RunBeat-only hint. Returns true when the action
    // may proceed.
    private function runBeatGuard() as Boolean {
        if (!_state.isRunBeatActive()) {
            _state.setAck(_strRunBeatOnlyHint, true);
            WatchUi.requestUpdate();
            return false;
        }
        return true;
    }

    // Sends cadence_use_current with the ring-buffer mean. Guarded: RunBeat
    // only, and only when a usable mean exists.
    function useCurrentAsTarget() as Void {
        if (!_state.isRunBeatActive()) {
            _state.setAck(_strRunBeatOnlyHint, true);
            WatchUi.requestUpdate();
            return;
        }
        var mean = _cadenceBuffer.mean(System.getTimer());
        if (mean == null) {
            // No usable cadence in the last 60 s (not running / just started).
            _state.setAck(_strNoCadenceHint, true);
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
