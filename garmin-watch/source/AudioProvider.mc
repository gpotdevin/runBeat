import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Media;
import Toybox.WatchUi;

// Audio-content-provider plumbing (manifest type
// "audio-content-provider-app" makes RunBeat appear as a music provider
// on the watch). RunBeat never serves or plays audio on the watch — all
// playback stays on the phone — so the content iterator is empty and
// sync is a no-op; the provider entry point leads to the remote UI.
// Shape follows the SDK's own audio-content-provider-app template.

// The system music player's view into "content" — RunBeat has none on
// the watch, so the iterator yields nothing.
class RunBeatContentDelegate extends Media.ContentDelegate {

    function initialize() {
        ContentDelegate.initialize();
    }

    function getContentIterator() as ContentIterator? {
        return new RunBeatEmptyContentIterator();
    }

    function resetContentIterator() as ContentIterator? {
        return new RunBeatEmptyContentIterator();
    }
}

// An iterator over zero tracks (all accessors return null / false).
class RunBeatEmptyContentIterator extends Media.ContentIterator {

    function initialize() {
        ContentIterator.initialize();
    }

    function canSkip() as Boolean {
        return false;
    }

    function get() as Content? {
        return null;
    }

    function getPlaybackProfile() as PlaybackProfile? {
        var profile = new Media.PlaybackProfile();
        profile.attemptSkipAfterThumbsDown = false;
        profile.playbackControls = [
            PLAYBACK_CONTROL_SKIP_BACKWARD,
            PLAYBACK_CONTROL_PLAYBACK,
            PLAYBACK_CONTROL_SKIP_FORWARD
        ];
        profile.playbackNotificationThreshold = 1;
        profile.requirePlaybackNotification = false;
        profile.skipPreviousThreshold = null;
        return profile;
    }

    function next() as Content? {
        return null;
    }

    function peekNext() as Content? {
        return null;
    }

    function peekPrevious() as Content? {
        return null;
    }

    function previous() as Content? {
        return null;
    }

    function shuffling() as Boolean {
        return false;
    }
}

// "Configure sync" view: nothing to sync — music plays on the phone.
class RunBeatSyncConfigView extends WatchUi.View {

    function initialize() {
        View.initialize();
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(w / 2, h / 2, Graphics.FONT_SMALL,
            WatchUi.loadResource(Rez.Strings.PhonePlaybackOnly) as String,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

class RunBeatSyncConfigDelegate extends WatchUi.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }

    // Back (button or right-swipe) leaves the sync configuration.
    function onBack() as Boolean {
        return false;
    }
}
