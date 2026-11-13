# ID3 Tag / Metadata Integration

Store BPM in audio file metadata (ID3v2 `TBPM` frame) for interoperability with other players.
Uses Android's `MediaMetadataEditor` (API 33+); no extra libraries. Writes go through per-file
MediaStore write grants — the app does **not** declare `MANAGE_EXTERNAL_STORAGE` (legacy
`WRITE_EXTERNAL_STORAGE` covers API ≤ 29 only).

## API
```kotlin
// TrackRepository
saveBpmToFileMetadata(trackId, bpm): Boolean
readBpmFromFileMetadata(trackId): Float?
syncBpmToFileMetadata(trackId): Boolean
syncAllBpmToFileMetadata(concurrency = 4): Int
canModifyTrackFileMetadata(trackId): Boolean
// LibraryViewModel: same operations + hasFileModificationPermission(), isMetadataEditingAvailable()
```

## Implementation
- `audio/MetadataEditor.kt` — save/read BPM, `canHandleFile`, `isFileWritable` (reflection for API compat)
- `audio/MetadataWorker.kt` — batch processing, 4 concurrent workers, sequential fallback, max 2 retries
- `util/PermissionUtils.kt` — runtime permission checks
- UI: sync button in Library toolbar, long-press context menu, permission/progress/confirmation dialogs

## Requirements & limits
- API 33+ for MediaMetadataEditor; supported types: MP3, FLAC, OGG, WAV, M4A, AAC
- Below API 33, full support would need an extra tag library (jaudiotagger) — not shipped
- User grants file access at runtime; denying it only disables metadata writes, not playback

## Test checklist
Android 13+ device → permission flow → single save → batch sync with progress → error handling → MP3/FLAC/…
