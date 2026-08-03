package com.batterycast.quant.telemetry.precision

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.batterycast.quant.core.database.dao.PrecisionSessionDao
import com.batterycast.quant.core.database.entity.PrecisionSessionEntity
import com.batterycast.quant.notifications.BatteryCastNotifier
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.repository.ObservationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * A short, user-started, self-terminating high-cadence measurement session.
 *
 * This is the *only* foreground service in the app and the only place sampling happens faster
 * than the background schedule. It exists because a phone with a working current sensor can pin
 * down its short-term drain far more precisely in fifteen minutes of dense sampling than in an
 * hour of fifteen-minute polls — but that is a trade the user has to opt into, so:
 *
 * - it never starts on its own;
 * - it shows an ongoing notification for its entire life, saying it uses slightly more power;
 * - it holds no wake lock, so it does not keep the device awake and does not fight Doze;
 * - it stops itself at the end of the chosen window, and the window is capped.
 *
 * Because there is no wake lock, samples may be spaced further apart than requested while the
 * screen is off. That is correct behaviour, not a bug: each observation carries its own timestamp
 * and the model handles irregular spacing.
 */
@AndroidEntryPoint
class PrecisionSessionService : Service() {

    @Inject lateinit var observationRepository: ObservationRepository

    @Inject lateinit var precisionSessionDao: PrecisionSessionDao

    @Inject lateinit var notifier: BatteryCastNotifier

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var sessionJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedMs = intent?.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
            ?: DEFAULT_DURATION_MS
        val durationMs = requestedMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

        startForegroundSafely(durationMs)

        if (sessionJob?.isActive == true) return START_NOT_STICKY
        sessionJob = scope.launch { runSession(durationMs) }

        // Not sticky: if the system kills this, it must not silently come back. A measurement
        // session the user did not ask for is exactly what this app promises not to do.
        return START_NOT_STICKY
    }

    private suspend fun runSession(durationMs: Long) {
        val start = observationRepository.recordSample(ObservationSource.PRECISION_SESSION)
        val sessionId = precisionSessionDao.insert(
            PrecisionSessionEntity(
                startedAtMs = System.currentTimeMillis(),
                plannedDurationMs = durationMs,
                startPercent = start?.batteryPercent ?: 0.0,
            ),
        )

        val endAtMs = System.currentTimeMillis() + durationMs
        var samples = if (start != null) 1 else 0

        while (System.currentTimeMillis() < endAtMs) {
            val remainingMs = endAtMs - System.currentTimeMillis()
            delay(minOf(SAMPLING_INTERVAL_MS, remainingMs).coerceAtLeast(1_000L))
            if (System.currentTimeMillis() >= endAtMs) break

            observationRepository.recordSample(ObservationSource.PRECISION_SESSION)?.let { samples++ }
            updateNotification(((endAtMs - System.currentTimeMillis()) / 60_000.0).roundToInt())
        }

        val last = observationRepository.recordSample(ObservationSource.PRECISION_SESSION)
        if (last != null) samples++

        precisionSessionDao.finish(
            id = sessionId,
            endedAtMs = System.currentTimeMillis(),
            endPercent = last?.batteryPercent,
            sampleCount = samples,
            completed = true,
        )

        stopSelf()
    }

    private fun startForegroundSafely(durationMs: Long) {
        val notification = notifier.buildPrecisionSessionNotification((durationMs / 60_000).toInt())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    BatteryCastNotifier.ID_PRECISION_SESSION,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(BatteryCastNotifier.ID_PRECISION_SESSION, notification)
            }
        }.onFailure { stopSelf() }
    }

    private fun updateNotification(remainingMinutes: Int) {
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
            manager?.notify(
                BatteryCastNotifier.ID_PRECISION_SESSION,
                notifier.buildPrecisionSessionNotification(remainingMinutes.coerceAtLeast(0)),
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DURATION_MS = "duration_ms"

        /** Fifteen minutes: long enough to resolve a rate, short enough to be inconsequential. */
        const val DEFAULT_DURATION_MS = 15 * 60 * 1000L
        const val MIN_DURATION_MS = 10 * 60 * 1000L

        /** Hard cap. A precision session can never be left running indefinitely. */
        const val MAX_DURATION_MS = 20 * 60 * 1000L

        /** Thirty seconds while the session is running; the background rate is 15 minutes. */
        const val SAMPLING_INTERVAL_MS = 30 * 1000L

        fun start(context: Context, durationMs: Long = DEFAULT_DURATION_MS) {
            val intent = Intent(context, PrecisionSessionService::class.java)
                .putExtra(EXTRA_DURATION_MS, durationMs)
            ContextCompatStartForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PrecisionSessionService::class.java))
        }

        private fun ContextCompatStartForegroundService(context: Context, intent: Intent) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
