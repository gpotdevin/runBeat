import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// Cadence page (RunBeat only; greyed out when source != "RunBeat").
// Center: live cadence (big), 60 s average, target cadence.
// Four quadrant buttons around the edges:
//   top-left     [SET TARGET]       opens the pushed target-cadence adjuster
//   top-right    [USE AVG AS TARGET] sends the live 60 s average as the target
//   bottom-left  [TEMPO ON/OFF]     toggles speed correction
//   bottom-right [FIX BPM]          sends cadence_avg for BPM back-calculation
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
        var now = System.getTimer();

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        updateHeader(_strHeaderCadence, runBeat ? Graphics.COLOR_LT_GRAY : dim);

        // --- Center info block, vertically centered as a whole -------------
        // Three lines: live cadence (big), AVG/60s, TARGET.
        // The block is centered on h/2, not its first line.
        var fhLarge = dc.getFontHeight(Graphics.FONT_LARGE);
        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);
        var gap = maxNum(2, (h * 0.012).toNumber()).toNumber();
        var blockH = fhLarge + fhTiny * 2 + gap * 2;
        var y = ((h - blockH) / 2).toNumber();

        var live = _cadenceBuffer.last(now);
        var liveText = live == null ? _strNoValue : (live as Float).format("%.0f");
        dc.setColor(runBeat ? Graphics.COLOR_WHITE : dim, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, y + fhLarge / 2, Graphics.FONT_LARGE, liveText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        y += fhLarge + gap;


        var mean = _cadenceBuffer.mean(now);
        var meanText = mean == null ? _strNoValue : (mean as Float).format("%.0f");
        dc.setColor(runBeat ? Graphics.COLOR_LT_GRAY : dim, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, y + fhTiny / 2, Graphics.FONT_TINY,
            _strAvgCaption + " " + meanText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        y += fhTiny + gap;

        var target = _state.targetCadenceText();
        if (target.length() == 0) {
            target = _strNoValue;
        }
        dc.setColor(runBeat ? Graphics.COLOR_GREEN : dim, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, y + fhTiny / 2, Graphics.FONT_TINY,
            _strTargetPrefix + " " + target,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // --- Four quadrant buttons (RunBeat only) -------------------------
        // True quadrants: top-left, top-right, bottom-left, bottom-right.
        // Pill-shaped, two-line labels so text stays visible at FONT_TINY.
        var bW = minNum(w * 0.38, 150).toNumber();
        var bH = minNum(52, h * 0.125).toNumber();
        if (bH < 36) { bH = 36; }
        var colL = (w * 0.27).toNumber();
        var colR = (w * 0.73).toNumber();
        var rowT = (h * 0.26).toNumber();
        var rowB = (h * 0.82).toNumber();

        if (runBeat) {
            _btnSetTarget = [colL - bW / 2, rowT - bH / 2, bW, bH];
            _btnUseAvg    = [colR - bW / 2, rowT - bH / 2, bW, bH];
            _btnTempo     = [colL - bW / 2, rowB - bH / 2, bW, bH];
            _btnFixBpm    = [colR - bW / 2, rowB - bH / 2, bW, bH];
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
            dc.drawText(w / 2, h * 0.82, Graphics.FONT_TINY,
                _strRunBeatOnlyHint,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        drawAck(dc);
        drawPageIndicator(dc, 3, 4);
    }

    // One rounded action button with a centered label (the label is
    // truncated to the button width as a safety net).
    // Pill-shaped button with a two-line label (split at the space nearest
    // the middle) so FONT_TINY text stays visible inside the quadrant width.
    private function drawActionButton(dc as Dc, rect as Array, text as String, textColor as Number) as Void {
        var x = rect[0] as Number;
        var y = rect[1] as Number;
        var bw = rect[2] as Number;
        var bh = rect[3] as Number;
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
        dc.fillRoundedRectangle(x, y, bw, bh, bh / 2);
        dc.setColor(textColor, Graphics.COLOR_BLACK);
        var fh = dc.getFontHeight(Graphics.FONT_TINY);
        var line1 = text;
        var line2 = "";
        var sp = splitNearMiddle(text);
        if (sp > 0) {
            line1 = text.substring(0, sp);
            line2 = text.substring(sp + 1, text.length());
        }
        var l1 = truncateToWidth(dc, line1, Graphics.FONT_TINY, bw - 8);
        var l2 = line2.length() > 0 ? truncateToWidth(dc, line2, Graphics.FONT_TINY, bw - 8) : "";
        if (l2.length() > 0) {
            dc.drawText(x + bw / 2, y + bh / 2 - fh / 2, Graphics.FONT_TINY, l1,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.drawText(x + bw / 2, y + bh / 2 + fh / 2, Graphics.FONT_TINY, l2,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            dc.drawText(x + bw / 2, y + bh / 2, Graphics.FONT_TINY, l1,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    // Index of the space nearest the middle of the string, or -1 if none.
    private function splitNearMiddle(text as String) as Number {
        var mid = text.length() / 2;
        var best = -1;
        var bestDist = text.length();
        for (var i = 0; i < text.length(); i++) {
            if (text.substring(i, i + 1).equals(" ")) {
                var d = i - mid;
                if (d < 0) { d = -d; }
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
        }
        return best;
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
