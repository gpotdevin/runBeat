# RunBeat - UI Architecture Documentation

## 1. UI Framework & Technology Stack
- **Framework**: Jetpack Compose (Material Design 3)
- **Architecture Pattern**: MVVM (Model-View-ViewModel) with Clean Architecture separation
- **State Management**: Kotlin Coroutines Flow for reactive UI updates
- **Dependency Injection**: Hilt for ViewModel injection
- **Navigation**: Jetpack Navigation Compose with persistent Bottom Navigation Bar

---

## 2. UI Layer Structure

### 2.1 Directory Structure
```
app/src/main/java/com/bpmapp/audio/
├── ui/
│   ├── navigation/          # Bottom navigation & routes
│   │   └── BottomNav.kt          # NavDestination enum + BottomNavigationBar
│   ├── screens/            # Main application screens
│   │   ├── BpmToolsScreen.kt    # Tabbed BPM tools (Cadence/Detect/Manual)
│   │   └── SettingsScreen.kt    # Application settings (6 sections)
│   ├── components/         # Reusable UI components
│   │   └── TrackCard.kt         # Compact library track row
│   ├── theme/              # Design system and theming
│   │   ├── Theme.kt             # Color schemes, typography, spacing, BPM colors
│   │   └── TouchTargets.kt      # Touch target sizing standards
│   └── LibraryScreen.kt         # Music library management (tabs + chips)
├── viewmodel/
│   ├── PlayerViewModel.kt      # Player state, BPM detection, cadence matching
│   ├── LibraryViewModel.kt     # Library operations (import, scanning, BPM analysis)
│   ├── BpmToolsViewModel.kt    # Shared state for BPM detection / cadence matching
│   └── SettingsViewModel.kt    # Settings persistence via SharedPreferences
└── MainActivity.kt       # Entry point with Scaffold + NavHost + BottomNavigationBar (PlayerScreen inline)
```

### 2.2 ViewModel Layer
```
app/src/main/java/com/bpmapp/audio/
├── viewmodel/
│   ├── PlayerViewModel.kt     # Player state, BPM detection, cadence matching
│   ├── LibraryViewModel.kt    # Manages library operations (import, scanning, BPM analysis)
│   ├── BpmToolsViewModel.kt   # Shared state for BpmToolsScreen tabs
│   └── SettingsViewModel.kt   # Settings persistence via SharedPreferences
```

---

## 3. Screen Architecture

### 3.1 PlayerScreen (inline composable in `MainActivity.kt`)
**Purpose**: Primary user interface for audio playback with a simplified, focused layout

**Layout Structure**:
- **App Header**: "RunBeat" title + app version
- **Error Card**: Prominent error message when playback fails
- **Playback Controls** (`PlaybackControlsSection`): Current track title with elapsed/total time, progress slider, FAB Play/Pause + grouped Previous/Stop/Next + speed-correction toggle
- **Speed + Cadence display** (`TempoAndBpmSection`): Target cadence selector (`-` / value / `+`), speed factor, original BPM
- **Playlist** (`PlaylistSection`): "Music Library (selected: N)" header with shuffle / save-playlist / clear actions, full `LazyColumn` of `PlaylistItem` rows

**Key Features**:
- FAB Play/Pause (72dp, primary-container, centered) + grouped Previous/Stop/Next (48dp)
- Speed + Cadence text display (e.g., `Factor: 1.00x • Original: 172 BPM`)
- `PlaylistItem` rows where title + BPM/speed factor sit on one line and artist/album span a full-width secondary line
- Shuffle toggle (randomizes `playNext`/`playPrevious`) and a **Save playlist** button that lets the user create a new playlist or update an existing library playlist from the current song list

**Navigation**:
- Reached via bottom navigation (Player tab, default destination)
- The "Music Library" header row opens the Library screen

**Note**: `PlayerScreen` and `PlayerScreenWrapper` are top-level composables defined inline in `MainActivity.kt` (there is no `ui/screens/PlayerScreen.kt`). It includes `TempoAndBpmSection`, `PlaybackControlsSection`, `PlaylistSection`, and the `PlaylistItem` composable. The `TapBpmSection` composable remains only as unused dead code in `MainActivity.kt`.

