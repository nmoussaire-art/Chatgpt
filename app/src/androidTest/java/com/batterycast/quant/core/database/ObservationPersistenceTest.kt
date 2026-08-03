package com.batterycast.quant.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.batterycast.quant.core.database.entity.toDomain
import com.batterycast.quant.core.database.entity.toEntity
import com.batterycast.quant.telemetry.model.BatteryHealth
import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.BluetoothState
import com.batterycast.quant.telemetry.model.ChargingStatus
import com.batterycast.quant.telemetry.model.NetworkType
import com.batterycast.quant.telemetry.model.ObservationQuality
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.QualityNote
import com.batterycast.quant.telemetry.model.ThermalStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room round trips, with particular attention to nulls.
 *
 * A device that cannot report current must read back as *unmeasured*, not as zero current, or
 * every drain estimate downstream would be wrong in a way that looks perfectly reasonable.
 */
@RunWith(AndroidJUnit4::class)
class ObservationPersistenceTest {

    private lateinit var database: BatteryCastDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BatteryCastDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() = database.close()

    private fun observation(
        timestampMs: Long,
        percent: Double = 70.0,
        chargeCounterMicroAh: Long? = null,
        currentMicroA: Long? = null,
        notes: Set<QualityNote> = emptySet(),
    ) = BatteryObservation(
        timestampMs = timestampMs,
        elapsedRealtimeMs = timestampMs - 1_600_000_000_000L,
        bootSessionId = 42L,
        batteryPercent = percent,
        rawLevel = percent.toInt(),
        rawScale = 100,
        chargeCounterMicroAh = chargeCounterMicroAh,
        currentMicroA = currentMicroA,
        averageCurrentMicroA = null,
        energyNanoWh = null,
        voltageMv = 3900,
        temperatureDeciC = 305,
        isCharging = false,
        chargingStatus = ChargingStatus.DISCHARGING,
        plugType = PlugType.NONE,
        batteryHealth = BatteryHealth.GOOD,
        chargingPolicy = null,
        powerSaveEnabled = false,
        thermalStatus = ThermalStatus.NONE,
        screenInteractive = true,
        networkType = NetworkType.WIFI,
        bluetoothState = BluetoothState.UNKNOWN,
        recentScreenTimeMs = null,
        recentForegroundUsageMs = null,
        dominantUsageCategory = null,
        quality = ObservationQuality.ACCEPTABLE,
        notes = notes,
        source = ObservationSource.PERIODIC_WORK,
    )

    @Test
    fun theDatabaseShipsEmpty() = runTest {
        assertThat(database.observationDao().count()).isEqualTo(0)
        assertThat(database.modelCellDao().all()).isEmpty()
        assertThat(database.capabilityEvidenceDao().get()).isNull()
    }

    @Test
    fun anUnmeasuredFieldReadsBackAsNullNotZero() = runTest {
        val dao = database.observationDao()
        dao.insert(observation(1_700_000_000_000L).toEntity())

        val restored = dao.latest()!!.toDomain()

        assertThat(restored.chargeCounterMicroAh).isNull()
        assertThat(restored.currentMicroA).isNull()
        assertThat(restored.energyNanoWh).isNull()
        assertThat(restored.recentScreenTimeMs).isNull()
        assertThat(restored.dominantUsageCategory).isNull()
    }

    @Test
    fun everyFieldSurvivesTheRoundTrip() = runTest {
        val dao = database.observationDao()
        val original = observation(
            timestampMs = 1_700_000_000_000L,
            chargeCounterMicroAh = 3_150_000,
            currentMicroA = -415_000,
            notes = setOf(QualityNote.CLOCK_JUMP_DETECTED, QualityNote.ENERGY_UNSUPPORTED),
        )

        val id = dao.insert(original.toEntity())
        val restored = dao.latest()!!.toDomain()

        assertThat(restored).isEqualTo(original.copy(id = id))
        assertThat(restored.notes).containsExactly(
            QualityNote.CLOCK_JUMP_DETECTED,
            QualityNote.ENERGY_UNSUPPORTED,
        )
    }

    @Test
    fun observationsAreReturnedInChronologicalOrder() = runTest {
        val dao = database.observationDao()
        listOf(3L, 1L, 2L).forEach { offset ->
            dao.insert(observation(1_700_000_000_000L + offset * 60_000).toEntity())
        }

        val since = dao.since(0L)

        assertThat(since.map { it.timestampMs }).isInOrder()
        assertThat(dao.latest()!!.timestampMs).isEqualTo(1_700_000_000_000L + 3 * 60_000)
    }

    @Test
    fun theNearestQueryRefusesToReachBeyondItsTolerance() = runTest {
        val dao = database.observationDao()
        val target = 1_700_000_000_000L
        dao.insert(observation(target).toEntity())

        assertThat(dao.nearest(target + 5 * 60_000, toleranceMs = 10 * 60_000)).isNotNull()
        // An hour away is not evidence about the target moment, so nothing is returned.
        assertThat(dao.nearest(target + 60 * 60_000, toleranceMs = 10 * 60_000)).isNull()
    }

    @Test
    fun pruningRemovesOnlyHistoryOlderThanTheCutoff() = runTest {
        val dao = database.observationDao()
        val now = 1_700_000_000_000L
        dao.insert(observation(now - 40L * 24 * 3_600_000).toEntity())
        dao.insert(observation(now).toEntity())

        val deleted = dao.deleteBefore(now - 28L * 24 * 3_600_000)

        assertThat(deleted).isEqualTo(1)
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun deletingEverythingLeavesNothingBehind() = runTest {
        val dao = database.observationDao()
        repeat(5) { index -> dao.insert(observation(1_700_000_000_000L + index * 60_000).toEntity()) }

        dao.deleteAll()

        assertThat(dao.count()).isEqualTo(0)
        assertThat(dao.latest()).isNull()
    }
}
