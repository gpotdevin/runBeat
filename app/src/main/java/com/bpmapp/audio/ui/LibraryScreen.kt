// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.bpmapp.audio.audio.CadenceMatcher
import com.bpmapp.audio.ui.components.TrackCard
import com.bpmapp.audio.data.Playlist
import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackSource
import com.bpmapp.audio.ui.theme.AppSpacing
import com.bpmapp.audio.ui.theme.BpmAppTheme
import com.bpmapp.audio.ui.theme.TouchTargets
import com.bpmapp.audio.ui.theme.bpmColor
import com.bpmapp.audio.ui.theme.speedFactorColor
import com.bpmapp.audio.ui.theme.touchTarget
import com.bpmapp.audio.util.PermissionUtils
import com.bpmapp.audio.viewmodel.LibraryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DecimalFormat

/**
 * Library Screen for managing the music library
 * Displays tracks with their BPM information
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class
)
@Composable
fun LibraryScreen(
    onTrackSelected: (Track) -> Unit,
    onBack: () -> Unit,
    onPlayAllTracks: (List<Track>) -> Unit = {},
    onPlayTrack: (Track) -> Unit = {},
    onAddToUpcoming: (Track) -> Unit = {},
    targetCadence: Int = 172,
    viewModel: LibraryViewModel? = null
) {
    // Use the provided (Activity-scoped) ViewModel so library filters persist across
    // navigation. Fall back to a destination-scoped instance when none is supplied.
    val viewModel = viewModel ?: hiltViewModel()
    val cadenceMatcher = remember { CadenceMatcher() }
    
    // Tab state
    val tabTitles = listOf("All", "Playlists", "Favorites")
    val selectedTabIndex by viewModel.selectedTabIndex.collectAsState()
    
    val isLoading by viewModel.isLoading.collectAsState(false)
    val importCount by viewModel.importCount.collectAsState(0)
    val errorMessage by viewModel.errorMessage.collectAsState(null)
    val isAnalyzingLibrary by viewModel.isAnalyzingLibrary.collectAsState(false)
    val analysisProgress by viewModel.analysisProgress.collectAsState(0 to 0)
    val analysisResults by viewModel.analysisResults.collectAsState(0)
    val isResolvingBpm by viewModel.isResolvingBpm.collectAsState(false)
    val bpmResolutionProgress by viewModel.bpmResolutionProgress.collectAsState(0 to 0)
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Search and filter state from ViewModel
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedArtists by viewModel.selectedArtists.collectAsState()
    val selectedAlbums by viewModel.selectedAlbums.collectAsState()
    val selectedGenres by viewModel.selectedGenres.collectAsState()
    val artistOptions by viewModel.artistOptions.collectAsState(emptyList())
    val albumOptions by viewModel.albumOptions.collectAsState(emptyList())
    val genreOptions by viewModel.genreOptions.collectAsState(emptyList())
    val filteredBaseTracks by viewModel.filteredTracks.collectAsState(emptyList())
    val favoriteTracks by viewModel.favoriteTracks.collectAsState(emptyList())
    val playlists by viewModel.playlists.collectAsState(emptyList())
    val playlistTrackMap by viewModel.playlistTrackMap.collectAsState(emptyMap())
    
    // Focus and keyboard controllers for dismissing the search field
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Filter state (persisted in the Activity-scoped ViewModel)
    val showOnlyKnownBpm by viewModel.showOnlyKnownBpm.collectAsState()
    val filterBySpeedFactor by viewModel.filterBySpeedFactor.collectAsState()

    // Sort state - default to BPM sort (persisted in the Activity-scoped ViewModel)
    val sortByBpm by viewModel.sortByBpm.collectAsState()
    val sortByAlbum by viewModel.sortByAlbum.collectAsState()
    val sortByArtist by viewModel.sortByArtist.collectAsState()
    val sortDescending by viewModel.sortDescending.collectAsState()
    
    // Multi-select state
    var selectedTrackIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var multiSelectMode by rememberSaveable { mutableStateOf(false) }
    
    // Dialog state
    var showClearDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showMetadataPermissionDialog by remember { mutableStateOf(false) }
    var showSyncMetadataDialog by remember { mutableStateOf(false) }
    var showMetadataSaveConfirmDialog by remember { mutableStateOf(false) }
    var selectedTrackForMetadata by remember { mutableStateOf<Track?>(null) }
    
    // Playlist dialog state
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playlistNameInput by remember { mutableStateOf("") }
    var playlistBeingRenamed by remember { mutableStateOf<Playlist?>(null) }
    var playlistBeingDeleted by remember { mutableStateOf<Playlist?>(null) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var targetTrackIds by remember { mutableStateOf<List<String>>(emptyList()) }
    
    // Permission state
    val context = LocalContext.current
    val hasPermission = remember { 
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ContextCompat.checkSelfPermission(
            context,
            permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    
    // Metadata editing state
    val hasMetadataPermission = remember { 
        PermissionUtils.hasManageExternalStoragePermission(context)
    }
    val isMetadataEditingAvailable = remember { 
        viewModel.isMetadataEditingAvailable()
    }
    val isSavingMetadata by viewModel.isSavingMetadata.collectAsState(false)
    val metadataSaveProgress by viewModel.metadataSaveProgress.collectAsState(0 to 0)
    val metadataSaveResults by viewModel.metadataSaveResults.collectAsState(0)
    val metadataError by viewModel.metadataError.collectAsState(null)
    
    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.scanSystemLibrary()
        } else {
            viewModel.setError("Permission denied. Cannot scan music library without storage permission.")
        }
    }
    
    // CSV import launcher
    val csvImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            viewModel.importFromCsv(selectedUri)
        }
    }
    
    // Check if any filter is active
    val hasActiveFilter = remember(
        showOnlyKnownBpm, filterBySpeedFactor, searchQuery,
        selectedArtists, selectedAlbums, selectedGenres
    ) {
        showOnlyKnownBpm || filterBySpeedFactor || searchQuery.isNotBlank() ||
            selectedArtists.isNotEmpty() || selectedAlbums.isNotEmpty() || selectedGenres.isNotEmpty()
    }

    // Filtered and sorted tracks
    val filteredTracks = remember(filteredBaseTracks, showOnlyKnownBpm, filterBySpeedFactor, sortByBpm, sortByAlbum, sortByArtist, sortDescending, targetCadence) {
        filteredBaseTracks
            .filter { track ->
                // Filter by BPM status
                val bpmFilter = if (showOnlyKnownBpm) {
                    track.bpm != null && track.bpm > 0
                } else {
                    true
                }
                
                // Filter by speed factor range [0.9, 1.1]
                val speedFactorFilter = if (!filterBySpeedFactor) {
                    true
                } else {
                    track.bpm?.let { bpm ->
                        val matchResult = cadenceMatcher.findOptimalMatch(
                            detectedBpm = bpm.toFloat(),
                            targetCadence = targetCadence.toFloat()
                        )
                        if (matchResult.rhythmPattern.factor > 0) {
                            val speedFactor = targetCadence.toFloat() / (bpm * matchResult.rhythmPattern.factor)
                            speedFactor >= 0.9f && speedFactor <= 1.1f
                        } else {
                            false
                        }
                     } ?: false
                }
                
                bpmFilter && speedFactorFilter
            }
            .sortedWith(
                when {
                    sortByBpm -> {
                        if (sortDescending) {
                            compareByDescending<Track> { if (it.bpm != null && it.bpm > 0) it.bpm else Float.NEGATIVE_INFINITY }
                                .thenBy { it.fileName.lowercase() }
                        } else {
                            compareBy<Track> { if (it.bpm != null && it.bpm > 0) it.bpm else Float.POSITIVE_INFINITY }
                                .thenBy { it.fileName.lowercase() }
                        }
                    }
                    sortByAlbum -> {
                        if (sortDescending) {
                            compareByDescending<Track> { it.metadataAlbum?.lowercase() ?: it.fileName.lowercase() }
                                .thenBy { it.fileName.lowercase() }
                        } else {
                            compareBy<Track> { it.metadataAlbum?.lowercase() ?: it.fileName.lowercase() }
                                .thenBy { it.fileName.lowercase() }
                        }
                    }
                    sortByArtist -> {
                        if (sortDescending) {
                            compareByDescending<Track> { it.metadataArtist?.lowercase() ?: it.fileName.lowercase() }
                                .thenBy { it.fileName.lowercase() }
                        } else {
                            compareBy<Track> { it.metadataArtist?.lowercase() ?: it.fileName.lowercase() }
                                .thenBy { it.fileName.lowercase() }
                        }
                    }
                    else -> {
                        compareBy<Track> { it.fileName.lowercase() }
                    }
                }
            )
    }

    // Preserve selection when filters change - remove tracks not in filtered list
    LaunchedEffect(filteredTracks) {
        selectedTrackIds = selectedTrackIds.filter { trackId ->
            filteredTracks.any { it.id == trackId }
        }.toSet()
        // Exit multi-select mode if no tracks are selected
        if (selectedTrackIds.isEmpty()) {
            multiSelectMode = false
        }
    }
    
    // Show success message when import completes
    if (importCount > 0) {
        LaunchedEffect(importCount) {
            viewModel.clearImportCount()
        }
    }
    
    // Overflow menu state
    var showOverflowMenu by remember { mutableStateOf(false) }
    
    // Search state - show the field when a query is active so it stays visible
    var showSearchField by remember { mutableStateOf(searchQuery.isNotBlank()) }
    
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    if (showSearchField) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs)
                        ) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.onSearchQueryChange(it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                }),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                "Search tracks...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    } else {
                        Text(
                            "Library",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(AppSpacing.xxl)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close library and return to player",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    // Search icon
                    IconButton(
                        onClick = {
                            showSearchField = !showSearchField
                            if (!showSearchField && searchQuery.isNotBlank()) {
                                viewModel.clearSearch()
                            }
                        },
                        modifier = Modifier.size(AppSpacing.xxl)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search tracks",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    
                    // Overflow menu
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.size(AppSpacing.xxl)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "Library menu",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            modifier = Modifier.widthIn(min = 200.dp, max = 280.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Scan Library", style = MaterialTheme.typography.bodyMedium) },
                                onClick = { 
                                    if (hasPermission) {
                                        viewModel.scanSystemLibrary()
                                    } else {
                                        showPermissionDialog = true
                                    }
                                    showOverflowMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Scan Library",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            )
                            
                            DropdownMenuItem(
                                text = { Text("Analyze BPM", style = MaterialTheme.typography.bodyMedium) },
                                onClick = { 
                                    if (!isAnalyzingLibrary) {
                                        viewModel.analyzeLibraryBpm()
                                    }
                                    showOverflowMenu = false
                                },
                                enabled = !isAnalyzingLibrary,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Timer,
                                        contentDescription = "Analyze BPM",
                                        modifier = Modifier.size(18.dp),
                                        tint = if (isAnalyzingLibrary) {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        }
                                    )
                                }
                            )
                            
                            DropdownMenuItem(
                                text = { Text("Import CSV", style = MaterialTheme.typography.bodyMedium) },
                                onClick = { 
                                    csvImportLauncher.launch("*/*")
                                    showOverflowMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.ImportExport,
                                        contentDescription = "Import CSV",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            )
                            
                            DropdownMenuItem(
                                text = { Text("Clear Library", style = MaterialTheme.typography.bodyMedium) },
                                onClick = { 
                                    showClearDialog = true
                                    showOverflowMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Clear Library",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                            
                            // Sync Metadata (if available)
                            if (isMetadataEditingAvailable) {
                                DropdownMenuItem(
                                    text = { Text("Sync Metadata", style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { 
                                        if (hasMetadataPermission) {
                                            showSyncMetadataDialog = true
                                        } else {
                                            showMetadataPermissionDialog = true
                                        }
                                        showOverflowMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Sync,
                                            contentDescription = "Sync Metadata",
                                            modifier = Modifier.size(18.dp),
                                            tint = if (hasMetadataPermission) {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        snackbarHost = { 
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(AppSpacing.sm)
            )
        }
    ) { paddingValues ->
        val lazyListState = rememberLazyListState()
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                }
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                 // TabRow for All, Playlists, Favorites
                 TabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { viewModel.setSelectedTabIndex(index) },
                            text = { Text(title, style = MaterialTheme.typography.bodyMedium) }
                        )
                    }
                }
                
                // Filter Chips (All tab only - BPM Known / Speed filters)
                if (selectedTabIndex == 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            label = "BPM Known",
                            selected = showOnlyKnownBpm,
                            onSelected = { viewModel.setShowOnlyKnownBpm(it) },
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        FilterChip(
                            label = "Speed 0.9-1.1",
                            selected = filterBySpeedFactor,
                            onSelected = { viewModel.setFilterBySpeedFactor(it) },
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (hasActiveFilter) {
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            TextButton(
                                onClick = { viewModel.clearAllFilters() },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(
                                    text = "Clear filters",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
                
                // Album, Artist, Genre filter chips (All tab only)
                if (selectedTabIndex == 0 &&
                    (artistOptions.isNotEmpty() || albumOptions.isNotEmpty() || genreOptions.isNotEmpty())
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
                    ) {
                        if (artistOptions.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxxs),
                                contentPadding = PaddingValues(horizontal = AppSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                item {
                                    Text(
                                        "Artist",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = AppSpacing.xxs)
                                    )
                                }
                                items(artistOptions, key = { it }) { artist ->
                                    FilterChip(
                                        label = artist,
                                        selected = selectedArtists.contains(artist),
                                        onSelected = { viewModel.toggleArtist(artist) },
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }
                        if (albumOptions.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxxs),
                                contentPadding = PaddingValues(horizontal = AppSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                item {
                                    Text(
                                        "Album",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = AppSpacing.xxs)
                                    )
                                }
                                items(albumOptions, key = { it }) { album ->
                                    FilterChip(
                                        label = album,
                                        selected = selectedAlbums.contains(album),
                                        onSelected = { viewModel.toggleAlbum(album) },
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                        if (genreOptions.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxxs),
                                contentPadding = PaddingValues(horizontal = AppSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                item {
                                    Text(
                                        "Genre",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = AppSpacing.xxs)
                                    )
                                }
                                items(genreOptions, key = { it }) { genre ->
                                    FilterChip(
                                        label = genre,
                                        selected = selectedGenres.contains(genre),
                                        onSelected = { viewModel.toggleGenre(genre) },
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                
                // Main content area
                Box(modifier = Modifier.weight(1f)) {
                    // Progress overlay for library BPM analysis
                    if (selectedTabIndex == 0 && isAnalyzingLibrary) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                                .clickable(enabled = false) {},
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    "Analyzing library...",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                analysisProgress.let { (completed, total) ->
                                    Text(
                                        "Processing $completed/$total tracks",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                    
                    // Slim progress banner while resolving BPM for tracks
                    // being added to the upcoming playlist
                    if (selectedTabIndex == 0 && isResolvingBpm) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xxs),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
                        ) {
                            bpmResolutionProgress.let { (completed, total) ->
                                if (total > 0) {
                                    LinearProgressIndicator(
                                        progress = completed.toFloat() / total,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            Text(
                                text = "Analyzing BPM for added tracks...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Multi-select menu overlay (appears on top of the list when in multi-select mode)
                    if (selectedTabIndex == 0 && multiSelectMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = AppSpacing.xs, start = AppSpacing.xs, end = AppSpacing.xs)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(vertical = AppSpacing.xxs)
                                .horizontalScroll(rememberScrollState())
                                .zIndex(1f),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxxs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { 
                                    multiSelectMode = false
                                    selectedTrackIds = setOf()
                                },
                                modifier = Modifier.height(AppSpacing.xxl),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = { 
                                    val selectedTracks = filteredTracks.filter { selectedTrackIds.contains(it.id) }
                                    onPlayAllTracks(selectedTracks)
                                    selectedTrackIds = setOf()
                                    multiSelectMode = false
                                },
                                enabled = selectedTrackIds.isNotEmpty(),
                                modifier = Modifier.height(AppSpacing.xxl),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.tertiary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.xxs))
                                Text("Play (${selectedTrackIds.size})")
                            }
                            Button(
                                onClick = {
                                    targetTrackIds = selectedTrackIds.toList()
                                    showAddToPlaylistDialog = true
                                },
                                enabled = selectedTrackIds.isNotEmpty(),
                                modifier = Modifier.height(AppSpacing.xxl),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.primary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlaylistAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.xxs))
                                Text("Playlist")
                            }
                            Button(
                                onClick = {
                                    viewModel.addTracksToFavorites(selectedTrackIds.toList())
                                    selectedTrackIds = setOf()
                                    multiSelectMode = false
                                },
                                enabled = selectedTrackIds.isNotEmpty(),
                                modifier = Modifier.height(AppSpacing.xxl),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.secondary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.xxs))
                                Text("Favorites")
                            }
                        }
                    }
                    
                    // All tab: track list
                    if (selectedTabIndex == 0) {
                     LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(AppSpacing.xs)
                            .imePadding(),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xxxs)
                     ) {
                         // Play all filtered tracks button as sticky header
                         if (filteredTracks.isNotEmpty()) {
                             stickyHeader {
                                 Row(
                                     modifier = Modifier.fillMaxWidth(),
                                     verticalAlignment = Alignment.CenterVertically
                                 ) {
                                     if (selectedTrackIds.isNotEmpty()) {
                                         IconButton(
                                             onClick = { 
                                                 selectedTrackIds = setOf()
                                                 multiSelectMode = false
                                             },
                                             modifier = Modifier.height(AppSpacing.lg)
                                         ) {
                                             Icon(
                                                 imageVector = Icons.Filled.Close,
                                                 contentDescription = "Reset selection",
                                                 modifier = Modifier.size(18.dp)
                                             )
                                         }
                                     }
                                     Button(
                                         onClick = { onPlayAllTracks(filteredTracks) },
                                         modifier = Modifier
                                             .weight(1f)
                                             .height(TouchTargets.standard),
                                         colors = ButtonDefaults.buttonColors(
                                             containerColor = MaterialTheme.colorScheme.primaryContainer,
                                             contentColor = MaterialTheme.colorScheme.primary
                                         )
                                     ) {
                                         Icon(
                                             imageVector = Icons.Filled.PlayArrow,
                                             contentDescription = "Play all filtered tracks",
                                             modifier = Modifier.size(18.dp)
                                         )
                                         Spacer(modifier = Modifier.width(AppSpacing.xxs))
                                         Text("Play All (${filteredTracks.size})", style = MaterialTheme.typography.bodyMedium)
                                     }
                                 }
                             }
                         }
                
                // Track list
                if (isLoading) {
                    // Loading - show progress indicator
                    item {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                } else if (!hasActiveFilter) {
                    // No filter active - show message to use filters
                    item {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FilterList,
                                    contentDescription = "No filters active - use filters to display songs",
                                    modifier = Modifier.size(AppSpacing.xxxl),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Use filters to display songs",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                 Text(
                                     text = "Use filter chips to display songs",
                                     style = MaterialTheme.typography.bodyMedium,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                                     textAlign = TextAlign.Center
                                 )
                            }
                        }
                    }
                } else if (filteredTracks.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MusicNote,
                                    contentDescription = "No tracks match the current filters",
                                    modifier = Modifier.size(AppSpacing.xxxl),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "No tracks found",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Try adjusting your filters",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                 } else {
                     // Track list with new TrackCard component
                     items(filteredTracks.size) { index ->
                         val track = filteredTracks[index]
                         val speedFactor = track.bpm?.let { bpm ->
                             // Skip if BPM is 0 or negative (shouldn't happen with fix in TrackRepository, but be defensive)
                             if (bpm <= 0) {
                                 null
                             } else {
                                 val matchResult = cadenceMatcher.findOptimalMatch(
                                     detectedBpm = bpm.toFloat(),
                                     targetCadence = targetCadence.toFloat()
                                 )
                                 if (matchResult.rhythmPattern.factor > 0) {
                                     targetCadence.toFloat() / (bpm * matchResult.rhythmPattern.factor)
                                 } else {
                                     null
                                 }
                             }
                         }

                         // Use the new TrackCard component
                         TrackCard(
                             track = track,
                             onClick = { 
                                 if (multiSelectMode) {
                                     selectedTrackIds = if (selectedTrackIds.contains(track.id)) {
                                         selectedTrackIds - track.id
                                     } else {
                                         selectedTrackIds + track.id
                                     }
                                 } else {
                                     onTrackSelected(track)
                                 }
                             },
                             onPlay = { onPlayTrack(track) },
                             onEditBpm = {
                                 // TODO: Implement Edit BPM functionality
                                 selectedTrackForMetadata = track
                                 // For now, just show a message or open edit dialog
                             },
                             onDelete = { 
                                 viewModel.deleteTrack(track.id)
                             },
onAddToPlaylist = {
                                 targetTrackIds = listOf(track.id)
                                 showAddToPlaylistDialog = true
                             },
                             onAddToUpcoming = { onAddToUpcoming(track) },
                             onToggleFavorite = { viewModel.toggleFavorite(track.id) },
                             isFavorite = track.isFavorite,
                             speedFactor = speedFactor,
                            isSelected = selectedTrackIds.contains(track.id),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxxs)
                        )
                     }
                 }
            }
            }

            // Playlists tab
            if (selectedTabIndex == 1) {
                PlaylistsContent(
                    playlists = playlists,
                    playlistTracks = playlistTrackMap,
                    onPlayPlaylist = { tracks -> onPlayAllTracks(tracks) },
                    onTrackSelected = onTrackSelected,
                    onToggleFavorite = { track -> viewModel.toggleFavorite(track.id) },
                    onRemoveTrack = { playlist, track ->
                        viewModel.removeTrackFromPlaylist(playlist.id, track.id)
                    },
                    onCreatePlaylist = { showCreatePlaylistDialog = true },
                    onRenamePlaylist = { playlist -> playlistBeingRenamed = playlist },
                    onDeletePlaylist = { playlist -> playlistBeingDeleted = playlist }
                )
            }

            // Favorites tab
            if (selectedTabIndex == 2) {
                FavoritesContent(
                    tracks = favoriteTracks,
                    onTrackSelected = onTrackSelected,
                    onPlayTrack = onPlayTrack,
                    onPlayAll = { tracks -> onPlayAllTracks(tracks) },
                    onToggleFavorite = { track -> viewModel.toggleFavorite(track.id) },
                    onAddToPlaylist = { track ->
                        targetTrackIds = listOf(track.id)
                        showAddToPlaylistDialog = true
                    },
                    onAddToUpcoming = onAddToUpcoming
                )
            }
        }
    }
    
    // Show results snackbar when library analysis completes
    LaunchedEffect(analysisResults) {
        val results = analysisResults
        if (results > 0) {
            snackbarHostState.showSnackbar(
                "BPM analysis complete! Updated $results tracks"
            )
            viewModel.clearAnalysisResults()
        }
    }
    
    // Clear library confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Library", style = MaterialTheme.typography.titleLarge) },
            text = { 
                Text(
                    "Are you sure you want to delete all tracks from the library? This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { 
                        viewModel.deleteAllTracks()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showClearDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Permission dialog
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Permission Required", style = MaterialTheme.typography.titleLarge) },
            text = { 
                Text(
                    "To scan your music library, the app needs permission to access media files on your device.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { 
                        showPermissionDialog = false
                        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_AUDIO
                        } else {
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        permissionLauncher.launch(permission)
                    }
                ) {
                    Text("Grant Permission")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showPermissionDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Error message
    errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Error", style = MaterialTheme.typography.titleLarge) },
            text = { Text(error, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearError() }
                ) {
                    Text("OK")
                }
            }
        )
    }
    
    // Metadata permission dialog
    if (showMetadataPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showMetadataPermissionDialog = false },
            title = { Text("File Access Required", style = MaterialTheme.typography.titleLarge) },
            text = { 
                Column {
                    Text(
                        viewModel.getFileAccessExplanation(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    Text(
                        "This will open your device settings where you can enable file access for RunBeat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        showMetadataPermissionDialog = false
                        PermissionUtils.requestManageExternalStoragePermission(
                            context as ComponentActivity
                        )
                    }
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showMetadataPermissionDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Sync metadata confirmation dialog
    if (showSyncMetadataDialog) {
        AlertDialog(
            onDismissRequest = { showSyncMetadataDialog = false },
            title = { Text("Sync BPM to Files", style = MaterialTheme.typography.titleLarge) },
            text = { 
                Column {
                    Text(
                        "This will save BPM values from the app database to the ID3 tags of your music files.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    Text(
                        "Only tracks with known BPM values will be updated. This may take some time for large libraries.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        showSyncMetadataDialog = false
                        viewModel.syncAllBpmToFileMetadata()
                    }
                ) {
                    Text("Sync All")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showSyncMetadataDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Metadata save progress dialog
    if (isSavingMetadata) {
        AlertDialog(
            onDismissRequest = { 
                // Don't allow dismiss during sync
            },
            title = { Text("Syncing BPM to Files", style = MaterialTheme.typography.titleLarge) },
            text = { 
                Column {
                    if (metadataSaveProgress.second > 0) {
                        val progress = metadataSaveProgress.first.toFloat() / metadataSaveProgress.second.toFloat()
                        Text(
                            "Processing track ${metadataSaveProgress.first} of ${metadataSaveProgress.second}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearMetadataSaveState() },
                    enabled = !isSavingMetadata
                ) {
                    Text("Close")
                }
            }
        )
    }
    
    // Metadata save results
    if (metadataSaveResults > 0 && !isSavingMetadata) {
        LaunchedEffect(metadataSaveResults) {
            // Show a snackbar with the results
            snackbarHostState.showSnackbar(
                "Successfully synced BPM to ${metadataSaveResults} files"
            )
            viewModel.clearMetadataSaveState()
        }
    }
    
    // Metadata error
    metadataError?.let { error ->
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearMetadataSaveState()
        }
    }
    
    // Metadata save confirmation dialog
    if (showMetadataSaveConfirmDialog && selectedTrackForMetadata != null) {
        AlertDialog(
            onDismissRequest = { showMetadataSaveConfirmDialog = false },
            title = { 
                Text(
                    "Save BPM to File",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = { 
                Column {
                    Text(
                        "Save BPM ${selectedTrackForMetadata?.bpm} to file metadata for:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        selectedTrackForMetadata?.metadataTitle ?: selectedTrackForMetadata?.fileName ?: "Unknown",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    selectedTrackForMetadata?.metadataArtist?.let { artist ->
                        Spacer(modifier = Modifier.height(AppSpacing.xxs))
                        Text(
                            artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        showMetadataSaveConfirmDialog = false
                        selectedTrackForMetadata?.let { track ->
                            track.bpm?.let { bpm ->
                                viewModel.saveBpmToFileMetadata(track.id, bpm)
                            }
                        }
                        selectedTrackForMetadata = null
                    },
                    enabled = selectedTrackForMetadata?.bpm != null
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Button(
                    onClick = { 
                        showMetadataSaveConfirmDialog = false
                        selectedTrackForMetadata = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Create / Rename playlist dialog
    if (showCreatePlaylistDialog || playlistBeingRenamed != null) {
        val isRenaming = playlistBeingRenamed != null
        AlertDialog(
            onDismissRequest = {
                showCreatePlaylistDialog = false
                playlistBeingRenamed = null
                playlistNameInput = ""
            },
            title = {
                Text(
                    if (isRenaming) "Rename Playlist" else "New Playlist",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                OutlinedTextField(
                    value = playlistNameInput,
                    onValueChange = { playlistNameInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(playlistBeingRenamed?.name ?: "Playlist name", style = MaterialTheme.typography.bodyMedium)
                    },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = playlistNameInput.trim()
                        if (name.isNotEmpty()) {
                            if (isRenaming) {
                                viewModel.renamePlaylist(playlistBeingRenamed!!.id, name)
                            } else {
                                viewModel.createPlaylist(name)
                            }
                        }
                        showCreatePlaylistDialog = false
                        playlistBeingRenamed = null
                        playlistNameInput = ""
                    },
                    enabled = playlistNameInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showCreatePlaylistDialog = false
                        playlistBeingRenamed = null
                        playlistNameInput = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Delete playlist confirmation dialog
    if (playlistBeingDeleted != null) {
        AlertDialog(
            onDismissRequest = { playlistBeingDeleted = null },
            title = { Text("Delete Playlist", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "Delete \"${playlistBeingDeleted?.name}\"? The tracks themselves will not be removed.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePlaylist(playlistBeingDeleted!!.id)
                        playlistBeingDeleted = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Button(onClick = { playlistBeingDeleted = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Add to playlist dialog (supports a single track or a batch of selected tracks)
    if (showAddToPlaylistDialog) {
        var newPlaylistName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showAddToPlaylistDialog = false
                targetTrackIds = emptyList()
            },
            title = {
                Text(
                    "Add to Playlist",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("New playlist name", style = MaterialTheme.typography.bodyMedium)
                        },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val name = newPlaylistName.trim()
                            if (name.isNotEmpty()) {
                                viewModel.createPlaylistWithTracks(name, targetTrackIds)
                            }
                            selectedTrackIds = setOf()
                            multiSelectMode = false
                            showAddToPlaylistDialog = false
                            targetTrackIds = emptyList()
                        },
                        enabled = newPlaylistName.isNotBlank() && targetTrackIds.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AppSpacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xxs))
                        Text("Create & Add")
                    }
                    if (playlists.isEmpty()) {
                        Text(
                            "No playlists yet - create one above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = AppSpacing.xs)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Text(
                            "Or add to an existing playlist",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.xxs))
                        playlists.forEach { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        viewModel.addSelectedTracksToPlaylist(playlist.id, targetTrackIds)
                                        selectedTrackIds = setOf()
                                        multiSelectMode = false
                                        showAddToPlaylistDialog = false
                                        targetTrackIds = emptyList()
                                    }
                                    .padding(AppSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlaylistAdd,
                                    contentDescription = "Add to ${playlist.name}",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAddToPlaylistDialog = false
                        targetTrackIds = emptyList()
                    }
                ) {
                    Text("Close")
                }
            }
        )
    }
}
}
}

@Composable
fun PlaylistsContent(
    playlists: List<Playlist>,
    playlistTracks: Map<String, List<Track>>,
    onPlayPlaylist: (List<Track>) -> Unit,
    onTrackSelected: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onRemoveTrack: (Playlist, Track) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRenamePlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit
) {
    var expandedPlaylistId by remember { mutableStateOf<String?>(null) }
    
    if (playlists.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = "No playlists",
                modifier = Modifier.size(AppSpacing.xxxl),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "No playlists yet",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Create a playlist to organize your tracks",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Button(
                onClick = onCreatePlaylist,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)
                    .height(AppSpacing.lg),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Create playlist",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xxs))
                Text("Create Playlist", style = MaterialTheme.typography.bodyMedium)
            }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xxxs)
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistItem(
                        playlist = playlist,
                        tracks = playlistTracks[playlist.id].orEmpty(),
                        expanded = expandedPlaylistId == playlist.id,
                        onToggleExpanded = {
                            expandedPlaylistId = if (expandedPlaylistId == playlist.id) null else playlist.id
                        },
                        onPlay = { onPlayPlaylist(playlistTracks[playlist.id].orEmpty()) },
                        onRename = { onRenamePlaylist(playlist) },
                        onDelete = { onDeletePlaylist(playlist) },
                        onRemoveTrack = { track -> onRemoveTrack(playlist, track) },
                        onTrackSelected = onTrackSelected,
                        onToggleFavorite = onToggleFavorite
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistItem(
    playlist: Playlist,
    tracks: List<Track>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRemoveTrack: (Track) -> Unit,
    onTrackSelected: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxxs),
        shape = MaterialTheme.shapes.small,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
                    .padding(AppSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.PlaylistAdd,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${tracks.size} tracks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPlay, modifier = Modifier.touchTarget()) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play playlist",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onRename, modifier = Modifier.touchTarget()) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Rename playlist",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.touchTarget()) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete playlist",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            AnimatedVisibility(visible = expanded) {
                if (tracks.isEmpty()) {
                    Text(
                        text = "No tracks in this playlist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(AppSpacing.sm)
                    )
                } else {
                    tracks.forEach { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTrackSelected(track) }
                                .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (track.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "Toggle favorite",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onToggleFavorite(track) },
                                tint = if (track.isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                }
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.metadataTitle ?: track.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                track.metadataArtist?.let { artist ->
                                    Text(
                                        text = artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = { onRemoveTrack(track) }, modifier = Modifier.touchTarget()) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove from playlist",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FavoritesContent(
    tracks: List<Track>,
    onTrackSelected: (Track) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayAll: (List<Track>) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onAddToUpcoming: (Track) -> Unit = {}
) {
    if (tracks.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.StarBorder,
                contentDescription = "No favorites",
                modifier = Modifier.size(AppSpacing.xxxl),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "No favorites yet",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Tap the star on any track to add it to favorites",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Button(
                onClick = { onPlayAll(tracks) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)
                    .height(TouchTargets.standard),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play all favorites",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xxs))
                Text("Play All Favorites (${tracks.size})", style = MaterialTheme.typography.bodyMedium)
            }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xxxs)
            ) {
                items(tracks.size) { index ->
                    val track = tracks[index]
                    TrackCard(
                        track = track,
                        onClick = { onTrackSelected(track) },
                        onPlay = { onPlayTrack(track) },
                        onEditBpm = {},
                        onDelete = {},
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onAddToUpcoming = { onAddToUpcoming(track) },
                        isFavorite = true,
                        onToggleFavorite = { onToggleFavorite(track) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxxs)
                    )
                }
            }
        }
    }
}

@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onSelected: (Boolean) -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (selected) {
        color.copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }
    
    val contentColor = if (selected) {
        color
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }
    
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(),
                onClick = { onSelected(!selected) }
            )
            .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs)
            .heightIn(min = AppSpacing.xxs)
            .semantics {
                contentDescription = "Filter chip: $label, ${if (selected) "selected" else "not selected"}"
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = contentColor
        )
    }
}

@Preview(showBackground = true)
@Composable
fun LibraryScreenPreview() {
    BpmAppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Text("Preview requires Hilt setup")
        }
    }
}
