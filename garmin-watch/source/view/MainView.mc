using Toybox.Graphics;
using Toybox.WatchUi;

// Screen 1 — transport. Works for RunBeat AND external apps:
// source label, track title (truncated), play/pause icon from state.
// Select = toggle; menu = next/previous.
class MainView extends BaseView {

    function initialize(state, messages) {
        BaseView.initialize(state, messages, Rez.Layouts.MainLayout, Rez.Layouts.MainLayoutRound);
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();

        dc.setColor(Graphics.COLOR_BLACK);
        dc.clear();

        // Header: where the audio is coming from (or "Phone not connected").
        updateHeader(sourceLabelText(), sourceLabelColor());

        // Play/pause icon.
        var iconH = isRound() ? 26 : 34;
        var iconCenterY = h * 0.40;
        dc.setColor(_state.isPlaying ? Graphics.COLOR_GREEN : Graphics.COLOR_WHITE);
        if (_state.isPlaying) {
            drawPauseIcon(dc, w / 2, iconCenterY, iconH);
        } else {
            drawPlayIcon(dc, w / 2, iconCenterY, iconH);
        }

        // Track title (truncated to fit).
        var title = _state.title.length() > 0 ? _state.title : Rez.Strings.NoValue;
        dc.setColor(Graphics.COLOR_WHITE);
        dc.drawText(w / 2, h * 0.62, Graphics.FONT_MEDIUM,
            truncateToWidth(dc, title, Graphics.FONT_MEDIUM, w * 0.84),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // RunBeat metadata: BPM and playback speed.
        var meta = metaText();
        if (meta.length() > 0) {
            dc.setColor(Graphics.COLOR_LIGHT_GRAY);
            dc.drawText(w / 2, h * 0.71, Graphics.FONT_TINY, meta,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        drawAck(dc);
        drawPageIndicator(dc, 1, 3);
    }

    // Select: toggle play/pause (works for RunBeat and external apps).
    function onSelectPressed() as Void {
        _messages.sendToggle();
    }

    private function sourceLabelText() as String {
        if (_state.source == "RunBeat") {
            return Rez.Strings.AppName;
        }
        if (_state.source.length() == 0 || _state.source == "none") {
            return Rez.Strings.NoPlayback;
        }
        if (_state.source == "external") {
            return Rez.Strings.ExternalApp;
        }
        // External app name pushed by the phone (optional notification access).
        return _state.source;
    }

    private function sourceLabelColor() as Number {
        if (_state.source == "RunBeat") {
            return Graphics.COLOR_GREEN;
        }
        return Graphics.COLOR_LIGHT_GRAY;
    }

    private function metaText() as String {
        if (!_state.isRunBeatActive()) {
            return "";
        }
        var parts = "";
        var bpm = _state.bpmText();
        if (bpm.length() > 0) {
            parts = bpm + Rez.Strings.BpmSuffix;
        }
        var speed = _state.playbackSpeedText();
        if (speed.length() > 0) {
            if (parts.length() > 0) {
                parts += "  ";
            }
            parts += speed + Rez.Strings.SpeedSuffix;
        }
        return parts;
    }

    // Right-pointing triangle (scanline-filled; sticks to fillRectangle,
    // which exists on every API level).
    private function drawPlayIcon(dc as Dc, centerX as Number, centerY as Number, h as Number) as Void {
        var iw = h * 0.8;
        var top = centerY - h / 2;
        var half = h / 2;
        for (var row = 0; row < h; row++) {
            var span = 0.0;
            if (row < half) {
                span = iw * row / half;
            } else {
                span = iw * (h - row) / half;
            }
            if (span > 0) {
                dc.fillRectangle(centerX - iw / 2, top + row, span, 1);
            }
        }
    }

    private function drawPauseIcon(dc as Dc, centerX as Number, centerY as Number, h as Number) as Void {
        var barW = h * 0.26;
        var gap = h * 0.18;
        var totalW = barW * 2 + gap;
        var top = centerY - h / 2;
        var left = centerX - totalW / 2;
        dc.fillRectangle(left, top, barW, h);
        dc.fillRectangle(left + barW + gap, top, barW, h);
    }
}

class MainViewMenuDelegate extends WatchUi.MenuInputDelegate {

    private var _messages;

    function initialize(messages) {
        MenuInputDelegate.initialize();
        _messages = messages;
    }

    function onMenuItem(item as WatchUi.MenuItem) as Void {
        switch (item.getId()) {
            case :menu_next:
                _messages.sendNext();
                break;
            case :menu_prev:
                _messages.sendPrev();
                break;
        }
    }
}
