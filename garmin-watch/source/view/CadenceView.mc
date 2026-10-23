using Toybox.Graphics;
using Toybox.System;
using Toybox.WatchUi;

// Screen 2 — cadence (RunBeat only; greyed out when source != "RunBeat").
// Big live 60-second average cadence, target cadence from phone state.
// Select = "use current as target"; menu = +1 / -1 / use current.
class CadenceView extends BaseView {

    private var _cadenceBuffer;   // CadenceRingBuffer

    function initialize(state, messages, cadenceBuffer) {
        BaseView.initialize(state, messages, Rez.Layouts.CadenceLayout, Rez.Layouts.CadenceLayoutRound);
        _cadenceBuffer = cadenceBuffer;
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var runBeat = _state.isRunBeatActive();
        var dim = Graphics.COLOR_DARK_GRAY;

        dc.setColor(Graphics.COLOR_BLACK);
        dc.clear();

        updateHeader(Rez.Strings.HeaderCadence, runBeat ? Graphics.COLOR_LIGHT_GRAY : dim);

        // Big live 60-second average (greyed out unless RunBeat is playing).
        var mean = _cadenceBuffer.mean(System.getTimer());
        var meanText = mean == null ? Rez.Strings.NoValue : (mean as Float).format("%.0f");
        dc.setColor(runBeat ? Graphics.COLOR_WHITE : dim);
        dc.drawText(w / 2, h * 0.40, Graphics.FONT_LARGE, meanText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        dc.setColor(Graphics.COLOR_LIGHT_GRAY);
        dc.drawText(w / 2, h * 0.52, Graphics.FONT_TINY, Rez.Strings.AvgCaption,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Target cadence from the phone state.
        var target = _state.targetCadenceText();
        if (target.length() == 0) {
            target = Rez.Strings.NoValue;
        }
        dc.setColor(runBeat ? Graphics.COLOR_LIGHT_GRAY : dim);
        dc.drawText(w / 2, h * 0.63, Graphics.FONT_SMALL,
            Rez.Strings.TargetPrefix + " " + target,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        if (!runBeat) {
            dc.setColor(dim);
            dc.drawText(w / 2, h * 0.72, Graphics.FONT_TINY, Rez.Strings.RunBeatOnlyHint,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        drawAck(dc);
        drawPageIndicator(dc, 2, 3);
    }

    // Select: use the current 60 s average as the target cadence.
    function onSelectPressed() as Void {
        useCurrentAsTarget();
    }

    // Sends cadence_use_current with the ring-buffer mean. Guarded: RunBeat
    // only, and only when a usable mean exists.
    function useCurrentAsTarget() as Void {
        if (!_state.isRunBeatActive()) {
            _state.setAck(Rez.Strings.RunBeatOnlyHint, true);
            WatchUi.requestUpdate();
            return;
        }
        var mean = _cadenceBuffer.mean(System.getTimer());
        if (mean == null) {
            // No usable cadence in the last 60 s (not running / just started).
            _state.setAck(Rez.Strings.NoCadenceHint, true);
            WatchUi.requestUpdate();
            return;
        }
        _messages.sendCadenceUseCurrent(mean as Float);
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

    function onMenuItem(item as WatchUi.MenuItem) as Void {
        if (!_state.isRunBeatActive()) {
            _state.setAck(Rez.Strings.RunBeatOnlyHint, true);
            WatchUi.requestUpdate();
            return;
        }
        switch (item.getId()) {
            case :cadence_plus:
                _messages.sendCadenceDelta(1);
                break;
            case :cadence_minus:
                _messages.sendCadenceDelta(-1);
                break;
            case :cadence_use_current:
                _view.useCurrentAsTarget();
                break;
        }
    }
}
