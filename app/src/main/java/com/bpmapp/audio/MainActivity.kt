package com.bpmapp.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.media3.common.MediaItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bpmapp.audio.R
import com.bpmapp.audio.audio.AudioService
import com.bpmapp.audio.audio.CadenceMatcher
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.data.Track
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableItemScope
import sh.calvin.reorderable.rememberReorderableLazyColumnState
import com.bpmapp.audio.ui.LibraryScreen
import com.bpmapp.audio.ui.navigation.BottomNavigationBar
import com.bpmapp.audio.ui.screens.BpmToolsScreen
import com.bpmapp.audio.ui.screens.RunBeatIntroScreen
import com.bpmapp.audio.ui.screens.SettingsScreen
import com.bpmapp.audio.ui.theme.AppSpacing
import com.bpmapp.audio.ui.theme.BpmAppTheme
import com.bpmapp.audio.ui.theme.bpmColor
import com.bpmapp.audio.ui.theme.speedFactorColor
import com.bpmapp.audio.ui.theme.touchTarget
import com.bpmapp.audio.viewmodel.PlayerViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt



/**
 * Main Activity for RunBeat
 * Entry point for the application with full player UI
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var playerRepository: PlayerRepository

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start AudioService for notification and Bluetooth controls
        // Use startForegroundService to allow foreground service start
        startForegroundService(Intent(this, AudioService::class.java))
        
        requestNotificationPermissionIfNeeded()
        
        setContent {
            BpmAppTheme {
                val context = LocalContext.current
                val prefs = remember { context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE) }
                var showIntro by remember { mutableStateOf(!prefs.getBoolean("intro_seen", false)) }
                
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val navController = rememberNavController()
                        val libraryViewModel: com.bpmapp.audio.viewmodel.LibraryViewModel = hiltViewModel()
                        
                        Scaffold(
                            bottomBar = {
                                BottomNavigationBar(navController = navController)
                            },
                            snackbarHost = { SnackbarHost(hostState = remember { SnackbarHostState() }) }
                        ) { paddingValues ->
                            NavHost(
                                navController = navController,
                                startDestination = com.bpmapp.audio.ui.navigation.NavDestination.PLAYER.route,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            ) {
                                composable(com.bpmapp.audio.ui.navigation.NavDestination.PLAYER.route) {
                                    val playerViewModel: PlayerViewModel = hiltViewModel()
                                    PlayerScreenWrapper(
                                        playerRepository = playerRepository,
                                        libraryViewModel = libraryViewModel,
                                        playerViewModel = playerViewModel,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(paddingValues)
                                    )
                                }
                                composable(com.bpmapp.audio.ui.navigation.NavDestination.LIBRARY.route) {
                                    LibraryScreenWrapper(
                                        libraryViewModel = libraryViewModel,
                                        playerRepository = playerRepository,
                                        navController = navController,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(paddingValues)
                                    )
                                }
                                composable(com.bpmapp.audio.ui.navigation.NavDestination.BPM_TOOLS.route) {
                                    BpmToolsScreen(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(paddingValues)
                                    )
                                }
                                composable(com.bpmapp.audio.ui.navigation.NavDestination.SETTINGS.route) {
                                    SettingsScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        libraryViewModel = libraryViewModel,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(paddingValues)
                                    )
                                }
                            }
                        }
                    }
                    // Pale watermark overlay (above content, no touch handling)
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        colorFilter = ColorFilter.tint(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    )
                    // First-launch intro, shown over the main UI with an opaque background
                    if (showIntro) {
                        RunBeatIntroScreen(
                            onDismiss = {
                                showIntro = false
                                prefs.edit().putBoolean("intro_seen", true).apply()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        if (prefs.getBoolean("notification_permission_asked", false)) return
        prefs.edit().putBoolean("notification_permission_asked", true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}



/**
 * Player Screen Wrapper - adapts the existing PlayerScreen to work with navigation
 */
