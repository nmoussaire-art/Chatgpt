package com.batterycast.quant.forecast

import com.batterycast.quant.model.BatteryObservation
import com.batterycast.quant.model.PlugType
import kotlin.math.abs
import kotlin.math.max

class ChargingModel {
    data class Bin(val from: Int, val to: Int, val ratePercentPerHour: Double, val sigma: Double, val n: Int)
    data class Model(
        val plugType: PlugType,
        val bins: List<Bin>,
        val known: Boolean,
        val hotTemperatureFactor: Double?,
        val sessionCount: Int
    )

    fun fit(observations: List<BatteryObservation>, currentPlug: PlugType): Model {
        val segments = BatterySegmenter().detect(observations)
            .filter { it.type == BatterySegmentType.CHARGING }
        val grouped = mutableMapOf<Int, MutableList<Double>>()
        val hotRates = mutableListOf<Double>()
        val normalRates = mutableListOf<Double>()
        var sessionCount = 0

        for (segment in segments) {
            val charging = segment.observations.filter {
                currentPlug == PlugType.UNKNOWN || currentPlug == PlugType.NONE || it.plugType == currentPlug
            }
            if (charging.size < 2) continue
            var contributed = false
            for (i in 1 until charging.size) {
                val a = charging[i - 1]
                val b = charging[i]
                val dtH = (b.timestamp - a.timestamp) / 3_600_000.0
                if (dtH !in (1.0 / 60.0)..1.5 || a.plugType != b.plugType || b.batteryPercent < a.batteryPercent) continue
                val rate = (b.batteryPercent - a.batteryPercent) / dtH
                if (rate !in 0.1..250.0) continue
                grouped.getOrPut((a.batteryPercent.toInt() / 10) * 10) { mutableListOf() }.add(rate)
                val temp = listOfNotNull(a.temperatureDeciC, b.temperatureDeciC).average().takeUnless { it.isNaN() }
                if (temp != null && temp >= 420.0) hotRates += rate else normalRates += rate
                contributed = true
            }
            if (contributed) sessionCount++
        }

        val bins = (0 until 100 step 10).mapNotNull { low ->
            val values = grouped[low].orEmpty()
            val med = values.median().takeUnless { it.isNaN() } ?: return@mapNotNull null
            Bin(low, low + 10, med, max(1.0, mad(values) * 1.4826), values.size)
        }
        val hotFactor = if (hotRates.size >= 3 && normalRates.size >= 3) {
            (hotRates.median() / normalRates.median()).coerceIn(0.4, 1.2)
        } else null
        return Model(currentPlug, bins, bins.sumOf { it.n } >= 6 && bins.size >= 2, hotFactor, sessionCount)
    }

    fun rateAt(model: Model, percent: Double, temperatureDeciC: Int?): Pair<Double?, Double?> {
        val bin = model.bins.firstOrNull { percent >= it.from && percent < it.to }
            ?: model.bins.minByOrNull { abs((it.from + it.to) / 2.0 - percent) }
            ?: return null to null
        val isHot = temperatureDeciC != null && temperatureDeciC >= 420
        val factor = if (isHot) model.hotTemperatureFactor ?: 1.0 else 1.0
        val sigma = bin.sigma * if (isHot && model.hotTemperatureFactor == null) 1.35 else 1.0
        return bin.ratePercentPerHour * factor to sigma
    }

    internal fun advance(
        model: Model,
        startPercent: Double,
        minutes: Int,
        temperatureDeciC: Int?,
        gaussian: GaussianRandom
    ): Double {
        var battery = startPercent
        repeat(minutes.coerceAtLeast(0)) {
            if (battery >= 100.0) return 100.0
            val (rate, sigma) = rateAt(model, battery, temperatureDeciC)
            if (rate == null) return battery
            val sampled = max(0.0, rate + gaussian.next() * (sigma ?: max(1.0, rate * 0.25)))
            battery = clampBattery(battery + sampled / 60.0)
        }
        return battery
    }
}
