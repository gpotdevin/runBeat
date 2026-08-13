# Metadata Editing Implementation

## Overview
Enables saving BPM values to audio file ID3 tags using Android's MediaMetadataEditor (API 33+).

## Files Added
- `app/src/main/java/com/bpmapp/audio/util/PermissionUtils.kt` - Permission handling
- `app/src/main/java/com/bpmapp/audio/audio/MetadataEditor.kt` - Core metadata editing
- `app/src/main/java/com/bpmapp/audio/audio/MetadataWorker.kt` - Parallel processing

## Files Modified
- `app/src/main/AndroidManifest.xml` - Added MANAGE_EXTERNAL_STORAGE permission
- `app/src/main/java/com/bpmapp/audio/audio/TrackRepository.kt` - Added metadata methods
- `app/src/main/java/com/bpmapp/audio/viewmodel/LibraryViewModel.kt` - Added ViewModel methods
- `app/src/main/java/com/bpmapp/audio/ui/LibraryScreen.kt` - Added UI components

## Key Classes

### PermissionUtils
- `hasManageExternalStoragePermission()` - Check file access permission
- `requestManageExternalStoragePermission()` - Request permission
- `canModifyFile()` - Check if specific file can be modified

### MetadataEditor
- `isMediaMetadataEditorAvailable()` - Check API 33+ availability
- `saveBpmToFile(uri, bpm)` - Save BPM to file metadata
- `readBpmFromFile(uri)` - Read BPM from file metadata
- `saveMetadata(uri, metadata)` - Save multiple metadata fields
- `canHandleFile(uri)` - Check file type support
- `isFileWritable(uri)` - Check file writability

### MetadataWorker
- `processTracksInParallel(tracks, concurrency, onProgress, onComplete)` - Batch processing
- `processTracksSequentially(tracks, onProgress, onComplete)` - Sequential fallback
- Uses 4 concurrent workers by default

## Usage

### Single Track
```kotlin
viewModel.saveBpmToFileMetadata(trackId, 128.0f)
viewModel.syncBpmToFileMetadata(trackId)
```

### Batch Operations
```kotlin
viewModel.syncAllBpmToFileMetadata()
```

### Permission Check
```kotlin
val hasPermission = viewModel.hasFileModificationPermission()
val available = viewModel.isMetadataEditingAvailable()
```

## UI Components
- Toolbar sync button
- Track context menu (long-press)
- Permission dialogs
- Progress dialogs
- Confirmation dialogs

## Requirements
- Android 13+ (API 33+) for MediaMetadataEditor
- MANAGE_EXTERNAL_STORAGE permission
- Supported file types: MP3, FLAC, OGG, WAV, M4A, AAC

## Limitations
- Android < 13: Requires jaudiotagger library for full functionality
- User must manually grant file access permission in settings