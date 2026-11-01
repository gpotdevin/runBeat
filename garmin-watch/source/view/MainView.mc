import Toybox.Graphics;

import Toybox.Lang;

import Toybox.System;

import Toybox.WatchUi;

// Screen 1 — music-control remote for RunBeat (the phone ignores the
// commands when RunBeat is not the active player).
// Top to bottom: header (source label / connection status), a big shuffle
// toggle icon (RunBeat only; gray when RunBeat is not active), artist / album,
// then a transport row (◀ prev
// button, progress bar with an m:ss caption, ▶ next button), the track
// title in a large font, and the "Exclude" button at the very bottom.
//
// Input (wired in RunBeatCompanionApp.mc): a tap anywhere except the
// buttons (or select/ENTER) = play/pause, tapping the shuffle icon =
// shuffle toggle (RunBeat only), MENU = flat transport menu (button
// devices), horizontal swipe = previous track, ◀/▶ tap buttons =
// previous / next track (touch devices; swipe_right is system BACK).
// Volume lives on the Controls page. Everything is drawn relative to the
// screen size, so it adapts from 218 px (fr245) to 454 px (venu3), round
// and square; layouts.xml only provides the header label.
class MainView extends BaseView {

    // Drawn button rects [x, y, w, h] from the last onUpdate (tap hit-testing).
    private var _btnShuffle; // Array? or null
    private var _btnMark;   // Array? or null (null when not RunBeat)
    private var _btnPrev;   // Array? or null
    private var _btnNext;   // Array? or null
    private var _btnBarPrev; // Array? or null (left half of progress bar row = prev)
    private var _btnBarNext; // Array? or null (right half of progress bar row = next)

    // Cached resources (battery/CPU: loadResource() hits the file system
    // and must not run in the onUpdate draw path — loaded once here).
    private var _strNoValue as String;
    private var _strMarkNotForRunning as String;
    private var _strAppName as String;
    private var _strNoPlayback as String;
    private var _strAckRunBeatOnly as String;