### 3.2 LibraryScreen (`ui/LibraryScreen.kt`)
**Purpose**: Music library management with tabs, filtering, and BPM analysis

**Layout Structure**:
- **TopAppBar**: Title, Search icon (toggles inline search field), Overflow menu
  - Overflow menu: Scan Library, Clear Library, Sync Metadata
- **Tabs**: `All` / `Playlists` / `Favorites` (TabRow)
- **Filter Chips**: BPM Known, Speed 0.9-1.1 (All tab only) + compact Artist / Album / Genre chip rows
- **Track List**: LazyColumn of `TrackCard` components (compact, ~56dp row height)

**Key Features**:
- Search filters in real time across **title, artist, album** (plus filename/genre) and updates the Artist/Album/Genre filter chips to reflect search results
- Tapping outside the search field dismisses the keyboard/clears focus; search state clears when leaving the tab
- Search + overflow menu replaces direct TopAppBar actions
- Tab-based filtering (All/Playlists/Favorites)
- **BPM Known / Speed 0.9-1.1 filter chips are only shown on the All tab** (hidden on Playlists and Favorites)
- TrackCard shows tempo below the song title (full-width song info) with action buttons (star, Add, overflow) compact at the far right
- Multi-select (long-press-free selection) toolbar: **Play (N)**, **Add to Playlist**, **Add to Favorites**, Cancel
- Add-to-playlist dialog supports **new or existing** playlists, for a single track or a batch of selected tracks
- Dynamic filter updates: selecting a genre/artist/album hides non-matching options in the other filter chips (options derived from `filteredTrackFlow`)
- TrackCard overflow menu: Play (Replace Current), **Add to Playlist**, Edit BPM, Delete
- System library scanning (CSV import lives under Settings → Advanced)
- Library-wide BPM analysis with progress overlay
- Track selection for playback

**Navigation**:
- Reached via bottom navigation (Library tab)
- `onTrackSelected` / `onPlayAllTracks` load tracks into player

### 3.3 BpmToolsScreen (`ui/screens/BpmToolsScreen.kt`)
**Purpose**: Tabbed screen for BPM detection and cadence matching

**Layout Structure**:
- **TabRow**: 3 tabs - Cadence Matcher (default), Detect BPM, Manual Entry
- **Cadence Matcher tab**: Target cadence slider (150-195) + `-`/`+` buttons, rhythm group chips **Binary (1.0x / 2.0x)** and **Ternary (1.5x / 3.0x)** (two multi-select groups, default all selected, persisted via SharedPreferences), Cadence Matches list (pattern + speed factor)
- **Detect BPM tab**: Tap area ("Tap beat to detect BPM"), Aubio detection button, Use/Clear buttons
- **Manual Entry tab**: Numeric keypad BPM input, Save button ("Save BPM to file ID3 tag")

**Key Features**:
- Shared state via `BpmToolsViewModel`
- Default tab: Cadence Matcher
- Aubio-based automatic BPM detection
- Rhythm patterns are presented as two multi-select groups: Binary (1.0x / 2.0x) and Ternary (1.5x / 3.0x), applied by the cadence-matching algorithm

**Navigation**:
- Reached via bottom navigation (BPM Tools tab)

### 3.4 SettingsScreen (`ui/screens/SettingsScreen.kt`)
**Purpose**: Application settings and preferences

**Layout Structure** (6 sections):
- **🎵 PLAYBACK**: Default Cadence (slider 150-195), Auto-Apply Cadence Match (toggle)
- **📚 LIBRARY**: Auto-Scan on Startup (toggle), Default Sort (dropdown: BPM ▼/▲, Title, Artist, Album)
- **🎨 APPEARANCE**: Theme (dropdown: System/Light/Dark), Dynamic Colors (toggle)
- **⚙️ ADVANCED**: Import CSV (with inline CSV format description)
- **❓ HELP**: Introduction (shows the intro/help dialog)
- **ℹ️ ABOUT**: Project repository link, Version, Licenses (SoundTouch, Aubio)

