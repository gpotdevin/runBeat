import Toybox.Graphics;

import Toybox.Lang;

import Toybox.Math;

import Toybox.WatchUi;

// Screen 1 — music-control remote. Works for RunBeat AND external apps:
// a big play/pause state icon at the top, header (source label /
// connection status), the live track block (artist / album / title in a
// large font), and a progress bar with an m:ss caption.
//
// Input (wired in RunBeatCompanionApp.mc): a tap anywhere (or
// select/ENTER) = play/pause, MENU = flat transport menu (button
// devices), horizontal swipe = previous / next track (touch devices).
// Volume lives on the Controls page. Everything is drawn relative to the
// screen size, so it adapts from 218 px (fr245) to 454 px (venu3), round
// and square; layouts.xml only provides the header label.
class MainView extends BaseView {

    function initialize(state, messages) {
        BaseView.initialize(state, messages);
    }

    function onLayout(dc as Dc) as Void {
        if (isRound()) {
            setLayout(Rez.Layouts.MainLayoutRound(dc));
        } else {
            setLayout(Rez.Layouts.MainLayout(dc));
        }
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        // Header: where the audio is coming from (or the connection status).
        updateHeader(sourceLabelText(), sourceLabelColor());

        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);
        var fhSmall = dc.getFontHeight(Graphics.FONT_SMALL);
        var fhLarge = dc.getFontHeight(Graphics.FONT_LARGE);
        var runBeat = _state.isRunBeatActive();

        // --- Row geometry -------------------------------------------------
        // Design targets are fractions of h (state icon ~0.16, track
        // block ~0.30/0.39/0.51, progress ~0.65). Each row is pushed down
        // past the previous row's bottom, from 218 px (fr245) to 454 px
        // (venu3), round and square.
        var headerBottom = (isRound() ? 34 : 18) + fhTiny / 2;

        // Big play/pause state icon at the top: a tap anywhere toggles;
        // the icon shows which action the next tap will do.
        var iconH = maxNum(18, minNum(40, h * 0.10)).toNumber();
        var iconCY = maxNum(h * 0.16, headerBottom + iconH / 2 + 4).toNumber();
        var iconBottom = iconCY + iconH / 2;

        var yArtist = maxNum(h * 0.30, iconBottom + 8).toNumber();
        var yAlbum = maxNum(h * 0.39, yArtist + fhSmall / 2 + 2 + fhTiny / 2).toNumber();
        var yTitle = maxNum(h * 0.51, yAlbum + fhTiny / 2 + 2 + fhLarge / 2).toNumber();

        // Progress row (only when the duration is known).
        var durationMs = _state.durationMs;
        var positionMs = _state.positionMs;
        var showProgress = runBeat && durationMs != null && (durationMs as Number) > 0;
        var barH = maxNum(3, minNum(8, h * 0.02)).toNumber();
        var yCaption = 0;
        var barTop = 0;
        if (showProgress) {
            yCaption = maxNum(h * 0.65, yTitle + fhLarge / 2 + 3 + barH + 2 + fhTiny / 2).toNumber();
            barTop = yCaption - fhTiny / 2 - 2 - barH;
        }