    function initialize(state, messages) {
        BaseView.initialize(state, messages);
        _strNoValue = WatchUi.loadResource(Rez.Strings.NoValue) as String;
        _strMarkNotForRunning = WatchUi.loadResource(Rez.Strings.MenuMarkNotForRunning) as String;
        _strAppName = WatchUi.loadResource(Rez.Strings.AppName) as String;
        _strNoPlayback = WatchUi.loadResource(Rez.Strings.NoPlayback) as String;
        _strAckRunBeatOnly = WatchUi.loadResource(Rez.Strings.AckRunBeatOnly) as String;
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
        // Upper part unchanged, top-down: state icon ~0.16 h, artist
        // ~0.30 h, album ~0.39 h. The lower stack (transport row, title,
        // Exclude button) is anchored bottom-up so the rows can never
        // overlap, from 218 px (fr245) to 454 px (venu3), round and
        // square: prev | progress | next row ~0.55 h, title ~0.72 h,
        // Exclude button ~0.85 h (bottom-most, above the ack line and
        // page indicator).
        var headerBottom = (isRound() ? 34 : 18) + fhTiny / 2;

        // Big shuffle toggle icon at the top: tapping the icon toggles
        // shuffle (RunBeat only); a tap anywhere else toggles play/pause.
        var iconH = maxNum(18, minNum(40, h * 0.10)).toNumber();
        var iconCY = maxNum(h * 0.16, headerBottom + iconH / 2 + 4).toNumber();
        var iconBottom = iconCY + iconH / 2;

        var yArtist = maxNum(h * 0.30, iconBottom + 8).toNumber();
        var yAlbum = maxNum(h * 0.39, yArtist + fhSmall / 2 + 2 + fhTiny / 2).toNumber();

        // Progress data (only drawn when the duration is known).
        // Extrapolated to now: the phone pushes position in 5 s buckets,
        // the bar advances locally between pushes while playing.
        var durationMs = _state.durationMs;
        var positionMs = _state.effectivePositionMs(System.getTimer());
        var showProgress = runBeat && durationMs != null && (durationMs as Number) > 0;
        var barH = maxNum(3, minNum(8, h * 0.02)).toNumber();

        // --- Lower stack, bottom-up so rows cannot overlap ----------------
        // Exclude button (bottom-most; RunBeat only): target ~0.85 h,
        // clamped so its bottom stays above the ack line (h - 44) and the
        // page indicator (h - 22).
        var bH = maxNum(18, minNum(26, h * 0.06)).toNumber();
        var bW = minNum(w * 0.56, 240).toNumber();
        var bLeft = ((w - bW) / 2).toNumber();
        var bTop = minNum((h * 0.85).toNumber(), (h - 50 - bH).toNumber());

        // Track title just above the Exclude button, target ~0.72 h.
        var yTitle = maxNum(h * 0.72, yAlbum + fhTiny / 2 + 2 + fhLarge / 2).toNumber();
        var yTitleMax = (bTop - 5 - fhLarge / 2).toNumber();
        if (yTitle > yTitleMax) {
            yTitle = yTitleMax;
        }

        // Prev/next row with the progress bar BETWEEN the buttons,
        // target ~0.55 h, clamped to end above the title.
        var tB = minNum(72, h * 0.18).toNumber();
        var tY = maxNum((h * 0.55).toNumber(), (yAlbum + fhTiny / 2 + 4).toNumber());
        var tYMax = (yTitle - fhLarge / 2 - 4 - tB).toNumber();
        if (tY > tYMax) {
            tY = tYMax;
        }
        var tPrevX = (w * 0.15 - tB / 2).toNumber();
        var tNextX = (w * 0.85 - tB / 2).toNumber();
        // Progress bar spans the space between the two buttons
        // (~0.30-0.70 w), vertically centered on the button row; its
        // m:ss caption sits just below the bar.
        var barX = (w * 0.30).toNumber();
        var barW = (w * 0.40).toNumber();
        var barCY = tY + tB / 2;
        var barTop = (barCY - barH / 2).toNumber();
        var yCaption = (barCY + barH / 2 + 2 + fhTiny / 2).toNumber();

        // --- Shuffle toggle icon (top of the screen) -----------------------
        // RunBeat-only feature: WHITE when shuffle is off, GREEN when on;
        // dimmed gray when RunBeat is not active (tapping it there shows
        // the "RunBeat only" ack). Tapping the icon sends the toggle;
        // tapping anywhere else still toggles play/pause. The hit rect is
        // padded well beyond the drawn icon (min 56x56) so taps land reliably.
        var iconHit = maxNum(56, iconH).toNumber();
        _btnShuffle = [((w - iconHit) / 2).toNumber(), (iconCY - iconHit / 2).toNumber(), iconHit, iconHit];
        if (!runBeat) {
            dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
        } else if (_state.shuffle) {
            dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_BLACK);
        } else {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        }
        drawShuffleIcon(dc, w / 2, iconCY, iconH);

