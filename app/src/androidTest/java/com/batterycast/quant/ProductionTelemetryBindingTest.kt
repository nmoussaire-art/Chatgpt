package com.batterycast.quant

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.batterycast.quant.telemetry.AndroidBatteryTelemetrySource
import com.batterycast.quant.telemetry.BatteryTelemetrySource
import com.batterycast.quant.telemetry.CapabilityStore
import com.batterycast.quant.core.database.RoomCapabilityStore
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Resolves the real dependency graph on a real device and checks what it produced.
 *
 * The Gradle task and the unit test both inspect *source*; this one inspects the *object* Hilt
 * actually constructs at runtime. Together they close the loop: there is no build configuration,
 * no product flavour, and no injection point through which a fake telemetry provider could reach
 * the shipped app.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ProductionTelemetryBindingTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var telemetrySource: BatteryTelemetrySource

    @Inject lateinit var capabilityStore: CapabilityStore

    @Before fun setUp() = hiltRule.inject()

    @Test
    fun theInjectedTelemetrySourceIsTheRealAndroidImplementation() {
        assertThat(telemetrySource).isInstanceOf(AndroidBatteryTelemetrySource::class.java)
    }

    @Test
    fun theCapabilityStoreIsBackedByTheRealDatabase() {
        assertThat(capabilityStore).isInstanceOf(RoomCapabilityStore::class.java)
    }

    @Test
    fun noTypeInTheGraphIsNamedLikeATestDouble() {
        val name = telemetrySource::class.java.name
        listOf("Fake", "Mock", "Demo", "Sample", "Stub", "Dummy").forEach { banned ->
            assertThat(name).doesNotContain(banned)
        }
    }
}
