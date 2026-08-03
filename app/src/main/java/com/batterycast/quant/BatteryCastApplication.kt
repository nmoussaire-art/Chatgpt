package com.batterycast.quant

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.batterycast.quant.notifications.BatteryCastNotifier
import com.batterycast.quant.telemetry.work.ObservationScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Startup does three things and nothing more: configure WorkManager, create the notification
 * channels, and make sure the periodic observation work exists. No background thread is started,
 * no service is launched, and no measurement is taken here — a battery app should be close to
 * free to have installed.
 */
@HiltAndroidApp
class BatteryCastApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var observationScheduler: ObservationScheduler

    @Inject lateinit var notifier: BatteryCastNotifier

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        observationScheduler.ensureScheduled()
    }
}
