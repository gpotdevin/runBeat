import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// Screen 2 — cadence (RunBeat only; greyed out when source != "RunBeat").
// Big live 60-second average cadence, target cadence from phone state.
// Select = "use current as target"; menu = +1 / -1 / use current.
class CadenceView extends BaseView {

    private var _cadenceBuffer;   // CadenceRingBuffer

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
        dc.drawText(w / 2, h * 0.40, Graphics.FONT_LARGE, meanText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.52, Graphics.FONT_TINY, WatchUi.loadResource(Rez.Strings.AvgCaption) as String,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Target cadence from the phone state.
        var target = _state.targetCadenceText();
        if (target.length() == 0) {
            target = WatchUi.loadResource(Rez.Strings.NoValue) as String;
        }
        dc.setColor(runBeat ? Graphics.COLOR_LT_GRAY : dim, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.63, Graphics.FONT_SMALL,
            WatchUi.loadResource(Rez.Strings.TargetPrefix) as String + " " + target,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        if (!runBeat) {
            dc.setColor(dim, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, h * 0.72, Graphics.FONT_TINY, WatchUi.loadResource(Rez.Strings.RunBeatOnlyHint) as String,
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
        }
    }
}
