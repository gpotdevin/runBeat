using Toybox.App;
using Toybox.Communications;
using Toybox.System;
using Toybox.WatchUi;

// RunBeat companion watch app (Connect IQ / Monkey C).
//
// Remote control for the RunBeat Android app (and, through phone-side
// routing, any other music app on the phone). The watch never plays audio
// itself; it only exchanges messages with the phone companion
// (GarminCompanionManager, see docs/companion_garmin_app/architecture.md).
class RunBeatCompanionApp extends Toybox.App.AppBase {

    private var _state;           // WatchState
    private var _cadenceBuffer;   // CadenceRingBuffer
    private var _cadenceSampler;  // CadenceSampler
    private var _messages;        // MessageHandler

    function initialize() {
        Toybox.App.AppBase.initialize();
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
        var pager = new WatchUi.SwitchableView([
            new MainView(_state, _messages),
            new CadenceView(_state, _messages, _cadenceBuffer),
            new ControlView(_state, _messages),
        ]);
        return [pager, new RunBeatDelegate(pager, _state, _messages)];
    }

    function onCadenceTick() as Void {
        // 1 Hz redraw: live cadence number and ack expiry.
        WatchUi.requestUpdate();
    }
}

// App-level input delegate: up/down switch pages, select or tap triggers the
// current page's primary action, menu opens the current page's menu.
class RunBeatDelegate extends WatchUi.InputDelegate {

    private var _pager;      // WatchUi.SwitchableView
    private var _state;      // WatchState
    private var _messages;   // MessageHandler

    function initialize(pager, state, messages) {
        InputDelegate.initialize();
        _pager = pager;
        _state = state;
        _messages = messages;
    }

    function onKey(evt as WatchUi.KeyEvent) as Boolean {
        var key = evt.getKey();
        if (key == WatchUi.KEY_ENTER) {
            return onSelect();
        } else if (key == WatchUi.KEY_MENU) {
            showMenu();
            return true;
        } else if (key == WatchUi.KEY_UP) {
            _pager.previousView();
            return true;
        } else if (key == WatchUi.KEY_DOWN) {
            _pager.nextView();
            return true;
        }
        // BACK and everything else: default behavior (exit the app).
        return false;
    }

    function onTap(evt as WatchUi.TapEvent) as Boolean {
        // Touch-first devices have no ENTER key: a tap triggers the same
        // per-page primary action as select.
        return onSelect();
    }

    private function onSelect() as Boolean {
        var current = _pager.getCurrentView();
        if (current instanceof MainView) {
            (current as MainView).onSelectPressed();
            return true;
        } else if (current instanceof CadenceView) {
            (current as CadenceView).onSelectPressed();
            return true;
        } else if (current instanceof ControlView) {
            (current as ControlView).onSelectPressed();
            return true;
        }
        return false;
    }

    private function showMenu() as Void {
        var current = _pager.getCurrentView();
        if (current instanceof MainView) {
            WatchUi.pushView(new WatchUi.Menu(Rez.Menus.MainViewMenu),
                new MainViewMenuDelegate(_messages), WatchUi.SLIDE_IMMEDIATE);
        } else if (current instanceof CadenceView) {
            WatchUi.pushView(new WatchUi.Menu(Rez.Menus.CadenceViewMenu),
                new CadenceViewMenuDelegate(current as CadenceView, _messages, _state),
                WatchUi.SLIDE_IMMEDIATE);
        } else if (current instanceof ControlView) {
            WatchUi.pushView(new WatchUi.Menu(Rez.Menus.ControlViewMenu),
                new ControlViewMenuDelegate(_messages, _state), WatchUi.SLIDE_IMMEDIATE);
        }
    }
}
