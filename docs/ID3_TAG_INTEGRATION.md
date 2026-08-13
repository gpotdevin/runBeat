# ID3 Tag Integration Guide

## Purpose
Store BPM values in audio file metadata for interoperability with other music players.

## Implementation
- Uses MediaMetadataEditor (Android 13+) for ID3 tag modification
- Stores BPM in standard TBPM frame
- Parallel processing for batch operations
- Runtime permission handling

## API Reference

### TrackRepository
```kotlin
saveBpmToFileMetadata(trackId: String, bpm: Float): Boolean
readBpmFromFileMetadata(trackId: String): Float?
syncBpmToFileMetadata(trackId: String): Boolean
syncAllBpmToFileMetadata(concurrency: Int = 4): Int
canModifyTrackFileMetadata(trackId: String): Boolean
```

### LibraryViewModel
```kotlin
saveBpmToFileMetadata(trackId: String, bpm: Float)
syncBpmToFileMetadata(trackId: String)
syncAllBpmToFileMetadata()
hasFileModificationPermission(): Boolean
isMetadataEditingAvailable(): Boolean
canModifyTrackFileMetadata(trackId: String): Boolean
```

## UI Integration
- Sync button in LibraryScreen toolbar
- Track context menu on long-press
- Permission dialogs with explanations
- Progress indicators for batch operations

## Technical Details
- ID3v2 TBPM frame for BPM storage
- Reflection for API compatibility
- Coroutine-based parallel processing
- Automatic retry on failures (max 2)

## Dependencies
- AndroidX Media3 (already included)
- No additional libraries required for API 33+

## Testing Checklist
- [ ] Android 13+ device with test files
- [ ] Permission request flow
- [ ] Single track BPM save
- [ ] Batch BPM sync
- [ ] Progress tracking
- [ ] Error handling
- [ ] Various file formats (MP3, FLAC, etc.)