**Key Features**:
- All settings persisted to `SharedPreferences` via `SettingsViewModel`

**Navigation**:
- Reached via bottom navigation (Settings tab)

---

## 4. Component Architecture

### 4.1 PlaybackControlsSection (inline in `MainActivity.kt`)
**Purpose**: Playback control bar with hierarchical button sizes

**Layout**:
- Track info row (current title with elapsed/total time)
- Progress slider with time display (8dp thick)
- Control buttons row:
  - Secondary controls (Previous/Next/Stop) grouped in `Row` (48dp)
  - FAB Play/Pause (72dp, primary-container, centered)
  - Speed-correction toggle (48dp touch target)

**Design Compliance**:
- Proposition 2.3: Hierarchical Controls
- Proposition 4.1: Touch targets >= 48dp

**Visual States**:
- Play/Pause FAB changes icon based on `isPlaying` state
- Progress slider updates in real-time

### 4.2 PlaylistSection / PlaylistItem (inline in `MainActivity.kt`)
**Purpose**: Full playlist display on the PlayerScreen with playback, shuffle, save, and clear actions

**Layout**:
- Playlist header row: "Music Library (selected: N)" (clickable to open the Library), shuffle toggle, Save playlist, and Clear buttons
- `LazyColumn` (`weight(1f)`, `AppSpacing.xxxs`/4dp spacing) of `PlaylistItem` rows
- `PlaylistItem`: title + BPM/speed factor on the first line, artist - album on a full-width second line; selected track highlighted (`primaryContainer`)
- Empty state ("No upcoming songs")
- Save playlist `AlertDialog`: new playlist name field + "Update existing playlist" list

**Features**:
- Shuffle toggle randomizes `playNext`/`playPrevious`
- Save playlist creates a new library playlist or updates an existing one from the current song list
- Current track indicator

**Design Compliance**:
- Proposition 3.1: PlayerScreen with PlaybackControlsSection and PlaylistSection

### 4.3 TrackCard (`ui/components/TrackCard.kt`)
**Purpose**: Compact library track row for LibraryScreen

**Layout**:
- ~56dp height
- Track title + artist span the full width (ellipsized); BPM display (color-coded, with speed-factor status icon) shown below the artist
- Favorite star toggle (32dp touch target, 20dp icon)
- "Add" button (Add to Playlist, 30dp height, 14dp icon, compact contentPadding)
- Overflow menu (⋮) at the far right (32dp touch target, 20dp icon): Play (Replace Current), Add to Playlist, Edit BPM, Delete

**Features**:
- Explicit "Add to Playlist" button
- Context menu for track actions including Add to Playlist
- Semantic content descriptions for accessibility

### 4.4 TapBpmSection / TempoAndBpmSection (Legacy)
**Purpose**: Legacy components from the pre-restructure PlayerScreen
**Status**: Files removed from `ui/components/`. The composables now live inline in `MainActivity.kt`. `TempoAndBpmSection` (Target Cadence) is still used by the active `PlayerScreen` in a compact layout (title left of the cadence selector, factor in a right-aligned third column); `TapBpmSection` ("BPM (Tap to set)") is no longer shown and remains only as unused dead code.

---

## 5. Design System

### 5.1 Color System
**Location**: `Theme.kt`

**Color Schemes**:
- **Dark Theme**: Music-themed palette with energetic colors
  - Primary: `Color(0xFFD0BCFF)` (purple)
  - Secondary: `Color(0xFF03DAC5)` (cyan)
  - Tertiary: `Color(0xFF8A4EA7)` (purple)
  - Background: `Color(0xFF1C1B1F)` (dark gray)
- **Light Theme**: Corresponding light variants

**BPM Semantic Colors**:
- `BpmGreen`: `Color(0xFF00C853)` - Good speed factor matches
- `BpmGreenLight`: `Color(0xFF80E27E)` - Light variant
- `BpmGreenDark`: `Color(0xFF2E7D32)` - Dark variant

