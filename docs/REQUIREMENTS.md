# RunBeat Requirements

> **Note**: This is the consolidated requirements document. For the original raw requirements, see [requirements/requirements.md](../requirements/requirements.md). For detailed analysis, see [requirements/REQUIREMENTS_ANALYSIS.md](../requirements/REQUIREMENTS_ANALYSIS.md).


## Functional Requirements

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-001 | Audio playback (MP3, WAV, FLAC, AAC, OGG) | Core |
| FR-002 | Tempo adjustment +/-20% with pitch preservation | Core |
| FR-003 | BPM:Cadence ratios: 1:1, 2:1, 3:2, 6:2 | Core | 
| FR-004 | Target cadence: 172 BPM (range: 160-195, increments: +/-1) | Core |
| FR-005 | OS integration (MediaSession, Bluetooth controls) | Core | 
| FR-006 | Automatic BPM detection | High | 
| FR-007 | Manual BPM input (tap-to-beat) | Core |
| FR-008 | Playback controls (play/pause/ff/rew/seek) | Core |
| FR-009 | Playlist management (create/edit/import) | Core |
| FR-010 | Shuffle/random play | Expected |
| FR-011 | Streaming (Qobuz/Spotify) | Low |
| FR-012 | Export modified music files | Low |

## Non-Functional Requirements

| ID | Requirement | Details |
|----|-------------|---------|
| NFR-001 | Target Platform | Android (v1), designed for future cross-platform |
| NFR-002 | License | MIT (permissive) |
| NFR-003 | Performance | <100ms latency for tempo changes |
| NFR-004 | User Interface | Runner-friendly (large buttons, clear BPM/cadence/ratio display) |

## Decisions Affecting Requirements

### LibraryScreen
All features implemented:
- Clear Library button in TopAppBar with confirmation
- CSV import with duplicate detection (filename + filesize)
- System library scan with duplicate detection
- Search field for filename, relativePath, album, artist, title
- Play All button for filtered tracks
- BPM displayed in green for known tracks, "unknown" for null
- Stats card showing total/known/unknown counts
- Filter by BPM status (known/unknown)
- Sort by BPM, Album, Artist
- Speed factor filter [0.9-1.1]
- Speed factor display per track
- Compact track cards
- Compact FilterBar (2 rows with overflow menu)
- Multi-select tracks (long press + click)
- Add selected tracks to playlist

### PlayerScreen
All features implemented:
- Open Audio File and Open Music Library buttons side-by-side (icon buttons)
- Tap BPM Section and Tempo/Cadence Section side-by-side with equal height (140dp)
- Upcoming Songs section connected to playlist
- Clear playlist button
- BPM transfer from library to player
- Unknown BPM shows "Tap Here" instead of "0"
- Save BPM to Library button with confirmation
- Speed correction toggle button (rightmost)
- Compact playback controls (all touch targets 48dp minimum)

### Pending Features

#### Playlist Management
- [ ] Import existing playlists (M3U, PLS formats)
- [ ] Repeat modes (none, one, all)
- [ ] Drag-and-drop reordering (handles added, implementation deferred)

#### Testing
- [ ] Unit tests for BPM calculations
- [ ] Unit tests for tempo adjustment logic
- [ ] Integration tests for audio pipeline
- [ ] Performance testing

## Technical Notes

### Track Model
```kotlin
data class Track(
    val id: String,
    val relativePath: String,
    val fileName: String,
    val bpm: Float?,  // Known BPM from CSV import or manual tap-to-beat
    val fileSizeBytes: Long?,
    val durationMs: Long?,
    val source: TrackSource,  // SYSTEM or APP
    val metadataTitle: String?,
    val metadataArtist: String?,
    val metadataAlbum: String?
)
```

### BPM Sources
1. **CSV Import**: Pre-analyzed BPM data from external library analyzer
2. **Manual Entry**: Tap-to-beat in PlayerScreen
3. **System**: Unknown (null) until imported or manually entered


## UI/UX Guidelines

1. **Consistency**: Use Material Design 3 system throughout
2. **Accessibility**: Ensure touch targets are at least 48dp
3. **Feedback**: Visual confirmation for all actions
4. **Error Handling**: Clear error messages with recovery options
5. **Performance**: Lazy loading for large lists, virtualization


