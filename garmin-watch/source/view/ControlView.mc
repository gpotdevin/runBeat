using Toybox.Graphics;
using Toybox.WatchUi;

// Screen 3 — controls.
// Speed-correction toggle (RunBeat only, shows on/off), volume -/+ (always
// enabled, sends volume_delta), "mark as not for running" (RunBeat only;
// the ack shows as a transient confirmation). RunBeat-only controls are
// greyed out when source != "RunBeat".
class ControlView extends BaseView {

    function initialize(state, messages) {
        BaseView.initialize(state, messages, Rez.Layouts.ControlLayout, Rez.Layouts.ControlLayoutRound);
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var runBeat = _state.isRunBeatActive();
        var active = Graphics.COLOR_WHITE;
        var dim = Graphics.COLOR_DARK_GRAY;
        var label = Graphics.COLOR_LIGHT_GRAY;

        dc.setColor(Graphics.COLOR_BLACK);
        dc.clear();

        updateHeader(Rez.Strings.HeaderControls, runBeat ? label : dim);

        // Row 1: speed correction (RunBeat only).
        dc.setColor(runBeat ? label : dim);
        dc.drawText(w * 0.12, h * 0.32, Graphics.FONT_TINY, Rez.Strings.SpeedCorrLabel,
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        var speedText = runBeat ? (_state.speedCorrection ? Rez.Strings.On : Rez.Strings.Off) : Rez.Strings.NoValue;
        dc.setColor(runBeat ? active : dim);
        dc.drawText(w * 0.88, h * 0.32, Graphics.FONT_MEDIUM, speedText,
            Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);

        // Row 2: volume (always enabled — STREAM_MUSIC on the phone).
        dc.setColor(label);
        dc.drawText(w * 0.12, h * 0.48, Graphics.FONT_TINY, Rez.Strings.VolumeLabel,
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        var volText = _state.volumeText();
        if (volText.length() == 0) {
            volText = Rez.Strings.NoValue;
        }
        dc.setColor(active);
        dc.drawText(w * 0.88, h * 0.48, Graphics.FONT_MEDIUM, volText,
            Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);

        // Row 3: mark the current track "not for running" (RunBeat only).
        dc.setColor(runBeat ? active : dim);
        dc.drawText(w / 2, h * 0.64, Graphics.FONT_TINY, Rez.Strings.MarkLabel,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        drawAck(dc);
        drawPageIndicator(dc, 3, 3);
    }

    // Select: toggle speed correction (RunBeat only).
    function onSelectPressed() as Void {
        if (!_state.isRunBeatActive()) {
            _state.setAck(Rez.Strings.RunBeatOnlyHint, true);
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

    function onMenuItem(item as WatchUi.MenuItem) as Void {
        switch (item.getId()) {
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
        _state.setAck(Rez.Strings.RunBeatOnlyHint, true);
        WatchUi.requestUpdate();
        return false;
    }
}
