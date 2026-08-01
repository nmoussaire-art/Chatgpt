package com.ontimequant.data.repository

import com.ontimequant.data.db.AppointmentDao
import com.ontimequant.data.db.JourneyDao
import com.ontimequant.data.db.LocationDao
import com.ontimequant.data.db.toDomain
import com.ontimequant.data.db.toEntity
import com.ontimequant.model.Appointment
import com.ontimequant.model.LocationKind
import com.ontimequant.model.SavedJourney
import com.ontimequant.model.SavedLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Places, saved journeys and appointments. The only writer of those three tables. */
@Singleton
class JourneyRepository @Inject constructor(
    private val locations: LocationDao,
    private val journeys: JourneyDao,
    private val appointments: AppointmentDao,
) {

    val savedLocations: Flow<List<SavedLocation>> =
        locations.observeAll().map { list -> list.map { it.toDomain() } }

    val savedJourneys: Flow<List<SavedJourney>> =
        journeys.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * Appointments joined with their places. Emitted as a flow so the home screen updates
     * the moment an appointment or a place changes, without any manual refresh.
     */
    val upcomingAppointments: Flow<List<Appointment>> =
        combine(appointments.observeUpcoming(), locations.observeAll()) { rows, places ->
            val byId = places.associateBy { it.id }
            rows.mapNotNull { row ->
                val origin = byId[row.originId]?.toDomain() ?: return@mapNotNull null
                val destination = byId[row.destinationId]?.toDomain() ?: return@mapNotNull null
                row.toDomain(origin, destination)
            }
        }

    suspend fun location(id: String): SavedLocation? = locations.byId(id)?.toDomain()

    suspend fun locationOfKind(kind: LocationKind): SavedLocation? =
        locations.byKind(kind.name)?.toDomain()

    suspend fun saveLocation(location: SavedLocation) {
        // Home and Work are singletons: saving a new one replaces the old.
        if (location.kind == LocationKind.HOME || location.kind == LocationKind.WORK) {
            locations.byKind(location.kind.name)?.let { existing ->
                if (existing.id != location.id) {
                    locations.upsert(existing.copy(kind = LocationKind.OTHER.name))
                }
            }
        }
        locations.upsert(location.toEntity())
    }

    suspend fun deleteLocation(id: String) = locations.delete(id)

    suspend fun saveJourney(journey: SavedJourney) = journeys.upsert(journey.toEntity())

    suspend fun journey(id: String): SavedJourney? = journeys.byId(id)?.toDomain()

    suspend fun deleteJourney(id: String) = journeys.delete(id)

    suspend fun saveAppointment(appointment: Appointment) {
        saveLocation(appointment.origin)
        saveLocation(appointment.destination)
        appointments.upsert(appointment.toEntity())
    }

    suspend fun appointment(id: String): Appointment? {
        val row = appointments.byId(id) ?: return null
        val origin = locations.byId(row.originId)?.toDomain() ?: return null
        val destination = locations.byId(row.destinationId)?.toDomain() ?: return null
        return row.toDomain(origin, destination)
    }

    suspend fun appointmentForCalendarEvent(eventId: Long): Appointment? {
        val row = appointments.byCalendarEvent(eventId) ?: return null
        return appointment(row.id)
    }

    /** The appointment the home screen should lead with: the soonest that has not started. */
    suspend fun nextAppointment(now: Instant = Instant.now()): Appointment? {
        val row = appointments.nextAfter(now.epochSecond) ?: return null
        return appointment(row.id)
    }

    suspend fun allActiveAppointments(): List<Appointment> =
        appointments.allActive().mapNotNull { appointment(it.id) }

    suspend fun dismissAppointment(id: String) {
        appointments.byId(id)?.let { appointments.update(it.copy(dismissed = true)) }
    }

    suspend fun setReminders(id: String, enabled: Boolean) {
        appointments.byId(id)?.let { appointments.update(it.copy(remindersEnabled = enabled)) }
    }

    suspend fun deleteAppointment(id: String) = appointments.delete(id)
}
