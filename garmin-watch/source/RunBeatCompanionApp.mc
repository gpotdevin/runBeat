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
// Remote control for the RunBeat Android app only (the phone ignores
// commands when RunBeat is not the active player). The watch never plays
// audio itself; it only exchanges messages with the phone companion
// (GarminCompanionManager, see docs/companion_garmin_app/architecture.md).
//
// The app is an audio content provider (manifest type
// "audio-content-provider-app") so it is reachable from the watch's Music
// app / provider list and the music shortcut, like Spotify. RunBeat plays
// no audio on the watch: the content iterator is empty (AudioProvider.mc)
// and the playback-configuration view IS the remote UI. The app-menu
// launch (getInitialView) goes straight to the same remote UI without the
// stock Music app as host.
//
// Recognition handshake: on start (and, while the state is stale, with
// exponential backoff) the watch sends {"type":"request_state"}; the
// phone answers the sender with one state push. This fixes the "phone
// not recognized" case where the watch app starts after the phone
// already pushed its state.
//
// Battery: the cadence tick repaints the screen ONLY when visible
// content changed (deferred position push, connection-header flip, ack
// expiry, changed cadence number) instead of unconditionally — a full
// repaint every second was the main battery drain. The tick itself is
// ADAPTIVE (reconcileTickPeriod): 1 Hz while music is playing, 5 s while
// paused — a repeating 1 s Timer wakes the CPU 60x/min even when the
// tick has nothing to do. The SENSOR READ additionally runs only while
// music is playing (shouldSampleCadence): when paused, the slower tick
// still fires but touches no sensor, so an open but idle app costs
// almost nothing. The stale retry backs off (5 -> 10 -> 20 -> 40 -> 60 s)
// so an unreachable phone does not keep waking the BLE radio every 5 s.
// Battery / lifecycle: the app is a remote control and must not run when
// it is not needed.
//   - It repaints only when visible content changed (see shouldRedraw).
//   - It goes inert and exits itself (System.exit) after IDLE_EXIT_MS
//     without user interaction once music has stopped or the phone link is
//     stale — exitApp() stops all timers, unregisters the phone-message
//     listener and sends "bye" FIRST, so even where System.exit is a no-op
//     the app stops sending request_state keepalives and the phone's push
//     window expires: a closed/idle app costs the watch nothing.
//   - onStop / exitApp send "bye" so the phone stops its state stream
//     stream immediately, and UNREGISTER the phone-message listener
//     (registerForPhoneAppMessages(null)): System.exit() is a no-op in
//     this provider context, so the process can linger after the view
//     pops — a registered callback keeps it wakeable by BLE traffic
//     (known CIQ drain, forums.garmin.com connect-iq discussion/872).
//   - The app connects to the phone ONLY when started manually: there is
//     no background service and no wake message — the phone never
//     initiates contact (that drained the watch battery); the request_state
//     handshake on start is what arms the phone's push window.
//   - Back at a page root runs the same cleanup as the idle exit
//     (exitApp): send "bye", unregister the phone-message listener, stop the
//     cadence sampler and the 5 s state timer, THEN attempt System.exit —
//     and never swallows the event, so the system pop (to the stock
//     Music app in the provider flow, to the watch face from the
//     app-menu launch) still happens.
//   - While no page is visible (backlight off / covered / another view
//     pushed) the page views' onHide suspends all periodic work
//     (cadence sampler + state timer); onShow resumes it.
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

    // ViewLoop page indexes (RunBeatViewLoopFactory.getView): only MainView
    // renders the playback position, only CadenceView/CadenceSetView render
    // the cadence mean — the 1 Hz tick gates its repaints on what the
    // visible page actually draws. The fallback (no ViewLoop) is always
    // the main page.
    private static const PAGE_MAIN = 0;
    private static const PAGE_CADENCE = 2;
    private static const PAGE_COUNT = 4;
    private var _currentPage as Number = 0;      // visible ViewLoop page

    // Called by RunBeatViewLoopDelegate on every page change (+1/-1 with
    // wrap-around, ViewLoop is created with :wrap => true) so shouldRedraw
    // knows which page is visible.
    function notePageDelta(delta as Number) as Void {
        var page = (_currentPage + delta) % PAGE_COUNT;
        if (page < 0) {
            page += PAGE_COUNT;
        }
        _currentPage = page;
    }

    // Called by the delegates' onMenu right before pushing a page menu: the
    // 1 Hz tick must not repaint the static menu (BaseView.onShow clears
    // the flag when a page becomes visible again).
    function noteMenuPushed() as Void {
        var s = _state;
        if (s != null) {
            (s as WatchState).menuOpen = true;
        }
    }

    // Auto-exit: the last user interaction (any tap/key/menu in a
    // delegate — see noteUserActivity). Once IDLE_EXIT_MS has passed with
    // music stopped (or the phone link stale) the app exits itself.
    private var _lastUserAt as Number = 0;

    private static const IDLE_EXIT_MS = 120000; // 2 minutes

    // Playing keepalive period. The phone pushes state ONLY within 30 s of
    // the last watch->phone message, so while playing the watch re-arms that
    // window with a request_state every KEEPALIVE_PLAYING_MS (20 s leaves a
    // 10 s margin; was 5 s — 6x more radio traffic than the window needs).
    private static const KEEPALIVE_PLAYING_MS = 20000; // 20 seconds

    // Called by the input delegates (via Application.getApp()) on every
    // user interaction.
    function noteUserActivity() as Void {
        _lastUserAt = System.getTimer();
    }

    // Battery gate for the cadence sampler: the SENSOR READ runs
    // only while music is playing — the 60 s average is only meaningful
    // during a run. When paused, the (slower) tick still fires (it drives
    // the idle exit and the conditional repaint) but CadenceSampler
    // performs no sensor read at all, so a paused, open app costs almost
    // nothing.
    function shouldSampleCadence() as Boolean {
        var s = _state;
        return s != null && (s as WatchState).isPlaying;
    }

    // Adaptive tick periods (battery): 1 Hz while playing (the cadence
    // sample and a smooth progress bar need it), 5 s while paused (the
    // tick then only drives the idle-exit / repaint / auto-pop checks —
    // nothing time-critical at 1 Hz, and a 1 s Timer wakes the CPU
    // 60x/min for nothing).
    private static const TICK_PLAYING_MS = 1000; // 1 second
    private static const TICK_IDLE_MS = 5000;    // 5 seconds

    // Aligns the cadence sampler's timer period with the playing state.
    // Called from BOTH periodic ticks (onCadenceTick and onStateTimerTick)
    // so a play/pause transition is picked up within at most 5 s even when
    // the cadence tick itself is running at the 5 s idle period.
    private function reconcileTickPeriod() as Void {
        var sampler = _cadenceSampler;
        if (sampler != null) {
            (sampler as CadenceSampler).setPeriodMs(
                shouldSampleCadence() ? TICK_PLAYING_MS : TICK_IDLE_MS);
        }
    }

    private var _foregroundStarted as Boolean = false;
    private var _visible as Boolean = false;   // a page view is currently shown

    function initialize() {
        Application.AudioContentProviderApp.initialize();
    }
 
    function onStart(state as Dictionary?) as Void {
        // Nothing here: foreground setup happens lazily in
        // ensureForegroundStarted(), called from the foreground-only entry
        // points (getInitialView / getPlaybackConfigurationView).
    }
 
    // Foreground-only startup (idempotent). Called from getInitialView() /
    // getPlaybackConfigurationView() — never from the background path.
    private function ensureForegroundStarted() as Void {
        if (_foregroundStarted) {
            return;
        }
        _foregroundStarted = true;
        _state = new WatchState();
        _cadenceBuffer = new CadenceRingBuffer();
        _cadenceSampler = new CadenceSampler(_cadenceBuffer, method(:onCadenceTick), method(:shouldSampleCadence));
        _messages = new MessageHandler(_state);
        // Receive state/ack pushes from the phone companion.
        Communications.registerForPhoneAppMessages(_messages.method(:onPhoneMessage));
        // Handshake: ask for the current state right away (the phone may
        // have pushed it before this app started listening).
        _messages.sendRequestState();
        _lastRequestAt = System.getTimer();
        _retryDelayMs = 5000;
        _lastUserAt = System.getTimer();
        // Cadence sampling tick (adaptive: 1 Hz playing / 5 s paused —
        // reconcileTickPeriod aligns it); also drives the conditional
        // screen refresh and the idle exit.
        _cadenceSampler.start();
        // Re-ask with exponential backoff while no message arrived in the
        // last 10 s: covers "no message ever" and a dropped link; recovery
        // is automatic once the phone answers.
        var timer = new Timer.Timer();
        timer.start(method(:onStateTimerTick), 5000, true);
        _stateTimer = timer;
        // This entry point is foreground-only, so the first page's onShow
        // must not re-run the resume path (it would double the handshake).
        _visible = true;
        System.println("RunBeat companion started");
    }

    function onStop(state as Dictionary?) as Void {
        // Best-effort farewell: the phone drops its watch-app active
        // window immediately and stops the state stream, instead of
        // waiting for the window to expire (battery: a closed app must
        // cost the watch nothing). Null-guarded: in the background context
        // onStop can run without the foreground objects ever existing.
        var messages = _messages;
        if (messages != null) {
            (messages as MessageHandler).sendBye();
        }
        // Unregister the phone-message listener (API: registerForPhoneAppMessages
        // accepts null to unregister). Known CIQ drain (forums.garmin.com
        // connect-iq discussion/872 + discussion/304686): a callback that is
        // not unregistered keeps the app process alive/wakeable after the app
        // is closed — and System.exit() is a no-op in this provider context,
        // so the process can linger with a live BLE listener. Passing null
        // here is what actually makes a closed app cost nothing.
        Communications.registerForPhoneAppMessages(null);
        var sampler = _cadenceSampler;
        if (sampler != null) {
            (sampler as CadenceSampler).stop();
        }
        var timer = _stateTimer;
        if (timer != null) {
            (timer as Timer.Timer).stop();
            _stateTimer = null;
        }
        // Reset the foreground-start flag and drop the sampler reference so
        // re-entering the app in the same process starts cleanly again
        // (ensureForegroundStarted re-creates everything).
        _foregroundStarted = false;
        _cadenceSampler = null;
        _visible = false;
    }

    function onCadenceTick() as Void {
        var now = System.getTimer();
        // Keep the tick period aligned with playing/paused (1 Hz / 5 s).
        reconcileTickPeriod();
        // The app is a remote control: once the user is idle and music has
        // stopped (or the phone link is stale), nothing on screen can
        // change — go inert and exit instead of burning power until the
        // system kills us.
        if (shouldExit(now)) {
            exitApp();
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

    // Full cleanup + exit attempt. Used by BOTH the idle auto-exit and
    // the back path (the delegates' onBack routes here): stop ALL
    // periodic work first, then try System.exit().
    // Order matters for battery: the cleanup is what actually stops the BLE
    // cost — no more request_state keepalives are sent, the "bye" drops the
    // phone's watch-app active window immediately, the listener is
    // unregistered and the stopped timers mean no further radio or repaint
    // activity even if the process lingers until the system's own
    // inactivity timeout (System.exit() may or may not take effect).
    function exitApp() as Void {
        var messages = _messages;
        if (messages != null) {
            (messages as MessageHandler).sendBye();
        }
        // Unregister the phone-message listener BEFORE anything else can
        // linger: System.exit() is a no-op from this provider context, so
        // the process may survive the view pop — a still-registered
        // callback would keep it wakeable by BLE traffic (battery; see
        // onStop). Re-entry re-registers via ensureForegroundStarted.
        Communications.registerForPhoneAppMessages(null);
        _messages = null;
        _foregroundStarted = false;
        var sampler = _cadenceSampler;
        if (sampler != null) {
            (sampler as CadenceSampler).stop();
            _cadenceSampler = null;
        }
        var timer = _stateTimer;
        if (timer != null) {
            (timer as Timer.Timer).stop();
            _stateTimer = null;
        }
        try {
            System.exit();
        } catch (e) {
            // Exit unavailable in this context: the app is already inert
            // (see above), the system pop/timeout is the way out.
        }
    }

    // Visibility hook for the page views (BaseView.onShow/onHide): while
    // the app is not visible (backlight off, covered, another view
    // pushed) all periodic work is suspended; it resumes on return.
    // Transition-guarded so the initial onShow after
    // ensureForegroundStarted is a no-op.
    function onAppVisible(visible as Boolean) as Void {
        if (visible == _visible) {
            return;
        }
        _visible = visible;
        if (!_foregroundStarted) {
            return;
        }
        if (visible) {
            resumeForeground();
        } else {
            pauseForeground();
        }
    }

    // Hidden: suspend ALL periodic work — no 1 Hz sensor reads, no 5 s
    // BLE keepalives, no repaint ticks — until the app is visible again.
    // No "bye" here: the app is still open, only covered.
    private function pauseForeground() as Void {
        var sampler = _cadenceSampler;
        if (sampler != null) {
            (sampler as CadenceSampler).stop();
        }
        var timer = _stateTimer;
        if (timer != null) {
            (timer as Timer.Timer).stop();
            _stateTimer = null;
        }
    }

    // Visible again: resume the 1 Hz cadence sampler and the 5 s state
    // timer, and re-arm the phone link right away if it went stale while
    // hidden (no handshake while still fresh — that would be extra BLE).
    private function resumeForeground() as Void {
        var sampler = _cadenceSampler;
        if (sampler != null) {
            (sampler as CadenceSampler).start();
        }
        if (_stateTimer == null) {
            var timer = new Timer.Timer();
            timer.start(method(:onStateTimerTick), 5000, true);
            _stateTimer = timer;
            var now = System.getTimer();
            var messages = _messages;
            if (messages != null && _state.isStale(now)) {
                (messages as MessageHandler).sendRequestState();
                _lastRequestAt = now;
                _retryDelayMs = 5000;
            }
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

    // True when something VISIBLE changed since the last repaint, page-aware:
    // a position-only state push deferred by MessageHandler repaints only
    // when MainView (the only page with a progress bar) is visible and not
    // covered; the connection header flip and ack expiry repaint every page
    // (both are drawn by BaseView) but never an open menu; the displayed
    // cadence number repaints only the cadence page / cadence adjuster.
    // Paused and standing still, nothing repaints at all.
    private function shouldRedraw(now as Number) as Boolean {
        var screen = _state.adjustScreen;
        // Position is rendered only by MainView — on every other page (and
        // while a menu/adjust screen covers it) the deferred position push
        // is consumed and discarded instead of triggering a repaint.
        if (_state.consumePositionDirty()) {
            if (screen == null && !_state.menuOpen && _currentPage == PAGE_MAIN) {
                return true;
            }
        }
        // Menus are static: never repaint them from the 1 Hz tick. Keep the
        // trackers in sync so no extra repaint fires right after the menu
        // pops (the pop itself repaints the page beneath).
        if (_state.menuOpen) {
            _wasStale = _state.isStale(now);
            _ackWasVisible = _state.isAckVisible(now);
            return false;
        }
        var stale = _state.isStale(now);
        if (stale != _wasStale) {
            _wasStale = stale;
            // An adjust screen draws no header/ack line: sync without painting.
            if (screen == null) {
                return true;
            }
        }
        var ackVisible = _state.isAckVisible(now);
        if (ackVisible != _ackWasVisible) {
            _ackWasVisible = ackVisible;
            if (screen == null) {
                return true;
            }
        }
        // The cadence mean is displayed only by the cadence page and the
        // pushed cadence adjuster — compare the formatted "%.0f" value so
        // sub-display drift does not repaint, and skip the check entirely
        // on pages that never show cadence (MainView, VolumeSetView, ...).
        var showsCadence = _currentPage == PAGE_CADENCE &&
            (screen == null || screen instanceof CadenceSetView);
        if (showsCadence) {
            var mean = _cadenceBuffer.mean(now);
            var meanText = mean == null ? "" : (mean as Float).format("%.0f");
            if (!meanText.equals(_lastMeanText)) {
                _lastMeanText = meanText;
                return true;
            }
        }
        return false;
    }

    // 5 s retry tick. Two roles:
    //   - Keepalive while playing and fresh: the phone pushes state ONLY
    //     to a recently-heard-from watch app (30 s window — battery, a
    //     closed app must cost nothing), and while playing the app itself
    //     sends nothing, so this tick re-arms the phone's active window —
    //     but only every KEEPALIVE_PLAYING_MS (20 s), not every tick.
    //   - Stale retry with exponential backoff (5 -> 10 -> 20 -> 40 -> 60
    //     s cap): an unreachable phone must not trigger a BLE session
    //     attempt every 5 s forever. The backoff resets as soon as the
    //     state is fresh again (any message arriving does that), so a live
    //     link still recovers within one tick.
    function onStateTimerTick() as Void {
        var now = System.getTimer();
        // A play/pause transition may have happened since the last cadence
        // tick (which can be up to 5 s away while paused) — realign now.
        reconcileTickPeriod();
        if (!_state.isStale(now)) {
            _retryDelayMs = 5000;
            if (_state.isPlaying && now - _lastRequestAt >= KEEPALIVE_PLAYING_MS) {
                _messages.sendRequestState();
                _lastRequestAt = now;
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
        // Foreground entry point — safe to build the foreground objects.
        ensureForegroundStarted();
        // ViewLoop (API 3.4.0) replaces SwitchableView. Only a real fr245
        // running CIQ 3.3.1 (< 3.4.0) takes the fallback: main page only.
        if (WatchUi has :ViewLoop) {
            var factory = new RunBeatViewLoopFactory(_state, _messages, _cadenceBuffer);
            var viewLoop = new WatchUi.ViewLoop(factory,
                {:page => 0, :wrap => true, :color => Graphics.COLOR_GREEN});
            return [viewLoop, new RunBeatViewLoopDelegate(viewLoop)];
        }
        return [new MainView(_state, _messages), new RunBeatFallbackDelegate(_messages, _state)];
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

    // Reached from the watch's music provider list / music shortcut: the
    // remote UI itself (pushed on top of the stock Music app, which stays
    // as host — back from here pops to it; that is firmware behavior).
    function getPlaybackConfigurationView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return getInitialView();
    }

    // Nothing to sync — a note screen with back.
    function getSyncConfigurationView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return [new RunBeatSyncConfigView(), new RunBeatSyncConfigDelegate()];
    }

    // Provider branding in the Music app's provider list (like Spotify's
    // icon): reuse the launcher icon with a green background color.
    function getProviderIconInfo() as Media.ProviderIconInfo? {
        return new Media.ProviderIconInfo(Rez.Drawables.LauncherIcon, Graphics.COLOR_GREEN);
    }
}

// ViewLoop factory: one view/delegate pair per page
// (0 = MainView, 1 = ControlView, 2 = CadenceView, 3 = PlaylistsView).
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
        } else if (page == 3) {
            // Playlists page is tap-only: no menu (onMenu guards a null
            // menu and returns false).
            var view = new PlaylistsView(_state, _messages);
            return [view, new RunBeatPageDelegate(view,
                null,
                null,
                view.method(:onTap),
                null)];
        }
        var view = new MainView(_state, _messages);
        return [view, new RunBeatPageDelegate(view,
            new Rez.Menus.MainViewMenu(),
            new MainViewMenuDelegate(_messages, _state),
            view.method(:onTap),
            view.method(:onHorizontalSwipe))];
    }

    function getSize() {
        return 4;
    }
}

// Per-page input delegate: select triggers the page's primary action, menu
// pushes the page's menu (with its existing MenuInputDelegate). Pages
// additionally carry optional tap/swipe handlers (Methods or null): taps
// are mapped to the page's drawn controls, horizontal swipes to the page's
// gesture (prev/next track on page 0).
class RunBeatPageDelegate extends WatchUi.BehaviorDelegate {

    private var _view;          // MainView | ControlView | CadenceView | PlaylistsView
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

    // Back at a page root runs the same cleanup as the idle exit
    // (RunBeatCompanionApp.exitApp): send "bye", unregister the phone
    // message listener, stop the cadence sampler and the 5 s state timer,
    // THEN attempt System.exit(). The cleanup is what actually stops the
    // background sensor/BLE/repaint loop. The event is still NEVER
    // swallowed (return false): returning true would trap the user if
    // System.exit does nothing in the provider context, so the system pop
    // (to the stock Music app there, to the watch face from the app-menu
    // launch) always happens.
    function onBack() as Boolean {
        var app = Application.getApp();
        if (app instanceof RunBeatCompanionApp) {
            (app as RunBeatCompanionApp).exitApp();
        } else {
            try {
                System.exit();
            } catch (e) {
                // Exit unavailable here — the fall-through pop is the way
                // out.
            }
        }
        return false;
    }

    function onMenu() as Boolean {
        noteActivity();
        var menu = _menu;
        if (menu == null) {
            // Tap-only page (playlists): no menu to push.
            return false;
        }
        var app = Application.getApp();
        if (app instanceof RunBeatCompanionApp) {
            (app as RunBeatCompanionApp).noteMenuPushed();
        }
        WatchUi.pushView(menu, _menuDelegate, WatchUi.SLIDE_IMMEDIATE);
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
        notePageDelta(1);
        return true;
    }

    function onPreviousView() as Boolean {
        _viewLoop.changeView(WatchUi.ViewLoop.DIRECTION_PREVIOUS);
        notePageDelta(-1);
        return true;
    }

    // Keeps the app's visible-page tracker in sync (wrap-around) so the
    // 1 Hz tick can gate repaints on what the visible page renders.
    private function notePageDelta(delta as Number) as Void {
        var app = Application.getApp();
        if (app instanceof RunBeatCompanionApp) {
            (app as RunBeatCompanionApp).notePageDelta(delta);
        }
    }
}

// Fallback input delegate for devices without ViewLoop support (a real
// fr245 running CIQ 3.3.1 < 3.4.0): the main page only — select toggles
// play/pause, menu opens the transport menu (4 flat items). Play control
// stays usable; the cadence and control pages are unreachable on that one
// model.
class RunBeatFallbackDelegate extends WatchUi.BehaviorDelegate {

    private var _messages;   // MessageHandler
    private var _state;      // WatchState

    function initialize(messages, state) {
        BehaviorDelegate.initialize();
        _messages = messages;
        _state = state;
    }

    function onSelect() as Boolean {
        noteActivity();
        _messages.sendToggle();
        return true;
    }

    // Back runs the same cleanup as the idle exit (bye + stop timers,
    // then System.exit) but always falls through to the system pop (see
    // RunBeatPageDelegate.onBack — returning true traps the user when
    // System.exit is a no-op on device).
    function onBack() as Boolean {
        var app = Application.getApp();
        if (app instanceof RunBeatCompanionApp) {
            (app as RunBeatCompanionApp).exitApp();
        } else {
            try {
                System.exit();
            } catch (e) {
            }
        }
        return false;
    }

    function onMenu() as Boolean {
        noteActivity();
        var app = Application.getApp();
        if (app instanceof RunBeatCompanionApp) {
            (app as RunBeatCompanionApp).noteMenuPushed();
        }
        WatchUi.pushView(new Rez.Menus.MainViewMenu(),
            new MainViewMenuDelegate(_messages, _state), WatchUi.SLIDE_IMMEDIATE);
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