@Composable
fun PlayerScreenWrapper(
    playerRepository: PlayerRepository,
    libraryViewModel: com.bpmapp.audio.viewmodel.LibraryViewModel,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    var showLibrary by remember { mutableStateOf(false) }
    var selectedTrackForPlayback: Track? by remember { mutableStateOf(null) }
    var selectedTrackBpm: Int? by remember { mutableStateOf(null) }
    val targetCadence = playerViewModel.targetCadence

    // Update BPM in player viewModel when track is selected
    LaunchedEffect(selectedTrackBpm) {
        playerViewModel.updateMusicBpm(selectedTrackBpm)
    }

    Box(modifier = modifier) {
        if (showLibrary) {
            LibraryScreen(
                onTrackSelected = { track ->
                    // Load the selected track into the player
                    selectedTrackForPlayback = track
                    selectedTrackBpm = track.bpm?.roundToInt()
                    showLibrary = false
                    libraryViewModel.resolveBpmForTrackIfNeeded(track)
                },
                onBack = { showLibrary = false },
                onAddToUpcoming = { track ->
                    val mediaItem = libraryViewModel.getMediaItemForTrack(track)
                    playerRepository.addToPlaylist(mediaItem)
                    libraryViewModel.resolveBpmForTrackIfNeeded(track) { bpm ->
                        if (bpm != null && bpm > 0) {
                            playerViewModel.updateMusicBpm(bpm.roundToInt())
                        }
                    }
                },
                onAddToUpcomingBatch = { tracks ->
                    val mediaItems = tracks.map { libraryViewModel.getMediaItemForTrack(it) }
                    playerRepository.addAllToPlaylist(mediaItems)
                    libraryViewModel.resolveBmpsForTracksIfNeeded(tracks)
                },
                onPlayAllTracks = { tracks ->
                    // Load all tracks into playlist and play first
                    val mediaItems = tracks.map { track ->
                        libraryViewModel.getMediaItemForTrack(track)
                    }
                    if (mediaItems.isNotEmpty()) {
                        playerRepository.loadPlaylist(mediaItems)
                        playerRepository.play()
                        showLibrary = false
                    }
                    // Detect BPM for tracks with unknown BPM and persist to DB
                    libraryViewModel.resolveBmpsForTracksIfNeeded(tracks)
                },
                targetCadence = targetCadence,
                viewModel = libraryViewModel
            )
        } else {
            // Check if we have a track to load
            selectedTrackForPlayback?.let { track ->
                LaunchedEffect(track) {
                    val mediaItem = libraryViewModel.getMediaItemForTrack(track)
                    playerRepository.loadMediaItem(mediaItem)
                    selectedTrackForPlayback = null
                }
            }
            
            // Use the existing PlayerScreen from the original MainActivity
            PlayerScreen(
                playerRepository = playerRepository,
                onOpenLibrary = { 
                    showLibrary = true
                    selectedTrackBpm = null
                },
                libraryViewModel = libraryViewModel
            )
        }
    }
}

/**
 * Library Screen Wrapper - adapts the existing LibraryScreen to work with navigation
 */
@Composable
fun LibraryScreenWrapper(
    libraryViewModel: com.bpmapp.audio.viewmodel.LibraryViewModel,
    playerRepository: PlayerRepository,
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val targetCadence = playerViewModel.targetCadence

    Box(modifier = modifier) {
        LibraryScreen(
            onTrackSelected = { track ->
                // Load the selected track into the player and navigate back
                val mediaItem = libraryViewModel.getMediaItemForTrack(track)
                playerRepository.loadMediaItem(mediaItem)
                playerViewModel.updateMusicBpm(track.bpm?.roundToInt())
                libraryViewModel.resolveBpmForTrackIfNeeded(track)
                navController.popBackStack()
            },
            onBack = { navController.popBackStack() },
            onAddToUpcoming = { track ->
                val mediaItem = libraryViewModel.getMediaItemForTrack(track)
                playerRepository.addToPlaylist(mediaItem)
                libraryViewModel.resolveBpmForTrackIfNeeded(track) { bpm ->
                    if (bpm != null && bpm > 0) {
                        playerViewModel.updateMusicBpm(bpm.roundToInt())
                    }
                }
            },
            onAddToUpcomingBatch = { tracks ->
                val mediaItems = tracks.map { libraryViewModel.getMediaItemForTrack(it) }
                playerRepository.addAllToPlaylist(mediaItems)
                libraryViewModel.resolveBmpsForTracksIfNeeded(tracks)
            },
            onPlayAllTracks = { tracks ->
                // Load all tracks into playlist and play first
                val mediaItems = tracks.map { track ->
                    libraryViewModel.getMediaItemForTrack(track)
                }
                if (mediaItems.isNotEmpty()) {
                    playerRepository.loadPlaylist(mediaItems)
                    playerRepository.play()
                    navController.popBackStack()
                }
                // Detect BPM for tracks with unknown BPM and persist to DB
                libraryViewModel.resolveBmpsForTracksIfNeeded(tracks)
            },
            targetCadence = targetCadence,
            viewModel = libraryViewModel
        )
    }
}

