package com.ontimequant.data.repository

import com.ontimequant.data.db.OnTimeQuantDatabase
import com.ontimequant.forecast.demo.DemoScenario
import com.ontimequant.model.Appointment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the demo journey into the real database.
 *
 * Important: the demo is *seeded data*, not a parallel code path. Once seeded, the demo
 * appointment goes through the same repository, the same engine and the same screens as a
 * live one — the only difference is that the routing, weather and event providers are the
 * offline ones and everything is labelled `DEMO`. That is what makes the demo a genuine
 * exercise of the product rather than a mock-up.
 */
@Singleton
class DemoSeeder @Inject constructor(
    private val database: OnTimeQuantDatabase,
    private val journeys: JourneyRepository,
    private val trips: TripRepository,
) {

    suspend fun isSeeded(): Boolean =
        database.appointmentDao().byId(DEMO_APPOINTMENT_ID) != null

    /** Seeds if empty. Safe to call on every launch. */
    suspend fun seedIfNeeded(): Boolean {
        if (isSeeded()) return false
        seed()
        return true
    }

    suspend fun reseed() {
        clear()
        seed()
    }

    private suspend fun seed() = withContext(Dispatchers.IO) {
        val zone = DemoScenario.ZONE
        val today = LocalDate.now(zone)
        // The demo meeting is always the next 08:00 that has not happened yet, so the
        // countdown on the home screen is live rather than stale.
        val day = if (LocalTime.now(zone).isBefore(LocalTime.of(8, 0))) today else today.plusDays(1)

        journeys.saveLocation(DemoScenario.HOME)
        journeys.saveLocation(DemoScenario.OFFICE)
        journeys.saveLocation(DemoScenario.AIRPORT)
        journeys.saveJourney(DemoScenario.JOURNEY)
        journeys.saveJourney(DemoScenario.AIRPORT_JOURNEY)

        journeys.saveAppointment(demoAppointment(day, zone))
        journeys.saveAppointment(DemoScenario.secondaryAppointment(day, zone))

        // 24 completed commutes ending yesterday: enough to reach "Personalized", to fill
        // the accuracy screen past its minimum sample, and to produce real insights.
        DemoScenario.completedTrips(day, zone, count = 24).forEach { trip ->
            database.tripDao().upsert(com.ontimequant.data.db.CompletedTripEntity(
                id = trip.id,
                journeyId = trip.journeyId,
                originId = trip.originId,
                destinationId = trip.destinationId,
                appointmentTitle = trip.appointmentTitle,
                zoneId = trip.zone.id,
                requiredArrivalEpoch = trip.requiredArrival.epochSecond,
                recommendedDepartureEpoch = trip.recommendedDeparture?.epochSecond,
                actualDepartureEpoch = trip.actualDeparture.epochSecond,
                actualArrivalEpoch = trip.actualArrival.epochSecond,
                predictedTravelSeconds = trip.predictedTravelSeconds,
                actualTravelSeconds = trip.actualTravelSeconds,
                predictedArrivalEpoch = trip.predictedArrival?.epochSecond,
                onTimeProbabilityAtDeparture = trip.onTimeProbabilityAtDeparture,
                preparationSeconds = trip.preparationSeconds,
                parkingSeconds = trip.parkingSeconds,
                walkingSeconds = trip.walkingSeconds,
                weatherSeverity = trip.weatherSeverity,
                weatherCategory = trip.weatherCategory.name,
                eventPressure = trip.eventPressure,
                trafficRegime = trip.trafficRegime.name,
                timeBucket = trip.timeBucket.name,
                dayType = trip.dayType.name,
                dayOfWeek = trip.dayOfWeek.name,
                excludedFromLearning = false,
                unusualCircumstances = null,
                modelVersion = trip.modelVersion,
                source = trip.source.name,
            ))
        }
        trips.refreshDerivedState()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        database.clearAllTables()
    }

    fun demoAppointment(day: LocalDate, zone: ZoneId): Appointment =
        DemoScenario.appointment(day, zone).copy(id = DEMO_APPOINTMENT_ID)

    /** The next demo deadline, used by the home screen countdown when nothing is stored. */
    fun nextDemoDeadline(now: Instant = Instant.now()): Instant {
        val zone = DemoScenario.ZONE
        val today = LocalDate.now(zone)
        val day = if (now.atZone(zone).toLocalTime().isBefore(LocalTime.of(8, 0))) today else today.plusDays(1)
        return day.atTime(8, 0).atZone(zone).toInstant()
    }

    companion object {
        const val DEMO_APPOINTMENT_ID = "demo-appointment"
    }
}
