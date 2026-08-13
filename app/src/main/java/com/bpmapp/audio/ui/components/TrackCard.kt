// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bpmapp.audio.data.Track
import com.bpmapp.audio.ui.theme.AppSpacing
import com.bpmapp.audio.ui.theme.bpmColor
import com.bpmapp.audio.ui.theme.speedFactorColor
import java.text.DecimalFormat

/**
 * Compact TrackCard component for LibraryScreen
 * Height: 26dp (compact layout)
 * Features: Overflow menu with Play/Replace, Edit BPM, Delete actions
 *           Explicit "Add to Playlist" button
 */
@Composable
fun TrackCard(
    track: Track,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onEditBpm: () -> Unit,
    onDelete: () -> Unit,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToUpcoming: () -> Unit = {},
    speedFactor: Float? = null,
    isSelected: Boolean = false,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null
) {
    // Get speed factor color for BPM display
    val bpmColorValue = bpmColor(speedFactor)
    
    // Get speed factor icon based on value
    val speedFactorIcon = when {
        speedFactor == null -> Icons.Filled.HelpOutline
        speedFactor == 1.0f -> Icons.Filled.Check
        speedFactor < 0.9f || speedFactor > 1.1f -> Icons.Filled.Warning
        else -> Icons.Filled.Tune
    }
    
    // Get speed factor icon color
    val speedFactorIconColor = speedFactorColor(speedFactor)
    
    // Context menu state
    var showContextMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .semantics {
                contentDescription = "Track: ${track.metadataTitle ?: track.fileName}, " +
                    "${track.metadataArtist ?: "Unknown artist"}, " +
                    "${if (track.bpm != null && track.bpm > 0) "${track.bpm} BPM" else "unknown BPM"}"
            },
        shape = MaterialTheme.shapes.small,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = AppSpacing.xs, vertical = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Track info (Title, Artist) spanning full width, BPM subline below
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // Title
                val displayTitle = track.metadataTitle ?: track.fileName.takeIf { it.isNotEmpty() } ?: "Unknown"
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Artist (if available)
                track.metadataArtist?.let { artist ->
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // BPM subline with speed-factor status icon
                if (track.bpm != null && track.bpm > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = speedFactorIcon,
                            contentDescription = when {
                                speedFactor == null -> "Speed factor unknown"
                                speedFactor == 1.0f -> "Perfect match"
                                speedFactor < 0.9f || speedFactor > 1.1f -> "Needs adjustment"
                                else -> "Good match"
                            },
                            modifier = Modifier.size(12.dp),
                            tint = speedFactorIconColor
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "${DecimalFormat("#.##").format(track.bpm)} BPM",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = bpmColorValue,
                            maxLines = 1
                        )
                    }
                } else {
                    Text(
                        text = "Unknown BPM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            
            // Right side: Favorite, Add to Playlist, Overflow menu (compact, far right)
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Favorite toggle button
                if (onToggleFavorite != null) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                            modifier = Modifier.size(20.dp),
                            tint = if (isFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }
                        )
                    }
                }
                
                // Add to Playlist button
                Button(
                    onClick = onAddToPlaylist,
                    modifier = Modifier.height(30.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = AppSpacing.xxs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add to Playlist",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(1.dp))
                    Text(
                        text = "Add",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                
                // Overflow menu
                Box {
                    IconButton(
                        onClick = { showContextMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Track actions",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    
                    // Context menu
                    DropdownMenu(
                        expanded = showContextMenu,
                        onDismissRequest = { showContextMenu = false },
                        modifier = Modifier.widthIn(min = 180.dp, max = 240.dp)
                    ) {
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    "Play (Replace Current)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            onClick = { 
                                onPlay()
                                showContextMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "Play",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    "Add to Upcoming",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            onClick = { 
                                onAddToUpcoming()
                                showContextMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Add to Upcoming",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    "Add to Playlist",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            onClick = { 
                                onAddToPlaylist()
                                showContextMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.PlaylistAdd,
                                    contentDescription = "Add to Playlist",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    "Edit BPM",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            onClick = { 
                                onEditBpm()
                                showContextMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Edit BPM",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    "Delete",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { 
                                onDelete()
                                showContextMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
