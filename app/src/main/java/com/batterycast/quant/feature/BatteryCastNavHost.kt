package com.batterycast.quant.feature

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.batterycast.quant.feature.accuracy.AccuracyScreen
import com.batterycast.quant.feature.forecast.ForecastScreen
import com.batterycast.quant.feature.history.HistoryScreen
import com.batterycast.quant.feature.home.HomeScreen
import com.batterycast.quant.feature.planner.ChargePlannerScreen
import com.batterycast.quant.feature.scenarios.ScenarioScreen
import com.batterycast.quant.feature.settings.SettingsScreen
import com.batterycast.quant.feature.whatchanged.WhatChangedScreen

object Routes {
    const val HOME = "home"
    const val FORECAST = "forecast"
    const val SCENARIOS = "scenarios"
    const val CHARGE = "charge"

    const val SETTINGS = "settings"
    const val WHAT_CHANGED = "what_changed"
    const val HISTORY = "history"
    const val ACCURACY = "accuracy"
}

/**
 * Four destinations, named for what the user wants rather than what the model produces.
 *
 * v1 had five, two of which ("Curve", "Chances") were named after visualisations and statistics.
 * "Chances" also wrapped onto two lines at the device's display size, which broke the alignment of
 * the entire bar on every screen. Every label here is one short word, single-line by construction.
 */
private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun BatteryCastNavHost(navController: NavHostController = rememberNavController()) {
    val destinations = listOf(
        Destination(Routes.HOME, "Home", Icons.Rounded.Home),
        Destination(Routes.FORECAST, "Forecast", Icons.Rounded.Timeline),
        Destination(Routes.SCENARIOS, "Scenarios", Icons.Rounded.Science),
        Destination(Routes.CHARGE, "Charge", Icons.Rounded.Bolt),
    )

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = destinations.any { destination ->
        currentDestination?.hierarchy?.any { it.route == destination.route } == true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Content is laid out edge to edge and scrolls *under* the bar; each screen receives the
        // inset as content padding rather than being clipped above it, which is what made v1 look
        // like a rectangular page wedged between the system bars.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                Box {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        thickness = 0.5.dp,
                    )
                    NavigationBar(
                        containerColor = Color.Transparent,
                        modifier = Modifier.background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.86f),
                                    MaterialTheme.colorScheme.background,
                                ),
                            ),
                        ),
                        windowInsets = WindowInsets.navigationBars,
                    ) {
                        destinations.forEach { destination ->
                            val selected =
                                currentDestination?.hierarchy?.any { it.route == destination.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        destination.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                    )
                                },
                                label = {
                                    Text(
                                        destination.label,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Visible,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(140)) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    contentPadding = innerPadding,
                    onOpenForecast = { navController.navigate(Routes.FORECAST) },
                    onOpenScenarios = { navController.navigate(Routes.SCENARIOS) },
                    onOpenCharge = { navController.navigate(Routes.CHARGE) },
                    onOpenWhatChanged = { navController.navigate(Routes.WHAT_CHANGED) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.FORECAST) { ForecastScreen(contentPadding = innerPadding) }
            composable(Routes.SCENARIOS) { ScenarioScreen(contentPadding = innerPadding) }
            composable(Routes.CHARGE) { ChargePlannerScreen(contentPadding = innerPadding) }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigate = { route -> navController.navigate(route) },
                )
            }
            composable(Routes.WHAT_CHANGED) {
                WhatChangedScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ACCURACY) {
                AccuracyScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
