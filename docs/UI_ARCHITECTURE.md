# RunBeat — UI Architecture

Jetpack Compose + Material Design 3, MVVM (Hilt), state via Kotlin Flow/StateFlow collected with
`collectAsState()`, Navigation Compose with a persistent bottom bar. App is locked to portrait.

## Structure
```
app/src/main/java/com/bpmapp/audio/
├── MainActivity.kt            # Scaffold + NavHost + BottomNavigationBar; PlayerScreen inline
├── ui/
│   ├── LibraryScreen.kt       # library (tabs, filters, multi-select)
│   ├── navigation/BottomNav.kt  # NavDestination enum + bottom bar
│   ├── screens/               # BpmToolsScreen.kt, RunBeatIntroScreen.kt, SettingsScreen.kt
│   ├── components/TrackCard.kt
│   └── theme/                 # Theme.kt (colors/typography/spacing/BPM colors), TouchTargets.kt
└── viewmodel/                 # PlayerViewModel, LibraryViewModel, BpmToolsViewModel, SettingsViewModel
```

## Screens (bottom-nav destinations)
- **Player** (default; inline in `MainActivity.kt`): header + error card, `PlaybackControlsSection`
  (title, times, progress slider, 72dp play/pause FAB, grouped prev/stop/next 48dp, speed-correction
  toggle), `TempoAndBpmSection` (cadence −/value/+ , speed factor, original BPM), `PlaylistSection`
  (header opens Library, shuffle, save-playlist dialog, clear, LazyColumn of rows; empty state).
  `TapBpmSection` remains as unused dead code.
- **Library** (`ui/LibraryScreen.kt`): TopAppBar with inline search + overflow (Scan, Analyze BPM,
  Clear Library, Sync Metadata; CSV import moved to Settings → Advanced); tabs All / Playlists /
  Favorites; filter chips (BPM known, speed 0.9–1.1, browse-by Artist/Album/Genre/Path with N-level
  path drill-down + breadcrumbs); LazyColumn of TrackCards (~56dp; title/artist, BPM below, star /
  Add / overflow: Play, Add to Playlist, Edit BPM, Delete); multi-select toolbar (Play N, Add to
  Playlist, Add to Favorites). Search covers title/artist/album/filename/genre; filtering in
  LibraryViewModel, sorting in the screen.
- **BPM Tools** (`ui/screens/BpmToolsScreen.kt`): 3 tabs — Cadence Matcher (cadence slider 150–195,
  Binary/Ternary rhythm-pattern multi-select chips persisted in SharedPreferences, match list),
  Detect BPM (tap area, Aubio button; speed-adjusted warning + confirm dialog), Manual Entry
  (numeric field → save to ID3 TBPM).
- **Settings** (`ui/screens/SettingsScreen.kt`): 6 sections — Playback (default cadence, auto-apply
  cadence match), Library (auto-scan, sort), Appearance (theme, dynamic colors), Advanced (CSV import
  with format docs), Help (intro dialog), About. All persisted via SharedPreferences (`AppSettings`).
- **First launch**: `RunBeatIntroScreen` opaque overlay (gated by `intro_seen` pref); pale watermark
  logo drawn over content.

## Design system (`ui/theme/`)
- Colors: dark/light schemes + dynamic colors on Android 12+; BPM semantic green `0xFF00C853`.
  `speedFactorColor(f)`: green at 1.0, error color outside [0.9, 1.1], secondary otherwise, muted when unknown.
- Spacing `AppSpacing`: 4/8/12/16/24/32/40/48/56dp (8dp grid). Shapes: 4–28dp radii. Elevation 4dp.
- Touch targets (`TouchTargets.kt`): standard 48dp, large 56dp — all interactive elements ≥ 48dp.
- Typography: M3 styles; `BpmTextStyles.largeBpmDisplay` 24sp bold for prominent BPM.

## ViewModel state (highlights)
- `PlayerViewModel`: musicBpm, targetCadence (default 172, 150–195), selectedRatio, calculatedSpeed,
  cadenceMatchResult, applyCadenceMatch, selectedRhythmPatterns (default all 4), tap/auto BPM states
- `LibraryViewModel`: allTracks flow, searchQuery, per-dimension filter selections + non-self-pruning
  option flows, base/filtered track flows, analysis progress, playlist batch ops
- `BpmToolsViewModel`: tab index, cadence, current BPM, rhythm matches, detectedBpm (+fromTap), playbackSpeed
- `SettingsViewModel`: all settings as StateFlow over SharedPreferences

## Patterns
- Cards for sections, LazyColumn for lists, error card / Snackbar / AlertDialog / progress overlay /
  empty states; pulse animation on tap; `@Preview` variants on major components; heavy work (BPM
  analysis, scanning) on coroutines with progress.

## File references
| File | Role |
|---|---|
| `MainActivity.kt` | entry point, NavHost, inline PlayerScreen + overlays |
| `ui/LibraryScreen.kt` | library UI |
| `ui/screens/BpmToolsScreen.kt` | BPM tools tabs |
| `ui/screens/SettingsScreen.kt` | settings |
| `ui/screens/RunBeatIntroScreen.kt` | first-launch intro |
| `ui/components/TrackCard.kt` | library row |
| `ui/navigation/BottomNav.kt` | destinations + bottom bar |
| `ui/theme/Theme.kt`, `TouchTargets.kt` | design system |
| `viewmodel/*.kt` | 4 ViewModels |
| `audio/PlayerRepository.kt` | playback/playlist/ExoPlayer, `playbackSpeed` flow |
