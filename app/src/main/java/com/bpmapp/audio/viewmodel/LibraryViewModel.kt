// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.viewmodel

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.bpmapp.audio.audio.BpmResolver
import com.bpmapp.audio.audio.CadenceMatcher
import com.bpmapp.audio.audio.MetadataEditor
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.audio.TrackRepository
import com.bpmapp.audio.data.AppDatabase
import com.bpmapp.audio.data.Playlist
import com.bpmapp.audio.data.PlaylistDao
import com.bpmapp.audio.data.PlaylistTrack
import com.bpmapp.audio.data.Track
import com.bpmapp.audio.data.TrackSource
import com.bpmapp.audio.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for LibraryScreen
 * Manages track library, CSV import, and system library scanning
 */
// The @ApplicationContext field references the application scope (a process-wide singleton),
// so retaining it in this ViewModel does not leak any Activity or short-lived component.
@SuppressLint("StaticFieldLeak")
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val bpmResolver: BpmResolver,
    private val playerRepository: PlayerRepository,
    appDatabase: AppDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val trackDao = appDatabase.trackDao()
    private val playlistDao: PlaylistDao = appDatabase.playlistDao()

    // Used for the "Speed 0.9-1.1" library filter, which mirrors the per-track
    // speed-factor computation shown on TrackCard.
    private val cadenceMatcher = CadenceMatcher()
    
    companion object {
        private const val TAG = "LibraryViewModel"
    }
    
    // All tracks from repository
    val allTracks = trackRepository.allTracks
    
    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    // Category filter state (album, artist, genre)
    private val _selectedArtists = MutableStateFlow<Set<String>>(emptySet())
    val selectedArtists: StateFlow<Set<String>> = _selectedArtists.asStateFlow()
    
    private val _selectedAlbums = MutableStateFlow<Set<String>>(emptySet())
    val selectedAlbums: StateFlow<Set<String>> = _selectedAlbums.asStateFlow()
    
    private val _selectedGenres = MutableStateFlow<Set<String>>(emptySet())
    val selectedGenres: StateFlow<Set<String>> = _selectedGenres.asStateFlow()

    // Browse dimension: controls which single category chip row is visible on
    // the All tab. Selecting a dimension does not activate or deactivate any
    // filter; it only changes which set of chips is displayed. Selections made
    // in one dimension persist when switching to another.
    enum class BrowseDimension { ARTIST, ALBUM, GENRE, PATH }
    private val _browseDimension = MutableStateFlow(BrowseDimension.ARTIST)
    val browseDimension: StateFlow<BrowseDimension> = _browseDimension.asStateFlow()

    // Path drill-down (N-level). Level 0 is single-select (the base folder);
    // deeper levels are multi-select within the chosen parent. Each entry
    // stores full path prefixes (e.g. "Music/GoGo_Penguin"). The list grows
    // dynamically as the user drills down; level d is always one deeper than
    // the deepest selection, ready for the next drill.
    private val _selectedPathLevels = MutableStateFlow<List<Set<String>>>(listOf(emptySet()))
    val selectedPathLevels: StateFlow<List<Set<String>>> = _selectedPathLevels.asStateFlow()
    
    // Persisted filter/sort state. This ViewModel is Activity-scoped, so the library
    // filters survive switching between the Library and Player destinations and can
    // only be cleared manually by the user.
    private val _showOnlyKnownBpm = MutableStateFlow(false)
    val showOnlyKnownBpm: StateFlow<Boolean> = _showOnlyKnownBpm.asStateFlow()
    
    private val _filterBySpeedFactor = MutableStateFlow(false)
    val filterBySpeedFactor: StateFlow<Boolean> = _filterBySpeedFactor.asStateFlow()
    
    private val _sortByBpm = MutableStateFlow(true)
    val sortByBpm: StateFlow<Boolean> = _sortByBpm.asStateFlow()
    
    private val _sortByAlbum = MutableStateFlow(false)
    val sortByAlbum: StateFlow<Boolean> = _sortByAlbum.asStateFlow()
    
    private val _sortByArtist = MutableStateFlow(false)
    val sortByArtist: StateFlow<Boolean> = _sortByArtist.asStateFlow()
    
    private val _sortDescending = MutableStateFlow(false)
    val sortDescending: StateFlow<Boolean> = _sortDescending.asStateFlow()
    
    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()
    
    // Favorite tracks
    val favoriteTracks: StateFlow<List<Track>> = trackDao.getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    // Playlists
    val playlists: StateFlow<List<Playlist>> = playlistDao.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    // Playlist id -> ordered list of tracks
    val playlistTrackMap: StateFlow<Map<String, List<Track>>> = combine(
        playlistDao.getAllPlaylistTracks(),
        allTracks
    ) { relations, tracks ->
        val tracksById = tracks.associateBy { it.id }
        relations.groupBy { it.playlistId }
            .mapValues { (_, rels) ->
                rels.sortedBy { it.position }
                    .mapNotNull { tracksById[it.trackId] }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    
    // Base filter: search query + BPM-known + speed-factor (within 0.9-1.1).
    // Everything that is NOT a category/dimension filter. Dimension option
    // flows and the final track list both build on top of this, so the chip
    // rows reflect the BPM/speed filters exactly as the track list does.
    private val baseFilteredTrackFlow: Flow<List<Track>> = combine(
        allTracks,
        _searchQuery,
        _showOnlyKnownBpm,
        _filterBySpeedFactor,
        playerRepository.targetCadence
    ) { tracks, query, showKnownBpm, filterSpeed, targetCadence ->
        tracks.filter { track ->
            val matchesQuery = query.isBlank() || track.matchesSearch(query)
            val matchesBpm = !showKnownBpm || (track.bpm != null && track.bpm > 0)
            val matchesSpeed = !filterSpeed || track.matchesSpeedFactor(targetCadence)
            matchesQuery && matchesBpm && matchesSpeed
        }
    }

    // Bundled dimension selections so the final flow and each option flow can
    // combine a single dimension-selection flow instead of five separate ones.
    private data class DimensionSelections(
        val artists: Set<String>,
        val albums: Set<String>,
        val genres: Set<String>,
        val pathLevels: List<Set<String>>
    )

    private val dimensionSelections: Flow<DimensionSelections> = combine(
        _selectedArtists,
        _selectedAlbums,
        _selectedGenres,
        _selectedPathLevels
    ) { artists, albums, genres, pathLevels ->
        DimensionSelections(artists, albums, genres, pathLevels)
    }

    // Final filtered list (search + BPM/speed + all dimensions), unsorted.
    // Sorting is a display concern and stays in the screen.
    private val filteredTrackFlow: Flow<List<Track>> = combine(
        baseFilteredTrackFlow,
        dimensionSelections
    ) { tracks, sel ->
        tracks.filter { it.matchesDimensions(sel.artists, sel.albums, sel.genres, sel.pathLevels) }
    }

    val filteredTracks: StateFlow<List<Track>> = filteredTrackFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Category filter options. Each option flow excludes its own dimension
    // (by passing an empty selection for it) so the chips stay visible and
    // multi-select works. The other dimensions and the BPM/speed filters
    // still prune the options, so the chips always reflect the already-
    // filtered list.
    val artistOptions: StateFlow<List<String>> = combine(
        baseFilteredTrackFlow,
        _selectedAlbums,
        _selectedGenres,
        _selectedPathLevels
    ) { tracks, albums, genres, pathLevels ->
        tracks
            .filter { it.matchesDimensions(emptySet(), albums, genres, pathLevels) }
            .mapNotNull { it.metadataArtist }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albumOptions: StateFlow<List<String>> = combine(
        baseFilteredTrackFlow,
        _selectedArtists,
        _selectedGenres,
        _selectedPathLevels
    ) { tracks, artists, genres, pathLevels ->
        tracks
            .filter { it.matchesDimensions(artists, emptySet(), genres, pathLevels) }
            .mapNotNull { it.metadataAlbum }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val genreOptions: StateFlow<List<String>> = combine(
        baseFilteredTrackFlow,
        _selectedArtists,
        _selectedAlbums,
        _selectedPathLevels
    ) { tracks, artists, albums, pathLevels ->
        tracks
            .filter { it.matchesDimensions(artists, albums, emptySet(), pathLevels) }
            .map { it.genre() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // N-level path drill-down options. pathOptions[d] is the list of full
    // path prefixes (length d+1) available at level d. Level 0 is always
    // computed; deeper levels appear only when the parent level has a
    // selection. Each level excludes its own selection (non-self-pruning).
    val pathOptions: StateFlow<List<List<String>>> = combine(
        baseFilteredTrackFlow,
        _selectedArtists,
        _selectedAlbums,
        _selectedGenres,
        _selectedPathLevels
    ) { tracks, artists, albums, genres, pathLevels ->
        val result = mutableListOf<List<String>>()
        // Level 0: base folders, no path filter applied.
        result.add(
            tracks
                .filter { it.matchesDimensions(artists, albums, genres, emptyList()) }
                .map { it.pathPrefix(1) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        )
        // Deeper levels: options under the parent selection at the previous level.
        var depth = 1
        while (depth < 32) {
            val parentSelections = pathLevels.getOrElse(depth - 1) { emptySet() }
            if (parentSelections.isEmpty()) break
            val options = tracks
                .filter { it.matchesDimensions(artists, albums, genres, pathLevels.take(depth)) }
                .map { it.pathPrefix(depth + 1) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            if (options.isEmpty()) break
            result.add(options)
            depth++
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(emptyList()))
    
    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // Import count (number of tracks imported in last operation)
    private val _importCount = MutableStateFlow(0)
    val importCount: StateFlow<Int> = _importCount.asStateFlow()
    
    // Error message
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // Library BPM analysis state
    private val _isAnalyzingLibrary = MutableStateFlow(false)
    val isAnalyzingLibrary: StateFlow<Boolean> = _isAnalyzingLibrary.asStateFlow()
    
    private val _analysisProgress = MutableStateFlow<Pair<Int, Int>>(0 to 0)
    val analysisProgress: StateFlow<Pair<Int, Int>> = _analysisProgress.asStateFlow()
    
    private val _analysisResults = MutableStateFlow<Int>(0)
    val analysisResults: StateFlow<Int> = _analysisResults.asStateFlow()
    
    // BPM resolution state (used when adding tracks to upcoming songs)
    private val _isResolvingBpm = MutableStateFlow(false)
    val isResolvingBpm: StateFlow<Boolean> = _isResolvingBpm.asStateFlow()
    
    private val _bpmResolutionProgress = MutableStateFlow<Pair<Int, Int>>(0 to 0)
    val bpmResolutionProgress: StateFlow<Pair<Int, Int>> = _bpmResolutionProgress.asStateFlow()
    
    // Check if we have permission to read media
    private fun hasReadMediaPermission(): Boolean {
        // READ_MEDIA_AUDIO only exists on Android 13+ (API 33); older devices use READ_EXTERNAL_STORAGE
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    // Set error message
    fun setError(message: String) {
        _errorMessage.value = message
    }
    
    /**
     * Import tracks from CSV file
     * Uses a default base directory (external files directory) for resolving relative paths
     */
    fun importFromCsv(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _errorMessage.value = null
            
            try {
                // Use external files directory as base directory for CSV import
                // This is where the app should store/access music files
                val baseDirectory = context.getExternalFilesDir(null)?.absolutePath
                
                val count = trackRepository.importFromCsv(uri, baseDirectory)
                _importCount.value = count
                _isLoading.value = false
                
                if (count == 0) {
                    _errorMessage.value = "No tracks were imported. Please check the CSV file format."
                }
            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = "Failed to import CSV: ${e.message}"
                Log.e(TAG, "CSV import error", e)
            }
        }
    }
    
    /**
     * Scan system music library and add tracks to the database
     */
    @SuppressLint("InlinedApi")
    fun scanSystemLibrary() {
        if (!hasReadMediaPermission()) {
            _errorMessage.value = "Permission required to access system music library"
            return
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _errorMessage.value = null
            
            var count = 0
            
            try {
                val projection = buildList {
                    add(MediaStore.Audio.Media._ID)
                    add(MediaStore.Audio.Media.DISPLAY_NAME)
                    add(MediaStore.Audio.Media.RELATIVE_PATH)
                    add(MediaStore.Audio.Media.SIZE)
                    add(MediaStore.Audio.Media.DURATION)
                    add(MediaStore.Audio.Media.TITLE)
                    add(MediaStore.Audio.Media.ARTIST)
                    add(MediaStore.Audio.Media.ALBUM)
                    // GENRE only exists on Android 11+ (API R); on older devices it is omitted
                    // and the column index resolves to -1, leaving the genre null.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        add(MediaStore.Audio.Media.GENRE)
                    }
                }.toTypedArray()
                
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
                
                val cursor = context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    null
                )
                
                cursor?.use { c ->
                    val idColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val pathColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                    val sizeColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val durationColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val titleColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    // -1 when GENRE was omitted from the projection (API < R)
                    val genreColumn = c.getColumnIndex(MediaStore.Audio.Media.GENRE)
                    
                    while (c.moveToNext()) {
                        val id = c.getLong(idColumn)
                        val fileName = c.getString(nameColumn) ?: continue
                        val relativePath = c.getString(pathColumn)?.trimEnd('/')?.ifEmpty { "." } ?: "."
                        val size = c.getLong(sizeColumn)
                        val duration = c.getLong(durationColumn)
                        val title = c.getString(titleColumn)
                        val artist = c.getString(artistColumn)
                        val album = c.getString(albumColumn)
                        val genre = if (genreColumn >= 0) c.getString(genreColumn) else null
                        
                        // Create content URI for the track
                        val uri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )
                        
                        // Create track
                        val track = trackRepository.createTrack(
                            filePath = uri.toString(),
                            fileName = fileName,
                            relativePath = relativePath,
                            bpm = null, // BPM unknown from system library
                            fileSizeBytes = if (size > 0) size else null,
                            durationMs = if (duration > 0) duration else null,
                            source = TrackSource.SYSTEM,
                            metadataTitle = title,
                            metadataArtist = artist,
                            metadataAlbum = album,
                            metadataGenre = genre
                        )
                        
                        // Add/update the track
                        // If a track with the same filename and size already exists
                        // (e.g. imported from CSV), its metadata is merged with the
                        // system library data and ID3 tags instead of being skipped
                        try {
                            val success = trackRepository.addOrMergeSystemTrack(track)
                            if (success) {
                                count++
                                Log.d(TAG, "Scanned system track: $fileName")
                            } else {
                                Log.w(TAG, "Failed to add system track: $fileName")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to add system track: $fileName", e)
                        }
                    }
                }
                
                _importCount.value = count
                _isLoading.value = false
                
                if (count == 0) {
                    _errorMessage.value = "No tracks found in system music library"
                } else {
                    Log.i(TAG, "Scanned $count tracks from system library")
                }
                
            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = "Failed to scan system library: ${e.message}"
                Log.e(TAG, "System library scan error", e)
            }
        }
    }
    
    /**
     * Update BPM for a track
     */
    fun updateTrackBpm(trackId: String, bpm: Float?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                trackRepository.updateBpm(trackId, bpm)
                Log.d(TAG, "Updated BPM for track: $trackId to $bpm")
            } catch (e: Exception) {
                _errorMessage.value = "Failed to update BPM: ${e.message}"
                Log.e(TAG, "BPM update error", e)
            }
        }
    }
    
    /**
     * Delete a track from the library
     */
    fun deleteTrack(trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                trackRepository.deleteTrack(trackId)
                Log.d(TAG, "Deleted track: $trackId")
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete track: ${e.message}"
                Log.e(TAG, "Track deletion error", e)
            }
        }
    }
    
    /**
     * Delete all tracks from the library
     */
    fun deleteAllTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                trackRepository.deleteAllTracks()
                Log.d(TAG, "Deleted all tracks")
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete tracks: ${e.message}"
                Log.e(TAG, "Delete all tracks error", e)
            }
        }
    }
    
    /**
     * Clear import count
     */
    fun clearImportCount() {
        _importCount.value = 0
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    // ==================== Search & Filter State ====================
    
    /**
     * Update the search query
     */
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }
    
    /**
     * Clear the search query
     */
    fun clearSearch() {
        _searchQuery.value = ""
    }
    
    /**
     * Toggle a selected artist filter
     */
    fun toggleArtist(artist: String) {
        _selectedArtists.value = if (artist in _selectedArtists.value) {
            _selectedArtists.value - artist
        } else {
            _selectedArtists.value + artist
        }
    }
    
    /**
     * Toggle a selected album filter
     */
    fun toggleAlbum(album: String) {
        _selectedAlbums.value = if (album in _selectedAlbums.value) {
            _selectedAlbums.value - album
        } else {
            _selectedAlbums.value + album
        }
    }
    
    /**
     * Toggle a selected genre filter
     */
    fun toggleGenre(genre: String) {
        _selectedGenres.value = if (genre in _selectedGenres.value) {
            _selectedGenres.value - genre
        } else {
            _selectedGenres.value + genre
        }
    }

    /**
     * Select which category dimension's chip row is shown on the All tab.
     * Does not change any active filter; only the visible chip row.
     */
    fun setBrowseDimension(dimension: BrowseDimension) {
        _browseDimension.value = dimension
    }

    /**
     * Select the base folder (level 0) for the Path drill-down. Pass null to
     * clear the entire path dimension. Changing the base folder clears all
     * deeper levels, since they are relative to it.
     */
    fun setPathLevel0(folder: String?) {
        if (folder == null) {
            _selectedPathLevels.value = listOf(emptySet())
        } else {
            _selectedPathLevels.value = listOf(setOf(folder), emptySet())
        }
    }

    /**
     * Toggle a folder selection at the given depth (depth >= 1). Clearing or
     * adding a selection at this depth truncates all deeper levels, since
     * they depend on this level's selection.
     */
    fun togglePathAtDepth(depth: Int, path: String) {
        if (depth < 1) return
        val current = _selectedPathLevels.value
        // Ensure the list is large enough to index this depth.
        val padded = current.toMutableList()
        while (padded.size <= depth) padded.add(emptySet())
        val levelSet = padded[depth].toMutableSet()
        if (path in levelSet) levelSet.remove(path) else levelSet.add(path)
        // Truncate everything deeper than this level, then append the updated
        // level plus one empty level ready for the next drill.
        val truncated = padded.subList(0, depth).toList()
        _selectedPathLevels.value = truncated + listOf(levelSet.toSet(), emptySet())
    }
    
    /**
     * Update the "BPM known" filter
     */
    fun setShowOnlyKnownBpm(enabled: Boolean) {
        _showOnlyKnownBpm.value = enabled
    }
    
    /**
     * Update the "speed factor within 0.9-1.1" filter
     */
    fun setFilterBySpeedFactor(enabled: Boolean) {
        _filterBySpeedFactor.value = enabled
    }
    
    /**
     * Set the primary sort key (true = sort by BPM, mutually exclusive with album/artist)
     */
    fun setSortByBpm(enabled: Boolean) {
        _sortByBpm.value = enabled
    }
    
    /**
     * Set the primary sort key to album (mutually exclusive with BPM/artist)
     */
    fun setSortByAlbum(enabled: Boolean) {
        _sortByAlbum.value = enabled
    }
    
    /**
     * Set the primary sort key to artist (mutually exclusive with BPM/album)
     */
    fun setSortByArtist(enabled: Boolean) {
        _sortByArtist.value = enabled
    }
    
    /**
     * Set the sort direction (true = descending)
     */
    fun setSortDescending(enabled: Boolean) {
        _sortDescending.value = enabled
    }
    
    /**
     * Set the active library tab (0 = All, 1 = Playlists, 2 = Favorites)
     */
    fun setSelectedTabIndex(index: Int) {
        _selectedTabIndex.value = index
    }
    
    /**
     * Reset every library filter and sort option back to its default.
     * Called manually by the user via the "Clear filters" control.
     */
    fun clearAllFilters() {
        _searchQuery.value = ""
        _selectedArtists.value = emptySet()
        _selectedAlbums.value = emptySet()
        _selectedGenres.value = emptySet()
        _selectedPathLevels.value = listOf(emptySet())
        _showOnlyKnownBpm.value = false
        _filterBySpeedFactor.value = false
        _sortByBpm.value = true
        _sortByAlbum.value = false
        _sortByArtist.value = false
        _sortDescending.value = false
    }
    
    // ==================== Favorites ====================
    
    /**
     * Toggle the favorite flag for a track
     */
    fun toggleFavorite(trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val track = trackDao.getTrackById(trackId).firstOrNull()
                if (track != null) {
                    trackDao.updateFavorite(trackId, !track.isFavorite)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to update favorite: ${e.message}"
                Log.e(TAG, "Favorite toggle error", e)
            }
        }
    }
    
    // ==================== Playlists ====================
    
    /**
     * Create a new playlist
     */
    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = UUID.randomUUID().toString()
                playlistDao.insertOrReplace(Playlist(id = id, name = trimmed))
            } catch (e: Exception) {
                _errorMessage.value = "Failed to create playlist: ${e.message}"
                Log.e(TAG, "Playlist creation error", e)
            }
        }
    }
    
    /**
     * Rename an existing playlist
     */
    fun renamePlaylist(playlistId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val playlist = playlistDao.getPlaylistById(playlistId)
                if (playlist != null) {
                    playlistDao.update(playlist.copy(name = trimmed))
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to rename playlist: ${e.message}"
                Log.e(TAG, "Playlist rename error", e)
            }
        }
    }
    
    /**
     * Delete a playlist and its track membership
     */
    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                playlistDao.delete(playlistId)
                playlistDao.deleteTracksForPlaylist(playlistId)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete playlist: ${e.message}"
                Log.e(TAG, "Playlist deletion error", e)
            }
        }
    }

    /**
     * Add the currently selected tracks to a playlist (no-op if already present)
     */
    fun addSelectedTracksToPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                addTracksToPlaylistInternal(playlistId, trackIds)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to add selected tracks to playlist: ${e.message}"
                Log.e(TAG, "Add selected tracks to playlist error", e)
            }
        }
    }

    /**
     * Create a new playlist containing the given tracks
     * Returns the new playlist id, or null when the name is blank or trackIds is empty
     */
    fun createPlaylistWithTracks(name: String, trackIds: List<String>): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trackIds.isEmpty()) {
            _errorMessage.value = "Playlist name and at least one track are required"
            return null
        }
        val id = UUID.randomUUID().toString()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                playlistDao.insertOrReplace(Playlist(id = id, name = trimmed))
                insertPlaylistTracksWithPositions(id, trackIds, startPosition = 0)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to create playlist: ${e.message}"
                Log.e(TAG, "Create playlist with tracks error", e)
            }
        }
        return id
    }

    /**
     * Replace the contents of a playlist with the given tracks (positions 0..n-1)
     */
    fun updatePlaylistTracks(playlistId: String, trackIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                playlistDao.deleteTracksForPlaylist(playlistId)
                if (trackIds.isEmpty()) return@launch
                insertPlaylistTracksWithPositions(playlistId, trackIds, startPosition = 0)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to update playlist: ${e.message}"
                Log.e(TAG, "Update playlist tracks error", e)
            }
        }
    }

    /**
     * Mark multiple tracks as favorites
     */
    fun addTracksToFavorites(trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                trackDao.updateFavorites(trackIds, true)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to update favorites: ${e.message}"
                Log.e(TAG, "Add tracks to favorites error", e)
            }
        }
    }

    /**
     * Extract track ids from a list of MediaItems (from the "trackId" extra)
     */
    fun trackIdsFromMediaItems(mediaItems: List<MediaItem>): List<String> {
        return mediaItems.mapNotNull { mediaItem ->
            mediaItem.mediaMetadata?.extras?.getString("trackId")
        }
    }

    /**
     * Add the given trackIds to a playlist, skipping any already present
     * and continuing positions from the current max position
     */
    private suspend fun addTracksToPlaylistInternal(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val existing = playlistDao.getPlaylistTracksOnce(playlistId)
        val existingIds = existing.map { it.trackId }.toSet()
        val newTracks = trackIds.filter { it !in existingIds }
        if (newTracks.isEmpty()) return
        val startPosition = (existing.maxOfOrNull { it.position } ?: -1) + 1
        insertPlaylistTracksWithPositions(playlistId, newTracks, startPosition = startPosition)
    }

    /**
     * Insert the given trackIds into a playlist with consecutive positions
     * starting from startPosition
     */
    private suspend fun insertPlaylistTracksWithPositions(
        playlistId: String,
        trackIds: List<String>,
        startPosition: Int
    ) {
        val relations = trackIds.mapIndexed { index, trackId ->
            PlaylistTrack(playlistId = playlistId, trackId = trackId, position = startPosition + index)
        }
        playlistDao.insertPlaylistTracks(relations)
    }
    
    /**
     * Remove a track from a playlist
     */
    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                playlistDao.deleteTrackFromPlaylist(playlistId, trackId)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to remove track from playlist: ${e.message}"
                Log.e(TAG, "Remove from playlist error", e)
            }
        }
    }
    
    /**
     * Get a MediaItem for a track to play it
     * Includes BPM information in the media metadata for display in playlist
     */
    fun getMediaItemForTrack(track: Track): MediaItem {
        // Use track.id directly if it's already a valid URI
        // For APP tracks from CSV import, track.id contains the full file:// URI
        val uriString = if (track.id.startsWith("file://") || track.id.startsWith("content://")) {
            track.id
        } else if (track.source == TrackSource.APP) {
            // Fallback: construct file URI from relativePath and fileName
            val path = if (track.relativePath == ".") {
                track.fileName
            } else {
                "${track.relativePath}/${track.fileName}"
            }
            "file:///$path"
        } else {
            track.id
        }
        
        return MediaItem.Builder()
            .setUri(Uri.parse(uriString))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(track.metadataTitle ?: track.fileName)
                    .setArtist(track.metadataArtist ?: "Unknown")
                    .setAlbumTitle(track.metadataAlbum ?: "Unknown")
                    .apply {
                        val extras = Bundle().apply {
                            putString("trackId", track.id)
                            putString("bpm", track.bpm?.toString() ?: "")
                            putString("fileName", track.fileName)
                            putLong("durationMs", track.durationMs ?: 0L)
                        }
                        setExtras(extras)
                    }
                    .build()
            )
            .build()
    }
    
    /**
     * Analyze BPM for all tracks in library
     * Skips tracks that already have BPM set
     */
    fun analyzeLibraryBpm() {
        viewModelScope.launch(Dispatchers.IO) {
            _isAnalyzingLibrary.value = true
            _errorMessage.value = null
            
            try {
                val tracks = trackRepository.allTracks.firstOrNull() ?: emptyList()
                val total = tracks.size
                var completed = 0
                var successCount = 0
                
                tracks.forEach { track ->
                    if (track.bpm != null && track.bpm > 0) {
                        completed++
                        _analysisProgress.value = completed to total
                        return@forEach
                    }
                    
                    try {
                        val bpm = bpmResolver.resolveBpmForTrack(track)
                        
                        if (bpm != null && bpm > 0) {
                            successCount++
                            playerRepository.updateBpmForTrack(track.id, bpm)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to analyze BPM for track ${track.id}", e)
                    }
                    
                    completed++
                    _analysisProgress.value = completed to total
                }
                
                _analysisResults.value = successCount
                
            } catch (e: Exception) {
                _errorMessage.value = "Library analysis failed: ${e.message}"
                Log.e(TAG, "Library BPM analysis error", e)
            } finally {
                _isAnalyzingLibrary.value = false
            }
        }
    }
    
    /**
     * Clear analysis results state
     */
    fun clearAnalysisResults() {
        _analysisResults.value = 0
        _analysisProgress.value = 0 to 0
    }

    // ==================== BPM Resolution (upcoming songs) ====================

    /**
     * Resolve the BPM for a single track if it is not already known in the database.
     * Reads ID3 tags first, then falls back to automatic Aubio detection, and persists
     * any resolved value to the database.
     *
     * @param track Track to resolve BPM for
     * @param onResult Callback with the resolved BPM (null when unresolvable/unchanged)
     */
    fun resolveBpmForTrackIfNeeded(track: Track, onResult: (Float?) -> Unit = {}) {
        if (track.bpm != null && track.bpm > 0) return
        _isResolvingBpm.value = true
        _bpmResolutionProgress.value = 0 to 1
        viewModelScope.launch(Dispatchers.IO) {
            val bpm = try {
                val resolved = bpmResolver.resolveBpmForTrack(track)
                _bpmResolutionProgress.value = 1 to 1
                if (resolved != null && resolved > 0) {
                    Log.d(TAG, "Resolved BPM for ${track.fileName}: $resolved")
                    playerRepository.updateBpmForTrack(track.id, resolved)
                }
                resolved
            } catch (e: Exception) {
                _errorMessage.value = "Failed to resolve BPM: ${e.message}"
                Log.e(TAG, "BPM resolution error", e)
                null
            }
            // The callback may touch the player (e.g. apply the cadence-matched speed),
            // so it must run on the main thread.
            withContext(Dispatchers.Main) {
                onResult(bpm)
                _isResolvingBpm.value = false
            }
        }
    }

    /**
     * When a track starts playing from the player, sync any known BPM from the library
     * into the playlist tile, or auto-detect it when it is unknown (persisting the result).
     *
     * @param mediaItem The media item that just started playing (may carry the trackId extra)
     * @param onResult Callback with the BPM that is now known for the track (null when unresolved)
     */
    fun resolveBpmForCurrentPlaybackIfNeeded(mediaItem: MediaItem?, onResult: (Float?) -> Unit = {}) {
        val trackId = mediaItem?.mediaMetadata?.extras?.getString("trackId") ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val track = trackRepository.allTracks.firstOrNull()?.firstOrNull { it.id == trackId }
                ?: return@launch
            val knownBpm = track.bpm?.takeIf { it > 0 }
            if (knownBpm != null) {
                playerRepository.updateBpmForTrack(track.id, knownBpm)
                withContext(Dispatchers.Main) {
                    onResult(knownBpm)
                }
            } else {
                resolveBpmForTrackIfNeeded(track) { bpm ->
                    onResult(bpm?.takeIf { it > 0 })
                }
            }
        }
    }

    /**
     * Resolve BPM for multiple tracks that don't have one yet.
     * Used e.g. when loading an entire library list into the upcoming songs playlist.
     *
     * @param tracks Tracks to resolve BPM for (already-known BPMs are skipped)
     * @param onProgress Optional callback with (completed, total) after each track
     * @param onCompleted Optional callback with (resolvedCount, total)
     */
    fun resolveBmpsForTracksIfNeeded(
        tracks: List<Track>,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
        onCompleted: ((resolvedCount: Int, total: Int) -> Unit)? = null
    ) {
        val pending = tracks.filter { it.bpm == null || it.bpm <= 0 }
        if (pending.isEmpty()) {
            onCompleted?.invoke(0, tracks.size)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isResolvingBpm.value = true
            _bpmResolutionProgress.value = 0 to pending.size
            // Callbacks may touch the player, so post them on the main thread
            val mainHandler = Handler(Looper.getMainLooper())
            var completed = 0
            var resolvedCount = 0
            try {
                bpmResolver.resolveBpmForTracks(pending) { _, total, bpm ->
                    completed++
                    val track = pending.getOrNull(completed - 1)
                    if (bpm != null && bpm > 0) {
                        resolvedCount++
                        if (track != null) {
                            playerRepository.updateBpmForTrack(track.id, bpm)
                        }
                    }
                    _bpmResolutionProgress.value = completed to total
                    onProgress?.let { cb ->
                        mainHandler.post { cb(completed, total) }
                    }
                }
                mainHandler.post { onCompleted?.invoke(resolvedCount, pending.size) }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to resolve BPMs: ${e.message}"
                Log.e(TAG, "Batch BPM resolution error", e)
                mainHandler.post { onCompleted?.invoke(resolvedCount, pending.size) }
            } finally {
                _isResolvingBpm.value = false
            }
        }
    }

    // ==================== Metadata Editing State ====================

    // Metadata editing state
    private val _isSavingMetadata = MutableStateFlow(false)
    val isSavingMetadata: StateFlow<Boolean> = _isSavingMetadata.asStateFlow()

    private val _metadataSaveProgress = MutableStateFlow<Pair<Int, Int>>(0 to 0)
    val metadataSaveProgress: StateFlow<Pair<Int, Int>> = _metadataSaveProgress.asStateFlow()

    private val _metadataSaveResults = MutableStateFlow<Int>(0)
    val metadataSaveResults: StateFlow<Int> = _metadataSaveResults.asStateFlow()

    private val _metadataError = MutableStateFlow<String?>(null)
    val metadataError: StateFlow<String?> = _metadataError.asStateFlow()

    /**
     * Check if MediaMetadataEditor is available on this device
     */
    fun isMetadataEditingAvailable(): Boolean {
        return trackRepository.isMetadataEditingAvailable()
    }

    /**
     * Save BPM to file metadata for a specific track
     */
    fun saveBpmToFileMetadata(trackId: String, bpm: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            _isSavingMetadata.value = true
            _metadataError.value = null

            try {
                val success = trackRepository.saveBpmToFileMetadata(trackId, bpm)
                if (success) {
                    _metadataSaveResults.value = 1
                } else {
                    _metadataError.value = "Failed to save BPM to file metadata"
                }
            } catch (e: Exception) {
                _metadataError.value = "Error saving BPM: ${e.message}"
                Log.e(TAG, "Error saving BPM to file metadata", e)
            } finally {
                _isSavingMetadata.value = false
            }
        }
    }

    /**
     * Sync BPM from database to file metadata for all tracks
     * Uses parallel processing for better performance
     */
    fun syncAllBpmToFileMetadata() {
        viewModelScope.launch(Dispatchers.IO) {
            _isSavingMetadata.value = true
            _metadataError.value = null
            _metadataSaveProgress.value = 0 to 0

            try {
                trackRepository.syncAllBpmToFileMetadata(
                    concurrency = 4, // Use 4 concurrent workers
                    onProgress = { progress ->
                        _metadataSaveProgress.value = progress.completed to progress.total
                    }
                )

                // The progress callback will handle the final count
                // We'll get the final result from the progress updates

            } catch (e: Exception) {
                _metadataError.value = "Error syncing BPM to files: ${e.message}"
                Log.e(TAG, "Error syncing all BPM to file metadata", e)
            } finally {
                _isSavingMetadata.value = false
            }
        }
    }
    
    /**
     * Get explanation for file access permission
     */
    fun getFileAccessExplanation(): String {
        return PermissionUtils.getFileAccessExplanation()
    }

    /**
     * Clear metadata save state
     */
    fun clearMetadataSaveState() {
        _metadataSaveResults.value = 0
        _metadataSaveProgress.value = 0 to 0
        _metadataError.value = null
    }
}

