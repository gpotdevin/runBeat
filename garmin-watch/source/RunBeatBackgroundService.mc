import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;

// Background service: re-activation without running.
//
// RunBeatCompanionApp registers Background.registerForPhoneAppMessageEvent()
// on start; the registration persists across app restarts, so whenever the
// phone companion sends this app a message while the foreground app is NOT
// running, the system starts this background task (<= 30 s, 32 KB heap) and
// delivers the message here.
//
// The phone sends exactly one message in that situation:
//   {"type":"wake"}  — playback started while no watch app was active.
// The service answers with the launch-confirmation dialog
// (Background.requestApplicationWake): the dialog only appears after the
// background task exits, so Background.exit(null) follows immediately.
// Everything else (a stray state push, a malformed message) is ignored.
//
// CAUTION: the (:background) annotation keeps this class out of the
// foreground build; do not reference foreground classes from here (the
// background heap only loads annotated code).
(:background)
class RunBeatBackgroundService extends System.ServiceDelegate {

    function initialize() {
        ServiceDelegate.initialize();
    }

    function onPhoneAppMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        // The background heap must stay tiny: no JSON parser, just a
        // substring match on the exact wire format the phone emits
        // (GarminMessageCodec.encodeWake, no whitespace).
        if (data instanceof String
            && (data as String).find("\"type\":\"wake\"") != null) {
            Background.requestApplicationWake("RunBeat is playing - open remote?");
        }
        Background.exit(null);
    }
}
