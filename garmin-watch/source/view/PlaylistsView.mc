import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.WatchUi;

// Screen 4 — library playlists from the phone (step 3 of the companion
// plan). On page show the view asks the phone for the playlist list
// ({"type":"playlist_list"}; the phone answers the sender with one
// {"type":"playlists",...} push that MessageHandler stores in WatchState).
// Each drawn row is a tap zone: tapping a row plays that playlist on the
// phone ({"type":"playlist_play","playlistId":...}, acked — the ack line
// shows AckPlaylistStarted / AckPlaylistFailed). Select/ENTER plays the
// highlighted row. An empty state is shown while no list has arrived.
class PlaylistsView extends BaseView {

    // Drawn row rects [x, y, w, h] from the last onUpdate (tap hit-testing).
    private var _rowRects;   // Array of Array

    function initialize(state, messages) {
        BaseView.initialize(state, messages);
        _rowRects = [];
    }

    function onLayout(dc as Dc) as Void {
        if (isRound()) {
            setLayout(Rez.Layouts.PlaylistsLayoutRound(dc));
        } else {
            setLayout(Rez.Layouts.PlaylistsLayout(dc));
        }
    }

    // Page shown: refresh the playlist list from the phone (link-layer
    // message — no ack, the answer is the playlists push itself).
    function onShow() as Void {
        _messages.sendPlaylistList();
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        updateHeader(WatchUi.loadResource(Rez.Strings.PlaylistsHeader) as String, Graphics.COLOR_LT_GRAY);

        var count = _state.playlistCount;
        var headerBottom = (isRound() ? 34 : 18) + fhTiny / 2;

        // Rows are centered, 0.8*w wide; on round screens the text is
        // additionally clamped to the bezel chord at the row's y (same
        // textWidthAt logic as MainView).
        _rowRects = [];
        if (count == 0) {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, h / 2, Graphics.FONT_SMALL,
                WatchUi.loadResource(Rez.Strings.NoPlaylists) as String,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            var regionTop = maxNum(h * 0.14, headerBottom + 6).toNumber();
            var regionBottom = h - 30;
            var rowW = (w * 0.8).toNumber();
            var rowH = (regionBottom - regionTop) / count;
            if (rowH > 40) {
                rowH = 40;
            }
            rowH = rowH.toNumber();
            var totalH = rowH * count;
            var top = (regionTop + (regionBottom - regionTop - totalH) / 2).toNumber();
            var rowX = ((w - rowW) / 2).toNumber();
            var selected = _state.selectedPlaylistIndex;
            for (var i = 0; i < count; i++) {
                var y = (top + i * rowH).toNumber();
                var rect = [rowX, y, rowW, rowH];
                _rowRects.add(rect);
                var textY = (y + rowH / 2).toNumber();
                if (i == selected) {
                    dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_BLACK);
                    dc.fillRoundedRectangle(rowX, y, rowW, rowH, 8);
                    dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
                } else {
                    dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
                }
                var name = _state.playlistNames[i] as String;
                dc.drawText(w / 2, textY, Graphics.FONT_SMALL,
                    truncateToWidth(dc, name, Graphics.FONT_SMALL, textWidthAt(dc, textY, 0.8)),
                    Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            }
        }

        drawAck(dc);
        drawPageIndicator(dc, 4, 4);
    }

    // Tap handler for RunBeatPageDelegate: a row tap highlights the row and
    // plays that playlist on the phone. Returns true when handled; a tap
    // that misses every row falls through to select (play highlighted row).
    function onTap(x as Number, y as Number) as Boolean {
        var rects = _rowRects;
        var n = rects.size();
        for (var i = 0; i < n; i++) {
            if (hitTest(rects[i], x, y)) {
                _state.selectedPlaylistIndex = i;
                var id = _state.playlistIds[i] as String;
                if (id.length() > 0) {
                    _messages.sendPlaylistPlay(id);
                }
                WatchUi.requestUpdate();
                return true;
            }
        }
        return false;
    }

    // Select/ENTER: play the highlighted row (if any).
    function onSelectPressed() as Void {
        var count = _state.playlistCount;
        if (count == 0) {
            return;
        }
        var idx = _state.selectedPlaylistIndex;
        if (idx < 0 || idx >= count) {
            idx = 0;
        }
        _state.selectedPlaylistIndex = idx;
        var id = _state.playlistIds[idx] as String;
        if (id.length() > 0) {
            _messages.sendPlaylistPlay(id);
        }
    }

    // Numeric max/min with untyped operands (Number or Float); the result
    // is normalized with toNumber() where a Number is required.
    private function maxNum(a, b) {
        return a > b ? a : b;
    }

    // Max text width at height y: the full width times maxFraction on
    // square screens; on round screens the chord of the circle at y, so
    // truncated text stays inside the bezel (same as MainView).
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
}
