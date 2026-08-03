package com.batterycast.quant.di

import com.batterycast.quant.core.database.RoomCapabilityStore
import com.batterycast.quant.telemetry.AndroidBatteryTelemetrySource
import com.batterycast.quant.telemetry.BatteryTelemetrySource
import com.batterycast.quant.telemetry.CapabilityStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The production telemetry graph.
 *
 * This module is the single point where the app decides where its numbers come from, so it is
 * guarded three ways: the Gradle task `verifyProductionTelemetryBinding` parses this file and
 * fails the build if the binding target changes, `ProductionTelemetryBindingTest` asserts the
 * same from a unit test, and the instrumentation test resolves the real graph on a device and
 * checks the injected instance's type.
 *
 * There is no debug variant of this module, no build-flavour switch, and no fake implementation
 * anywhere in `src/main` to switch to.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TelemetryModule {

    @Binds
    @Singleton
    abstract fun bindBatteryTelemetrySource(impl: AndroidBatteryTelemetrySource): BatteryTelemetrySource

    @Binds
    @Singleton
    abstract fun bindCapabilityStore(impl: RoomCapabilityStore): CapabilityStore
}
