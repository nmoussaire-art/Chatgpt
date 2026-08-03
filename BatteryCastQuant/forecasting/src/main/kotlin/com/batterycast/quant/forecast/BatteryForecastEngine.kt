package com.batterycast.quant.forecast

import com.batterycast.quant.model.*
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class BatteryForecastEngine(
    private val simulations: Int = 2_000,
    private val randomSeed: Long? = null,
    private val cleaner: ObservationCleaner = ObservationCleaner(),
    private val estimator: DrainEstimator = DrainEstimator()
) {
    init { require(simulations >= 2_000) { "Production forecasts require at least 2,000 paths." } }

    private val heavyRegimes = setOf(
        UsageRegime.HEAVY,
        UsageRegime.MEDIA_GAMING,
        UsageRegime.NAVIGATION,
        UsageRegime.HOTSPOT
    )

    fun forecast(
        observations: List<BatteryObservation>,
        targetTime: Long,
        reservePercent: Int,
        scenario: ScenarioDefinition? = null,
        now: Long = observations.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
    ): ForecastResult {
        val cleaned = cleaner.clean(observations).valid
        val current = cleaned.lastOrNull()
            ?: return insufficient(now, targetTime, reservePercent, "No live battery observation is available yet.")
        if (targetTime <= now) return insufficient(now, targetTime, reservePercent, "Choose a target time in the future.")
        if (current.isCharging) return chargingProjection(cleaned, current, targetTime, reservePercent, now)

        val estimate = estimator.estimate(cleaned, now)
            ?: return insufficient(
                now,
                targetTime,
                reservePercent,
                "BatteryCast is collecting live battery behaviour. A preliminary forecast will become available after enough change has been observed."
            )

        val scenarioEstimate = scenarioRate(cleaned, estimate.percentPerHour, scenario)
        val baselineRate = estimate.percentPerHour
        val explicitScenarioRate = scenarioEstimate.rate
        var sigma = estimate.uncertaintyPerHour
        if (scenarioEstimate.lowConfidence) sigma *= 1.55
        if ((current.thermalStatus ?: 0) >= 3) sigma *= 1.20

        val rng = randomSeed?.let(::Random) ?: Random.Default
        val gaussian = GaussianRandom(rng)
        val stepMs = 5 * 60_000L
        val steps = max(1, ((targetTime - now + stepMs - 1) / stepMs).toInt())
        val paths = Array(simulations) { DoubleArray(steps + 1) }
        val thresholdTimes = mapOf(
            20 to mutableListOf<Long>(),
            10 to mutableListOf(),
            5 to mutableListOf(),
            0 to mutableListOf()
        )
        val finalValues = DoubleArray(simulations)
        val contextRates = empiricalContextRates(cleaned)
        val scenarioEnd = scenario?.let { now + it.durationMinutes.coerceAtLeast(0) * 60_000L }

        repeat(simulations) { pathIndex ->
            var battery = current.batteryPercent
            paths[pathIndex][0] = battery
            var persistentShock = gaussian.next() * sigma * 0.30
            var contextFactor = 1.0
            var hit20: Long? = null
            var hit10: Long? = null
            var hit5: Long? = null
            var hit0: Long? = null

            for (step in 1..steps) {
                val stepStart = now + (step - 1) * stepMs
                val dtH = min(stepMs, targetTime - stepStart).coerceAtLeast(0L) / 3_600_000.0
                if (step % 6 == 1) {
                    persistentShock = 0.65 * persistentShock + gaussian.next() * sigma * 0.45
                    contextFactor = if (contextRates.size >= 4 && (scenarioEnd == null || stepStart >= scenarioEnd)) {
                        (contextRates[rng.nextInt(contextRates.size)] / baselineRate).coerceIn(0.35, 2.50)
                    } else 1.0
                }

                val explicitScenarioActive = scenarioEnd != null && stepStart < scenarioEnd
                val centreRate = if (explicitScenarioActive) explicitScenarioRate else baselineRate * contextFactor
                val residual = bootstrapResidual(estimate.residuals, rng) + gaussian.next() * sigma * 0.35
                val levelFactor = learnedBatteryLevelFactor(cleaned, battery, baselineRate)
                val lowBatteryVolatility = if (battery <= 10.0) 1.25 else 1.0
                val sampledRate = max(0.0, (centreRate + persistentShock + residual * lowBatteryVolatility) * levelFactor)
                battery = clampBattery(battery - sampledRate * dtH)
                paths[pathIndex][step] = battery

                val timestamp = min(targetTime, now + step * stepMs)
                if (hit20 == null && battery <= 20.0) hit20 = timestamp
                if (hit10 == null && battery <= 10.0) hit10 = timestamp
                if (hit5 == null && battery <= 5.0) hit5 = timestamp
                if (hit0 == null && battery <= 0.0) hit0 = timestamp
            }

            finalValues[pathIndex] = battery

            // Continue threshold-only simulation beyond the selected target. This keeps the
            // chart and target metrics focused while still providing a live time-to-empty estimate.
            val thresholdHorizon = now + 48 * 60 * 60_000L
            var continuationTime = targetTime
            var continuationStep = 0
            while (battery > 0.0 && continuationTime < thresholdHorizon) {
                val nextTime = min(thresholdHorizon, continuationTime + 15 * 60_000L)
                val dtH = (nextTime - continuationTime) / 3_600_000.0
                if (continuationStep % 4 == 0) {
                    persistentShock = 0.65 * persistentShock + gaussian.next() * sigma * 0.45
                    contextFactor = if (contextRates.size >= 4) {
                        (contextRates[rng.nextInt(contextRates.size)] / baselineRate).coerceIn(0.35, 2.50)
                    } else 1.0
                }
                val residual = bootstrapResidual(estimate.residuals, rng) + gaussian.next() * sigma * 0.35
                val levelFactor = learnedBatteryLevelFactor(cleaned, battery, baselineRate)
                val lowBatteryVolatility = if (battery <= 10.0) 1.25 else 1.0
                val sampledRate = max(
                    0.0,
                    (baselineRate * contextFactor + persistentShock + residual * lowBatteryVolatility) * levelFactor
                )
                battery = clampBattery(battery - sampledRate * dtH)
                continuationTime = nextTime
                continuationStep++
                if (hit20 == null && battery <= 20.0) hit20 = continuationTime
                if (hit10 == null && battery <= 10.0) hit10 = continuationTime
                if (hit5 == null && battery <= 5.0) hit5 = continuationTime
                if (hit0 == null && battery <= 0.0) hit0 = continuationTime
            }

            hit20?.let { thresholdTimes.getValue(20).add(it) }
            hit10?.let { thresholdTimes.getValue(10).add(it) }
            hit5?.let { thresholdTimes.getValue(5).add(it) }
            hit0?.let { thresholdTimes.getValue(0).add(it) }
        }

        val points = (0..steps).map { step ->
            val values = DoubleArray(simulations) { paths[it][step] }
            ForecastPoint(
                timestamp = min(targetTime, now + step * stepMs),
                p05 = values.quantile(.05),
                p10 = values.quantile(.10),
                p25 = values.quantile(.25),
                median = values.quantile(.50),
                mean = values.average(),
                p75 = values.quantile(.75),
                p90 = values.quantile(.90),
                p95 = values.quantile(.95),
                probabilityAboveReserve = values.count { it > reservePercent }.toDouble() / simulations
            )
        }
        val survival = finalValues.count { it > reservePercent }.toDouble() / simulations
        val thresholds = thresholdTimes.map { (threshold, values) ->
            val sortedTimes = values.sorted()
            ThresholdDistribution(
                threshold = threshold,
                medianTimestamp = unconditionalThresholdQuantile(sortedTimes, .50),
                p10Timestamp = unconditionalThresholdQuantile(sortedTimes, .10),
                p90Timestamp = unconditionalThresholdQuantile(sortedTimes, .90),
                probabilityReachedBeforeTarget = values.count { it <= targetTime }.toDouble() / simulations
            )
        }
        val confidence = confidence(estimate, cleaned)
        val result = ForecastResult(
            generatedAt = now,
            targetTime = targetTime,
            reservePercent = reservePercent,
            confidence = confidence,
            qualityMessage = qualityMessage(confidence, estimate.preliminary),
            survivalProbability = survival,
            expectedAtTarget = finalValues.average(),
            medianAtTarget = finalValues.quantile(.50),
            conservativeAtTarget = finalValues.quantile(.05),
            targetP05 = finalValues.quantile(.05),
            targetP10 = finalValues.quantile(.10),
            targetP25 = finalValues.quantile(.25),
            targetP75 = finalValues.quantile(.75),
            targetP90 = finalValues.quantile(.90),
            targetP95 = finalValues.quantile(.95),
            points = points,
            thresholds = thresholds,
            baseDrainPercentPerHour = baselineRate,
            baseDrainMicroAhPerHour = estimate.microAhPerHour,
            baseDrainMilliWhPerHour = estimate.milliWhPerHour,
            uncertaintyPercentPerHour = sigma,
            mainDriver = driver(cleaned, estimate, scenarioEstimate.note),
            sampleCount = estimate.sampleCount,
            preliminary = estimate.preliminary
        )
        return if (scenario != null && scenario.mix.isEmpty() && scenario.regime in heavyRegimes) {
            enforceHeavyScenarioMonotonicity(result, forecast(observations, targetTime, reservePercent, null, now))
        } else {
            result
        }
    }

    private fun enforceHeavyScenarioMonotonicity(
        scenario: ForecastResult,
        baseline: ForecastResult
    ): ForecastResult {
        val baselinePoints = baseline.points.associateBy { it.timestamp }
        val points = scenario.points.map { point ->
            val base = baselinePoints[point.timestamp] ?: return@map point
            point.copy(
                p05 = min(point.p05, base.p05),
                p10 = min(point.p10, base.p10),
                p25 = min(point.p25, base.p25),
                median = min(point.median, base.median),
                mean = min(point.mean, base.mean),
                p75 = min(point.p75, base.p75),
                p90 = min(point.p90, base.p90),
                p95 = min(point.p95, base.p95),
                probabilityAboveReserve = minNullable(point.probabilityAboveReserve, base.probabilityAboveReserve)
            )
        }
        val baselineThresholds = baseline.thresholds.associateBy { it.threshold }
        val thresholds = scenario.thresholds.map { threshold ->
            val base = baselineThresholds[threshold.threshold] ?: return@map threshold
            threshold.copy(
                medianTimestamp = earlier(threshold.medianTimestamp, base.medianTimestamp),
                p10Timestamp = earlier(threshold.p10Timestamp, base.p10Timestamp),
                p90Timestamp = earlier(threshold.p90Timestamp, base.p90Timestamp),
                probabilityReachedBeforeTarget = max(
                    threshold.probabilityReachedBeforeTarget,
                    base.probabilityReachedBeforeTarget
                )
            )
        }
        return scenario.copy(
            survivalProbability = minNullable(scenario.survivalProbability, baseline.survivalProbability),
            expectedAtTarget = minNullable(scenario.expectedAtTarget, baseline.expectedAtTarget),
            medianAtTarget = minNullable(scenario.medianAtTarget, baseline.medianAtTarget),
            conservativeAtTarget = minNullable(scenario.conservativeAtTarget, baseline.conservativeAtTarget),
            targetP05 = minNullable(scenario.targetP05, baseline.targetP05),
            targetP10 = minNullable(scenario.targetP10, baseline.targetP10),
            targetP25 = minNullable(scenario.targetP25, baseline.targetP25),
            targetP75 = minNullable(scenario.targetP75, baseline.targetP75),
            targetP90 = minNullable(scenario.targetP90, baseline.targetP90),
            targetP95 = minNullable(scenario.targetP95, baseline.targetP95),
            points = points,
            thresholds = thresholds,
            baseDrainPercentPerHour = max(
                scenario.baseDrainPercentPerHour ?: 0.0,
                baseline.baseDrainPercentPerHour ?: 0.0
            )
        )
    }

    private fun unconditionalThresholdQuantile(sortedReachedTimes: List<Long>, quantile: Double): Long? {
        val requiredRank = kotlin.math.ceil(quantile.coerceIn(0.0, 1.0) * simulations).toInt()
        if (requiredRank <= 0) return sortedReachedTimes.firstOrNull()
        if (sortedReachedTimes.size < requiredRank) return null
        return sortedReachedTimes[requiredRank - 1]
    }

    private fun minNullable(a: Double?, b: Double?): Double? = when {
        a == null -> b
        b == null -> a
        else -> min(a, b)
    }

    private fun earlier(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> min(a, b)
    }

    private fun chargingProjection(
        observations: List<BatteryObservation>,
        current: BatteryObservation,
        targetTime: Long,
        reserve: Int,
        now: Long
    ): ForecastResult {
        val chargingModel = ChargingModel()
        val model = chargingModel.fit(observations, current.plugType)
        val (rate, sigma) = chargingModel.rateAt(model, current.batteryPercent, current.temperatureDeciC)
        if (rate == null) {
            return insufficient(
                now,
                targetTime,
                reserve,
                "Charging is detected, but BatteryCast has not yet observed enough live charging behaviour for this charger."
            )
        }

        val rng = randomSeed?.let(::Random) ?: Random.Default
        val gaussian = GaussianRandom(rng)
        val stepMs = 5 * 60_000L
        val steps = max(1, ((targetTime - now + stepMs - 1) / stepMs).toInt())
        val paths = Array(simulations) { DoubleArray(steps + 1) }
        val finals = DoubleArray(simulations)

        repeat(simulations) { pathIndex ->
            var battery = current.batteryPercent
            paths[pathIndex][0] = battery
            for (step in 1..steps) {
                val minutes = (min(stepMs, targetTime - (now + (step - 1) * stepMs)).coerceAtLeast(0L) / 60_000L).toInt()
                battery = chargingModel.advance(model, battery, minutes, current.temperatureDeciC, gaussian)
                paths[pathIndex][step] = battery
            }
            finals[pathIndex] = battery
        }

        val points = (0..steps).map { step ->
            val values = DoubleArray(simulations) { paths[it][step] }
            ForecastPoint(
                min(targetTime, now + step * stepMs),
                values.quantile(.05), values.quantile(.10), values.quantile(.25), values.quantile(.50),
                values.average(), values.quantile(.75), values.quantile(.90), values.quantile(.95),
                values.count { it > reserve }.toDouble() / simulations
            )
        }
        return ForecastResult(
            generatedAt = now,
            targetTime = targetTime,
            reservePercent = reserve,
            confidence = if (model.known) ForecastConfidence.MODERATE else ForecastConfidence.LOW,
            qualityMessage = if (model.known) {
                "Based on observed charging sessions with this charger type."
            } else {
                "Low-confidence charging estimate from limited live observations."
            },
            survivalProbability = finals.count { it > reserve }.toDouble() / simulations,
            expectedAtTarget = finals.average(),
            medianAtTarget = finals.quantile(.50),
            conservativeAtTarget = finals.quantile(.05),
            targetP05 = finals.quantile(.05),
            targetP10 = finals.quantile(.10),
            targetP25 = finals.quantile(.25),
            targetP75 = finals.quantile(.75),
            targetP90 = finals.quantile(.90),
            targetP95 = finals.quantile(.95),
            points = points,
            thresholds = emptyList(),
            baseDrainPercentPerHour = -rate,
            baseDrainMicroAhPerHour = null,
            baseDrainMilliWhPerHour = null,
            uncertaintyPercentPerHour = sigma,
            mainDriver = if ((current.temperatureDeciC ?: 0) >= 420 && model.hotTemperatureFactor == null) {
                "The phone is charging, but high-temperature charging behaviour is not yet well learned, so uncertainty is wider."
            } else {
                "The phone is currently charging using its observed piecewise charging curve."
            },
            sampleCount = observations.size,
            preliminary = !model.known
        )
    }

    fun scenarioForecasts(
        observations: List<BatteryObservation>,
        targetTime: Long,
        reserve: Int,
        scenarios: List<ScenarioDefinition>
    ): List<ScenarioForecast> = scenarios.map { scenario ->
        val forecast = forecast(observations, targetTime, reserve, scenario)
        ScenarioForecast(
            scenario,
            forecast.survivalProbability,
            forecast.medianAtTarget,
            forecast.confidence,
            forecast.mainDriver
        )
    }

    private data class ScenarioRate(val rate: Double, val note: String, val lowConfidence: Boolean)

    private fun scenarioRate(
        observations: List<BatteryObservation>,
        baseline: Double,
        scenario: ScenarioDefinition?
    ): ScenarioRate {
        if (scenario == null) return ScenarioRate(baseline, "", false)
        if (scenario.mix.isNotEmpty()) {
            val weighted = scenario.mix.filterValues { it > 0.0 }
            val totalWeight = weighted.values.sum()
            if (totalWeight > 0.0) {
                val components = weighted.map { (regime, weight) ->
                    val component = scenarioRate(
                        observations,
                        baseline,
                        scenario.copy(
                            id = "${scenario.id}-${regime.name.lowercase()}",
                            title = regime.name.lowercase().replace('_', ' '),
                            regime = regime,
                            powerSave = null,
                            mix = emptyMap()
                        )
                    )
                    component to (weight / totalWeight)
                }
                return ScenarioRate(
                    rate = components.sumOf { (component, weight) -> component.rate * weight },
                    note = if (components.any { it.first.lowConfidence }) {
                        "Low confidence: part of this custom mix has not been personally observed; closest live regimes were used and uncertainty widened."
                    } else {
                        "Custom mixed-use rate learned from this device's observed component regimes."
                    },
                    lowConfidence = components.any { it.first.lowConfidence }
                )
            }
        }

        val exactRates = when {
            scenario.powerSave == true -> observedRates(observations) { it.powerSaveEnabled }
            else -> observedRates(observations) { it.usageRegime == scenario.regime }
        }
        if (exactRates.size >= 3) {
            val learned = exactRates.median().coerceAtLeast(.01)
            return ScenarioRate(
                rate = monotonicScenarioFloor(scenario.regime, baseline, learned),
                note = "Learned from ${exactRates.size} personal ${scenario.title.lowercase()} observations.",
                lowConfidence = false
            )
        }

        val closest = when (scenario.regime) {
            UsageRegime.NAVIGATION, UsageRegime.HOTSPOT, UsageRegime.MEDIA_GAMING -> listOf(UsageRegime.HEAVY, UsageRegime.NORMAL, UsageRegime.LIGHT)
            UsageRegime.HEAVY -> listOf(UsageRegime.NORMAL, UsageRegime.LIGHT)
            UsageRegime.STANDBY -> listOf(UsageRegime.LIGHT, UsageRegime.NORMAL)
            UsageRegime.LIGHT -> listOf(UsageRegime.NORMAL, UsageRegime.STANDBY)
            else -> listOf(UsageRegime.NORMAL, UsageRegime.LIGHT, UsageRegime.STANDBY)
        }
        for (regime in closest) {
            val rates = observedRates(observations) { it.usageRegime == regime }
            if (rates.size >= 3) {
                val learned = monotonicScenarioFloor(scenario.regime, baseline, rates.median().coerceAtLeast(.01))
                return ScenarioRate(
                    learned,
                    "Low confidence: no personal ${scenario.title.lowercase()} history; the closest observed ${regime.name.lowercase().replace('_', ' ')} regime was used and uncertainty widened.",
                    true
                )
            }
        }
        return ScenarioRate(
            baseline,
            "Low confidence: no personal scenario history exists; the current live baseline was retained and uncertainty widened.",
            true
        )
    }

    private fun monotonicScenarioFloor(regime: UsageRegime, baseline: Double, learned: Double): Double = when (regime) {
        UsageRegime.HEAVY, UsageRegime.MEDIA_GAMING, UsageRegime.NAVIGATION, UsageRegime.HOTSPOT -> max(baseline, learned)
        UsageRegime.STANDBY -> min(baseline, learned)
        else -> learned
    }

    private fun observedRates(
        observations: List<BatteryObservation>,
        predicate: (BatteryObservation) -> Boolean
    ): List<Double> = pairRates(observations, predicate)

    private fun empiricalContextRates(observations: List<BatteryObservation>): List<Double> =
        pairRates(observations) { true }

    private fun pairRates(
        observations: List<BatteryObservation>,
        predicate: (BatteryObservation) -> Boolean
    ): List<Double> = buildList {
        val sorted = observations.sortedBy { it.timestamp }
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            if (a.isCharging || b.isCharging || !predicate(a) || !predicate(b)) continue
            val hours = (b.timestamp - a.timestamp) / 3_600_000.0
            if (hours !in (2.0 / 60.0)..1.5 || !ObservationCleaner.sameBootSession(a, b)) continue
            val drop = a.batteryPercent - b.batteryPercent
            if (drop >= -0.25) {
                val rate = max(0.0, drop / hours)
                if (rate <= 80.0) add(rate)
            }
        }
    }

    private fun learnedBatteryLevelFactor(
        observations: List<BatteryObservation>,
        battery: Double,
        baseline: Double
    ): Double {
        if (baseline <= 0.0) return 1.0
        val targetBin = (battery.toInt() / 10) * 10
        val rates = buildList {
            for (i in 1 until observations.size) {
                val a = observations[i - 1]
                val b = observations[i]
                val hours = (b.timestamp - a.timestamp) / 3_600_000.0
                val bin = (a.batteryPercent.toInt() / 10) * 10
                if (!a.isCharging && !b.isCharging && bin == targetBin &&
                    hours in (2.0 / 60.0)..1.5 && ObservationCleaner.sameBootSession(a, b) &&
                    b.batteryPercent <= a.batteryPercent
                ) {
                    val rate = (a.batteryPercent - b.batteryPercent) / hours
                    if (rate in 0.0..80.0) add(rate)
                }
            }
        }
        if (rates.size < 4) return 1.0
        val raw = rates.median() / baseline
        val weight = rates.size.toDouble() / (rates.size + 8.0)
        return (weight * raw + (1.0 - weight)).coerceIn(0.65, 1.45)
    }

    private fun bootstrapResidual(residuals: List<Double>, rng: Random): Double =
        if (residuals.isEmpty()) 0.0 else residuals[rng.nextInt(residuals.size)]

    private fun confidence(estimate: DrainEstimator.Estimate, observations: List<BatteryObservation>): ForecastConfidence {
        if (estimate.preliminary) return ForecastConfidence.PRELIMINARY
        val spanHours = observations.last().timestamp.minus(observations.first().timestamp) / 3_600_000.0
        return when {
            observations.size >= 80 && estimate.specificStateCount >= 12 && spanHours >= 48.0 -> ForecastConfidence.HIGH
            observations.size >= 24 && spanHours >= 6.0 -> ForecastConfidence.MODERATE
            else -> ForecastConfidence.LOW
        }
    }

    private fun qualityMessage(confidence: ForecastConfidence, preliminary: Boolean): String = when {
        preliminary -> "Preliminary live estimate from supported instantaneous sensors. Uncertainty is intentionally wide."
        confidence == ForecastConfidence.HIGH -> "High confidence from repeated live observations in similar device states."
        confidence == ForecastConfidence.MODERATE -> "Moderate confidence from recent and historical live battery behaviour."
        else -> "Low confidence while BatteryCast learns this device and usage pattern."
    }

    private fun driver(
        observations: List<BatteryObservation>,
        estimate: DrainEstimator.Estimate,
        scenarioNote: String
    ): String {
        if (scenarioNote.isNotBlank()) return scenarioNote
        val current = observations.last()
        val recent = estimate.horizonRates["60m"]
        val longer = estimate.horizonRates["3h"]
        val typical = estimate.typicalStatePercentPerHour ?: estimate.generalPercentPerHour
        val relativeChange = if (recent != null && typical != null && typical > .05) {
            ((recent / typical) - 1.0) * 100.0
        } else null
        return when {
            current.powerSaveEnabled -> "Power-saving mode is active and the forecast uses your observed power-saving behaviour where available."
            (current.thermalStatus ?: 0) >= 3 -> "Device thermal stress is elevated, so forecast uncertainty was widened."
            relativeChange != null && relativeChange >= 20.0 ->
                "Battery drain during the last hour was approximately ${relativeChange.toInt()}% faster than your learned similar-state pattern."
            relativeChange != null && relativeChange <= -20.0 ->
                "Battery drain during the last hour was approximately ${kotlin.math.abs(relativeChange).toInt()}% slower than your learned similar-state pattern."
            recent != null && longer != null && recent > longer * 1.2 -> "Battery drain during the last hour is materially faster than the recent three-hour pattern."
            recent != null && longer != null && recent < longer * .8 -> "Battery drain during the last hour is slower than the recent three-hour pattern."
            current.screenInteractive -> "Current interactive screen use is the main observed usage signal."
            else -> "Recent screen-off behaviour and the device's live drain history drive this estimate."
        }
    }

    private fun insufficient(now: Long, target: Long, reserve: Int, message: String) = ForecastResult(
        generatedAt = now,
        targetTime = target,
        reservePercent = reserve,
        confidence = ForecastConfidence.PRELIMINARY,
        qualityMessage = message,
        survivalProbability = null,
        expectedAtTarget = null,
        medianAtTarget = null,
        conservativeAtTarget = null,
        targetP05 = null,
        targetP10 = null,
        targetP25 = null,
        targetP75 = null,
        targetP90 = null,
        targetP95 = null,
        points = emptyList(),
        thresholds = emptyList(),
        baseDrainPercentPerHour = null,
        baseDrainMicroAhPerHour = null,
        baseDrainMilliWhPerHour = null,
        uncertaintyPercentPerHour = null,
        mainDriver = message,
        sampleCount = 0,
        preliminary = true
    )
}
