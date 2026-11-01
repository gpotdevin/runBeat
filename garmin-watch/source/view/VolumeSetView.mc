import Toybox.Lang;
import Toybox.WatchUi;

// Pushed volume adjuster: upper half of the screen +1, lower half -1,
// center displays the current phone volume. Volume is the global
// STREAM_MUSIC volume — it works for RunBeat and external apps alike,
// so the screen is never source-gated. Auto-pops 3 s after the last
// tap (AdjustScreenView).
class VolumeSetView extends AdjustScreenView {

    // Cached resources (loadResource() hits the file system; these hooks
    // run from the onUpdate draw path — loaded once here).
    private var _strNoValue as String;
    private var _strVolumeLabel as String;

    function initialize(state) {
        AdjustScreenView.initialize(state);
        _strNoValue = WatchUi.loadResource(Rez.Strings.NoValue) as String;
        _strVolumeLabel = WatchUi.loadResource(Rez.Strings.VolumeLabel) as String;
    }

    function valueText() as String {
        var vol = _state.volumeText();
        if (vol.length() == 0) {
            return _strNoValue;
        }
        return vol;
    }

    function captionText() as String {
        return _strVolumeLabel;
    }
}

// Sends volume_delta +1/-1 per tap.
class VolumeSetDelegate extends AdjustScreenDelegate {

    function initialize(view, messages) {
        AdjustScreenDelegate.initialize(view, messages);
    }

    function applyDelta(delta as Number) as Void {
        _messages.sendVolumeDelta(delta);
    }
}
