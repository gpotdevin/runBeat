import Toybox.Graphics;
import Toybox.Lang;
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

    // Cached resources (battery/CPU: loadResource() hits the file system
    // and must not run in the onUpdate draw path — loaded once here).
    private var _strPlaylistsHeader as String;
    private var _strNoPlaylists as String;

    function initialize(state, messages) {
        BaseView.initialize(state, messages);
        _rowRects = [];
        _strPlaylistsHeader = WatchUi.loadResource(Rez.Strings.PlaylistsHeader) as String;
        _strNoPlaylists = WatchUi.loadResource(Rez.Strings.NoPlaylists) as String;
    }

    function onLayout(dc as Dc) as Void {
        if (isRound()) {
            setLayout(Rez.Layouts.PlaylistsLayoutRound(dc));
        } else {
            setLayout(Rez.Layouts.PlaylistsLayout(dc));
        }
    }

    // Page shown: resume app periodic work (BaseView hook), then refresh
    // the playlist list from the phone (link-layer message — no ack, the
    // answer is the playlists push itself).
    function onShow() as Void {
        BaseView.onShow();
        _messages.sendPlaylistList();
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var fhTiny = dc.getFontHeight(Graphics.FONT_TINY);

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        updateHeader(_strPlaylistsHeader, Graphics.COLOR_LT_GRAY);

        var count = _state.playlistCount;
        // Every coordinate passed to fillRoundedRectangle/drawText is
        // normalized with .toNumber() at each step (Number/Number division
        // yields Float) — mixed Float/Number args break rendering on
        // device, same step1c fix as MainView's mark button and
        // ControlView's volume button.
        var headerBottom = ((isRound() ? 34 : 18) + fhTiny / 2).toNumber();

        // Rows are centered, 0.8*w wide; on round screens the text is
        // additionally clamped to the bezel chord at the row's y (the
        // shared textWidthAt in BaseView).
        _rowRects = [];
        if (count == 0) {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText((w / 2).toNumber(), (h / 2).toNumber(), Graphics.FONT_SMALL,
                _strNoPlaylists,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            var regionTop = maxNum((h * 0.14).toNumber(), (headerBottom + 6).toNumber()).toNumber();
            var regionBottom = (h - 30).toNumber();
            var rowW = (w * 0.8).toNumber();
            var rowH = ((regionBottom - regionTop) / count).toNumber();
            if (rowH > 40) {
                rowH = 40;
            }
            var totalH = (rowH * count).toNumber();
            var top = (regionTop + (regionBottom - regionTop - totalH) / 2).toNumber();
            var rowX = ((w - rowW) / 2).toNumber();
            var selected = _state.selectedPlaylistIndex;
            for (var i = 0; i < count; i++) {
                var y = (top + i * rowH).toNumber();
                var rect = [rowX, y, rowW, rowH];
                _rowRects.add(rect);
                if (i == selected) {
                    dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_BLACK);
                    dc.fillRoundedRectangle(rowX, y, rowW, rowH, 16);
                    dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
                } else {
                    dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
                    dc.fillRoundedRectangle(rowX, y, rowW, rowH, 16);
                    dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
                }
                var name = _state.playlistNames[i] as String;
                var textY = (y + rowH / 2).toNumber();
                dc.drawText((w / 2).toNumber(), textY, Graphics.FONT_SMALL,
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

}
