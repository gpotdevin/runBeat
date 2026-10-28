import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Controls page: the volume entry. One big drawn button shows the
// current volume and opens the pushed volume adjuster (upper half +,
// lower half -, auto-return). Volume is the global STREAM_MUSIC volume —
// it works for RunBeat and external apps alike, so nothing here is
// source-gated. Select does the same as the button (tap = select).
class ControlView extends BaseView {

    // Drawn button rect [x, y, w, h] from the last onUpdate (tap hit-testing).
    private var _btnVolume;   // Array? or null

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
        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        updateHeader(WatchUi.loadResource(Rez.Strings.HeaderControls) as String, Graphics.COLOR_LT_GRAY);

        // Volume entry: caption above, one big button with the value,
        // "+ / -" hint below (tapping the button — or anywhere — opens
        // the pushed adjuster).
        var bW = minNum(w * 0.72, 300).toNumber();
        var bH = maxNum(40, minNum(80, h * 0.19)).toNumber();
        var bLeft = (w - bW) / 2;
        var bTop = (h * 0.40).toNumber();
        var bCenterY = bTop + bH / 2;
        _btnVolume = [bLeft, bTop, bW, bH];

        var volText = _state.volumeText();
        if (volText.length() == 0) {
            volText = WatchUi.loadResource(Rez.Strings.NoValue) as String;
        }

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, bTop - fhTiny / 2 - 6, Graphics.FONT_TINY, WatchUi.loadResource(Rez.Strings.VolumeLabel) as String,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
        dc.fillRoundedRectangle(bLeft, bTop, bW, bH, 16);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, bCenterY, Graphics.FONT_LARGE, volText,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        // The button opens the pushed adjuster — hint at the tap zones.
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, bTop + bH + fhTiny / 2 + 6, Graphics.FONT_TINY, "+ / -",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        drawAck(dc);
        drawPageIndicator(dc, 2, 3);
    }

    // Select opens the volume adjuster, same as tapping the button.
    function onSelectPressed() as Void {
        pushVolumeAdjustScreen();
    }

    // Tap handler for RunBeatPageDelegate: the volume button opens the
    // pushed volume adjuster. Returns true when handled.
    function onTap(x as Number, y as Number) as Boolean {
        if (hitTest(_btnVolume, x, y)) {
            pushVolumeAdjustScreen();
            return true;
        }
        return false;
    }

    private function maxNum(a, b) {
        return a > b ? a : b;
    }

    private function minNum(a, b) {
        return a < b ? a : b;
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
            // Volume always works: STREAM_MUSIC applies to RunBeat and
            // external apps alike. For non-touch devices without access
            // to the pushed adjuster.
            case :volume_down:
                _messages.sendVolumeDelta(-1);
                break;
            case :volume_up:
                _messages.sendVolumeDelta(1);
                break;
        }
    }
}