**Speed/BPM Status Colors** (`Theme.kt`):
- `speedFactorColor(factor)`: Green (`BpmGreen`) for factor 1.0, red (error color) outside [0.9, 1.1], secondary otherwise, muted `onSurfaceVariant` when unknown
- `bpmColor(speedFactor)`: Wraps `speedFactorColor`; defaults to `bpmSemanticColor` when the speed factor is unknown

### 5.2 Typography
**Location**: `Theme.kt`

**Text Styles** (following Material Design 3):
- `headlineMedium`: 28sp, bold
- `headlineSmall`: 24sp, bold
- `titleLarge`: 22sp, bold
- `titleMedium`: 18sp, bold
- `titleSmall`: 14sp, bold
- `bodyLarge`: 16sp
- `bodyMedium`: 14sp
- `bodySmall`: 12sp
- `labelLarge`: 14sp, medium
- `labelMedium`: 12sp, medium
- `labelSmall`: 11sp, medium

**BPM-Specific Styles** (`BpmTextStyles`):
- `largeBpmDisplay`: 24sp, bold - For prominent BPM display

### 5.3 Spacing System
**Location**: `Theme.kt`

**AppSpacing** (8dp baseline grid):
- `xxxs`: 4dp
- `xxs`: 8dp
- `xs`: 12dp
- `sm`: 16dp
- `md`: 24dp
- `lg`: 32dp
- `xl`: 40dp
- `xxl`: 48dp (minimum touch target)
- `xxxl`: 56dp

**ComponentSpacing**:
- `small`: 8dp
- `medium`: 16dp
- `large`: 24dp
- `cardElevation`: 4dp
- `buttonHeightLarge`: 48dp
- `progressBarHeight`: 4dp

### 5.4 Touch Targets
**Location**: `TouchTargets.kt`

**Standards** (Material Design 3 compliance):
- `standard`: 48dp (minimum touch target size)
- `large`: 56dp (for dominant buttons)

**Compliance**: All interactive elements meet or exceed 48dp minimum touch target size (Proposition 4.1)

### 5.5 Shapes
**Location**: `Theme.kt`

**Corner Radii**:
- `extraSmall`: 4dp
- `small`: 8dp
- `medium`: 12dp
- `large`: 16dp
- `extraLarge`: 28dp

---

## 6. State Management

### 6.1 Reactive UI Updates
- **Flow**: All ViewModels expose state as `StateFlow` or `Flow`
- **collectAsState**: UI components collect state using `collectAsState()` for automatic recomposition
- **LaunchedEffect**: Side effects triggered by state changes

### 6.2 PlayerViewModel State
**Location**: `viewmodel/PlayerViewModel.kt`

**Key State Variables**:
- `musicBpm`: Current track BPM (manual or from metadata)
- `targetCadence`: Target running cadence (default: 172, range: 150-195)
- `selectedRatio`: Selected rhythm pattern factor
- `adjustedCadence`: Calculated cadence after ratio adjustment
- `calculatedSpeed`: Speed factor to apply
- `currentPlaybackSpeed`: Actual playback speed
- `cadenceMatchResult`: Result from cadence matching algorithm (applied globally via `findBestCadenceMatch`)
- `applyCadenceMatch`: Whether to apply cadence matching
- `selectedRhythmPatterns`: Set of selected rhythm patterns, grouped as Binary/Ternary in the UI while the underlying storage remains a Set of factors, loaded from SharedPreferences (default: all four patterns)
- `tapBpm`: BPM detected from tapping
- `isTapping`: Tapping state for visual feedback
- `autoDetectedBpm`: BPM from automatic Aubio detection
- `isDetectingBpm`: Detection in progress state

**State Derivation**:
- `updateCalculatedSpeed()`: Recalculates speed factor when BPM or cadence changes
- `updateCadenceMatch()`: Updates cadence match result
- `findBestCadenceMatch()`: Selects the optimal cadence match filtered by globally selected rhythm patterns (from SharedPreferences)
- Automatic speed application when cadence match is enabled

### 6.3 LibraryViewModel State
**Location**: `LibraryViewModel.kt`

