import Toybox.Application;

import Toybox.Background;

import Toybox.Communications;

import Toybox.Graphics;

import Toybox.Lang;

import Toybox.Media;

import Toybox.System;

import Toybox.Timer;

import Toybox.WatchUi;

// RunBeat companion watch app (Connect IQ / Monkey C).
//
// Remote control for the RunBeat Android app (and, through phone-side
// routing, any other music app on the phone). The watch never plays audio
// itself; it only exchanges messages with the phone companion
// (GarminCompanionManager, see docs/companion_garmin_app/architecture.md).
//
// The app is registered as an audio content provider (manifest type
// "audio-content-provider-app") so it is reachable from the watch's
// music provider list; its playback-configuration view IS the remote UI
// (AudioProvider.mc holds the content/sync plumbing, which is empty —
// all playback happens on the phone).
//
// Recognition handshake: on start (and, while the state is stale, with
// exponential backoff) the watch sends {"type":"request_state"}; the
// phone answers the sender with one state push. This fixes the "phone
// not recognized" case where the watch app starts after the phone
// already pushed its state.
//
// Battery: the 1 Hz cadence tick repaints the screen ONLY when visible
// content changed (deferred position push, connection-header flip, ack
// expiry, changed cadence number) instead of unconditionally — a full
// repaint every second was the main battery drain. The stale retry
// backs off (5 -> 10 -> 20 -> 40 -> 60 s) so an unreachable phone does
// not keep waking the BLE radio every 5 s.
// Battery / lifecycle: the app is a remote control and must not run when
// it is not needed.
//   - It repaints only when visible content changed (see shouldRedraw).
//   - It exits itself (System.exit) after IDLE_EXIT_MS without user
//     interaction once music has stopped or the phone link is stale.
//   - onStop sends "bye" so the phone stops its ~1 Hz state stream
//     immediately (the phone only pushes to a recently-heard-from watch
//     app, so a closed app costs the watch nothing).
//   - Re-activation needs no polling: the background service
//     (RunBeatBackgroundService, registered via
//     Background.registerForPhoneAppMessageEvent) wakes when the phone
//     sends {"type":"wake"} (playback started, no watch app active) and
//     offers a launch dialog.
//   - Back at a page root attempts System.exit but never swallows the
//     event: on device System.exit does nothing from this
//     audio-content-provider context (2026-10-29), so back falls through
//     to the system pop (the stock music app beneath), as it always did.
class RunBeatCompanionApp extends Application.AudioContentProviderApp {

    private var _state;           // WatchState
    private var _cadenceBuffer;   // CadenceRingBuffer
    private var _cadenceSampler;  // CadenceSampler
    private var _messages;        // MessageHandler
    private var _stateTimer;      // Timer.Timer? — request_state retry

    // request_state retry backoff (exponential while the phone never
    // answers; reset as soon as any message arrives).
    private var _retryDelayMs as Number = 5000;
    private var _lastRequestAt as Number = 0;

    // Repaint-transition tracking for the 1 Hz tick (see shouldRedraw).
    private var _wasStale as Boolean = true;     // stale at app start
    private var _ackWasVisible as Boolean = false;
    private var _lastMeanText as String = "";    // displayed cadence number

    // Auto-exit: the last user interaction (any tap/key/menu in a
    // delegate — see noteUserActivity). Once IDLE_EXIT_MS has passed with
    // music stopped (or the phone link stale) the app exits itself.
    private var _lastUserAt as Number = 0;

    private static const IDLE_EXIT_MS = 180000; // 3 minutes

    // Called by the input delegates (via Application.getApp()) on every
    // user interaction.
    function noteUserActivity() as Void {
        _lastUserAt = System.getTimer();
    }

    function initialize() {
        Application.AudioContentProviderApp.initialize();
        _state = new WatchState();
        _cadenceBuffer = new CadenceRingBuffer();
        _cadenceSampler = new CadenceSampler(_cadenceBuffer, method(:onCadenceTick));
        _messages = new MessageHandler(_state);
    }

    function onStart(state as Dictionary?) as Void {
        // Receive state/ack pushes from the phone companion.
        Communications.registerForPhoneAppMessages(_messages.method(:onPhoneMessage));
        // Handshake: ask for the current state right away (the phone may
        // have pushed it before this app started listening).
        _messages.sendRequestState();
        _lastRequestAt = System.getTimer();
        _retryDelayMs = 5000;
        _lastUserAt = System.getTimer();
        // 1 Hz cadence sampling (also drives the conditional 1 Hz screen
        // refresh).
        _cadenceSampler.start();
        // Re-ask with exponential backoff while no message arrived in the
        // last 10 s: covers "no message ever" and a dropped link; recovery
        // is automatic once the phone answers.
        var timer = new Timer.Timer();
        timer.start(method(:onStateTimerTick), 5000, true);
        _stateTimer = timer;
        // Re-activation without running: the phone sends {"type":"wake"}
        // when playback starts while this app is closed; the background
        // service (RunBeatBackgroundService) then offers a launch dialog.
        // The registration persists across app restarts.
        Background.registerForPhoneAppMessageEvent();
        System.println("RunBeat companion started");
    }

