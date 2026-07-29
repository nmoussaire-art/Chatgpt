package com.loopguard.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.loopguard.app.ui.screens.DetailScreen
import com.loopguard.app.ui.screens.EditorScreen
import com.loopguard.app.ui.screens.FollowUpScreen
import com.loopguard.app.ui.screens.InsightsScreen
import com.loopguard.app.ui.screens.LoopsScreen
import com.loopguard.app.ui.screens.OnboardingScreen
import com.loopguard.app.ui.screens.SettingsScreen
import com.loopguard.app.ui.screens.TodayScreen
import com.loopguard.app.ui.theme.LoopGuardTheme

class MainActivity : ComponentActivity() {

    private var pendingSharedText: String? = null
    private var pendingLoopId: Long? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)

        setContent {
            val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)
            val state by viewModel.state.collectAsStateWithLifecycle()

            LoopGuardTheme(
                themeMode = state.settings.themeMode,
                dynamicColour = state.settings.dynamicColour,
            ) {
                if (state.ready) {
                    if (state.settings.onboardingComplete) {
                        LoopGuardNavigation(
                            viewModel = viewModel,
                            sharedText = pendingSharedText,
                            openLoopId = pendingLoopId,
                            onSharedTextConsumed = { pendingSharedText = null },
                            onOpenLoopConsumed = { pendingLoopId = null },
                        )
                    } else {
                        OnboardingScreen(
                            onFinish = { name, seed ->
                                viewModel.completeOnboarding(name, seed)
                                askForNotifications()
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            pendingSharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        intent.getLongExtra(EXTRA_OPEN_LOOP_ID, -1L).takeIf { it > 0 }?.let { pendingLoopId = it }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_LOOP_ID = "open_loop_id"
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "Today", Icons.Default.Today),
    LOOPS("loops", "Loops", Icons.AutoMirrored.Filled.ListAlt),
    INSIGHTS("insights", "Insights", Icons.Default.Insights),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
}

private object Routes {
    const val DETAIL = "detail/{loopId}"
    const val EDIT = "edit/{loopId}"
    const val NEW = "new"
    const val FOLLOW_UP = "followup/{loopId}"

    fun detail(id: Long) = "detail/$id"
    fun edit(id: Long) = "edit/$id"
    fun followUp(id: Long) = "followup/$id"
}

@Composable
private fun LoopGuardNavigation(
    viewModel: MainViewModel,
    sharedText: String?,
    openLoopId: Long?,
    onSharedTextConsumed: () -> Unit,
    onOpenLoopConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val onTab = Tab.entries.any { it.route == route }

    // A loop that read "due tomorrow" last night must read "due today" when
    // the app is reopened, so the date is re-read on every resume.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshToday()
        onPauseOrDispose { }
    }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            navController.navigate(Routes.NEW)
            onSharedTextConsumed()
        }
    }

    LaunchedEffect(openLoopId) {
        if (openLoopId != null) {
            navController.navigate(Routes.detail(openLoopId))
            onOpenLoopConsumed()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messageFlow.collect { message ->
            when (message) {
                is UiMessage.Text -> snackbarHost.showSnackbar(message.message)
                is UiMessage.Undoable -> {
                    val result = snackbarHost.showSnackbar(
                        message = message.message,
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undo(message.action, message.loopId)
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(Tab.TODAY.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (route == Tab.TODAY.route || route == Tab.LOOPS.route) {
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate(Routes.NEW) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Capture") },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.TODAY.route,
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(180)) },
        ) {
            composable(Tab.TODAY.route) {
                TodayScreen(
                    state = state,
                    contentPadding = padding,
                    onOpenLoop = { navController.navigate(Routes.detail(it)) },
                    onFollowUp = { navController.navigate(Routes.followUp(it)) },
                    onComplete = { viewModel.complete(it) },
                    onSnooze = { viewModel.snooze(it, 1) },
                    onUnsnooze = { viewModel.unsnooze(it) },
                    onSeeAll = { navController.navigate(Tab.LOOPS.route) },
                    onAdd = { navController.navigate(Routes.NEW) },
                )
            }

            composable(Tab.LOOPS.route) {
                LoopsScreen(
                    state = state,
                    contentPadding = padding,
                    onQueryChange = viewModel::setQuery,
                    onSideChange = viewModel::setSideFilter,
                    onCategoryChange = viewModel::setCategoryFilter,
                    onTagChange = viewModel::setTagFilter,
                    onShowCompleted = viewModel::setShowCompleted,
                    onClearFilters = viewModel::clearFilters,
                    onOpenLoop = { navController.navigate(Routes.detail(it)) },
                    onAdd = { navController.navigate(Routes.NEW) },
                )
            }

            composable(Tab.INSIGHTS.route) {
                InsightsScreen(state = state, contentPadding = padding)
            }

            composable(Tab.SETTINGS.route) {
                SettingsScreen(
                    state = state,
                    contentPadding = padding,
                    onName = viewModel::setDisplayName,
                    onTheme = viewModel::setThemeMode,
                    onDynamicColour = viewModel::setDynamicColour,
                    onReminders = viewModel::setRemindersEnabled,
                    onDigestHour = viewModel::setDigestHour,
                    onCadence = viewModel::setFollowUpCadence,
                    buildExport = { viewModel.buildExport() },
                    onImport = { text, mode -> viewModel.importFrom(text, mode) },
                    onNotify = viewModel::notify,
                )
            }

            composable(Routes.NEW) {
                EditorScreen(
                    existing = null,
                    today = state.today,
                    prefillText = sharedText,
                    onBack = { navController.popBackStack() },
                    onSave = { loop ->
                        viewModel.create(loop)
                        navController.popBackStack()
                    },
                )
            }

            composable(
                Routes.EDIT,
                arguments = listOf(navArgument("loopId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("loopId") ?: return@composable
                val loop by viewModel.observeLoop(id).collectAsState(initial = null)
                loop?.let { current ->
                    EditorScreen(
                        existing = current,
                        today = state.today,
                        onBack = { navController.popBackStack() },
                        onSave = { updated ->
                            viewModel.update(updated)
                            navController.popBackStack()
                        },
                    )
                }
            }

            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("loopId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("loopId") ?: return@composable
                val loop by viewModel.observeLoop(id).collectAsState(initial = null)
                val events by viewModel.observeEvents(id).collectAsState(initial = emptyList())

                loop?.let { current ->
                    DetailScreen(
                        loop = current,
                        events = events,
                        priority = viewModel.priorityFor(current),
                        today = state.today,
                        onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate(Routes.edit(id)) },
                        onFollowUp = { navController.navigate(Routes.followUp(id)) },
                        onComplete = {
                            viewModel.complete(id)
                            navController.popBackStack()
                        },
                        onReopen = { viewModel.reopen(id) },
                        onDelete = {
                            viewModel.delete(id)
                            navController.popBackStack()
                        },
                        onSnooze = { days -> viewModel.snooze(id, days) },
                        onSwapSide = { side -> viewModel.setSide(id, side) },
                        onTogglePin = { viewModel.togglePin(id) },
                        onLogAction = { text, handOver -> viewModel.logAction(id, text, handOver) },
                    )
                }
            }

            composable(
                Routes.FOLLOW_UP,
                arguments = listOf(navArgument("loopId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("loopId") ?: return@composable
                val loop by viewModel.observeLoop(id).collectAsState(initial = null)

                loop?.let { current ->
                    FollowUpScreen(
                        loop = current,
                        priority = viewModel.priorityFor(current),
                        senderName = state.settings.displayName,
                        today = state.today,
                        onBack = { navController.popBackStack() },
                        onSent = { tone -> viewModel.recordFollowUp(id, tone) },
                        onNotify = viewModel::notify,
                    )
                }
            }
        }
    }
}