**Key State Variables**:
- `allTracks`: List of all tracks from repository (Flow)
- `searchQuery`: Current search query (matches title, artist, album, filename, genre)
- `selectedArtists` / `selectedAlbums` / `selectedGenres`: Active category filter selections
- `artistOptions` / `albumOptions` / `genreOptions`: Filter options derived from tracks matching the current filters (dynamic — updates in real time as filters change)
- `filteredTracks`: Tracks filtered by search query + category selections (driven by `filteredTrackFlow`)
- `isLoading`: Loading state for import/scan operations
- `importCount`: Number of tracks imported in last operation
- `errorMessage`: Current error message (nullable)
- `isAnalyzingLibrary`: Library BPM analysis in progress
- `analysisProgress`: Progress of library analysis (completed, total)
- `analysisResults`: Number of tracks successfully analyzed

**Operations**:
- CSV import with progress tracking
- System library scanning with duplicate detection
- Library-wide BPM analysis using Aubio
- Track CRUD operations
- Search / category filtering (chips update from search results)
- Batch playlist operations: `addTracksToPlaylist`, `addSelectedTracksToPlaylist`, `createPlaylistWithTracks` (new playlist + tracks, returns id), `updatePlaylistTracks` (replace contents), plus `addTracksToFavorites` and `trackIdsFromMediaItems` (maps `MediaItem`s back to track ids via the `trackId` extra)

### 6.4 BpmToolsViewModel State
**Location**: `BpmToolsViewModel.kt`

**Key State**:
- `selectedTabIndex`: Active tab (0 = Cadence Matcher, default)
- `targetCadence`: Target cadence (150-195)
- `currentTrackBpm`: BPM of current track
- `selectedRhythmPatterns`: Set of rhythm multipliers (`1.0f`, `1.5f`, `2.0f`, `3.0f`), presented to the user as Binary/Ternary groups while the underlying storage remains a Set of factors, default `{1.0f, 1.5f, 2.0f, 3.0f}` (all selected), persisted via SharedPreferences
- `rhythmMatches`: Cadence matches for the selected rhythm patterns
- `detectedBpm`: BPM detected via tap or Aubio
- `isDetectingBpm`: Aubio detection in progress
- `manualBpmInput`: Manual entry text
- `errorMessage`: Current error message

### 6.5 SettingsViewModel State
**Location**: `SettingsViewModel.kt`

**State**: All settings exposed as `StateFlow`, backed by `SharedPreferences` (`AppSettings`):
- Playback: `defaultCadence`, `autoApplyCadenceMatch`
- Library: `autoScanOnStartup`, `defaultSort`
- Appearance: `theme`, `dynamicColors`

---

## 7. Navigation Architecture

### 7.1 Navigation Flow
```
MainActivity (Scaffold + NavHost + BottomNavigationBar)
    ├── Player (default tab)
    ├── Library
    ├── BPM Tools
    └── Settings
```

### 7.2 Navigation Implementation
**Location**: `MainActivity.kt`, `ui/navigation/BottomNav.kt`

**Approach**: Jetpack Navigation Compose with persistent bottom bar
- `NavDestination` enum defines routes (player, library, bpm_tools, settings) + icons
- `BottomNavigationBar` uses `popUpTo` + `saveState`/`restoreState` for state preservation
- `NavHost` in `MainActivity` renders the 4 top-level destinations

**Navigation Actions**:
- Tab taps navigate with `launchSingleTop` to avoid duplicate destinations
- `onTrackSelected` / `onPlayAllTracks` in Library load tracks into the player

---

## 8. UI Design Propositions Implementation

### 8.1 Proposition 1: Typography System
**Status**: ✅ Implemented
- Custom typography system with reduced unused styles
- Consistent spacing and sizing
- BPM-specific text styles for prominent display

### 8.2 Proposition 2: Component Layout
**Status**: ✅ Implemented
- **2.1**: Hierarchical information display (track info, BPM, duration)
- **2.2**: Card-based layout for sections
- **2.3**: Hierarchical controls (dominant FAB, grouped secondary controls)
- **2.4**: Focal BPM display with large, prominent BPM values

### 8.3 Proposition 3: Playlist Design
**Status**: ✅ Implemented
- **3.1**: PlayerScreen with PlaybackControlsSection and PlaylistSection (shuffle, save, clear)

