# RunBeat Privacy Note

**Last updated: 2026-09-12**

RunBeat is an offline-first, BPM-based audio player for runners. This note explains what data the app handles and which permissions it uses.

## Data collection

**RunBeat does not collect, transmit, or share any personal data.**

- No accounts, no sign-in, no analytics, no advertising, no tracking SDKs.
- No network requests are made on your behalf. The app works fully offline.
- Your music library, BPM values, playlists, and settings are stored **only on your device**, in a local Room database.
- Nothing leaves your device. There is no server-side component operated by the developer.

## Permissions

| Permission | Why RunBeat requests it |
|---|---|
| `READ_MEDIA_AUDIO` (Android 13+) | Read your audio files so they can be listed, played, and analyzed. |
| `READ_EXTERNAL_STORAGE` (Android 12 and below, `maxSdkVersion=32`) | Legacy equivalent of `READ_MEDIA_AUDIO` for older Android versions. |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (Android 13+) | Declared alongside `READ_MEDIA_AUDIO` as part of the media-access group; used only to read audio files. No image or video data is processed or transmitted. |
| `MANAGE_EXTERNAL_STORAGE` | Required to write BPM values back into audio file metadata (ID3 `TBPM` frame) so other music players can reuse them. Granted only on explicit user action; the app never modifies files you did not select. |
| `WRITE_EXTERNAL_STORAGE` (Android 9 and below, `maxSdkVersion=28`) | Legacy equivalent of `MANAGE_EXTERNAL_STORAGE` for writing metadata on very old Android versions. |
| `RECORD_AUDIO` | Used solely for optional automatic BPM detection (via the aubio library), which analyses audio characteristics locally. Microphone input is processed on-device and is **never recorded, stored, or transmitted**. |
| `MODIFY_AUDIO_SETTINGS` | Lets the audio engine configure playback parameters for tempo adjustment without pitch change. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keeps music playing in the background while you run, when the screen is off or another app is in front. |
| `POST_NOTIFICATIONS` (Android 13+) | Shows the media playback notification with playback controls. |

No permission is used to collect or send data off-device. Permissions that involve microphone or file access are requested at runtime only when the relevant feature is used, and can be denied without breaking core playback.

## Children's privacy

RunBeat is a general-purpose music tool and is not directed at children. No data is collected from anyone, regardless of age.

## Changes to this note

Material changes to data handling or permissions will be reflected in this document. Since no data is collected, the practical impact of any change is limited to how on-device permissions are used.

## Contact

Source code and issues: <https://github.com/gpotdevin/runBeat>
