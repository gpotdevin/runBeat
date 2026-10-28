import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// Base for the pushed full-screen value adjusters (volume, target
// cadence): tapping the upper half raises the value by one step, the
// lower half lowers it, and the center displays the value with a
// caption. The screen pops back to the page it was opened from 3 s
// after the last interaction.
//
// The auto-pop is driven by the app's existing 1 Hz cadence tick
// (RunBeatCompanionApp.onCadenceTick -> checkAutoPop) via the
// WatchState.adjustScreen slot: CIQ hosts expose very few concurrent
// Timers (default 3; "an error will occur if too many timers are set")
// and the app already runs two repeating ones, so this screen must NOT
// start a Timer of its own.
class AdjustScreenView extends WatchUi.View {

    var _state;                  // WatchState
    private var _screenH = 0;     // px, remembered from the last onUpdate
    private var _armed = false;  // true while the auto-return clock runs
    private var _lastTap = 0;     // ms timestamp of the last interaction

    function initialize(state) {
        View.initialize();
        _state = state;
    }

    // Subclass hook: the big center value ("12", "172", "--").
    function valueText() as String {
        return "";
    }

    // Subclass hook: the caption above the value ("VOLUME", "TARGET").
    function captionText() as String {
        return "";
    }

    // Subclass hook: optional line under the value; empty = not drawn.
    function secondaryText() as String {
        return "";
    }

    // Called by the pushing page right before pushView: arms the
    // auto-return clock. onShow() is NOT relied on — it does not fire
    // reliably for pushed views on all hosts.
    function arm() as Void {
        _armed = true;
        _lastTap = System.getTimer();
    }

    // Called by the delegate when the user leaves the screen (back) and
    // by onHide(): stops the auto-return and clears the registry slot.
    function disarm() as Void {
        _armed = false;
        _state.adjustScreen = null;
    }

    function onShow() as Void {
        arm();
    }

    function onHide() as Void {
        disarm();
    }

    // Called by the app's 1 Hz tick while this screen is the active one
    // (WatchState.adjustScreen is set by the pushing page and cleared in
    // disarm()). Pops 3 s after the last interaction.
    function checkAutoPop() as Void {
        if (_armed && System.getTimer() - _lastTap >= 3000) {
            disarm();
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        }
    }

    // The delegate calls this on every tap: restarts the auto-pop clock.
    function noteTap() as Void {
        _lastTap = System.getTimer();
    }

    // Half of the screen height — the tap-direction boundary. Remembered
    // from the last onUpdate (the delegate has no Dc).
    function halfHeight() as Number {
        return _screenH / 2;
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        _screenH = h;

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        // + / - hints at the top and bottom edges: the two tap zones.
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.09, Graphics.FONT_MEDIUM, "+",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.drawText(w / 2, h * 0.91, Graphics.FONT_MEDIUM, "-",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
        dc.drawLine(0, h / 2, w, h / 2);

        // Caption above, big value in the middle, optional line under it.
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.33, Graphics.FONT_TINY, captionText(),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h * 0.45, Graphics.FONT_LARGE, valueText(),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var secondary = secondaryText();
        if (secondary.length() > 0) {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, h * 0.57, Graphics.FONT_TINY, secondary,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }
}

// Delegate for the pushed adjust screens: BehaviorDelegate with the
// same dispatch chain as the page delegates (proven on the real watch):
// a tap maps to the Selection behavior first, onSelect returns false,
// and the tap continues to onTap() with its coordinates. A plain
// InputDelegate did NOT receive taps in this pushed context. Upper
// half = +1, lower half = -1; every tap restarts the auto-pop clock.
class AdjustScreenDelegate extends WatchUi.BehaviorDelegate {

    protected var _view;        // AdjustScreenView
    protected var _messages;    // MessageHandler

    function initialize(view, messages) {
        BehaviorDelegate.initialize();
        _view = view;
        _messages = messages;
    }

    // Let the tap fall through to onTap() (BehaviorDelegate translates
    // taps into the Selection behavior first).
    function onSelect() as Boolean {
        return false;
    }

    // Back (button or right-swipe): disarm the auto-return and let the
    // system pop the screen.
    function onBack() as Boolean {
        _view.disarm();
        return false;
    }

    function onTap(clickEvent as WatchUi.ClickEvent) as Boolean {
        var coords = clickEvent.getCoordinates();
        _view.noteTap();
        applyDelta((coords[1] as Number) < _view.halfHeight() ? 1 : -1);
        return true;
    }

    // Subclass hook: send the +1/-1 command.
    function applyDelta(delta as Number) as Void {
    }
}