/**
 * Main Player Screen
 * Contains all player controls, BPM info, tempo adjustment, and playlist
 */
@Composable
fun PlayerScreen(
    playerRepository: PlayerRepository,
    onOpenLibrary: () -> Unit = {},
    libraryViewModel: com.bpmapp.audio.viewmodel.LibraryViewModel? = null
) {
    val viewModel: PlayerViewModel = hiltViewModel()
    val playerState by playerRepository.playerState.collectAsState()
    val currentTrack by playerRepository.currentTrack.collectAsState()
    val playlist by playerRepository.playlist.collectAsState()
    val trackBpms by playerRepository.trackBpms.collectAsState()
    val error by playerRepository.error.collectAsState()
    val shuffleEnabled by playerRepository.shuffleEnabled.collectAsState()
    val existingPlaylists = libraryViewModel?.playlists?.collectAsState()?.value ?: emptyList()

    // Track ids from the current playlist, used to save/update a library playlist
    val trackIds = libraryViewModel?.trackIdsFromMediaItems(playlist) ?: emptyList()

    // Dialog state for saving the current playlist to the library
    var showSavePlaylistDialog by remember { mutableStateOf(false) }
    var playlistNameInput by remember { mutableStateOf("") }

    // Reorder mode state
    var reorderMode by remember { mutableStateOf(false) }

    // Use playerState.currentPosition directly - it's already updated by the repository
    val currentPosition = playerState.currentPosition

    // Reset BPM state and speed when track changes
    LaunchedEffect(currentTrack) {
        // Reset manually detected BPM state for new track
        viewModel.resetTapBpm()
        viewModel.clearAutoDetectedBpm()
        
        // Update BPM from metadata if available
        val metadataBpm = currentTrack?.mediaMetadata?.extras?.getString("bpm")?.toFloatOrNull()
        if (metadataBpm != null) {
            // Track has BPM in metadata, use it
            viewModel.updateMusicBpm(metadataBpm.roundToInt())
        } else {
            // No BPM in metadata, reset to unknown
            viewModel.updateMusicBpm(null)
            // Reset speed to normal for new track with unknown BPM
            viewModel.resetPlaybackSpeed()
            // Auto-detect BPM for tracks with unknown BPM and refresh the tile
            libraryViewModel?.resolveBpmForCurrentPlaybackIfNeeded(currentTrack) { bpm ->
                if (bpm != null && bpm > 0) {
                    viewModel.updateMusicBpm(bpm.roundToInt())
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(AppSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
    ) {
        // ========== APP VERSION HEADER ==========
        val context = LocalContext.current
        val versionPrefix = stringResource(R.string.player_version_prefix)
        val versionFallback = stringResource(R.string.player_version_fallback)
        val versionName = remember {
            try {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_META_DATA
                )
                versionPrefix.format(packageInfo.versionName)
            } catch (e: Exception) {
                versionFallback
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.player_app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = versionName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        
        // ========== ERROR MESSAGE ==========
        error?.let { errorMsg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Text(
                    text = errorMsg,
                    modifier = Modifier.padding(AppSpacing.md),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        // ========== PLAYBACK CONTROLS WITH PROGRESS ==========
        PlaybackControlsSection(
            trackName = currentTrack?.mediaMetadata?.title?.toString() ?: stringResource(R.string.player_no_track_selected),
            isPlaying = playerState.isPlaying,
            duration = playerState.duration,
            position = currentPosition,
            onPlay = { playerRepository.play() },
            onPause = { playerRepository.pause() },
            onStop = { playerRepository.stop() },
            onPrevious = { playerRepository.playPrevious() },
            onNext = { playerRepository.playNext() },
            onSeek = { newPosition -> playerRepository.seekTo(newPosition) },
            onToggleSpeedCorrection = { viewModel.toggleApplyCadenceMatch() },
            speedCorrectionEnabled = viewModel.applyCadenceMatch,
            modifier = Modifier.fillMaxWidth()
        )
        
        TempoAndBpmSection(
            targetCadence = viewModel.targetCadence,
            adjustedCadence = viewModel.adjustedCadence,
            speedFactor = viewModel.calculatedSpeed,
            onCadenceChange = { newCadence -> viewModel.updateTargetCadence(newCadence) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.xxs)
        )

        
        // ========== PLAYLIST SECTION ==========
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenLibrary() }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.player_upcoming_count, playlist.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (playlist.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { playerRepository.toggleShuffle() },
                            modifier = Modifier.size(AppSpacing.xxl)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = stringResource(R.string.player_toggle_shuffle),
                                tint = if (shuffleEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(
                            onClick = { showSavePlaylistDialog = true },
                            modifier = Modifier.size(AppSpacing.xxl)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlaylistAdd,
                                contentDescription = stringResource(R.string.player_save_playlist),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(
                            onClick = { playerRepository.clearPlaylist() },
                            modifier = Modifier.size(AppSpacing.xxl)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.player_clear_playlist),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(
                            onClick = { reorderMode = !reorderMode },
                            modifier = Modifier.size(AppSpacing.xxl)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DragHandle,
                                contentDescription = stringResource(R.string.player_toggle_reorder),
                                tint = if (reorderMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
        PlaylistSection(
            mediaItems = playlist,
            selectedMediaItem = currentTrack,
            onItemSelected = { mediaItem ->
                playerRepository.playMediaItem(mediaItem)
            },
            onMove = { from, to -> playerRepository.movePlaylistItem(from, to) },
            reorderMode = reorderMode,
            targetCadence = viewModel.targetCadence,
            analyzingTracks = libraryViewModel?.isResolvingBpm?.collectAsState()?.value ?: false,
            trackBpms = trackBpms,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

    }

    // ========== SAVE PLAYLIST DIALOG ==========
    if (showSavePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showSavePlaylistDialog = false },
            title = { Text(stringResource(R.string.dialog_save_playlist_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    if (trackIds.isEmpty()) {
                        Text(
                            text = stringResource(R.string.dialog_save_playlist_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = playlistNameInput,
                        onValueChange = { playlistNameInput = it },
                        label = { Text(stringResource(R.string.dialog_save_playlist_name_label)) },
                        singleLine = true,
                        enabled = trackIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.xxs))
                    Text(
                        text = stringResource(R.string.dialog_save_playlist_update_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    existingPlaylists.forEach { playlistItem ->
                        TextButton(
                            onClick = {
                                libraryViewModel?.updatePlaylistTracks(playlistItem.id, trackIds)
                                showSavePlaylistDialog = false
                            },
                            enabled = trackIds.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.dialog_save_playlist_update_button, playlistItem.name),
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = playlistNameInput.trim()
                        if (name.isNotBlank()) {
                            libraryViewModel?.createPlaylistWithTracks(name, trackIds)
                            playlistNameInput = ""
                            showSavePlaylistDialog = false
                        }
                    },
                    enabled = trackIds.isNotEmpty() && playlistNameInput.trim().isNotBlank()
                ) {
                    Text(stringResource(R.string.dialog_save_playlist_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePlaylistDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }
}

// ========== COMPOSABLE COMPONENTS ==========

@Composable
fun PlaybackControlsSection(
    trackName: String,
    isPlaying: Boolean,
    duration: Long,
    position: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleSpeedCorrection: () -> Unit,
    speedCorrectionEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.heightIn(min = 150.dp),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
        ) {
            // Track name with time display on sides
            val totalSeconds = duration / 1000
            val currentSeconds = position / 1000
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTime(currentSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = trackName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = AppSpacing.xxs),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTime(totalSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Progress bar
            val progress = if (duration > 0) position.toFloat() / duration else 0f
            Slider(
                value = progress,
                onValueChange = { newProgress ->
                    val newPosition = (newProgress * duration).toLong()
                    onSeek(newPosition)
                },
                valueRange = 0f..1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp) // Thicker track
            )
            
            // Playback controls: Previous, Play/Pause, Stop, Next
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous button
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.player_previous),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.width(AppSpacing.xxs))
                
                // Play/Pause button
                Button(
                    onClick = { if (isPlaying) onPause() else onPlay() },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(AppSpacing.xxs))
                
                // Stop button
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.stop),
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(AppSpacing.xxs))
                
                // Next button
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.player_next),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // Speed correction toggle button - 48dp touch target
                Spacer(modifier = Modifier.width(AppSpacing.xxs))
                IconButton(
                    onClick = onToggleSpeedCorrection,
                    modifier = Modifier.touchTarget()
                ) {
                    if (speedCorrectionEnabled) {
                        Icon(
                            imageVector = Icons.Filled.ToggleOn,
                            contentDescription = stringResource(R.string.player_disable_speed),
                            modifier = Modifier.size(48.dp),
                            tint = Color.Red  // MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.ToggleOff,
                            contentDescription = stringResource(R.string.player_enable_speed),
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TempoAndBpmSection(
    targetCadence: Int,
    adjustedCadence: Float,
    speedFactor: Float,
    onCadenceChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.heightIn(min = AppSpacing.sm),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.player_target_cadence),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )

                // Focal BPM display as per Proposition 2.4
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Minus button - smaller (sm = 16dp)
                    IconButton(
                        onClick = { onCadenceChange(targetCadence - 1) },
                        modifier = Modifier.size(AppSpacing.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Remove,
                            contentDescription = stringResource(R.string.player_decrease_bpm),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.width(AppSpacing.xs))

                    // BPM value - large and prominent in bordered box
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(AppSpacing.xs),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$targetCadence",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.width(AppSpacing.xs))

                    // Plus button - smaller (sm = 16dp)
                    IconButton(
                        onClick = { onCadenceChange(targetCadence + 1) },
                        modifier = Modifier.size(AppSpacing.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.player_increase_bpm),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Original BPM - secondary detail underneath
            val originalBpmText = if (adjustedCadence > 0) {
                stringResource(R.string.player_bpm_value, adjustedCadence.roundToInt())
            } else {
                stringResource(R.string.player_unknown)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.player_factor_display, speedFactor),
                    style = MaterialTheme.typography.bodySmall,
                    color = speedFactorColor(speedFactor),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.player_original_bpm, originalBpmText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistSection(
    mediaItems: List<MediaItem>,
    selectedMediaItem: MediaItem?,
    onItemSelected: (MediaItem) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    reorderMode: Boolean = false,
    modifier: Modifier = Modifier,
    targetCadence: Int = 172,
    analyzingTracks: Boolean = false,
    trackBpms: Map<String, Float> = emptyMap()
) {
    val cadenceMatcher = remember { CadenceMatcher() }
    
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(AppSpacing.xxs),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
        ) {
            if (analyzingTracks) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text(
                        text = stringResource(R.string.player_analyzing_bpm),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (mediaItems.isEmpty()) {
                Text(
                    text = stringResource(R.string.player_no_upcoming),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val lazyListState = rememberLazyListState()
                val reorderableLazyColumnState = rememberReorderableLazyColumnState(lazyListState) { from, to ->
                    onMove(from.index, to.index)
                }
                
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xxxs),
                    contentPadding = PaddingValues(bottom = AppSpacing.xxs)
                ) {
                    itemsIndexed(
                        items = mediaItems,
                        key = { _, it -> it.mediaMetadata.extras?.getString("trackId") ?: it.toString() }
                    ) { index, mediaItem ->
                        // Resolve BPM for this track: in-session map first (reactively
                        // refreshed after analysis), then MediaItem extras as a fallback.
                        val trackId = mediaItem.mediaMetadata.extras?.getString("trackId")
                        val bpm = trackId?.let { trackBpms[it] }
                            ?: mediaItem.mediaMetadata.extras?.getString("bpm")?.toFloatOrNull()

                        // Calculate speed factor for this track
                        val playbackSpeed = bpm?.let { detectedBpm ->
                            val matchResult = cadenceMatcher.findOptimalMatch(
                                detectedBpm = detectedBpm,
                                targetCadence = targetCadence.toFloat()
                            )
                            if (matchResult.rhythmPattern.factor > 0) {
                                targetCadence.toFloat() / (detectedBpm * matchResult.rhythmPattern.factor)
                            } else {
                                null
                            }
                        }

                        ReorderableItem(
                            reorderableLazyColumnState,
                            key = mediaItem.mediaMetadata.extras?.getString("trackId") ?: mediaItem.toString()
                        ) { isDragging ->
                            PlaylistItem(
                                mediaItem = mediaItem,
                                isSelected = mediaItem == selectedMediaItem,
                                onClick = { onItemSelected(mediaItem) },
                                playbackSpeed = playbackSpeed,
                                bpm = bpm,
                                reorderMode = reorderMode,
                                isDragging = isDragging,
                                scope = this
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistItem(
    mediaItem: MediaItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    playbackSpeed: Float? = null,
    bpm: Float? = null,
    reorderMode: Boolean = false,
    isDragging: Boolean = false,
    scope: ReorderableItemScope? = null
) {
    val title = mediaItem.mediaMetadata.title?.toString() ?: stringResource(R.string.error_unknown)
    val artist = mediaItem.mediaMetadata.artist?.toString()
    val album = mediaItem.mediaMetadata.albumTitle?.toString()
    
    val bpmText = if (bpm != null) stringResource(R.string.player_bpm_suffix, bpm.roundToInt()) else stringResource(R.string.player_bpm_unknown)
    
    if (reorderMode) {
        // In reorder mode: use Box with draggableHandle on the right-side icon
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else if (isDragging) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AppSpacing.xxxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xxxs)
                ) {
                    // Line 1: Title with BPM/speed factor trailing
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDragging) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = if (playbackSpeed != null) {
                                stringResource(R.string.player_bpm_with_x, bpmText, playbackSpeed)
                            } else {
                                bpmText
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = bpmColor(playbackSpeed),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    // Line 2: Artist - Album - full width, small and muted
                    val artistAlbumText = when {
                        !artist.isNullOrBlank() && !album.isNullOrBlank() -> "$artist - $album"
                        !artist.isNullOrBlank() -> artist
                        !album.isNullOrBlank() -> album
                        else -> ""
                    }
                    if (artistAlbumText.isNotBlank()) {
                        Text(
                            text = artistAlbumText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDragging) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                // Drag handle on the RIGHT side, using library's draggableHandle
                IconButton(
                    onClick = {},
                    modifier = with(scope!!) { Modifier.draggableHandle() }
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragHandle,
                        contentDescription = stringResource(R.string.player_drag_handle),
                        tint = if (isDragging) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        }
                    )
                }
            }
        }
    } else {
        // Normal mode: use Button with tap-to-play
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = if (isSelected) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha=0.3f),
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AppSpacing.xxxs),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xxxs)
            ) {
                // Line 1: Title with BPM/speed factor trailing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = if (playbackSpeed != null) {
                            "$bpmText  ${"%.2f".format(playbackSpeed)}x"
                        } else {
                            bpmText
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = bpmColor(playbackSpeed),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // Line 2: Artist - Album - full width, small and muted
                val artistAlbumText = when {
                    !artist.isNullOrBlank() && !album.isNullOrBlank() -> "$artist - $album"
                    !artist.isNullOrBlank() -> artist
                    !album.isNullOrBlank() -> album
                    else -> ""
                }
                if (artistAlbumText.isNotBlank()) {
                    Text(
                        text = artistAlbumText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// Utility function to format time
fun formatTime(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", minutes, secs)
}

