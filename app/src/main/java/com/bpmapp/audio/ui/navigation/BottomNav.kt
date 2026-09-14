package com.bpmapp.audio.ui.navigation

import com.bpmapp.audio.R

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Navigation destinations for the bottom navigation bar
 */
enum class NavDestination(
    val route: String,
    val icon: ImageVector,
    @StringRes val labelRes: Int
) {
    PLAYER(
        route = "player",
        icon = Icons.Filled.MusicNote,
        labelRes = R.string.nav_player
    ),
    LIBRARY(
        route = "library",
        icon = Icons.Filled.Book, // Fallback: Book (CD icon not available in standard Material Icons)
        labelRes = R.string.nav_library
    ),
    BPM_TOOLS(
        route = "bpm_tools",
        icon = Icons.Filled.Tune,
        labelRes = R.string.nav_bpm_tools
    ),
    SETTINGS(
        route = "settings",
        icon = Icons.Filled.Settings,
        labelRes = R.string.nav_settings
    )
}

/**
 * List of all navigation destinations for the bottom navigation bar
 */
val bottomNavDestinations = listOf(
    NavDestination.PLAYER,
    NavDestination.LIBRARY,
    NavDestination.BPM_TOOLS,
    NavDestination.SETTINGS
)

/**
 * Default navigation destination
 */
val defaultNavDestination = NavDestination.PLAYER

/**
 * Bottom Navigation Bar component
 */
@Composable
fun BottomNavigationBar(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val navBackStackEntry = navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry.value?.destination
    
    NavigationBar(
        modifier = modifier
    ) {
        bottomNavDestinations.forEach { destination ->
            val isSelected = currentDestination?.route == destination.route
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    navController.navigate(destination.route) {
                        // Pop up to the start destination of the graph to
                        // avoid building up a large stack of destinations
                        // on the back stack as users select items
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        // Avoid multiple copies of the same destination when
                        // reselecting the same item
                        launchSingleTop = true
                        // Restore state when reselecting a previously selected item
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = stringResource(destination.labelRes)
                    )
                },
                label = { Text(stringResource(destination.labelRes)) },
                alwaysShowLabel = true
            )
        }
    }
}