### 8.4 Proposition 4: Touch Targets
**Status**: ✅ Implemented
- **4.1**: All touch targets >= 48dp (standard: 48dp, large: 56dp)
- **4.2**: Color-coded speed factors (green/red/gray)

### 8.5 Proposition 5: BPM Color Coding
**Status**: ✅ Implemented
- **5.1**: BPM values color-coded based on speed factor:
  - Green: Speed factor within [0.9, 1.1] (good match)
  - Red: Speed factor outside [0.9, 1.1] (needs adjustment)
  - Gray: Unknown speed factor

---

## 9. Accessibility Features

### 9.1 Touch Targets
- All interactive elements meet Material Design 3 minimum touch target size (48dp)
- `touchTarget()` modifier extension for consistent sizing

### 9.2 Semantics
- Content descriptions for all icons and interactive elements
- `semantics` modifier used for accessibility labels
- Screen reader support for BPM values, track info, and controls

### 9.3 Visual Feedback
- Pulse animation on BPM tap
- Color changes for selected states
- Progress indicators for loading and analysis operations
- Border highlighting for selected tracks

---

## 10. Responsive Design

### 10.1 Layout Adaptation
- **LazyColumn**: Used for scrollable track lists
- **Row/Column**: Flexible arrangement with weight modifiers
- **Modifier.weight(1f)**: Equal distribution of space between sibling elements
- **fillMaxWidth/fillMaxSize**: Responsive to container dimensions

### 10.2 Adaptive Components
- **Cards**: Consistent elevation and shape across all screens
- **Buttons**: Standardized sizing and spacing
- **Text**: Truncation with ellipsis for long content
- **Images**: Fixed aspect ratios where applicable

---

## 11. Animation & Feedback

### 11.1 Visual Animations
- **Pulse Animation**: On BPM tap detection (`Animatable` with tween)
- **Fade In/Out**: For overlay visibility changes
- **Slide In/Out**: For dropdown and dialog appearances

### 11.2 State Transitions
- **LaunchedEffect**: Triggers animations and side effects on state changes
- **remember**: Preserves animation state across recompositions

---

## 12. Error Handling & User Feedback

### 12.1 Error Display
- **Error Cards**: Prominent error messages in PlayerScreen
- **Snackbar**: Non-blocking notifications for library operations
- **AlertDialog**: Confirmation dialogs for destructive actions

### 12.2 Loading States
- **CircularProgressIndicator**: For loading and analysis operations
- **Progress Overlay**: Semi-transparent overlay during library analysis
- **Progress Tracking**: Shows completed/total counts for batch operations

### 12.3 Empty States
- **No Tracks**: Informative messages when library is empty
- **No Filter Results**: Guidance when filters return no results
- **No Upcoming Songs**: Placeholder text in playlist

---

## 13. Theming & Customization

### 13.1 Dynamic Colors
- **Dynamic Color Support**: Uses `dynamicDarkColorScheme` and `dynamicLightColorScheme` on Android 12+
- **Fallback**: Custom dark/light color schemes for older versions

### 13.2 Theme Switching
- **System Preference**: Automatically follows system dark/light theme
- **Manual Override**: Can be controlled via `darkTheme` parameter

### 13.3 Custom Styling
- **BPM-Specific Colors**: Semantic colors for BPM status
- **Speed Factor Colors**: Color-coded based on adjustment needs
- **Touch Target Standards**: Consistent sizing across all interactive elements

---

## 14. Preview Support

All major components include `@Preview` composables for:
- Default states
- Various data states (with/without BPM, playing/paused)
- Empty states
- Different configurations

This enables design verification and development without running the full app.

---

## 15. Key UI Patterns

### 15.1 Card-Based Layout
- All major sections use `Card` composable
- Consistent elevation (`ComponentSpacing.cardElevation = 4.dp`)
- Rounded corners using `MaterialTheme.shapes`

### 15.2 Section Organization
- Clear section titles
- Consistent padding (`AppSpacing` system)
- Visual hierarchy through sizing and color

