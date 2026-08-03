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
 * Samples immediately when the device's power situation changes.
 *
 * These are the moments that carry the most information per reading: the instant a cable goes in
 * is the only way to know when a charging session started, and a power-save toggle changes the
 * drain rate discontinuously. Waiting up to fifteen minutes for the next periodic run would blur
 * exactly the transitions the model needs to segment on.
 *
 * `ACTION_BATTERY_CHANGED` is deliberately absent: it cannot be registered in the manifest, and
 * receiving it continuously would wake the app for every percentage point around the clock. It is
 * observed with a runtime receiver only while a screen is actually open.
 */
@AndroidEntryPoint
class BatteryEventReceiver : BroadcastReceiver() {

    @Inject lateinit var observationRepository: ObservationRepository

    @Inject lateinit var observationScheduler: ObservationScheduler

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        // goAsync keeps the broadcast alive across the suspend call; the work is a single read
        // and insert, comfortably inside the receiver's time budget.
        val pendingResult = goAsync()
        scope.launch {
            val stored = runCatching {
                observationRepository.recordSample(ObservationSource.STATE_CHANGE)
            }.getOrNull()

            // The platform occasionally has no battery state to give, and the receiver's window is
            // too short to wait for it. Handing the retry to expedited work keeps the transition
            // recorded rather than losing it — plug events are the most informative readings the
            // app ever gets.
            if (stored == null) {
                runCatching { observationScheduler.requestImmediateSample() }
            }

            runCatching { pendingResult.finish() }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_LOW,
            Intent.ACTION_BATTERY_OKAY,
            "android.os.action.POWER_SAVE_MODE_CHANGED",
        )
    }
}
