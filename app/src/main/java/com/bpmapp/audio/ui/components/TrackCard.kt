package com.bpmapp.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import com.bpmapp.audio.R
import com.bpmapp.audio.data.Track
import com.bpmapp.audio.ui.theme.AppSpacing
import com.bpmapp.audio.ui.theme.bpmColor
import com.bpmapp.audio.ui.theme.speedFactorColor
import androidx.compose.ui.res.stringResource
import java.text.DecimalFormat

/**
 * Compact TrackCard component for LibraryScreen
 * Height: 26dp (compact layout)
 * Features: Overflow menu with Play/Replace, Edit BPM, Delete actions
 *           Explicit "Add to Playlist" button
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    onToggleFavorite: (() -> Unit)? = null,
    onLongClick: () -> Unit = {}
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

    // Accessibility strings resolved in composable scope
    val unknownArtist = stringResource(R.string.trackcard_unknown_artist)
    val unknownBpmText = stringResource(R.string.trackcard_unknown_bpm)
    val bpmText = if (track.bpm != null && track.bpm > 0)
        stringResource(R.string.trackcard_bpm_value, DecimalFormat("#.##").format(track.bpm))
    else unknownBpmText
    val trackContentDesc = stringResource(
        R.string.trackcard_desc_template,
        track.metadataTitle ?: track.fileName,
        track.metadataArtist ?: unknownArtist,
        bpmText
    )

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
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick() },
                onLongClick = { onLongClick() }
            )
            .semantics {
                contentDescription = trackContentDesc
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
                val displayTitle = track.metadataTitle ?: track.fileName.takeIf { it.isNotEmpty() } ?: stringResource(R.string.trackcard_unknown)
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
                                speedFactor == null -> stringResource(R.string.trackcard_speed_unknown)
                                speedFactor == 1.0f -> stringResource(R.string.trackcard_speed_perfect)
                                speedFactor < 0.9f || speedFactor > 1.1f -> stringResource(R.string.trackcard_speed_needs_adjustment)
                                else -> stringResource(R.string.trackcard_speed_good)
                            },
                            modifier = Modifier.size(12.dp),
                            tint = speedFactorIconColor
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = stringResource(R.string.trackcard_bpm_value, DecimalFormat("#.##").format(track.bpm)),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = bpmColorValue,
                            maxLines = 1
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.trackcard_unknown_bpm_label),
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
                            contentDescription = if (isFavorite) stringResource(R.string.trackcard_remove_favorite) else stringResource(R.string.trackcard_add_favorite),
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
                        contentDescription = stringResource(R.string.trackcard_add_to_playlist),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(1.dp))
                    Text(
                        text = stringResource(R.string.trackcard_add),
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
                            contentDescription = stringResource(R.string.trackcard_actions),
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
                                    stringResource(R.string.trackcard_play_replace),
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
                                    contentDescription = stringResource(R.string.trackcard_play),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    stringResource(R.string.trackcard_add_to_upcoming),
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
                                    contentDescription = stringResource(R.string.trackcard_add_to_upcoming),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    stringResource(R.string.trackcard_add_to_playlist),
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
                                    contentDescription = stringResource(R.string.trackcard_add_to_playlist),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    stringResource(R.string.trackcard_edit_bpm),
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
                                    contentDescription = stringResource(R.string.trackcard_edit_bpm),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    stringResource(R.string.trackcard_delete),
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
                                    contentDescription = stringResource(R.string.trackcard_delete),
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
