package com.batterycast.quant.telemetry.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.batterycast.quant.di.ApplicationScope
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.repository.ObservationRepository
import com.batterycast.quant.telemetry.work.ObservationScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores observation after a restart, an update, or a time-zone change.
 *
 * A reboot invalidates the continuity anchor — `elapsedRealtime` resets to zero and charge-counter
 * deltas across the boundary are meaningless — so the first thing this does is take a fresh
 * reading, which the telemetry layer marks with a new boot session id. The cleaner then breaks
 * the segment there instead of differencing across the gap.
 *
 * A time-zone or daylight-saving change is handled the same way. Nothing is rewritten: every
 * observation already stores both wall-clock and monotonic time, so rates stay correct across the
 * change and only the *labels* on the history move.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var observationRepository: ObservationRepository

    @Inject lateinit var observationScheduler: ObservationScheduler

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                // WorkManager restores its own queue after boot, but re-asserting is cheap and
                // covers the force-stop case, where the app's work is cancelled outright.
                observationScheduler.ensureScheduled()
                observationRepository.primeCacheFromStorage()
                observationRepository.recordSample(ObservationSource.BOOT_COMPLETED)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
