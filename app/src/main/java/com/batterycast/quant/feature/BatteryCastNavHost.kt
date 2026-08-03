package com.batterycast.quant.feature

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.batterycast.quant.feature.accuracy.AccuracyScreen
import com.batterycast.quant.feature.dashboard.DashboardScreen
import com.batterycast.quant.feature.history.HistoryScreen
import com.batterycast.quant.feature.more.MoreScreen
import com.batterycast.quant.feature.planner.ChargePlannerScreen
import com.batterycast.quant.feature.probability.ProbabilityScreen
import com.batterycast.quant.feature.scenarios.ScenarioScreen
import com.batterycast.quant.feature.settings.SettingsScreen
import com.batterycast.quant.feature.survival.SurvivalCurveScreen
import com.batterycast.quant.feature.whatchanged.WhatChangedScreen

/** Every destination in the app. */
object Routes {
    const val DASHBOARD = "dashboard"
    const val SURVIVAL = "survival"
    const val PROBABILITY = "probability"
    const val PLANNER = "planner"
    const val MORE = "more"
    const val SCENARIOS = "scenarios"
    const val WHAT_CHANGED = "what_changed"
    const val HISTORY = "history"
    const val ACCURACY = "accuracy"
    const val SETTINGS = "settings"
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun BatteryCastNavHost(navController: NavHostController = rememberNavController()) {
    val destinations = listOf(
        BottomDestination(Routes.DASHBOARD, "Home", Icons.Outlined.Home),
        BottomDestination(Routes.SURVIVAL, "Curve", Icons.AutoMirrored.Outlined.ShowChart),
        BottomDestination(Routes.PROBABILITY, "Chances", Icons.Outlined.Percent),
        BottomDestination(Routes.PLANNER, "Charge", Icons.Outlined.Bolt),
        BottomDestination(Routes.MORE, "More", Icons.Outlined.MoreHoriz),
    )

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                destinations.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenSurvival = { navController.navigate(Routes.SURVIVAL) },
                    onOpenWhatChanged = { navController.navigate(Routes.WHAT_CHANGED) },
                    onOpenPlanner = { navController.navigate(Routes.PLANNER) },
                )
            }
            composable(Routes.SURVIVAL) { SurvivalCurveScreen() }
            composable(Routes.PROBABILITY) { ProbabilityScreen() }
            composable(Routes.PLANNER) { ChargePlannerScreen() }
            composable(Routes.MORE) {
                MoreScreen(
                    onNavigate = { route -> navController.navigate(route) },
                )
            }
            composable(Routes.SCENARIOS) { ScenarioScreen() }
            composable(Routes.WHAT_CHANGED) { WhatChangedScreen() }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.ACCURACY) { AccuracyScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}