        // --- Artist / album (upper track block, unchanged positions) -------
        if (runBeat) {
            var artist = _state.artist.length() > 0 ? _state.artist : _strNoValue;
            var album = _state.album.length() > 0 ? _state.album : _strNoValue;
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yArtist, Graphics.FONT_SMALL,
                truncateToWidth(dc, artist, Graphics.FONT_SMALL, textWidthAt(dc, yArtist, 0.84)),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yAlbum, Graphics.FONT_TINY,
                truncateToWidth(dc, album, Graphics.FONT_TINY, textWidthAt(dc, yAlbum, 0.84)),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        // --- Transport row: ◀ prev | progress bar | next ▶ (~0.55 h) ------
        // SWIPE_RIGHT is system-reserved as BACK on Connect IQ devices, so
        // "next track" via :swipe_right never reaches onHorizontalSwipe().
        // Tappable buttons are the reliable transport on touch devices.
        // The progress bar is drawn BETWEEN the two buttons; without a
        // known duration (RunBeat not active) only the buttons are drawn.
        _btnPrev = [tPrevX, tY, tB, tB];
        _btnNext = [tNextX, tY, tB, tB];
        // Progress-bar halves are the reliable prev/next tap zones: on
        // round devices the right-edge ▶ button sits in the system BACK-
        // gesture strip and its tap is never delivered, so the bar (center)
        // carries the transport. Left half = prev, right half = next.
        if (showProgress) {
            var halfW = (barW / 2).toNumber();
            _btnBarPrev = [barX, tY, halfW, tB];
            _btnBarNext = [barX + halfW, tY, halfW, tB];
        } else {
            _btnBarPrev = null;
            _btnBarNext = null;
        }
        // No background fill: just big white line-drawn arrow triangles
        // centered in each button's hit area (no grey boxes).
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        drawArrowLeft(dc, tPrevX + tB / 2, tY + tB / 2, tB);
        drawArrowRight(dc, tNextX + tB / 2, tY + tB / 2, tB);

        // --- Progress bar between the buttons + m:ss caption (RunBeat, duration known) ---
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
            dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
            dc.fillRectangle(barX, barTop, barW, barH);
            var fillW = (barW * pos / dur).toNumber();
            if (fillW > 0) {
                dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_BLACK);
                dc.fillRectangle(barX, barTop, fillW, barH);
            }
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yCaption, Graphics.FONT_TINY, _state.progressTextAt(System.getTimer()),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        // --- Track title (below the transport row, ~0.72 h) ----------------
        if (runBeat) {
            var title = _state.title.length() > 0 ? _state.title : _strNoValue;
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yTitle, Graphics.FONT_LARGE,
                truncateToWidth(dc, title, Graphics.FONT_LARGE, textWidthAt(dc, yTitle, 0.80)),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            // RunBeat not active: no track name, just the source label
            // ("Nothing playing" for source "none").
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(w / 2, yTitle, Graphics.FONT_LARGE,
                truncateToWidth(dc, sourceLabelText(), Graphics.FONT_LARGE, textWidthAt(dc, yTitle, 0.80)),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        // --- "Exclude" button (bottom-most, ~0.85 h; touch devices have no MENU key) --
        // Same proven rendering as ControlView's volume button: one rounded
        // rect (radius 16) with a white FONT_LARGE label centered in it.
        // All geometry is Number - mixed Float/Number args break rendering.
        // RunBeat-only command: not drawn at all when RunBeat is not active.
        if (runBeat) {
            _btnMark = [bLeft, bTop, bW, bH];
            dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_BLACK);
            dc.fillRoundedRectangle(bLeft, bTop, bW, bH, 16);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText((w / 2).toNumber(), (bTop + bH / 2).toNumber(), Graphics.FONT_LARGE,
                truncateToWidth(dc, _strMarkNotForRunning, Graphics.FONT_LARGE, bW - 16),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            _btnMark = null;
        }

        drawAck(dc);
        drawPageIndicator(dc, 1, 4);
    }

    // Tap handler for RunBeatPageDelegate: the shuffle toggle first, then
    // the prev/next buttons, then the "Exclude" button (RunBeat-only; null
    // otherwise). A tap that misses all buttons returns false so the
    // delegate falls through to play/pause.
    function onTap(x as Number, y as Number) as Boolean {
        if (_btnShuffle != null && hitTest(_btnShuffle, x, y)) {
            if (_state.isRunBeatActive()) {
                _messages.sendShuffleToggle();
            } else {
                // Shuffle is a RunBeat-only feature (like the exclude
                // button): .equals(), never == (Monkey C compares string
                // references with ==).
                _state.setAck(_strAckRunBeatOnly, true);
                WatchUi.requestUpdate();
            }
            return true;
        }
        // Progress-bar halves first: reliable center-zone transport (the
        // right-edge ▶ button tap is swallowed by the system BACK strip).
        if (_btnBarPrev != null && hitTest(_btnBarPrev, x, y)) {
            _messages.sendPrev();
            return true;
        }
        if (_btnBarNext != null && hitTest(_btnBarNext, x, y)) {
            _messages.sendNext();
            return true;
        }
        if (hitTest(_btnPrev, x, y)) {
            _messages.sendPrev();
            return true;
        }
        if (hitTest(_btnNext, x, y)) {
            _messages.sendNext();
            return true;
        }
        if (_btnMark != null && hitTest(_btnMark, x, y)) {
            _messages.sendMarkNotForRunning();
            return true;
        }
        return false;
    }

    // Select/ENTER = play/pause (the phone ignores it when RunBeat is not
    // the active player).
    function onSelectPressed() as Void {
        _messages.sendToggle();
    }

    // Horizontal-swipe handler for RunBeatPageDelegate (touch devices):
    // :swipe_left goes to the previous track. :swipe_right is kept for
    // completeness but is UNREACHABLE on device — Connect IQ reserves a
    // left-to-right swipe as BACK (it triggers onBack(), never this
    // handler), so "next track" is only reachable via the ▶ tap button.
    function onHorizontalSwipe(direction as Symbol) as Void {
        if (direction == :swipe_left) {
            _messages.sendPrev();
        } else if (direction == :swipe_right) {
            _messages.sendNext();
        }
    }

    private function sourceLabelText() as String {
        if (_state.source.equals("RunBeat")) {
            return _strAppName;
        }
        return _strNoPlayback;
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

    // Big left-pointing arrow triangle (prev), line-drawn, sized from the
    // button box tB; all geometry is Number - mixed Float/Number args
    // break rendering.
    private function drawArrowLeft(dc as Dc, centerX as Number, centerY as Number, tB as Number) as Void {
        var s = (tB * 0.56).toNumber();
        var hx = (s / 2).toNumber();
        var hy = (s / 2).toNumber();
        var tipX = (centerX - hx).toNumber();
        var backX = (centerX + hx).toNumber();
        var topY = (centerY - hy).toNumber();
        var botY = (centerY + hy).toNumber();
        dc.drawLine(backX, centerY, tipX, topY);
        dc.drawLine(backX, centerY, tipX, botY);
        dc.drawLine(tipX, topY, tipX, botY);
    }

    // Big right-pointing arrow triangle (next), mirror of drawArrowLeft.
    private function drawArrowRight(dc as Dc, centerX as Number, centerY as Number, tB as Number) as Void {
        var s = (tB * 0.56).toNumber();
        var hx = (s / 2).toNumber();
        var hy = (s / 2).toNumber();
        var tipX = (centerX + hx).toNumber();
        var backX = (centerX - hx).toNumber();
        var topY = (centerY - hy).toNumber();
        var botY = (centerY + hy).toNumber();
        dc.drawLine(backX, centerY, tipX, topY);
        dc.drawLine(backX, centerY, tipX, botY);
        dc.drawLine(tipX, topY, tipX, botY);
    }

    // Shuffle symbol: two horizontal arrows offset vertically (top one
    // pointing right, bottom one pointing left) with two crossing
    // connectors between them. Line-drawn; all geometry is Number -
    // mixed Float/Number args break rendering.
    private function drawShuffleIcon(dc as Dc, centerX as Number, centerY as Number, h as Number) as Void {
        var iw = (h * 0.8).toNumber();
        var ah = (h * 0.22).toNumber();
        var left = (centerX - iw / 2).toNumber();
        var right = (centerX + iw / 2).toNumber();
        var topY = (centerY - h * 0.20).toNumber();
        var botY = (centerY + h * 0.20).toNumber();
        // Top arrow pointing right.
        dc.drawLine(left, topY, right - ah, topY);
        dc.drawLine(right, topY, right - ah, topY - ah);
        dc.drawLine(right, topY, right - ah, topY + ah);
        // Bottom arrow pointing left.
        dc.drawLine(right, botY, left + ah, botY);
        dc.drawLine(left, botY, left + ah, botY - ah);
        dc.drawLine(left, botY, left + ah, botY + ah);
        // Crossing connectors between the two rows.
        dc.drawLine(left, topY, right, botY);
        dc.drawLine(right, topY, left, botY);
    }
}

// Main page menu (button devices): flat transport items — one press, no
// sub-menus. Touch devices use the gestures instead.
class MainViewMenuDelegate extends WatchUi.MenuInputDelegate {

    private var _messages;
    private var _state;     // WatchState

    function initialize(messages, state) {
        MenuInputDelegate.initialize();
        _messages = messages;
        _state = state;
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
            case :menu_mark_not_for_running:
                // RunBeat-only command: .equals(), never == (Monkey C
                // compares references with ==).
                if (!_state.source.equals("RunBeat")) {
                    _state.setAck(WatchUi.loadResource(Rez.Strings.AckRunBeatOnly) as String, true);
                    WatchUi.requestUpdate();
                    break;
                }
                _messages.sendMarkNotForRunning();
                break;
        }
    }
}
