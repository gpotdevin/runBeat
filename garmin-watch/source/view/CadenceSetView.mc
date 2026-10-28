import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// Pushed target-cadence adjuster (RunBeat only; the cadence page guards
// the entry): upper half of the screen +1 spm, lower half -1 spm; the
// center displays the current target cadence, the secondary line the
// live 60 s average. Each tap sends cadence_delta and bumps the displayed
// target immediately (optimistic, clamped to the phone's 150..195 spm
// bounds); the phone applies it, acks and pushes the updated state, which
// reconciles the number. Auto-pops 3 s after the last tap (AdjustScreenView).
class CadenceSetView extends AdjustScreenView {

    private var _cadenceBuffer;   // CadenceRingBuffer

    function initialize(state, cadenceBuffer) {
        AdjustScreenView.initialize(state);
        _cadenceBuffer = cadenceBuffer;
    }

    function valueText() as String {
        var target = _state.targetCadenceText();
        if (target.length() == 0) {
            return WatchUi.loadResource(Rez.Strings.NoValue) as String;
        }
        return target;
    }

    function captionText() as String {
        return WatchUi.loadResource(Rez.Strings.TargetPrefix) as String;
    }

    function secondaryText() as String {
        var mean = _cadenceBuffer.mean(System.getTimer());
        if (mean == null) {
            return "";
        }
        return WatchUi.loadResource(Rez.Strings.AvgCaption) as String + " " + (mean as Float).format("%.0f");
    }

    // Optimistic bump: the displayed target moves by delta right away
    // instead of waiting for the phone's next state push (which stays
    // the source of truth and reconciles the authoritative value).
    function bumpTarget(delta as Number) as Void {
        _state.bumpTargetCadence(delta);
        WatchUi.requestUpdate();
    }
}

// Sends cadence_delta +1/-1 per tap and bumps the displayed target
// optimistically.
class CadenceSetDelegate extends AdjustScreenDelegate {

    function initialize(view, messages) {
        AdjustScreenDelegate.initialize(view, messages);
    }

    function applyDelta(delta as Number) as Void {
        _messages.sendCadenceDelta(delta);
        _view.bumpTarget(delta);
    }
}
