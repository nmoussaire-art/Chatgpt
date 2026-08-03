package com.batterycast.quant.telemetry

import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.model.SensorCapabilities
import kotlinx.coroutines.flow.Flow

/**
 * The single seam between BatteryCast and the physical device.
 *
 * There is exactly one production implementation, `AndroidBatteryTelemetrySource`, and the
 * production Hilt graph binds only that one. `verifyProductionTelemetryBinding` (Gradle) and
 * `ProductionTelemetryBindingTest` (unit + instrumentation) fail the build if anything else is
 * ever bound, so the APK cannot be made to read invented numbers.
 *
 * Test source sets are free to implement this interface; nothing in `src/main` may.
 */
interface BatteryTelemetrySource {

    /**
     * Takes a reading right now.
     *
     * Returns null only when the platform gives us no battery state at all — for instance when
     * the sticky `ACTION_BATTERY_CHANGED` broadcast is not yet available immediately after boot.
     * It never returns a placeholder observation.
     */
    suspend fun sample(source: ObservationSource): BatteryObservation?

    /**
     * Emits a reading whenever the platform reports a battery change, and once on collection.
     *
     * Backed by a registered `ACTION_BATTERY_CHANGED` receiver, so it costs nothing while nobody
     * is collecting.
     */
    fun observations(): Flow<BatteryObservation>

    /**
     * What this particular device actually supports, learned from real readings rather than
     * assumed from the API level.
     */
    suspend fun capabilities(): SensorCapabilities
}
