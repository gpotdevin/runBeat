import Toybox.Application;

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
// Recognition handshake: on start (and every 5 s while the state is stale)
// the watch sends {"type":"request_state"}; the phone answers the sender
// with one state push. This fixes the "phone not recognized" case where the
// watch app starts after the phone already pushed its state.
class RunBeatCompanionApp extends Application.AudioContentProviderApp {

    private var _state;           // WatchState
    private var _cadenceBuffer;   // CadenceRingBuffer
    private var _cadenceSampler;  // CadenceSampler
    private var _messages;        // MessageHandler
    private var _stateTimer;      // Timer.Timer? — request_state retry

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
        // 1 Hz cadence sampling (also drives the 1 Hz screen refresh).
        _cadenceSampler.start();
        // Re-ask every 5 s while no message arrived in the last 10 s: covers
        // "no message ever" and a dropped link; recovery is automatic once
        // the phone answers.
        var timer = new Timer.Timer();
        timer.start(method(:onStateTimerTick), 5000, true);
        _stateTimer = timer;
        System.println("RunBeat companion started");
    }

    function onStop(state as Dictionary?) as Void {
        _cadenceSampler.stop();
        var timer = _stateTimer;
        if (timer != null) {
            (timer as Timer.Timer).stop();
            _stateTimer = null;
        }
    }

    function onCadenceTick() as Void {
        // 1 Hz redraw: live cadence number and ack expiry.
        WatchUi.requestUpdate();
        // Drive the pushed adjust screen's auto-return (it must not run a
        // Timer of its own: hosts allow only ~3 concurrent timers and the
        // app already runs two repeating ones).
        var screen = _state.adjustScreen;
        if (screen != null) {
            (screen as AdjustScreenView).checkAutoPop();
        }
    }

    // 5 s retry tick: re-send request_state while the state is stale.
    function onStateTimerTick() as Void {
        if (_state.isStale(System.getTimer())) {
            _messages.sendRequestState();
        }
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
        if (keyEvent.getKey() == WatchUi.KEY_ENTER) {
            _view.onSelectPressed();
            return true;
        }
        return false;
    }

    function onMenu() as Boolean {
        WatchUi.pushView(_menu, _menuDelegate, WatchUi.SLIDE_IMMEDIATE);
        return true;
    }

    // Touch: hand the tap coordinates to the page's tap handler (page 0
    // maps them to the drawn buttons). A tap that misses the drawn buttons
    // (or a page without tap zones) behaves like select: the page's
    // primary action.
    function onTap(clickEvent as WatchUi.ClickEvent) as Boolean {
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
        _messages.sendToggle();
        return true;
    }

    function onMenu() as Boolean {
        WatchUi.pushView(new Rez.Menus.MainViewMenu(),
            new MainViewMenuDelegate(_messages), WatchUi.SLIDE_IMMEDIATE);
        return true;
    }
}
