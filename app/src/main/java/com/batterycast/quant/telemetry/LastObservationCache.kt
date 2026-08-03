package com.batterycast.quant.telemetry

import com.batterycast.quant.telemetry.model.BatteryObservation
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The most recent observation, in memory.
 *
 * Continuity checks — reboot detection, percentage jumps, charge-counter resets — need the
 * previous reading. Holding it here keeps the telemetry source free of any database dependency
 * while still letting the repository prime it from storage when the process starts cold after a
 * reboot or force-stop.
 */
@Singleton
class LastObservationCache @Inject constructor() {
    private val reference = AtomicReference<BatteryObservation?>(null)

    fun get(): BatteryObservation? = reference.get()

    fun set(observation: BatteryObservation) {
        reference.set(observation)
    }

    /** Sets the value only if nothing has been recorded yet, used when priming from storage. */
    fun primeIfEmpty(observation: BatteryObservation?) {
        if (observation != null) reference.compareAndSet(null, observation)
    }

    fun clear() {
        reference.set(null)
    }
}