    function onStop(state as Dictionary?) as Void {
        // Best-effort farewell: the phone drops its watch-app active
        // window immediately and stops the ~1 Hz state stream, instead of
        // waiting for the window to expire (battery: a closed app must
        // cost the watch nothing).
        _messages.sendBye();
        _cadenceSampler.stop();
        var timer = _stateTimer;
        if (timer != null) {
            (timer as Timer.Timer).stop();
            _stateTimer = null;
        }
    }

    // The background service the system runs for wake messages (see
    // RunBeatBackgroundService.mc). The registration is done in onStart.
    function getServiceDelegate() as Lang.Array {
        return [new RunBeatBackgroundService()];
    }

    function onCadenceTick() as Void {
        var now = System.getTimer();
        // The app is a remote control: once the user is idle and music has
        // stopped (or the phone link is stale), nothing on screen can
        // change — exit instead of burning power until the system kills us.
        if (shouldExit(now)) {
            try {
                System.exit();
            } catch (e) {
                // Exit unavailable in this context (observed as a no-op
                // from the ACP provider context on device, 2026-10-29):
                // the system's own inactivity timeout is the remaining
                // bound, and the phone stops pushing at the "bye" / the
                // 30 s active-window expiry anyway.
            }
            return;
        }
        // Repaint only when visible content changed since the last pass —
        // a full-screen render every second was the main battery drain.
        if (shouldRedraw(now)) {
            WatchUi.requestUpdate();
        }
        // Drive the pushed adjust screen's auto-return (it must not run a
        // Timer of its own: hosts allow only ~3 concurrent timers and the
        // app already runs two repeating ones).
        var screen = _state.adjustScreen;
        if (screen != null) {
            (screen as AdjustScreenView).checkAutoPop();
        }
    }

    // True when the app should exit on its own: no user interaction for
    // IDLE_EXIT_MS and music is not playing (or the phone link is stale /
    // never connected). While playing with a live link the app stays — the
    // user may be glancing at cadence — and the system's own backlight
    // timeout still ends foreground sessions it considers idle.
    private function shouldExit(now as Number) as Boolean {
        if (now - _lastUserAt < IDLE_EXIT_MS) {
            return false;
        }
        return !_state.isPlaying || _state.isStale(now);
    }

    // True when something visible changed since the last repaint: a
    // position-only state push deferred by MessageHandler, the
    // connection header flipping between fresh and stale, the ack line
    // expiring, or the displayed cadence number changing. Paused and
    // standing still, nothing repaints at all.
    private function shouldRedraw(now as Number) as Boolean {
        if (_state.consumePositionDirty()) {
            return true;
        }
        var stale = _state.isStale(now);
        if (stale != _wasStale) {
            _wasStale = stale;
            return true;
        }
        var ackVisible = _state.isAckVisible(now);
        if (ackVisible != _ackWasVisible) {
            _ackWasVisible = ackVisible;
            return true;
        }
        // The cadence page displays mean() formatted "%.0f" — compare the
        // formatted value so sub-display drift does not repaint.
        var mean = _cadenceBuffer.mean(now);
        var meanText = mean == null ? "" : (mean as Float).format("%.0f");
        if (!meanText.equals(_lastMeanText)) {
            _lastMeanText = meanText;
            return true;
        }
        return false;
    }

    // 5 s retry tick. Two roles:
    //   - Keepalive while playing and fresh: the phone pushes state ONLY
    //     to a recently-heard-from watch app (battery — a closed app must
    //     cost nothing), and while playing the app itself sends nothing,
    //     so this tick re-arms the phone's active window.
    //   - Stale retry with exponential backoff (5 -> 10 -> 20 -> 40 -> 60
    //     s cap): an unreachable phone must not trigger a BLE session
    //     attempt every 5 s forever. The backoff resets as soon as the
    //     state is fresh again (any message arriving does that), so a live
    //     link still recovers within one tick.
    function onStateTimerTick() as Void {
        var now = System.getTimer();
        if (!_state.isStale(now)) {
            _retryDelayMs = 5000;
            if (_state.isPlaying) {
                _messages.sendRequestState();
            }
            return;
        }
        if (now - _lastRequestAt < _retryDelayMs) {
            return;
        }
        _messages.sendRequestState();
        _lastRequestAt = now;
        var next = _retryDelayMs * 2;
        _retryDelayMs = next > 60000 ? 60000 : next;
    }