        // --- Play/pause state icon (top of the screen) ---------------------
        if (_state.isPlaying) {
            dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_BLACK);
            drawPauseIcon(dc, w / 2, iconCY, iconH);
        } else {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            drawPlayIcon(dc, w / 2, iconCY, iconH);
        }

        // --- Track block ---------------------------------------------------
        if (runBeat) {
            var noValue = WatchUi.loadResource(Rez.Strings.NoValue) as String;
            var artist = _state.artist.length() > 0 ? _state.artist : noValue;
            var album = _state.album.length() > 0 ? _state.album : noValue;
            var title = _state.title.length() > 0 ? _state.title : noValue;
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yArtist, Graphics.FONT_SMALL,
                truncateToWidth(dc, artist, Graphics.FONT_SMALL, textWidthAt(dc, yArtist, 0.84)),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yAlbum, Graphics.FONT_TINY,
                truncateToWidth(dc, album, Graphics.FONT_TINY, textWidthAt(dc, yAlbum, 0.84)),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yTitle, Graphics.FONT_LARGE,
                truncateToWidth(dc, title, Graphics.FONT_LARGE, textWidthAt(dc, yTitle, 0.80)),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            // No RunBeat track: the source label only (as before).
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yTitle, Graphics.FONT_LARGE,
                truncateToWidth(dc, sourceLabelText(), Graphics.FONT_LARGE, textWidthAt(dc, yTitle, 0.80)),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        // --- Progress (only when the duration is known) --------------------
        if (showProgress) {
            var dur = durationMs as Number;
            var pos = 0;
            if (positionMs != null) {
                pos = positionMs as Number;
            }
            if (pos < 0) {
                pos = 0;
            }
            if (pos > dur) {
                pos = dur;
            }
            var barW = w * 0.7;
            var barX = (w - barW) / 2;
            dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
            dc.fillRectangle(barX, barTop, barW, barH);
            var fillW = barW * pos / dur;
            if (fillW > 0) {
                dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_BLACK);
                dc.fillRectangle(barX, barTop, fillW, barH);
            }
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yCaption, Graphics.FONT_TINY, _state.progressText(),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        drawAck(dc);
        drawPageIndicator(dc, 1, 3);
    }

    // Select/ENTER = play/pause (works for RunBeat and external apps).
    function onSelectPressed() as Void {
        _messages.sendToggle();
    }

    // Horizontal-swipe handler for RunBeatPageDelegate (touch devices):
    // :swipe_left goes to the previous track, :swipe_right to the next
    // (the transport buttons were replaced by this gesture).
    function onHorizontalSwipe(direction as Symbol) as Void {
        if (direction == :swipe_left) {
            _messages.sendPrev();
        } else if (direction == :swipe_right) {
            _messages.sendNext();
        }
    }

    private function sourceLabelText() as String {
        if (_state.source.equals("RunBeat")) {
            return WatchUi.loadResource(Rez.Strings.AppName) as String;
        }
        if (_state.source.length() == 0 || _state.source.equals("none")) {
            return WatchUi.loadResource(Rez.Strings.NoPlayback) as String;
        }
        if (_state.source.equals("external")) {
            return WatchUi.loadResource(Rez.Strings.ExternalApp) as String;
        }
        // External app name pushed by the phone (optional notification access).
        return _state.source;
    }

    private function sourceLabelColor() as Number {
        if (_state.source.equals("RunBeat")) {
            return Graphics.COLOR_GREEN;
        }
        return Graphics.COLOR_LT_GRAY;
    }

    // Numeric max/min with untyped operands (Number or Float); the result
    // is normalized with toNumber() where a Number is required.
    private function maxNum(a, b) {
        return a > b ? a : b;
    }

    private function minNum(a, b) {
        return a < b ? a : b;
    }

    // Max text width at height y: the full width times maxFraction on
    // square screens; on round screens the chord of the circle at y, so
    // truncated text stays inside the bezel.
    private function textWidthAt(dc as Dc, y as Number, maxFraction as Float) as Number {
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

// Main page menu (button devices): flat transport items — one press, no
// sub-menus. Touch devices use the gestures instead.
class MainViewMenuDelegate extends WatchUi.MenuInputDelegate {

    private var _messages;

    function initialize(messages) {
        MenuInputDelegate.initialize();
        _messages = messages;
    }

    function onMenuItem(item as Symbol) as Void {
        switch (item) {
            case :menu_next:
                _messages.sendNext();
                break;
            case :menu_prev:
                _messages.sendPrev();
                break;
            case :menu_volume_up:
                _messages.sendVolumeDelta(1);
                break;
            case :menu_volume_down:
                _messages.sendVolumeDelta(-1);
                break;
        }
    }
}
