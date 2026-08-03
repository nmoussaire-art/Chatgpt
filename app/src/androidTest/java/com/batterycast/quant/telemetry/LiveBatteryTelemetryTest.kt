package com.batterycast.quant.telemetry

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.repository.ObservationRepository
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Reads the real battery of the device or emulator this test is running on.
 *
 * This is the test that proves the foundational claim of the app: that it can read live battery
 * information, persist it, and get back exactly what the hardware said. Everything is asserted as
 * a physical plausibility bound rather than an exact value, because the value depends on the
 * device's actual state at the moment the test runs — which is the point.
 *
 * On an emulator, `adb emu power` and the extended controls change the values these assertions
 * see; see `docs/MANUAL_TESTING.md`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LiveBatteryTelemetryTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var telemetrySource: BatteryTelemetrySource

    @Inject lateinit var observationRepository: ObservationRepository

    @Before fun setUp() = hiltRule.inject()

    @Test
    fun samplingReturnsALiveReadingFromThisDevice() = runTest {
        val observation = telemetrySource.sample(ObservationSource.APP_FOREGROUND)

        assertThat(observation).isNotNull()
        observation!!

        // The battery percentage is the one field Android always provides.
        assertThat(observation.batteryPercent).isAtLeast(0.0)
        assertThat(observation.batteryPercent).isAtMost(100.0)
        assertThat(observation.rawScale).isGreaterThan(0)

        // Timestamps come from the device clocks, not from anything the app invented.
        assertThat(observation.timestampMs).isGreaterThan(0L)
        assertThat(observation.elapsedRealtimeMs).isAtLeast(0L)
    }

    @Test
    fun optionalFieldsAreEitherPhysicallyPlausibleOrExplicitlyAbsent() = runTest {
        val observation = telemetrySource.sample(ObservationSource.APP_FOREGROUND)!!

        // Every optional field is either a plausible measurement or a null carrying a reason.
        // Nothing is ever a substituted zero.
        observation.voltageMv?.let { assertThat(it).isIn(com.google.common.collect.Range.closed(2000, 5500)) }
        observation.temperatureDeciC?.let {
            assertThat(it).isIn(com.google.common.collect.Range.closed(-300, 800))
        }
        observation.chargeCounterMicroAh?.let { assertThat(it).isGreaterThan(0L) }
        observation.currentMicroA?.let { assertThat(kotlin.math.abs(it)).isGreaterThan(0L) }
        observation.energyNanoWh?.let { assertThat(it).isGreaterThan(0L) }

        assertThat(observation.notes).isNotNull()
    }

    @Test
    fun theBroadcastFlowEmitsTheCurrentStateWithoutWaitingForAChange() = runTest {
        // The sticky ACTION_BATTERY_CHANGED intent means the first emission is immediate.
        val observation = withTimeout(10_000) { telemetrySource.observations().first() }

        assertThat(observation.batteryPercent).isAtLeast(0.0)
        assertThat(observation.batteryPercent).isAtMost(100.0)
    }

    @Test
    fun aSampledObservationSurvivesARoundTripThroughStorage() = runTest {
        val stored = observationRepository.recordSample(ObservationSource.APP_FOREGROUND)
        assertThat(stored).isNotNull()

        val readBack = observationRepository.latest()

        assertThat(readBack).isNotNull()
        assertThat(readBack!!.batteryPercent).isEqualTo(stored!!.batteryPercent)
        assertThat(readBack.isCharging).isEqualTo(stored.isCharging)
        assertThat(readBack.plugType).isEqualTo(stored.plugType)
        // Crucially: an unsupported field must still be null after a round trip, not zero.
        assertThat(readBack.chargeCounterMicroAh).isEqualTo(stored.chargeCounterMicroAh)
        assertThat(readBack.currentMicroA).isEqualTo(stored.currentMicroA)
        assertThat(readBack.energyNanoWh).isEqualTo(stored.energyNanoWh)
        assertThat(readBack.notes).isEqualTo(stored.notes)
    }

    @Test
    fun capabilitiesAreLearnedFromRealReadingsRatherThanAssumed() = runTest {
        repeat(6) { telemetrySource.sample(ObservationSource.APP_FOREGROUND) }

        val capabilities = telemetrySource.capabilities()

        assertThat(capabilities.samplesConsidered).isAtLeast(6)
        assertThat(capabilities.percentGranularity).isGreaterThan(0.0)
        // Whatever this particular device supports, the verdicts must be self-consistent.
        capabilities.unsupportedFields().forEach { assertThat(it).isNotEmpty() }
    }

    @Test
    fun repeatedSamplingDoesNotStoreDuplicateReadings() = runTest {
        observationRepository.recordSample(ObservationSource.APP_FOREGROUND)
        val countAfterFirst = observationRepository.count()

        // Nothing changed in between, so the second sample carries no new information.
        observationRepository.recordSample(ObservationSource.APP_FOREGROUND)
        val countAfterSecond = observationRepository.count()

        assertThat(countAfterSecond).isEqualTo(countAfterFirst)
    }
}
