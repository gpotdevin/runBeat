import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Screen 3 — controls.
// Speed-correction toggle (RunBeat only, shows on/off), volume -/+ (always
// enabled, sends volume_delta), "mark as not for running" (RunBeat only;
// the ack shows as a transient confirmation). RunBeat-only controls are
// greyed out when source != "RunBeat".
class ControlView extends BaseView {

    function initialize(state, messages) {
        BaseView.initialize(state, messages);
    }

    function onLayout(dc as Dc) as Void {
        if (isRound()) {
            setLayout(Rez.Layouts.ControlLayoutRound(dc));
        } else {
            setLayout(Rez.Layouts.ControlLayout(dc));
        }
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var runBeat = _state.isRunBeatActive();
        var active = Graphics.COLOR_WHITE;
        var dim = Graphics.COLOR_DK_GRAY;
        var label = Graphics.COLOR_LT_GRAY;

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        updateHeader(WatchUi.loadResource(Rez.Strings.HeaderControls) as String, runBeat ? label : dim);

        // Row 1: speed correction (RunBeat only).
        dc.setColor(runBeat ? label : dim, Graphics.COLOR_BLACK);
        dc.drawText(w * 0.12, h * 0.32, Graphics.FONT_TINY, WatchUi.loadResource(Rez.Strings.SpeedCorrLabel) as String,
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        var speedText = runBeat ? (_state.speedCorrection ? WatchUi.loadResource(Rez.Strings.On) as String : WatchUi.loadResource(Rez.Strings.Off) as String) : WatchUi.loadResource(Rez.Strings.NoValue) as String;
        dc.setColor(runBeat ? active : dim, Graphics.COLOR_BLACK);
        dc.drawText(w * 0.88, h * 0.32, Graphics.FONT_MEDIUM, speedText,
            Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);

        // Row 2: volume (always enabled — STREAM_MUSIC on the phone).
        dc.setColor(label, Graphics.COLOR_BLACK);
        dc.drawText(w * 0.12, h * 0.48, Graphics.FONT_TINY, WatchUi.loadResource(Rez.Strings.VolumeLabel) as String,
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        var volText = _state.volumeText();
        if (volText.length() == 0) {
            volText = WatchUi.loadResource(Rez.Strings.NoValue) as String;
        }
        dc.setColor(active, Graphics.COLOR_BLACK);
        dc.drawText(w * 0.88, h * 0.48, Graphics.FONT_MEDIUM, volText,
            Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);

        // Row 3: mark the current track "not for running" (RunBeat only).
        dc.setColor(runBeat ? active : dim, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.64, Graphics.FONT_TINY, WatchUi.loadResource(Rez.Strings.MarkLabel) as String,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        drawAck(dc);
        drawPageIndicator(dc, 3, 3);
    }

    // Select: toggle speed correction (RunBeat only).
    function onSelectPressed() as Void {
        if (!_state.isRunBeatActive()) {
            _state.setAck(WatchUi.loadResource(Rez.Strings.RunBeatOnlyHint) as String, true);
            WatchUi.requestUpdate();
            return;
        }
        _messages.sendSpeedCorrectionToggle();
    }
}

class ControlViewMenuDelegate extends WatchUi.MenuInputDelegate {

    private var _messages;   // MessageHandler
    private var _state;      // WatchState

    function initialize(messages, state) {
        MenuInputDelegate.initialize();
        _messages = messages;
        _state = state;
    }

    function onMenuItem(item as Symbol) as Void {
        switch (item) {
            case :speed_correction:
                if (!requireRunBeat()) {
                    return;
                }
                _messages.sendSpeedCorrectionToggle();
                break;
            case :volume_down:
                // Always enabled: STREAM_MUSIC works for RunBeat and external apps.
                _messages.sendVolumeDelta(-1);
                break;
            case :volume_up:
                _messages.sendVolumeDelta(1);
                break;
            case :mark_not_for_running:
                if (!requireRunBeat()) {
                    return;
                }
                _messages.sendMarkNotForRunning();
                break;
        }
    }

    private function requireRunBeat() as Boolean {
        if (_state.isRunBeatActive()) {
            return true;
        }
        _state.setAck(WatchUi.loadResource(Rez.Strings.RunBeatOnlyHint) as String, true);
        WatchUi.requestUpdate();
        return false;
    }
}
