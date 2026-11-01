import Toybox.Application;
import Toybox.Graphics;
import Toybox.Math;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// Common behavior for the four pages: header label (three connection
// states), transient ack line, page indicator, text truncation, drawn-
// button hit-testing, the pushed volume adjuster. Each subclass picks
// its square/round layout in onLayout().
class BaseView extends WatchUi.View {

    var _state;                 // WatchState
    var _messages;              // MessageHandler

    // Cached resources (battery/CPU: WatchUi.loadResource() hits the file
    // system and must not run in the onUpdate draw path — resources are
    // constant, so they are loaded once here).
    private var _strConnecting as String;
    private var _strPhoneNotConnected as String;

    function initialize(state, messages) {
        View.initialize();
        _state = state;
        _messages = messages;
        _strConnecting = WatchUi.loadResource(Rez.Strings.Connecting) as String;
        _strPhoneNotConnected = WatchUi.loadResource(Rez.Strings.PhoneNotConnected) as String;
    }

    // Visibility hooks (battery): while no page is visible (backlight off,
    // app covered, another view pushed) the app suspends all periodic
    // work (cadence sampler + 5 s state timer) via
    // RunBeatCompanionApp.onAppVisible; it resumes on return. Subclasses
    // overriding onShow must call BaseView.onShow() (PlaylistsView does).
    function onShow() as Void {
        // A page is visible again (initial show, page change, or an overlay
        // popped): no menu is on top anymore.
        _state.menuOpen = false;
        notifyVisibility(true);
    }

    function onHide() as Void {
        notifyVisibility(false);
    }

    private function notifyVisibility(visible as Boolean) as Void {
        var app = Application.getApp();
        if (app instanceof RunBeatCompanionApp) {
            (app as RunBeatCompanionApp).onAppVisible(visible);
        }
    }

    // Opens the pushed volume adjuster: upper half of the screen raises
    // the volume, the lower half lowers it, the center displays the
    // current value; it auto-returns 3 s after the last tap (driven by
    // the app's 1 Hz tick through WatchState.adjustScreen). Volume is
    // the global STREAM_MUSIC volume: it works for RunBeat and external
    // apps alike, so no source gating here.
    function pushVolumeAdjustScreen() as Void {
        var view = new VolumeSetView(_state);
        view.arm();
        _state.adjustScreen = view;
        WatchUi.pushView(view, new VolumeSetDelegate(view, _messages), WatchUi.SLIDE_IMMEDIATE);
    }

    // Hit-tests one drawn button rect [x, y, w, h] from the last onUpdate.
    function hitTest(rect, x as Number, y as Number) as Boolean {
        if (rect == null) {
            return false;
        }
        var r = rect as Array;
        var rx = r[0] as Number;
        var ry = r[1] as Number;
        var rw = r[2] as Number;
        var rh = r[3] as Number;
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    // Header label, one of three connection states (from WatchState):
    //   - no message ever received  -> "Connecting..." (yellow)
    //   - last message older than 10 s -> "Phone not connected" (yellow)
    //   - otherwise the given text/color.
    // All three pages inherit this.
    function updateHeader(normalText as String, normalColor as Number) as Void {
        if (!_state.hasConnected()) {
            setHeaderLabel(_strConnecting, Graphics.COLOR_YELLOW);
        } else if (_state.isStale(System.getTimer())) {
            setHeaderLabel(_strPhoneNotConnected, Graphics.COLOR_YELLOW);
        } else {
            setHeaderLabel(normalText, normalColor);
        }
    }

    // Transient confirmation line, visible for ACK_VISIBLE_MS after it
    // was set (the app's 1 Hz tick repaints once at expiry).
    function drawAck(dc as Dc) as Void {
        _state.clearExpiredAck(System.getTimer(), WatchState.ACK_VISIBLE_MS);
        if (_state.ackText.length() == 0) {
            return;
        }
        dc.setColor(_state.ackIsError ? Graphics.COLOR_RED : Graphics.COLOR_GREEN, Graphics.COLOR_BLACK);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() - 44, Graphics.FONT_SMALL,
            truncateToWidth(dc, _state.ackText, Graphics.FONT_SMALL, dc.getWidth() * 0.9),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function drawPageIndicator(dc as Dc, page as Number, total as Number) as Void {
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() - 22, Graphics.FONT_TINY,
            page.format("%d") + "/" + total.format("%d"),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function truncateToWidth(dc as Dc, text as String, font, maxWidth as Numeric) as String {
        if (dc.getTextWidthInPixels(text, font) <= maxWidth) {
            return text;
        }
        var ellipsis = "...";
        var trimmed = text;
        while (trimmed.length() > 0 && dc.getTextWidthInPixels(trimmed + ellipsis, font) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + ellipsis;
    }

    // Max text width at height y: the full width times maxFraction on
    // square screens; on round screens the chord of the circle at y, so
    // truncated text stays inside the bezel (shared by all pages).
    function textWidthAt(dc as Dc, y as Number, maxFraction as Float) as Number {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var maxW = w * maxFraction;
        if (isRound()) {
            var r = w / 2;
            var dy = y - h / 2;
            if (dy < 0) {
                dy = -dy;
            }
            if (dy < r) {
                var chord = 2 * Math.sqrt(r * r - dy * dy);
                if (chord < maxW) {
                    maxW = chord;
                }
            } else {
                maxW = 0.0;
            }
        }
        return maxW.toNumber();
    }

    function isRound() as Boolean {
        return System.getDeviceSettings().screenShape == System.SCREEN_SHAPE_ROUND;
    }

    private function setHeaderLabel(text as String, color as Number) as Void {
        var label = findDrawableById(:headerLabel);
        if (label instanceof WatchUi.Text) {
            var t = label as WatchUi.Text;
            t.setText(text);
            t.setColor(color);
        }
    }
}
