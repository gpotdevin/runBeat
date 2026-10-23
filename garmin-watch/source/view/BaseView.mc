using Toybox.Graphics;
using Toybox.System;
using Toybox.WatchUi;

// Common behavior for the three pages: layout selection (square/round),
// header label (with "Phone not connected" state), transient ack line,
// page indicator, text truncation.
class BaseView extends WatchUi.View {

    var _state;                 // WatchState
    var _messages;              // MessageHandler
    private var _layoutSquare;  // Rez.Layouts symbol
    private var _layoutRound;   // Rez.Layouts symbol

    function initialize(state, messages, layoutSquare, layoutRound) {
        View.initialize();
        _state = state;
        _messages = messages;
        _layoutSquare = layoutSquare;
        _layoutRound = layoutRound;
    }

    function onShow() as Void {
        if (System.getDeviceSettings().screenShape == System.SCREEN_SHAPE_ROUND) {
            WatchUi.setLayout(_layoutRound);
        } else {
            WatchUi.setLayout(_layoutSquare);
        }
    }

    // Header label: shows "Phone not connected" while the phone is
    // unreachable, otherwise the given text/color.
    function updateHeader(normalText as String, normalColor as Number) as Void {
        if (!_state.phoneReachable) {
            setHeaderLabel(Rez.Strings.PhoneNotConnected, Graphics.COLOR_YELLOW);
        } else {
            setHeaderLabel(normalText, normalColor);
        }
    }

    // Transient confirmation line, visible for ~2.5 s after it was set.
    function drawAck(dc as Dc) as Void {
        _state.clearExpiredAck(System.getTimer(), 2500);
        if (_state.ackText.length() == 0) {
            return;
        }
        dc.setColor(_state.ackIsError ? Graphics.COLOR_RED : Graphics.COLOR_GREEN);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() - 44, Graphics.FONT_SMALL,
            truncateToWidth(dc, _state.ackText, Graphics.FONT_SMALL, dc.getWidth() * 0.9),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function drawPageIndicator(dc as Dc, page as Number, total as Number) as Void {
        dc.setColor(Graphics.COLOR_LIGHT_GRAY);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() - 22, Graphics.FONT_TINY,
            page.format("%d") + "/" + total.format("%d"),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function truncateToWidth(dc as Dc, text as String, font, maxWidth as Number) as String {
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
