package com.batterycast.quant.forecast

import com.batterycast.quant.model.BatteryObservation
import com.batterycast.quant.model.ChargePlan
import com.batterycast.quant.model.ChargePlanRequest
import com.batterycast.quant.model.PlugType
import kotlin.math.max
import kotlin.random.Random

class ChargePlanner(
    private val simulations: Int = 2_000,
    private val randomSeed: Long? = null
) {
    init {
        require(simulations >= 2_000) { "Production charge plans require at least 2,000 paths." }
    }

    private data class PlanOutcome(
        val confidence: Double,
        val expectedAfterCharging: Double,
        val conservativeAfterCharging: Double,
        val expectedAtEvent: Double,
        val conservativeAtEvent: Double
    )

    fun plan(
        observations: List<BatteryObservation>,
        request: ChargePlanRequest,
        now: Long = observations.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
    ): ChargePlan {
        val cleaned = ObservationCleaner().clean(observations).valid
        val current = cleaned.maxByOrNull { it.timestamp }
            ?: return noPlan(now, "A live battery reading is required.")
        if (request.eventTime <= now) return noPlan(now, "Choose a future event time.")

        val discharge = DrainEstimator().estimate(cleaned, now)
            ?: return noPlan(
                now,
                "BatteryCast needs more live discharge behaviour before planning a safe charge."
            )
        val chargingModel = ChargingModel()
        val plannedPlug = when {
            current.isCharging && current.plugType !in setOf(PlugType.NONE, PlugType.UNKNOWN) -> current.plugType
            else -> cleaned.asSequence()
                .filter { it.isCharging && it.plugType !in setOf(PlugType.NONE, PlugType.UNKNOWN) }
                .groupingBy { it.plugType }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: PlugType.UNKNOWN
        }
        val model = chargingModel.fit(cleaned, plannedPlug)
        val (knownRate, _) = chargingModel.rateAt(model, current.batteryPercent, current.temperatureDeciC)
        if (knownRate == null) {
            return noPlan(now, "No real charging curve has been observed yet for this charger type.")
        }

        fun outcome(start: Long, durationMinutes: Int): PlanOutcome {
            // Every candidate receives the same deterministic stream in tests, while production
            // receives a fresh non-fixed seed. This prevents search-order noise from deciding a plan.
            val candidateSeed = randomSeed?.xor(start)?.xor(durationMinutes.toLong())
            val rng = candidateSeed?.let(::Random) ?: Random.Default
            val gaussian = GaussianRandom(rng)
            val afterCharge = DoubleArray(simulations)
            val atEvent = DoubleArray(simulations)
            repeat(simulations) { index ->
                val preHours = (start - now).coerceAtLeast(0L) / 3_600_000.0
                val chargeEnd = start + durationMinutes * 60_000L
                val postHours = (request.eventTime - chargeEnd).coerceAtLeast(0L) / 3_600_000.0
                val drainRate = max(
                    0.0,
                    discharge.percentPerHour + gaussian.next() * discharge.uncertaintyPerHour
                )
                var battery = clampBattery(current.batteryPercent - drainRate * preHours)
                battery = chargingModel.advance(
                    model,
                    battery,
                    durationMinutes,
                    current.temperatureDeciC,
                    gaussian
                )
                afterCharge[index] = battery
                battery = clampBattery(battery - drainRate * postHours)
                atEvent[index] = battery
            }
            return PlanOutcome(
                confidence = atEvent.count { it >= request.desiredBatteryPercent }.toDouble() / simulations,
                expectedAfterCharging = afterCharge.average(),
                conservativeAfterCharging = afterCharge.quantile(.10),
                expectedAtEvent = atEvent.average(),
                conservativeAtEvent = atEvent.quantile(.10)
            )
        }

        var bestStart: Long? = null
        var bestDuration: Int? = null
        var bestOutcome: PlanOutcome? = null
        val maximumMinutes = minOf(
            request.maxChargeMinutes,
            ((request.eventTime - now) / 60_000L).toInt()
        )
        for (duration in 5..maximumMinutes step 5) {
            val start = request.eventTime - duration * 60_000L
            if (start < now) continue
            val result = outcome(start, duration)
            if (result.confidence >= request.desiredConfidence && (bestStart == null || start > bestStart)) {
                bestStart = start
                bestDuration = duration
                bestOutcome = result
            }
        }

        return if (bestStart != null && bestDuration != null && bestOutcome != null) {
            ChargePlan(
                generatedAt = now,
                latestSafeStart = bestStart,
                recommendedDurationMinutes = bestDuration,
                expectedBatteryAfterCharging = bestOutcome.expectedAfterCharging,
                conservativeBatteryAfterCharging = bestOutcome.conservativeAfterCharging,
                expectedBatteryAtEvent = bestOutcome.expectedAtEvent,
                conservativeBatteryAtEvent = bestOutcome.conservativeAtEvent,
                confidenceAchieved = bestOutcome.confidence,
                chargerKnown = model.known,
                message = "Start by the recommended time to meet the selected target using your observed " +
                    "${plannedPlug.name.lowercase()} charging curve."
            )
        } else {
            noPlan(
                now,
                "The requested battery target is not achievable with the observed charger before the event.",
                chargerKnown = model.known
            )
        }
    }

    private fun noPlan(
        now: Long,
        message: String,
        chargerKnown: Boolean = false
    ) = ChargePlan(
        generatedAt = now,
        latestSafeStart = null,
        recommendedDurationMinutes = null,
        expectedBatteryAfterCharging = null,
        conservativeBatteryAfterCharging = null,
        expectedBatteryAtEvent = null,
        conservativeBatteryAtEvent = null,
        confidenceAchieved = null,
        chargerKnown = chargerKnown,
        message = message
    )
}