### 15.3 Interactive Elements
- **Buttons**: Primary actions with consistent styling
- **IconButtons**: Secondary actions with 48dp touch targets
- **Sliders**: For progress and value adjustment
- **Clickable Areas**: Minimum 48dp touch targets

### 15.4 Data Display
- **BPM Values**: Prominent, color-coded, with status icons
- **Speed Factors**: Color-coded with precision formatting
- **Track Info**: Hierarchical (title > artist > album)
- **Duration**: Formatted as MM:SS

---

## 16. Performance Considerations

### 16.1 Lazy Loading
- **LazyColumn**: Efficient rendering of large track lists
- **Pagination**: Automatic as user scrolls
- **State Preservation**: Remember scroll positions

### 16.2 Reactive Updates
- **Flow**: Efficient state propagation
- **Derived State**: `remember` and `derivedStateOf` for computed values
- **Debouncing**: Prevent rapid state changes from causing excessive recomposition

### 16.3 Background Processing
- **Coroutines**: All heavy operations (BPM analysis, library scanning) run on background threads
- **Progress Tracking**: Visual feedback during long operations
- **Cancellation**: Support for cancelling ongoing operations

---

## 17. Testing Considerations

### 17.1 Preview Testing
- All components have preview variants
- Multiple states covered (empty, loading, error, success)

### 17.2 UI State Testing
- State changes trigger appropriate UI updates
- Error states display correctly
- Loading states show progress

### 17.3 Interaction Testing
- Touch targets meet minimum size requirements
- All interactive elements respond to input
- Navigation flows work correctly

---

## 18. Future Enhancements

### 18.1 Settings Screen
- Theme switching fully applied to app UI (currently persisted but not fully wired to runtime theme)
- Default cadence integration with player
- Audio processing options

### 18.2 Advanced Filtering
- BPM range filtering
- Duration filtering
- File type filtering
- Custom sort orders

### 18.3 Enhanced Visualizations
- BPM histogram for library
- Speed factor distribution
- Playback statistics

### 18.4 Accessibility Improvements
- Screen reader optimization
- High contrast mode
- Reduced motion support

### 18.5 Cleanup
- Remove the unused `TapBpmSection` composable and the legacy `PlayerScreen` composable from `MainActivity.kt` (PlayerViewModel is now a standalone `viewmodel/PlayerViewModel.kt`)

---

## 19. File References

| Component | File | Lines | Description |
|-----------|------|-------|-------------|
| MainActivity | `MainActivity.kt` | 1078 | Entry point, Scaffold + NavHost + BottomNavigationBar, inline PlayerScreen (PlaybackControlsSection, TempoAndBpmSection, PlaylistSection), shuffle + save-playlist UI |
| LibraryScreen | `ui/LibraryScreen.kt` | 2012 | Music library management (tabs + chips, multi-select, playlists, favorites) |
| BpmToolsScreen | `ui/screens/BpmToolsScreen.kt` | 694 | Tabbed BPM tools screen |
| SettingsScreen | `ui/screens/SettingsScreen.kt` | 423 | Application settings (6 sections) |
| TrackCard | `ui/components/TrackCard.kt` | 372 | Compact library track row (tempo below title) |
| BottomNav | `ui/navigation/BottomNav.kt` | 107 | NavDestination enum + BottomNavigationBar |
| PlayerViewModel | `viewmodel/PlayerViewModel.kt` | 196 | Player state management |
| LibraryViewModel | `viewmodel/LibraryViewModel.kt` | 1071 | Library state management (search, filters, playlists, batch ops) |
| PlayerRepository | `audio/PlayerRepository.kt` | 573 | Playback state, playlist, ExoPlayer, shuffle |
| BpmToolsViewModel | `viewmodel/BpmToolsViewModel.kt` | 330 | BPM tools shared state |
| SettingsViewModel | `viewmodel/SettingsViewModel.kt` | 151 | Settings persistence (SharedPreferences) |
| Theme | `ui/theme/Theme.kt` | 285 | Design system, BPM colors |
| TouchTargets | `ui/theme/TouchTargets.kt` | 12 | Touch target standards |
