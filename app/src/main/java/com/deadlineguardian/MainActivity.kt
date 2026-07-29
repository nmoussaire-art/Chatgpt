package com.deadlineguardian

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.deadlineguardian.ui.DeadlineGuardianTheme
import com.deadlineguardian.ui.GuardianViewModel
import com.deadlineguardian.ui.screens.DetailScreen
import com.deadlineguardian.ui.screens.HomeScreen
import com.deadlineguardian.ui.screens.ScanScreen
import com.deadlineguardian.ui.screens.SettingsScreen

class MainActivity : ComponentActivity() {

    private val viewModel: GuardianViewModel by viewModels()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val openDeadlineId = intent?.getLongExtra(EXTRA_DEADLINE_ID, -1L)?.takeIf { it > 0 }

        setContent {
            DeadlineGuardianTheme {
                val navController = rememberNavController()

                // Launched from a notification: jump straight to the thing that's expiring.
                LaunchedEffect(openDeadlineId) {
                    if (openDeadlineId != null) navController.navigate("detail/$openDeadlineId")
                }

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            viewModel = viewModel,
                            onScan = { navController.navigate("scan") },
                            onOpen = { navController.navigate("detail/$it") },
                            onSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("scan") {
                        ScanScreen(
                            viewModel = viewModel,
                            onDone = { navController.popBackStack() }
                        )
                    }
                    composable(
                        "detail/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType })
                    ) { entry ->
                        DetailScreen(
                            viewModel = viewModel,
                            deadlineId = entry.arguments?.getLong("id") ?: 0L,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_DEADLINE_ID = "deadline_id"
    }
}