/**
 * Case-insensitive search match against title, artist, album, filename, or genre
 */
private fun Track.matchesSearch(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return metadataTitle?.lowercase()?.contains(q) == true ||
        metadataArtist?.lowercase()?.contains(q) == true ||
        metadataAlbum?.lowercase()?.contains(q) == true ||
        fileName.lowercase().contains(q) ||
        genre().lowercase().contains(q)
}

/**
 * Genre label from the track's metadata (ID3 tag)
 * Returns "Unknown" when no ID3 genre tag is available
 */
private fun Track.genre(): String {
    return metadataGenre?.takeIf { it.isNotBlank() } ?: "Unknown"
}

/**
 * Whether the track passes all four dimension filters. Passing an empty set
 * (or an empty list for the path dimension) for a dimension disables that
 * filter, which is how the per-dimension option flows exclude their own
 * dimension so chips stay visible for multi-select.
 *
 * Path matching uses the deepest non-empty level in [pathLevels]: the track's
 * prefix at that depth must be in the set. Level d stores prefixes of length
 * d+1 (e.g. level 0 = "Music", level 1 = "Music/GoGo_Penguin").
 */
private fun Track.matchesDimensions(
    artists: Set<String>,
    albums: Set<String>,
    genres: Set<String>,
    pathLevels: List<Set<String>>
): Boolean {
    val matchesArtist = artists.isEmpty() || (metadataArtist != null && artists.contains(metadataArtist))
    val matchesAlbum = albums.isEmpty() || (metadataAlbum != null && albums.contains(metadataAlbum))
    val matchesGenre = genres.isEmpty() || genres.contains(genre())
    val matchesPath = run {
        var deepest = -1
        for (i in pathLevels.indices) {
            if (pathLevels[i].isNotEmpty()) deepest = i
        }
        if (deepest < 0) true else pathLevels[deepest].contains(pathPrefix(deepest + 1))
    }
    return matchesArtist && matchesAlbum && matchesGenre && matchesPath
}

/**
 * Whether the track's optimal cadence-match speed factor falls within the
 * playable [0.9, 1.1] range for the given target cadence. Mirrors the per-track
 * speed-factor computation shown on TrackCard.
 */
private fun Track.matchesSpeedFactor(targetCadence: Float): Boolean {
    val bpm = bpm ?: return false
    if (bpm <= 0f) return false
    val match = CadenceMatcher().findOptimalMatch(bpm.toFloat(), targetCadence)
    if (match.rhythmPattern.factor <= 0f) return false
    val factor = targetCadence / (bpm * match.rhythmPattern.factor)
    return factor in 0.9f..1.1f
}

/**
 * First [depth] segments of [relativePath] joined by '/', used as the path
 * prefix at the given drill-down level. Returns "" if the track has fewer than
 * [depth] segments. The path is trimmed of any trailing slash (system-scanned
 * tracks may store one) before splitting.
 */
private fun Track.pathPrefix(depth: Int): String {
    if (depth <= 0) return ""
    val parts = relativePath.trimEnd('/').split('/', limit = depth + 1)
    return if (parts.size >= depth) parts.take(depth).joinToString("/") else ""
}
