package com.batterycast.quant.forecast

import com.batterycast.quant.model.BatteryObservation
import com.batterycast.quant.model.DataQuality
import kotlin.math.abs
import kotlin.math.max

class ObservationCleaner {
    data class Result(val valid: List<BatteryObservation>, val rejected: List<BatteryObservation>)

    fun clean(input: List<BatteryObservation>): Result {
        val sorted = input
            .groupBy { it.timestamp }
            .mapNotNull { (_, duplicates) -> duplicates.maxByOrNull { qualityRank(it.overallQuality) } }
            .sortedBy { it.timestamp }

        val valid = ArrayList<BatteryObservation>()
        val rejected = ArrayList<BatteryObservation>()
        var previous: BatteryObservation? = null

        for (raw in sorted) {
            if (hasImpossibleCore(raw)) {
                rejected += raw.copy(overallQuality = DataQuality.SUSPECTED_OUTLIER)
                continue
            }

            var cleaned = sanitizeOptionalFields(raw)
            val prev = previous
            val sameBoot = prev != null && sameBootSession(prev, cleaned)
            val elapsed = if (prev == null) Long.MAX_VALUE else cleaned.timestamp - prev.timestamp
            val abruptPercent = sameBoot && elapsed in 1 until 10 * 60_000L && when {
                !cleaned.isCharging && !prev!!.isCharging ->
                    cleaned.batteryPercent - prev.batteryPercent > 3.0 ||
                        prev.batteryPercent - cleaned.batteryPercent > 8.0
                cleaned.isCharging && prev!!.isCharging ->
                    prev.batteryPercent - cleaned.batteryPercent > 5.0 ||
                        cleaned.batteryPercent - prev.batteryPercent > 15.0
                else -> false
            }
            if (abruptPercent) {
                rejected += cleaned.copy(overallQuality = DataQuality.SUSPECTED_OUTLIER)
                continue
            }

            if (sameBoot && elapsed in 1 until 30 * 60_000L) {
                val previousCounter = prev!!.chargeCounterMicroAh
                val currentCounter = cleaned.chargeCounterMicroAh
                val previousEnergy = prev.energyNanoWh
                val currentEnergy = cleaned.energyNanoWh
                val counterJump = currentCounter != null && previousCounter != null &&
                    abs(currentCounter - previousCounter) > max(1_500_000L, (previousCounter * .40).toLong())
                val energyJump = currentEnergy != null && previousEnergy != null &&
                    abs(currentEnergy - previousEnergy) > max(5_000_000_000L, (previousEnergy * .40).toLong())
                if (counterJump || energyJump) {
                    cleaned = cleaned.copy(
                        chargeCounterMicroAh = if (counterJump) null else cleaned.chargeCounterMicroAh,
                        energyNanoWh = if (energyJump) null else cleaned.energyNanoWh,
                        overallQuality = lowerQuality(cleaned.overallQuality)
                    )
                }
            }

            valid += cleaned
            previous = cleaned
        }
        return Result(valid, rejected)
    }

    private fun hasImpossibleCore(observation: BatteryObservation): Boolean =
        observation.timestamp <= 0L ||
            observation.batteryPercent !in 0.0..100.0 ||
            observation.elapsedRealtimeMs < 0L

    private fun sanitizeOptionalFields(observation: BatteryObservation): BatteryObservation {
        val voltage = observation.voltageMv?.takeIf { it in 2_500..5_500 }
        val temperature = observation.temperatureDeciC?.takeIf { it in -200..900 }
        val current = observation.currentMicroA?.takeIf { abs(it) <= 20_000_000L }
        val averageCurrent = observation.averageCurrentMicroA?.takeIf { abs(it) <= 20_000_000L }
        val counter = observation.chargeCounterMicroAh?.takeIf { it in 1_000L..30_000_000L }
        val energy = observation.energyNanoWh?.takeIf { it in 1L..1_000_000_000_000L }
        val changed = voltage != observation.voltageMv ||
            temperature != observation.temperatureDeciC ||
            current != observation.currentMicroA ||
            averageCurrent != observation.averageCurrentMicroA ||
            counter != observation.chargeCounterMicroAh ||
            energy != observation.energyNanoWh
        return observation.copy(
            voltageMv = voltage,
            temperatureDeciC = temperature,
            currentMicroA = current,
            averageCurrentMicroA = averageCurrent,
            chargeCounterMicroAh = counter,
            energyNanoWh = energy,
            overallQuality = if (changed) lowerQuality(observation.overallQuality) else observation.overallQuality
        )
    }

    private fun lowerQuality(quality: DataQuality): DataQuality = when (quality) {
        DataQuality.SUSPECTED_OUTLIER -> DataQuality.SUSPECTED_OUTLIER
        DataQuality.UNSUPPORTED_FIELD -> DataQuality.UNSUPPORTED_FIELD
        else -> DataQuality.LOW_PRECISION
    }

    private fun qualityRank(quality: DataQuality): Int = when (quality) {
        DataQuality.HIGH -> 5
        DataQuality.ACCEPTABLE -> 4
        DataQuality.LOW_PRECISION -> 3
        DataQuality.UNSUPPORTED_FIELD -> 2
        DataQuality.SUSPECTED_OUTLIER -> 1
    }

    companion object {
        fun sameBootSession(a: BatteryObservation, b: BatteryObservation): Boolean {
            if (b.elapsedRealtimeMs < a.elapsedRealtimeMs) return false
            if (a.bootCount != null && b.bootCount != null && a.bootCount != b.bootCount) return false
            return true
        }
    }
}