    function getInitialView() {
        // ViewLoop (API 3.4.0) replaces SwitchableView. Only a real fr245
        // running CIQ 3.3.1 (< 3.4.0) takes the fallback: main page only.
        if (WatchUi has :ViewLoop) {
            var factory = new RunBeatViewLoopFactory(_state, _messages, _cadenceBuffer);
            var viewLoop = new WatchUi.ViewLoop(factory,
                {:page => 0, :wrap => true, :color => Graphics.COLOR_GREEN});
            return [viewLoop, new RunBeatViewLoopDelegate(viewLoop)];
        }
        return [new MainView(_state, _messages), new RunBeatFallbackDelegate(_messages)];
    }

    // ----- audio-content-provider plumbing (AudioProvider.mc holds the
    // content/sync classes; RunBeat serves no on-watch audio) -----

    // The system music player's content view into this provider: empty —
    // playback happens on the phone.
    function getContentDelegate(args as Application.PersistableType) as Media.ContentDelegate {
        return new RunBeatContentDelegate();
    }

    // IMPORTANT: return null here. With a real SyncDelegate the system's
    // provider flow syncs and then opens its own (empty) content browser —
    // our remote UI is never launched (observed on the real watch,
    // 2026-10-28: null -> the playback-configuration view IS launched).
    function getSyncDelegate() as Communications.SyncDelegate? {
        return null;
    }

    // Reached from the watch's music provider list: the remote UI itself.
    function getPlaybackConfigurationView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return getInitialView();
    }

    // Nothing to sync — a note screen with back.
    function getSyncConfigurationView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return [new RunBeatSyncConfigView(), new RunBeatSyncConfigDelegate()];
    }
}

// ViewLoop factory: one view/delegate pair per page
// (0 = MainView, 1 = ControlView, 2 = CadenceView).
class RunBeatViewLoopFactory extends WatchUi.ViewLoopFactory {

    private var _state;           // WatchState
    private var _messages;        // MessageHandler
    private var _cadenceBuffer;   // CadenceRingBuffer

    function initialize(state, messages, cadenceBuffer) {
        ViewLoopFactory.initialize();
        _state = state;
        _messages = messages;
        _cadenceBuffer = cadenceBuffer;
    }

    function getView(page as Number) as [ViewLoopFactory.Views] or [ViewLoopFactory.Views, ViewLoopFactory.Delegates] {
        if (page == 1) {
            var view = new ControlView(_state, _messages);
            return [view, new RunBeatPageDelegate(view,
                new Rez.Menus.ControlViewMenu(),
                new ControlViewMenuDelegate(_messages, _state),
                view.method(:onTap),
                null)];
        } else if (page == 2) {
            var view = new CadenceView(_state, _messages, _cadenceBuffer);
            return [view, new RunBeatPageDelegate(view,
                new Rez.Menus.CadenceViewMenu(),
                new CadenceViewMenuDelegate(view, _messages, _state),
                view.method(:onTap),
                null)];
        }
        var view = new MainView(_state, _messages);
        return [view, new RunBeatPageDelegate(view,
            new Rez.Menus.MainViewMenu(),
            new MainViewMenuDelegate(_messages),
            null,
            view.method(:onHorizontalSwipe))];
    }

    function getSize() {
        return 3;
    }
}

// Per-page input delegate: select triggers the page's primary action, menu
// pushes the page's menu (with its existing MenuInputDelegate). Pages
// additionally carry optional tap/swipe handlers (Methods or null): taps
// are mapped to the page's drawn controls, horizontal swipes to the page's
// gesture (prev/next track on page 0).
class RunBeatPageDelegate extends WatchUi.BehaviorDelegate {

    private var _view;          // MainView | ControlView | CadenceView
    private var _menu;          // WatchUi.Menu
    private var _menuDelegate;  // WatchUi.MenuInputDelegate
    private var _tapHandler;    // Method? — view.onTap(x, y), or null
    private var _swipeHandler;  // Method? — view.onHorizontalSwipe(dir), or null

    function initialize(view, menu, menuDelegate, tapHandler, swipeHandler) {
        BehaviorDelegate.initialize();
        _view = view;
        _menu = menu;
        _menuDelegate = menuDelegate;
        _tapHandler = tapHandler;
        _swipeHandler = swipeHandler;
    }

    // BehaviorDelegate maps a CLICK_TYPE_TAP to the Selection behavior
    // FIRST (onSelect carries no coordinates); InputDelegate.onTap only
    // runs when onSelect returns false. Performing the primary action here
    // swallowed every tap as select (the tap bug), so return false instead:
    // taps continue to onTap() below, and the physical Start/Enter button
    // continues to onKey(). Both paths run the page's primary action.
    function onSelect() as Boolean {
        return false;
    }

