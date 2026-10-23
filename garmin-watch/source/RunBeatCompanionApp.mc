import Toybox.Application;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// RunBeat companion watch app (Connect IQ / Monkey C).
//
// Remote control for the RunBeat Android app (and, through phone-side
// routing, any other music app on the phone). The watch never plays audio
// itself; it only exchanges messages with the phone companion
// (GarminCompanionManager, see docs/companion_garmin_app/architecture.md).
class RunBeatCompanionApp extends Application.AppBase {

    private var _state;           // WatchState
    private var _cadenceBuffer;   // CadenceRingBuffer
    private var _cadenceSampler;  // CadenceSampler
    private var _messages;        // MessageHandler

    function initialize() {
        Application.AppBase.initialize();
        _state = new WatchState();
        _cadenceBuffer = new CadenceRingBuffer();
        _cadenceSampler = new CadenceSampler(_cadenceBuffer, method(:onCadenceTick));
        _messages = new MessageHandler(_state);
    }

    function onStart(state as Dictionary?) as Void {
        // Receive state/ack pushes from the phone companion.
        Communications.registerForPhoneAppMessages(_messages.method(:onPhoneMessage));
        // 1 Hz cadence sampling (also drives the 1 Hz screen refresh).
        _cadenceSampler.start();
        System.println("RunBeat companion started");
    }

    function onStop(state as Dictionary?) as Void {
        _cadenceSampler.stop();
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

    function onCadenceTick() as Void {
        // 1 Hz redraw: live cadence number and ack expiry.
        WatchUi.requestUpdate();
    }
}

// ViewLoop factory: one view/delegate pair per page
// (0 = MainView, 1 = CadenceView, 2 = ControlView).
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
            var view = new CadenceView(_state, _messages, _cadenceBuffer);
            return [view, new RunBeatPageDelegate(view,
                new Rez.Menus.CadenceViewMenu(),
                new CadenceViewMenuDelegate(view, _messages, _state))];
        } else if (page == 2) {
            var view = new ControlView(_state, _messages);
            return [view, new RunBeatPageDelegate(view,
                new Rez.Menus.ControlViewMenu(),
                new ControlViewMenuDelegate(_messages, _state))];
        }
        var view = new MainView(_state, _messages);
        return [view, new RunBeatPageDelegate(view,
            new Rez.Menus.MainViewMenu(),
            new MainViewMenuDelegate(_messages))];
    }

    function getSize() {
        return 3;
    }
}

// Per-page input delegate: select triggers the page's primary action, menu
// pushes the page's menu (with its existing MenuInputDelegate).
class RunBeatPageDelegate extends WatchUi.BehaviorDelegate {

    private var _view;          // MainView | CadenceView | ControlView
    private var _menu;          // WatchUi.Menu
    private var _menuDelegate;  // WatchUi.MenuInputDelegate

    function initialize(view, menu, menuDelegate) {
        BehaviorDelegate.initialize();
        _view = view;
        _menu = menu;
        _menuDelegate = menuDelegate;
    }

    function onSelect() as Boolean {
        _view.onSelectPressed();
        return true;
    }

    function onMenu() as Boolean {
        WatchUi.pushView(_menu, _menuDelegate, WatchUi.SLIDE_IMMEDIATE);
        return true;
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
// play/pause, menu opens the transport menu. Play control stays usable;
// the cadence and control pages are unreachable on that one model.
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
