package com.bpmapp.audio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bpmapp.audio.R
import com.bpmapp.audio.ui.theme.AppSpacing
import com.bpmapp.audio.ui.theme.BpmGreenLight
import com.bpmapp.audio.ui.theme.ComponentSpacing
import com.bpmapp.audio.ui.theme.TouchTargets
import com.bpmapp.audio.ui.theme.speedFactorColor
import com.bpmapp.audio.viewmodel.BpmToolsViewModel
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * BPM Tools Screen
 * Tabbed screen for BPM tools (Detect BPM, Cadence Matcher, Manual Entry)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpmToolsScreen(
    modifier: Modifier = Modifier,
    viewModel: BpmToolsViewModel = hiltViewModel()
) {
    val selectedTabIndex by viewModel.selectedTabIndex.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        // Tab Row
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { viewModel.selectTab(0) },
                icon = { Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.bpmtools_tab_matcher)) },
                text = { Text(stringResource(R.string.bpmtools_tab_matcher)) }
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { viewModel.selectTab(1) },
                icon = { Icon(Icons.Filled.Timer, contentDescription = stringResource(R.string.bpmtools_tab_detect)) },
                text = { Text(stringResource(R.string.bpmtools_tab_detect)) }
            )
            Tab(
                selected = selectedTabIndex == 2,
                onClick = { viewModel.selectTab(2) },
                icon = { Icon(Icons.Filled.MusicNote, contentDescription = stringResource(R.string.bpmtools_tab_manual)) },
                text = { Text(stringResource(R.string.bpmtools_tab_manual)) }
            )
        }

        // Tab Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.md),
            contentAlignment = Alignment.TopCenter
        ) {
            when (selectedTabIndex) {
                0 -> CadenceMatcherTab(viewModel)
                1 -> DetectBpmTab(viewModel)
                2 -> ManualEntryTab(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CadenceMatcherTab(viewModel: BpmToolsViewModel) {
    val targetCadence by viewModel.targetCadence.collectAsState()
    val rhythmMatches by viewModel.rhythmMatches.collectAsState()
    val selectedRhythmPatterns by viewModel.selectedRhythmPatterns.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        Text(
            text = stringResource(R.string.bpmtools_cadence_matcher),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Target Cadence Slider (150-195 BPM)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = ComponentSpacing.cardElevation)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.decrementTargetCadence() },
                        modifier = Modifier.size(TouchTargets.standard)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Remove,
                            contentDescription = stringResource(R.string.bpmtools_decrease)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
                    ) {
                        Text(
                            text = stringResource(R.string.bpmtools_target_cadence),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.bpmtools_cadence_value, targetCadence),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = { viewModel.incrementTargetCadence() },
                        modifier = Modifier.size(TouchTargets.standard)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.bpmtools_increase)
                        )
                    }
                }
                Slider(
                    value = targetCadence.toFloat(),
                    onValueChange = { viewModel.setTargetCadence(it.toInt()) },
                    valueRange = 150f..195f,
                    steps = 44,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Rhythm Group Chips (multi-select): Binary and Ternary
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = ComponentSpacing.cardElevation)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.bpmtools_rhythm_pattern),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val groups = viewModel.getRhythmGroups()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    groups.forEach { group ->
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
                        ) {
                            FilterChip(
                                selected = viewModel.isGroupSelected(group, selectedRhythmPatterns),
                                onClick = { viewModel.toggleRhythmGroup(group) },
                                label = { Text(text = stringResource(group.nameRes)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = stringResource(group.descriptionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }

        // Cadence Matches
        if (rhythmMatches.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = ComponentSpacing.cardElevation)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.bpmtools_cadence_matches),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    rhythmMatches.forEach { match ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
                            ) {
                                Text(
                                    text = stringResource(match.rhythmPattern.nameRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (match.messageRes != 0) stringResource(match.messageRes, *match.messageArgs) else match.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Text(
                                text = String.format(Locale.getDefault(), "%.2fx", match.speedFactor),
                                style = MaterialTheme.typography.titleMedium,
                                color = speedFactorColor(match.speedFactor)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetectBpmTab(viewModel: BpmToolsViewModel) {
    val detectedBpm by viewModel.detectedBpm.collectAsState()
    val isDetectingBpm by viewModel.isDetectingBpm.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val detectedFromTap by viewModel.detectedFromTap.collectAsState()
    var showUseConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        Text(
            text = stringResource(R.string.bpmtools_detect_bpm),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Aubio Detection (primary)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = ComponentSpacing.cardElevation),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !isDetectingBpm
                    ) {
                        viewModel.detectBpmWithAubio()
                    }
                    .padding(AppSpacing.md),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    if (isDetectingBpm) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(AppSpacing.lg)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = stringResource(R.string.bpmtools_detect_with_aubio),
                            modifier = Modifier.size(AppSpacing.lg)
                        )
                    }
                    Text(
                        text = if (isDetectingBpm) stringResource(R.string.bpmtools_detecting) else stringResource(R.string.bpmtools_detect_with_aubio),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        // Tap Area (secondary)
        Text(
            text = stringResource(R.string.bpmtools_or_tap),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        if ((playbackSpeed - 1.0f).absoluteValue > 0.01f) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        MaterialTheme.shapes.small
                    )
                    .padding(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(AppSpacing.sm)
                )
                Text(
                    text = stringResource(R.string.bpmtools_speed_adjusted, String.format(Locale.getDefault(), "%.2fx", playbackSpeed)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = ComponentSpacing.cardElevation)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        viewModel.onTap()
                    }
                    .padding(AppSpacing.md),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = stringResource(R.string.bpmtools_tap_here),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(AppSpacing.lg)
                    )
                    detectedBpm?.let { bpm ->
                        Text(
                            text = stringResource(R.string.bpmtools_detected_bpm, bpm),
                            style = MaterialTheme.typography.titleMedium,
                            color = BpmGreenLight
                        )
                    } ?: run {
                        Text(
                            text = stringResource(R.string.bpmtools_tap_beat),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // Use/Clear Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Button(
                onClick = {
                    if (detectedFromTap && (playbackSpeed - 1.0f).absoluteValue > 0.01f) {
                        showUseConfirm = true
                    } else {
                        viewModel.useDetectedBpm()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(TouchTargets.standard),
                enabled = detectedBpm != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = stringResource(R.string.bpmtools_use),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            OutlinedButton(
                onClick = { viewModel.clearDetectedBpm() },
                modifier = Modifier
                    .weight(1f)
                    .height(TouchTargets.standard),
                enabled = detectedBpm != null,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error)
                )
            ) {
                Text(
                    text = stringResource(R.string.bpmtools_clear),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // Error Message
        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (showUseConfirm) {
        AlertDialog(
            onDismissRequest = { showUseConfirm = false },
            title = {
                Text(stringResource(R.string.bpmtools_use_title), style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Text(
                    text = stringResource(R.string.bpmtools_use_body, String.format(Locale.getDefault(), "%.2fx", playbackSpeed)),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUseConfirm = false
                        viewModel.useDetectedBpm()
                    }
                ) {
                    Text(stringResource(R.string.bpmtools_use))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUseConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }
}

@Composable
fun ManualEntryTab(viewModel: BpmToolsViewModel) {
    val manualBpmInput by viewModel.manualBpmInput.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        Text(
            text = stringResource(R.string.bpmtools_manual_entry),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        // BPM Input
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = ComponentSpacing.cardElevation)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.bpmtools_enter_bpm),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.setManualBpmInput("") },
                        modifier = Modifier.size(TouchTargets.standard)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.bpmtools_clear)
                        )
                    }
                    OutlinedTextField(
                        value = manualBpmInput,
                        onValueChange = { viewModel.setManualBpmInput(it.filter { c -> c.isDigit() }.take(3)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.bpmtools_enter_bpm_hint),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    (1..9).forEach { num ->
                        Button(
                            onClick = { viewModel.setManualBpmInput(manualBpmInput + num.toString()) },
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(
                                text = num.toString(),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Button(
                        onClick = { viewModel.setManualBpmInput(manualBpmInput + "0") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            text = "0",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Button(
                        onClick = { 
                            if (manualBpmInput.isNotEmpty()) {
                                viewModel.setManualBpmInput(manualBpmInput.dropLast(1))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.trackcard_delete)
                        )
                    }
                }
            }
        }

        // Save Button
        Button(
            onClick = { viewModel.saveManualBpm() },
            modifier = Modifier
                .fillMaxWidth()
                .height(TouchTargets.standard),
            enabled = manualBpmInput.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                text = stringResource(R.string.bpmtools_save_bpm_file),
                style = MaterialTheme.typography.labelLarge
            )
        }

        // Error Message
        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BpmToolsScreenPreview() {
    MaterialTheme {
        BpmToolsScreen()
    }
}