    // The Selection behavior fell through to the raw key: the physical
    // select button (KEY_ENTER) performs the page's primary action.
    function onKey(keyEvent as WatchUi.KeyEvent) as Boolean {
        noteActivity();
        if (keyEvent.getKey() == WatchUi.KEY_ENTER) {
            _view.onSelectPressed();
            return true;
        }
        return false;
    }

    // Back at a page root attempts a clean exit, but NEVER swallows the
    // event: System.exit() is documented to end the app "from any point",
    // yet on the real device (2026-10-29) it does nothing from this
    // audio-content-provider context, and returning true trapped the
    // user in the app (no exit, no pop). Returning false lets the system
    // pop to the context that launched the provider view (the stock
    // music app) — back always leaves the screen one way or the other.
    function onBack() as Boolean {
        try {
            System.exit();
        } catch (e) {
            // Exit unavailable here — the fall-through pop below is the
            // way out.
        }
        return false;
    }

    function onMenu() as Boolean {
        noteActivity();
        WatchUi.pushView(_menu, _menuDelegate, WatchUi.SLIDE_IMMEDIATE);
        return true;
    }

    // Touch: hand the tap coordinates to the page's tap handler (page 0
    // maps them to the drawn buttons). A tap that misses the drawn buttons
    // (or a page without tap zones) behaves like select: the page's
    // primary action.
    function onTap(clickEvent as WatchUi.ClickEvent) as Boolean {
        noteActivity();
        var handler = _tapHandler;
        if (handler != null) {
            var coords = clickEvent.getCoordinates();
            if ((handler as Method).invoke(coords[0] as Number, coords[1] as Number) as Boolean) {
                return true;
            }
        }
        _view.onSelectPressed();
        return true;
    }

    // Touch: horizontal swipes go to the page's swipe handler (prev/next
    // track on page 0). Vertical swipes return false so the ViewLoopDelegate
    // keeps cycling the pages.
    function onSwipe(swipeEvent as WatchUi.SwipeEvent) as Boolean {
        noteActivity();
        var handler = _swipeHandler;
        if (handler == null) {
            return false;
        }
        var direction = swipeEvent.getDirection();
        if (direction == WatchUi.SWIPE_LEFT) {
            (handler as Method).invoke(:swipe_left);
            return true;
        }
        if (direction == WatchUi.SWIPE_RIGHT) {
            (handler as Method).invoke(:swipe_right);
            return true;
        }
        return false;
    }

    // Any input is user activity: restarts the app's idle auto-exit clock
    // (RunBeatCompanionApp.noteUserActivity).
    private function noteActivity() as Void {
        var app = Application.getApp();
        if (app instanceof RunBeatCompanionApp) {
            (app as RunBeatCompanionApp).noteUserActivity();
        }
    }
}

// ViewLoop delegate: up/down (or swipe) cycle through the pages.
class RunBeatViewLoopDelegate extends WatchUi.ViewLoopDelegate {

    private var _viewLoop;   // WatchUi.ViewLoop

    function initialize(viewLoop) {
        ViewLoopDelegate.initialize(viewLoop);
        _viewLoop = viewLoop;
    }

    function onNextView() as Boolean {
        _viewLoop.changeView(WatchUi.ViewLoop.DIRECTION_NEXT);
        return true;
    }

    function onPreviousView() as Boolean {
        _viewLoop.changeView(WatchUi.ViewLoop.DIRECTION_PREVIOUS);
        return true;
    }
}

// Fallback input delegate for devices without ViewLoop support (a real
// fr245 running CIQ 3.3.1 < 3.4.0): the main page only — select toggles
// play/pause, menu opens the transport menu (4 flat items). Play control
// stays usable; the cadence and control pages are unreachable on that one
// model.
class RunBeatFallbackDelegate extends WatchUi.BehaviorDelegate {

    private var _messages;   // MessageHandler

    function initialize(messages) {
        BehaviorDelegate.initialize();
        _messages = messages;
    }

    function onSelect() as Boolean {
        noteActivity();
        _messages.sendToggle();
        return true;
    }

    // Back attempts a clean exit but always falls through to the system
    // pop (see RunBeatPageDelegate.onBack — returning true traps the user
    // when System.exit is a no-op on device).
    function onBack() as Boolean {
        try {
            System.exit();
        } catch (e) {
        }
        return false;
    }

    function onMenu() as Boolean {
        noteActivity();
        WatchUi.pushView(new Rez.Menus.MainViewMenu(),
            new MainViewMenuDelegate(_messages), WatchUi.SLIDE_IMMEDIATE);
        return true;
    }

    // Any input is user activity: restarts the app's idle auto-exit clock.
    private function noteActivity() as Void {
        var app = Application.getApp();
        if (app instanceof RunBeatCompanionApp) {
            (app as RunBeatCompanionApp).noteUserActivity();
        }
    }
}
