package com.ontimequant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.ontimequant.data.db.OnTimeQuantDatabase
import com.ontimequant.data.db.toDomain
import com.ontimequant.data.db.toEntity
import com.ontimequant.data.repository.JourneyRepository
import com.ontimequant.data.repository.TripRepository
import com.ontimequant.forecast.demo.DemoScenario
import com.ontimequant.model.Appointment
import com.ontimequant.model.LocationKind
import com.ontimequant.model.TripSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PersistenceTest {

    private lateinit var database: OnTimeQuantDatabase
    private lateinit var journeys: JourneyRepository

    private val zone = DemoScenario.ZONE
    private val day: LocalDate = LocalDate.of(2025, 11, 4)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OnTimeQuantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        journeys = JourneyRepository(
            database.locationDao(), database.journeyDao(), database.appointmentDao(),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `an appointment round-trips through the database with its zone intact`() = runTest {
        val appointment = DemoScenario.appointment(day, ZoneId.of("Europe/London"))
        journeys.saveAppointment(appointment)
        val loaded = journeys.appointment(appointment.id)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.zone.id).isEqualTo("Europe/London")
        assertThat(loaded.startTime).isEqualTo(appointment.startTime)
        assertThat(loaded.localStart().hour).isEqualTo(8)
        assertThat(loaded.requiredArrival).isEqualTo(appointment.requiredArrival)
    }

    @Test
    fun `home and work are singletons`() = runTest {
        val first = DemoScenario.HOME
        val second = DemoScenario.HOME.copy(id = "another-home", label = "New home")
        journeys.saveLocation(first)
        journeys.saveLocation(second)
        val home = journeys.locationOfKind(LocationKind.HOME)
        assertThat(home!!.id).isEqualTo("another-home")
        // The old one is kept but demoted rather than deleted.
        assertThat(journeys.location(first.id)!!.kind).isEqualTo(LocationKind.OTHER)
    }

    @Test
    fun `completed trips round-trip with every enum preserved`() = runTest {
        val trip = DemoScenario.completedTrips(day, zone, count = 1).first()
        database.tripDao().upsert(trip.toEntity())
        val loaded = database.tripDao().byId(trip.id)!!.toDomain()
        assertThat(loaded.timeBucket).isEqualTo(trip.timeBucket)
        assertThat(loaded.dayType).isEqualTo(trip.dayType)
        assertThat(loaded.dayOfWeek).isEqualTo(trip.dayOfWeek)
        assertThat(loaded.trafficRegime).isEqualTo(trip.trafficRegime)
        assertThat(loaded.source).isEqualTo(TripSource.DEMO)
        assertThat(loaded.actualTravelSeconds).isWithin(1e-6).of(trip.actualTravelSeconds)
    }

    @Test
    fun `an unknown enum value decodes to a safe default rather than throwing`() = runTest {
        val trip = DemoScenario.completedTrips(day, zone, count = 1).first()
        database.tripDao().upsert(trip.toEntity().copy(trafficRegime = "NOT_A_REGIME"))
        val loaded = database.tripDao().byId(trip.id)!!.toDomain()
        assertThat(loaded.trafficRegime.name).isEqualTo("UNKNOWN")
    }

    @Test
    fun `duplicate trip detection catches a second recording of the same journey`() = runTest {
        val trip = DemoScenario.completedTrips(day, zone, count = 1).first()
        database.tripDao().upsert(trip.toEntity())
        val duplicate = database.tripDao().findDuplicate(
            trip.originId, trip.destinationId, trip.actualDeparture.epochSecond + 240,
        )
        assertThat(duplicate).isNotNull()
        val distinct = database.tripDao().findDuplicate(
            trip.originId, trip.destinationId, trip.actualDeparture.epochSecond + 7200,
        )
        assertThat(distinct).isNull()
    }

    @Test
    fun `deleting everything leaves no rows behind`() = runTest {
        val appointment = DemoScenario.appointment(day, zone)
        journeys.saveAppointment(appointment)
        DemoScenario.completedTrips(day, zone, count = 5).forEach {
            database.tripDao().upsert(it.toEntity())
        }
        assertThat(database.tripDao().allChronological()).hasSize(5)

        database.clearAllTables()

        assertThat(database.tripDao().allChronological()).isEmpty()
        assertThat(database.appointmentDao().allActive()).isEmpty()
        assertThat(database.locationDao().all()).isEmpty()
    }

    @Test
    fun `upcoming appointments are exposed as a flow joined with their places`() = runTest {
        journeys.saveAppointment(DemoScenario.appointment(day, zone))
        val appointments = journeys.upcomingAppointments.first()
        assertThat(appointments).hasSize(1)
        assertThat(appointments.first().origin.label).isEqualTo("Home")
        assertThat(appointments.first().destination.label).isEqualTo("Office")
    }

    @Test
    fun `an appointment whose place was deleted is skipped rather than crashing`() = runTest {
        val appointment = DemoScenario.appointment(day, zone)
        journeys.saveAppointment(appointment)
        journeys.deleteLocation(appointment.destination.id)
        assertThat(journeys.upcomingAppointments.first()).isEmpty()
        assertThat(journeys.appointment(appointment.id)).isNull()
    }

    @Test
    fun `trip learning writes residual and duration observations`() = runTest {
        val trips = TripRepository(
            database.tripDao(), database.tripObservationDao(),
            database.learningDao(), database.calibrationDao(),
            com.ontimequant.data.prefs.SettingsRepository(
                ApplicationProvider.getApplicationContext(),
            ),
        )
        val trip = DemoScenario.completedTrips(day, zone, count = 1).first()
        trips.saveTrip(trip)

        assertThat(database.learningDao().allResiduals()).hasSize(1)
        assertThat(database.learningDao().allPreparation()).hasSize(1)
        assertThat(database.learningDao().allParking()).hasSize(1)
        assertThat(database.learningDao().allWalking()).hasSize(1)
        assertThat(database.learningDao().observeBias().first()).isNotEmpty()
    }

    @Test
    fun `a trip excluded from learning writes no observations`() = runTest {
        val trips = TripRepository(
            database.tripDao(), database.tripObservationDao(),
            database.learningDao(), database.calibrationDao(),
            com.ontimequant.data.prefs.SettingsRepository(
                ApplicationProvider.getApplicationContext(),
            ),
        )
        val trip = DemoScenario.completedTrips(day, zone, count = 1).first()
        trips.saveTrip(trip.copy(excludedFromLearning = true))
        assertThat(database.learningDao().allResiduals()).isEmpty()
        assertThat(database.tripDao().allChronological()).hasSize(1)
    }

    @Test
    fun `journey lifecycle records preparation delay from decision to movement`() = runTest {
        val trips = TripRepository(
            database.tripDao(), database.tripObservationDao(),
            database.learningDao(), database.calibrationDao(),
            com.ontimequant.data.prefs.SettingsRepository(
                ApplicationProvider.getApplicationContext(),
            ),
        )
        val appointment: Appointment = DemoScenario.appointment(day, zone)
        journeys.saveAppointment(appointment)

        val decision = Instant.parse("2025-11-04T03:00:00Z")
        trips.beginJourney(
            appointment = appointment,
            predictedTravelSeconds = 900.0,
            weatherSeverity = 0.1,
            eventPressure = 0.0,
            trafficRegime = com.ontimequant.model.TrafficRegime.HEAVY,
            at = decision,
        )
        trips.recordMovement(decision.plusSeconds(420))
        val trip = trips.completeJourney(
            appointment = appointment,
            arrival = decision.plusSeconds(420 + 1100),
            parkingSeconds = 200.0,
            walkingSeconds = 150.0,
        )

        assertThat(trip).isNotNull()
        assertThat(trip!!.preparationSeconds).isWithin(1.0).of(420.0)
        assertThat(trip.actualTravelSeconds).isWithin(1.0).of(1100.0 - 200.0 - 150.0)
        assertThat(trips.activeObservationOnce()).isNull()
    }
}
