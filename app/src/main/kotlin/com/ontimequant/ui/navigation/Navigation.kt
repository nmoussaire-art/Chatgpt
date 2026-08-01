package com.ontimequant.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Route
import androidx.compose.ui.graphics.vector.ImageVector

/** Every destination in the app, in one enumerable place. */
sealed class Route(val path: String) {
    data object Onboarding : Route("onboarding")
    data object Home : Route("home")
    data object Calendar : Route("calendar")
    data object Journeys : Route("journeys")
    data object Insights : Route("insights")
    data object Settings : Route("settings")
    data object History : Route("history")
    data object Accuracy : Route("accuracy")

    data object Curve : Route("curve/{appointmentId}") {
        fun of(appointmentId: String) = "curve/$appointmentId"
    }

    data object Details : Route("details/{appointmentId}") {
        fun of(appointmentId: String) = "details/$appointmentId"
    }

    data object Appointment : Route("appointment?appointmentId={appointmentId}") {
        fun new() = "appointment?appointmentId="
        fun edit(appointmentId: String) = "appointment?appointmentId=$appointmentId"
    }

    companion object {
        const val ARG_APPOINTMENT_ID = "appointmentId"
    }
}

data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val bottomDestinations = listOf(
    BottomDestination(Route.Home.path, "Departure", Icons.Outlined.Home),
    BottomDestination(Route.Calendar.path, "Calendar", Icons.Outlined.CalendarMonth),
    BottomDestination(Route.Journeys.path, "Journeys", Icons.Outlined.Route),
    BottomDestination(Route.Insights.path, "Insights", Icons.Outlined.Insights),
    BottomDestination(Route.Accuracy.path, "Accuracy", Icons.Outlined.BarChart),
)
