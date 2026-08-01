package com.ontimequant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ontimequant.ui.accuracy.AccuracyScreen
import com.ontimequant.ui.accuracy.AccuracyViewModel
import com.ontimequant.ui.appointment.AppointmentScreen
import com.ontimequant.ui.appointment.AppointmentViewModel
import com.ontimequant.ui.calendar.CalendarScreen
import com.ontimequant.ui.calendar.CalendarViewModel
import com.ontimequant.ui.curve.CurveScreen
import com.ontimequant.ui.details.DetailsScreen
import com.ontimequant.ui.history.HistoryScreen
import com.ontimequant.ui.history.HistoryViewModel
import com.ontimequant.ui.home.HomeScreen
import com.ontimequant.ui.home.HomeViewModel
import com.ontimequant.ui.insights.InsightsScreen
import com.ontimequant.ui.insights.InsightsViewModel
import com.ontimequant.ui.journeys.JourneysScreen
import com.ontimequant.ui.journeys.JourneysViewModel
import com.ontimequant.ui.navigation.Route
import com.ontimequant.ui.navigation.bottomDestinations
import com.ontimequant.ui.onboarding.OnboardingScreen
import com.ontimequant.ui.onboarding.OnboardingViewModel
import com.ontimequant.ui.settings.SettingsScreen
import com.ontimequant.ui.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnTimeQuantApp(
    startAppointmentId: String? = null,
    onboardingComplete: Boolean = false,
) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val homeViewModel: HomeViewModel = hiltViewModel()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(startAppointmentId, homeState.appointments) {
        if (startAppointmentId != null) {
            homeState.appointments.firstOrNull { it.id == startAppointmentId }
                ?.let(homeViewModel::selectAppointment)
        }
    }

    LaunchedEffect(homeState.lastMessage) {
        homeState.lastMessage?.let {
            snackbar.showSnackbar(it)
            homeViewModel.consumeMessage()
        }
    }

    val showBottomBar = currentRoute in bottomDestinations.map { it.route }
    val title = when (currentRoute) {
        Route.Home.path -> "OnTime Quant"
        Route.Calendar.path -> "Calendar"
        Route.Journeys.path -> "Saved journeys"
        Route.Insights.path -> "Model insights"
        Route.Accuracy.path -> "Model accuracy"
        Route.History.path -> "Trip history"
        Route.Settings.path -> "Settings"
        Route.Curve.path -> "Departure curve"
        Route.Details.path -> "Forecast details"
        Route.Appointment.path -> "Appointment"
        else -> "OnTime Quant"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("app_root"),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (currentRoute != Route.Onboarding.path) {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        if (!showBottomBar) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                            }
                        }
                    },
                    actions = {
                        if (currentRoute == Route.Home.path) {
                            IconButton(onClick = { navController.navigate(Route.Appointment.new()) }) {
                                Icon(Icons.Outlined.Add, "Add appointment")
                            }
                        }
                        if (showBottomBar) {
                            IconButton(onClick = { navController.navigate(Route.History.path) }) {
                                Icon(Icons.Outlined.History, "Trip history")
                            }
                            IconButton(onClick = { navController.navigate(Route.Settings.path) }) {
                                Icon(Icons.Outlined.Settings, "Settings")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    bottomDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
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
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = if (onboardingComplete) Route.Home.path else Route.Onboarding.path,
            ) {
                composable(Route.Onboarding.path) {
                    val vm: OnboardingViewModel = hiltViewModel()
                    OnboardingScreen(
                        viewModel = vm,
                        onFinished = {
                            navController.navigate(Route.Home.path) {
                                popUpTo(Route.Onboarding.path) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Route.Home.path) {
                    HomeScreen(
                        state = homeState,
                        onRecalculate = homeViewModel::recalculate,
                        onOpenCurve = {
                            homeState.selected?.let { navController.navigate(Route.Curve.of(it.id)) }
                        },
                        onOpenDetails = {
                            homeState.selected?.let { navController.navigate(Route.Details.of(it.id)) }
                        },
                        onSelectAppointment = homeViewModel::selectAppointment,
                        onNewAppointment = { navController.navigate(Route.Appointment.new()) },
                        onConfidenceChange = homeViewModel::setConfidenceTarget,
                        onNavigate = { homeViewModel.startJourney() },
                        onDeclareDeparted = homeViewModel::declareDeparted,
                        onConfirmArrival = homeViewModel::confirmArrival,
                        onCancelJourney = homeViewModel::cancelJourney,
                    )
                }

                composable(
                    Route.Curve.path,
                    arguments = listOf(navArgument(Route.ARG_APPOINTMENT_ID) { type = NavType.StringType }),
                ) {
                    CurveScreen(
                        forecast = homeState.forecast,
                        settings = homeState.settings,
                        now = homeState.now,
                    )
                }

                composable(
                    Route.Details.path,
                    arguments = listOf(navArgument(Route.ARG_APPOINTMENT_ID) { type = NavType.StringType }),
                ) {
                    DetailsScreen(
                        forecast = homeState.forecast,
                        settings = homeState.settings,
                    )
                }

                composable(
                    Route.Appointment.path,
                    arguments = listOf(
                        navArgument(Route.ARG_APPOINTMENT_ID) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry ->
                    val vm: AppointmentViewModel = hiltViewModel()
                    AppointmentScreen(
                        viewModel = vm,
                        appointmentId = entry.arguments?.getString(Route.ARG_APPOINTMENT_ID)
                            ?.takeIf { it.isNotBlank() },
                        onSaved = {
                            homeViewModel.recalculate()
                            navController.popBackStack()
                        },
                    )
                }

                composable(Route.Calendar.path) {
                    val vm: CalendarViewModel = hiltViewModel()
                    CalendarScreen(
                        viewModel = vm,
                        onOpenAppointment = { id -> navController.navigate(Route.Details.of(id)) },
                    )
                }

                composable(Route.Journeys.path) {
                    val vm: JourneysViewModel = hiltViewModel()
                    JourneysScreen(viewModel = vm)
                }

                composable(Route.Insights.path) {
                    val vm: InsightsViewModel = hiltViewModel()
                    InsightsScreen(viewModel = vm)
                }

                composable(Route.Accuracy.path) {
                    val vm: AccuracyViewModel = hiltViewModel()
                    AccuracyScreen(viewModel = vm)
                }

                composable(Route.History.path) {
                    val vm: HistoryViewModel = hiltViewModel()
                    HistoryScreen(viewModel = vm)
                }

                composable(Route.Settings.path) {
                    val vm: SettingsViewModel = hiltViewModel()
                    SettingsScreen(viewModel = vm)
                }
            }
        }
    }
}
