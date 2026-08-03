package com.batterycast.quant

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.batterycast.quant.telemetry.TelemetryScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BatteryCastApplication : Application(), Configuration.Provider {
    private var lastBatteryLevel: Int? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            if (level >= 0 && level != lastBatteryLevel) {
                lastBatteryLevel = level
                scheduler.captureNow("battery_level_change")
            }
        }
    }

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduler: TelemetryScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        scheduler.ensurePeriodicSampling()
        scheduler.captureNow("app_start")
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
