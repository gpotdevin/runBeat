// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import com.bpmapp.audio.R
import com.bpmapp.audio.ui.theme.AppSpacing
import com.bpmapp.audio.viewmodel.LibraryViewModel
import com.bpmapp.audio.viewmodel.SettingsViewModel

/**
 * Settings Screen
 * Application settings and preferences
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    libraryViewModel: LibraryViewModel? = null
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val libVm = libraryViewModel ?: hiltViewModel<LibraryViewModel>()
    val context = LocalContext.current
    
    // Collect all settings from ViewModel
    val autoApplyCadenceMatch by viewModel.autoApplyCadenceMatch.collectAsState()
    val modeBeepSignal by viewModel.modeBeepSignal.collectAsState()
    val autoScanOnStartup by viewModel.autoScanOnStartup.collectAsState()
    val dynamicColors by viewModel.dynamicColors.collectAsState()
    
    val uriHandler = LocalUriHandler.current
    val versionInfo = remember { viewModel.getVersionInfo(context) }
    val appLicense = remember { viewModel.getAppLicense() }
    val libraries = remember { viewModel.getLibraries() }
    val repoUrl = stringResource(R.string.about_repo_url)
    
    // Help / intro dialog state
    var showIntroDialog by remember { mutableStateOf(false) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    val importCount by libVm.importCount.collectAsState(0)
    val csvImportError by libVm.errorMessage.collectAsState(null)

    LaunchedEffect(importCount) {
        if (importCount > 0) {
            snackbarHostState.showSnackbar("Imported $importCount tracks")
            libVm.clearImportCount()
        }
    }
    LaunchedEffect(csvImportError) {
        csvImportError?.let { snackbarHostState.showSnackbar(it) }
    }
    
    val csvImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { libVm.importFromCsv(it) }
    }
    
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
        // Top App Bar
        TopAppBar(
            title = { 
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xxs),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxxs)
        ) {
            // PLAYBACK Section
            SettingsSection(title = "🎵 PLAYBACK") {
                // Auto-Apply Cadence Match
                SettingItem(
                    title = "Auto-Apply Cadence Match",
                    description = "Automatically apply cadence matching when track changes"
                ) {
                    Switch(
                        checked = autoApplyCadenceMatch,
                        onCheckedChange = { viewModel.setAutoApplyCadenceMatch(it) },
                        modifier = Modifier.padding(end = AppSpacing.sm)
                    )
                }
                // Rhythm mode beep signal
                SettingItem(
                    title = stringResource(R.string.setting_mode_beep_title),
                    description = stringResource(R.string.setting_mode_beep_description)
                ) {
                    Switch(
                        checked = modeBeepSignal,
                        onCheckedChange = { viewModel.setModeBeepSignal(it) },
                        modifier = Modifier.padding(end = AppSpacing.sm)
                    )
                }
            }
            
            
            
            // LIBRARY Section
            SettingsSection(title = "📚 LIBRARY") {
                // Auto-Scan on Startup
                SettingItem(
                    title = "Auto-Scan on Startup",
                    description = "Automatically scan for new tracks when app starts"
                ) {
                    Switch(
                        checked = autoScanOnStartup,
                        onCheckedChange = { viewModel.setAutoScanOnStartup(it) },
                        modifier = Modifier.padding(end = AppSpacing.sm)
                    )
                }
            }
            
            
            
            // APPEARANCE Section
            SettingsSection(title = "🎨 APPEARANCE") {
                // Dynamic Colors
                SettingItem(
                    title = "Dynamic Colors",
                    description = "Use system dynamic colors (Android 12+)"
                ) {
                    Switch(
                        checked = dynamicColors,
                        onCheckedChange = { viewModel.setDynamicColors(it) },
                        modifier = Modifier.padding(end = AppSpacing.sm)
                    )
                }
            }
            
            
            
            // ADVANCED Section
            SettingsSection(title = "⚙️ ADVANCED") {
                SettingItem(
                    title = "Import CSV",
                    description = "Import tracks and BPM values from a CSV file"
                ) {
                    TextButton(onClick = { csvImportLauncher.launch("*/*") }) {
                        Text(
                            text = "Import",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.xxs),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xxxs)
                ) {
                    Text(
                        text = "CSV format — one track per line (header line is skipped):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "relative_path,filename,bpm,file_size_bytes[,title,artist,album,genre]",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Example: GoGo_Penguin/A_Humdrum_Star,1-2-Raven-320.mp3,152.0,12166665,Song Title,Artist Name,Album Name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Filenames containing commas must be quoted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            
            
            // HELP Section
            SettingsSection(title = "❓ ${stringResource(R.string.help_section_title)}") {
                // Introduction
                SettingItem(
                    title = stringResource(R.string.help_intro_title),
                    description = stringResource(R.string.help_intro_description)
                ) {
                    TextButton(
                        onClick = { showIntroDialog = true }
                    ) {
                        Text(
                            text = stringResource(R.string.help_intro_view),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            
            
            // ABOUT Section
            SettingsSection(title = "ℹ️ ABOUT") {
                // Project repository
                SettingItem(
                    title = stringResource(R.string.about_repo_title),
                    description = stringResource(R.string.about_repo_description)
                ) {
                    TextButton(
                        onClick = { uriHandler.openUri(repoUrl) }
                    ) {
                        Text(
                            text = stringResource(R.string.help_intro_view),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Version
                SettingItem(
                    title = "Version",
                    description = versionInfo
                ) {
                    // Display only - no interactive element
                }
                
                // App license
                SettingItem(
                    title = "App license",
                    description = appLicense
                ) {
                    // Display only - no interactive element
                }

                // Libraries
                SettingItem(
                    title = "Libraries",
                    description = "Third-party libraries used by this app"
                ) {
                    // Display only - no interactive element
                }
                libraries.forEach { library ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppSpacing.xxxs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxxs)
                        ) {
                            Text(
                                text = library.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = library.license,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { uriHandler.openUri(library.url) }
                        ) {
                            Text(
                                text = "View",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
    }

    // HELP / INTRO DIALOG
    if (showIntroDialog) {
        AlertDialog(
            onDismissRequest = { showIntroDialog = false },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    RunBeatIntroContent()
                }
            },
            confirmButton = {
                TextButton(onClick = { showIntroDialog = false }) {
                    Text(stringResource(R.string.intro_got_it))
                }
            }
        )
    }
}
/**
 * Settings Section Component
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xxxs)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

/**
 * Individual Setting Item Component
 */
@Composable
fun SettingItem(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.xxxs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxxs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        content()
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen()
    }
}
