// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bpmapp.audio.ui.theme.AppSpacing
import com.bpmapp.audio.ui.theme.BpmGreenLight
import com.bpmapp.audio.ui.theme.ComponentSpacing
import com.bpmapp.audio.ui.theme.TouchTargets
import com.bpmapp.audio.ui.theme.speedFactorColor
import com.bpmapp.audio.viewmodel.BpmToolsViewModel
import java.util.Locale

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
                icon = { Icon(Icons.Filled.Tune, contentDescription = "Cadence Matcher") },
                text = { Text("Cadence Matcher") }
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { viewModel.selectTab(1) },
                icon = { Icon(Icons.Filled.Timer, contentDescription = "Tap Beat to Detect BPM") },
                text = { Text("Tap Beat to Detect BPM") }
            )
            Tab(
                selected = selectedTabIndex == 2,
                onClick = { viewModel.selectTab(2) },
                icon = { Icon(Icons.Filled.MusicNote, contentDescription = "Manual Entry") },
                text = { Text("Manual Entry") }
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
    val selectedRhythmPatterns by viewModel.selectedRhythmPatterns.collectAsState()
    val rhythmMatches by viewModel.rhythmMatches.collectAsState()
    val rhythmPatterns = viewModel.getRhythmPatterns()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        Text(
            text = "Cadence Matcher",
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
                            contentDescription = "Decrease"
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
                    ) {
                        Text(
                            text = "Target Cadence",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "$targetCadence BPM",
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
                            contentDescription = "Increase"
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

        // Rhythm Pattern Chips (multi-select)
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
                    text = "Rhythm Pattern",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                rhythmPatterns.chunked(2).forEach { rowPatterns ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        rowPatterns.forEach { pattern ->
                            FilterChip(
                                selected = pattern in selectedRhythmPatterns,
                                onClick = { viewModel.toggleRhythmPattern(pattern) },
                                label = {
                                    Text(text = viewModel.getRhythmPatternDisplayName(pattern))
                                },
                                modifier = Modifier.weight(1f)
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
                        text = "Cadence Matches",
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
                                    text = match.rhythmPattern.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = match.message,
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

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        Text(
            text = "Detect BPM",
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
                            contentDescription = "Detect BPM with Aubio",
                            modifier = Modifier.size(AppSpacing.lg)
                        )
                    }
                    Text(
                        text = if (isDetectingBpm) "Detecting..." else "Detect BPM with Aubio",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        // Tap Area (secondary)
        Text(
            text = "Or tap to beat manually",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
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
                        contentDescription = "Tap here",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(AppSpacing.lg)
                    )
                    detectedBpm?.let { bpm ->
                        Text(
                            text = String.format(Locale.getDefault(), "Detected: %d BPM", bpm),
                            style = MaterialTheme.typography.titleMedium,
                            color = BpmGreenLight
                        )
                    } ?: run {
                        Text(
                            text = "Tap beat to detect BPM",
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
                onClick = { viewModel.useDetectedBpm() },
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
                    text = "Use",
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
                    text = "Clear",
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
            text = "Manual Entry",
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
                    text = "Enter BPM",
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
                            contentDescription = "Clear"
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(TouchTargets.standard)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(AppSpacing.sm),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (manualBpmInput.isEmpty()) {
                            Text(
                                text = "Enter BPM value",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        } else {
                            Text(
                                text = manualBpmInput,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
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
                            contentDescription = "Delete"
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
                text = "Save BPM to file ID3 tag",
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