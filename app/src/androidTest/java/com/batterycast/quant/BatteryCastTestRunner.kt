package com.batterycast.quant

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner that swaps in Hilt's test application.
 *
 * Note what this does *not* do: it does not replace the telemetry binding. The instrumentation
 * tests run against the real `AndroidBatteryTelemetrySource` reading the real battery of the
 * device or emulator, which is the only way to verify that the production path actually works.
 */
class BatteryCastTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
