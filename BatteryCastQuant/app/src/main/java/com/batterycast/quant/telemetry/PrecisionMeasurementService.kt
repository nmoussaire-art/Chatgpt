package com.batterycast.quant.telemetry

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.batterycast.quant.data.BatteryRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class PrecisionMeasurementService : Service() {
    @Inject lateinit var repository: BatteryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var measurementJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val duration = (intent?.getIntExtra(EXTRA_MINUTES, 15) ?: 15).coerceIn(10, 20)
        startForeground(NOTIFICATION_ID, notification(duration))
        measurementJob?.cancel()
        measurementJob = scope.launch {
            val end = System.currentTimeMillis() + duration * 60_000L
            while (isActive && System.currentTimeMillis() < end) {
                repository.capture("precision_session")
                delay(10_000L)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        measurementJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Precision measurement", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(minutes: Int): android.app.Notification {
        val stopIntent = Intent(this, PrecisionMeasurementService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Precision measurement active")
            .setContentText("Collecting live current readings for up to $minutes minutes")
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.batterycast.quant.STOP_PRECISION"
        const val EXTRA_MINUTES = "minutes"
        const val CHANNEL = "precision"
        private const val NOTIFICATION_ID = 4102
    }
}
