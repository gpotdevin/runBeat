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

    // Cached resources (loadResource() hits the file system; these hooks
    // run from the onUpdate draw path — loaded once here).
    private var _strNoValue as String;
    private var _strTargetPrefix as String;
    private var _strAvgCaption as String;

    function initialize(state, cadenceBuffer) {
        AdjustScreenView.initialize(state);
        _cadenceBuffer = cadenceBuffer;
        _strNoValue = WatchUi.loadResource(Rez.Strings.NoValue) as String;
        _strTargetPrefix = WatchUi.loadResource(Rez.Strings.TargetPrefix) as String;
        _strAvgCaption = WatchUi.loadResource(Rez.Strings.AvgCaption) as String;
    }

    function valueText() as String {
        var target = _state.targetCadenceText();
        if (target.length() == 0) {
            return _strNoValue;
        }
        return target;
    }

    function captionText() as String {
        return _strTargetPrefix;
    }

    function secondaryText() as String {
        var mean = _cadenceBuffer.mean(System.getTimer());
        if (mean == null) {
            return "";
        }
        return _strAvgCaption + " " + (mean as Float).format("%.0f");
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
