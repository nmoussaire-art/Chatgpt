package com.batterycast.quant.forecasting

import com.batterycast.quant.core.database.dao.ModelCellDao
import com.batterycast.quant.core.database.dao.RegimeTransitionDao
import com.batterycast.quant.core.database.dao.ResidualDao
import com.batterycast.quant.di.DefaultDispatcher
import com.batterycast.quant.forecasting.clean.CleanedHistory
import com.batterycast.quant.forecasting.clean.ObservationCleaner
import com.batterycast.quant.forecasting.drain.DrainRateEstimator
import com.batterycast.quant.forecasting.drain.EstimateConfidence
import com.batterycast.quant.forecasting.drain.HorizonEstimates
import com.batterycast.quant.forecasting.ewma.EwmaCell
import com.batterycast.quant.forecasting.ewma.EwmaModel
import com.batterycast.quant.forecasting.ewma.toDomain
import com.batterycast.quant.forecasting.explain.ChangeExplainer
import com.batterycast.quant.forecasting.model.BatteryForecast
import com.batterycast.quant.forecasting.model.ChargeCellKey
import com.batterycast.quant.forecasting.model.DataMaturity
import com.batterycast.quant.forecasting.model.DataQualityReport
import com.batterycast.quant.forecasting.model.DrainMetric
import com.batterycast.quant.forecasting.model.DrainSummary
import com.batterycast.quant.forecasting.model.ForecastModelSnapshot
import com.batterycast.quant.forecasting.model.HourlySurvival
import com.batterycast.quant.forecasting.model.ModelNamespace
import com.batterycast.quant.forecasting.model.RateDistribution
import com.batterycast.quant.forecasting.model.RegimeTransitionMatrix
import com.batterycast.quant.forecasting.model.SocBucket
import com.batterycast.quant.forecasting.model.SocDecile
import com.batterycast.quant.forecasting.model.TargetOutcome
import com.batterycast.quant.forecasting.model.ThermalBucket
import com.batterycast.quant.forecasting.model.ThresholdOutcome
import com.batterycast.quant.forecasting.model.UsageRegime
import com.batterycast.quant.forecasting.regime.RegimeClassifier
import com.batterycast.quant.forecasting.regime.RegimeThresholds
import com.batterycast.quant.forecasting.shrinkage.BayesianShrinkage
import com.batterycast.quant.forecasting.shrinkage.ShrunkEstimate
import com.batterycast.quant.forecasting.shrinkage.SupportLevel
import com.batterycast.quant.forecasting.sim.MonteCarloSimulator
import com.batterycast.quant.forecasting.sim.RegimeSchedule
import com.batterycast.quant.forecasting.sim.SimulationRequest
import com.batterycast.quant.forecasting.sim.SimulationResult
import com.batterycast.quant.forecasting.uncertainty.ResidualModel
import com.batterycast.quant.forecasting.uncertainty.TimedResidual
import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.SensorCapabilities
import com.batterycast.quant.telemetry.model.ThermalStatus
import com.batterycast.quant.telemetry.repository.ObservationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** What the caller wants forecast. */
data class ForecastRequest(
    val horizonMs: Long = DEFAULT_HORIZON_MS,
    val reservePercent: Double = 10.0,
    val targetMs: Long? = null,
    val targetLabel: String? = null,
    val paths: Int = SimulationRequest.DEFAULT_PATHS,
    val regimeOverride: RegimeSchedule? = null,
    val drainMultiplier: Double = 1.0,
    val chargingStartsAtMs: Long? = null,
    val chargingEndsAtMs: Long? = null,
    val chargingPlugType: PlugType = PlugType.AC,
) {
    companion object {
        /** Long enough to cover "will it last until bedtime" from any time of day. */
        const val DEFAULT_HORIZON_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * Turns the observation history and the learned model into a forecast.
 *
 * The engine's job is to be honest about how much it knows. It computes a [DataMaturity] first
 * and lets that gate what the rest of the app is allowed to claim: with nine minutes of history
 * it produces a preliminary estimate labelled as such, and with three weeks it produces a
 * personalised one — but it never produces the second while holding the evidence for the first.
 */
@Singleton
class ForecastEngine @Inject constructor(
    private val observationRepository: ObservationRepository,
    private val modelCellDao: ModelCellDao,
    private val regimeTransitionDao: RegimeTransitionDao,
    private val residualDao: ResidualDao,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {
    private val simulator = MonteCarloSimulator()
    private val classifier = RegimeClassifier()
    private val explainer = ChangeExplainer()

    /**
     * Produces a forecast, or null when the device has not yet produced a single reading.
     *
     * Null is a real answer here: it is what the "collecting live battery behaviour" state is
     * built on, and it is preferable to a plausible-looking curve drawn from nothing.
     */
    suspend fun forecast(
        request: ForecastRequest = ForecastRequest(),
        nowMs: Long = System.currentTimeMillis(),
    ): BatteryForecast? = withContext(defaultDispatcher) {
        val context = buildContext(nowMs) ?: return@withContext null
        buildForecast(context, request, nowMs)
    }

    /**
     * Loads history and model state once so several forecasts — baseline plus scenarios, or the
     * binary search inside the charge planner — can share it without re-reading the database.
     */
    suspend fun buildContext(nowMs: Long = System.currentTimeMillis()): ForecastContext? =
        withContext(defaultDispatcher) {
            val latest = observationRepository.latest() ?: return@withContext null
            val history = observationRepository.since(nowMs - HISTORY_WINDOW_MS)
            val cleaned = ObservationCleaner.clean(history)
            val capabilities = observationRepository.capabilities()

            val estimates = DrainRateEstimator.estimate(cleaned.segments, capabilities, nowMs)
            val thresholds = RegimeThresholds.learn(classifier.rawIntervals(cleaned.segments))
            val currentRegime = currentRegime(latest, estimates, thresholds)

            val cells = modelCellDao.all().associate { entity ->
                Triple(entity.namespace, entity.cellKey, entity.metric) to entity.toDomain()
            }
            val transitionCounts = regimeTransitionDao.all().associate { entity ->
                (entity.fromRegime to entity.toRegime) to EwmaModel.decayWeight(
                    entity.decayedCount,
                    entity.lastUpdateMs,
                    nowMs,
                    ModelUpdater.TRANSITION_HALF_LIFE_MS,
                )
            }
            val residuals = residualDao.mostRecent(RESIDUAL_WINDOW).map {
                TimedResidual(it.residualPercentPerHour, it.createdAtMs)
            }

            val maturity = assessMaturity(cleaned, estimates, cells, nowMs)
            val snapshot = buildSnapshot(
                latest = latest,
                cells = cells,
                transitionCounts = transitionCounts,
                residuals = ResidualModel.from(residuals, nowMs),
                capabilities = capabilities,
                estimates = estimates,
                currentRegime = currentRegime,
                maturity = maturity,
                nowMs = nowMs,
            )

            ForecastContext(
                latest = latest,
                cleaned = cleaned,
                capabilities = capabilities,
                estimates = estimates,
                regimeThresholds = thresholds,
                currentRegime = currentRegime,
                snapshot = snapshot,
                maturity = maturity,
                nowMs = nowMs,
            )
        }

    /** Runs one simulation against an already-built context. */
    fun simulate(context: ForecastContext, request: ForecastRequest): SimulationResult {
        val simulationRequest = SimulationRequest(
            startPercent = context.latest.batteryPercent,
            startMs = context.nowMs,
            horizonMs = request.horizonMs,
            isCharging = context.latest.isCharging,
            plugType = context.latest.plugType,
            initialRegime = context.currentRegime,
            thermalBucket = context.thermalBucket,
            powerSaveEnabled = context.latest.powerSaveEnabled,
            regimeOverride = request.regimeOverride,
            drainMultiplier = request.drainMultiplier,
            chargingStartsAtMs = request.chargingStartsAtMs,
            chargingEndsAtMs = request.chargingEndsAtMs,
            chargingPlugType = request.chargingPlugType,
            paths = request.paths,
        )
        return simulator.simulate(simulationRequest, context.snapshot)
    }

    fun buildForecast(
        context: ForecastContext,
        request: ForecastRequest,
        nowMs: Long,
    ): BatteryForecast {
        val result = simulate(context, request)

        val thresholdOutcomes = MonteCarloSimulator.THRESHOLDS.map { threshold ->
            ThresholdOutcome(
                thresholdPercent = threshold,
                medianMs = result.medianTimeToThresholdMs(threshold),
                p10Ms = result.timeToThresholdQuantileMs(threshold, 0.10),
                p90Ms = result.timeToThresholdQuantileMs(threshold, 0.90),
                probabilityOfReaching = result.probabilityOfReaching(threshold) ?: 0.0,
            )
        }

        val target = request.targetMs?.let { targetMs ->
            val point = result.pointAt(targetMs) ?: return@let null
            val survival = result.probabilityAbove(targetMs, request.reservePercent) ?: return@let null
            TargetOutcome(
                label = request.targetLabel.orEmpty(),
                targetMs = targetMs,
                reservePercent = request.reservePercent,
                survivalProbability = survival,
                median = point.median,
                p10 = point.p10,
                p25 = point.p25,
                p75 = point.p75,
                p90 = point.p90,
                conservative = point.p10,
            )
        }

        val hourly = buildList {
            var atMs = nowMs + 60 * 60 * 1000L
            while (atMs <= nowMs + request.horizonMs) {
                val probability = result.probabilityAbove(atMs, request.reservePercent)
                val point = result.pointAt(atMs)
                if (probability != null && point != null) {
                    add(HourlySurvival(atMs, probability, point.median))
                }
                atMs += 60 * 60 * 1000L
            }
        }

        return BatteryForecast(
            generatedAtMs = nowMs,
            observedAtMs = context.latest.timestampMs,
            currentPercent = context.latest.batteryPercent,
            isCharging = context.latest.isCharging,
            plugType = context.latest.plugType,
            currentRegime = context.currentRegime,
            maturity = context.maturity,
            horizonMs = request.horizonMs,
            curve = result.points,
            thresholds = thresholdOutcomes,
            target = target,
            hourlySurvival = hourly,
            drain = buildDrainSummary(context),
            dataQuality = buildDataQuality(context, nowMs),
            drivers = explainer.explain(context),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Model assembly
    // ---------------------------------------------------------------------------------------

    private fun buildSnapshot(
        latest: BatteryObservation,
        cells: Map<Triple<ModelNamespace, String, DrainMetric>, EwmaCell>,
        transitionCounts: Map<Pair<UsageRegime, UsageRegime>, Double>,
        residuals: ResidualModel,
        capabilities: SensorCapabilities,
        estimates: HorizonEstimates,
        currentRegime: UsageRegime,
        maturity: DataMaturity,
        nowMs: Long,
    ): ForecastModelSnapshot {
        fun cell(namespace: ModelNamespace, key: String, metric: DrainMetric = DrainMetric.PERCENT_PER_HOUR) =
            cells[Triple(namespace, key, metric)]

        val globalDrainEstimate = BayesianShrinkage.shrink(
            listOf(cell(ModelNamespace.DRAIN, "global")),
            nowMs,
        )

        val drainByRegime = UsageRegime.dischargeRegimes.mapNotNull { regime ->
            val key = classifier.stateKeyFor(latest, regime)
            val hierarchy = key.hierarchy().map { cell(ModelNamespace.DRAIN, it) }
            val shrunk = BayesianShrinkage.shrink(hierarchy, nowMs) ?: return@mapNotNull null
            regime to RateDistribution.from(shrunk)
        }.toMap().toMutableMap()

        // The live estimate is folded into the regime the phone is in right now. This is the
        // bridge between "what the model learned" and "what is happening this minute", and it is
        // the only reason a forecast is possible at all on the first day.
        val liveEstimate = estimates.chargeBasedPercentPerHour
            ?: estimates.blendedPercentPerHour
            ?: estimates.instantaneous
        if (liveEstimate != null) {
            val liveWeight = when (liveEstimate.confidence) {
                EstimateConfidence.GOOD -> LIVE_WEIGHT_GOOD
                EstimateConfidence.MODERATE -> LIVE_WEIGHT_MODERATE
                EstimateConfidence.LOW -> LIVE_WEIGHT_LOW
            }
            val liveVariance = (liveEstimate.residualScale * liveEstimate.residualScale)
                .coerceAtLeast(MIN_LIVE_VARIANCE)
            val blended = BayesianShrinkage.blendWithLive(
                model = drainByRegime[currentRegime]?.let {
                    ShrunkEstimate(it.mean, it.variance, it.effectiveSamples, 1.0, 0, 1)
                },
                liveMean = liveEstimate.ratePerHour,
                liveVariance = liveVariance,
                liveWeight = liveWeight,
            )
            if (blended != null) drainByRegime[currentRegime] = RateDistribution.from(blended)
        }

        val chargeRates = buildMap {
            PlugType.entries.forEach { plug ->
                SocBucket.entries.forEach { bucket ->
                    val key = com.batterycast.quant.forecasting.model.ChargeStateKey(
                        plugType = plug,
                        socBucket = bucket,
                        thermal = ThermalBucket.forStatus(latest.thermalStatus),
                    )
                    val hierarchy = key.hierarchy().map { cell(ModelNamespace.CHARGE, it) }
                    val shrunk = BayesianShrinkage.shrink(hierarchy, nowMs) ?: return@forEach
                    put(ChargeCellKey(plug, bucket), RateDistribution.from(shrunk))
                }
            }
        }

        val socCurve = SocDecile.entries.mapNotNull { decile ->
            val shrunk = BayesianShrinkage.shrink(
                listOf(cell(ModelNamespace.SOC_CURVE, decile.name)),
                nowMs,
            ) ?: return@mapNotNull null
            // A multiplier learned from few observations is pulled towards 1, so a single odd
            // reading in the 30–40 % band cannot bend the whole curve.
            val weight = shrunk.effectiveSamples / (shrunk.effectiveSamples + CURVE_PRIOR_STRENGTH)
            decile to (weight * shrunk.mean + (1 - weight) * 1.0).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        }.toMap()

        val thermalMultipliers = ThermalBucket.entries.mapNotNull { bucket ->
            val shrunk = BayesianShrinkage.shrink(
                listOf(cell(ModelNamespace.THERMAL, bucket.name)),
                nowMs,
            ) ?: return@mapNotNull null
            val weight = shrunk.effectiveSamples / (shrunk.effectiveSamples + CURVE_PRIOR_STRENGTH)
            bucket to (weight * shrunk.mean + (1 - weight) * 1.0).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        }.toMap()

        val powerSaveMultiplier = run {
            val on = cell(ModelNamespace.POWER_SAVE, "true")
            val off = cell(ModelNamespace.POWER_SAVE, "false")
            if (on == null || off == null || off.mean <= 0.0) return@run null
            val onWeight = EwmaModel.effectiveWeight(on, nowMs)
            val offWeight = EwmaModel.effectiveWeight(off, nowMs)
            // Both sides need real evidence before the app claims power saving changes anything
            // on this device — the effect varies enormously between manufacturers.
            if (onWeight < MIN_POWER_SAVE_EVIDENCE || offWeight < MIN_POWER_SAVE_EVIDENCE) return@run null
            (on.mean / off.mean).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        }

        return ForecastModelSnapshot(
            drainByRegime = drainByRegime,
            chargeRates = chargeRates,
            transitions = RegimeTransitionMatrix.of(transitionCounts, UsageRegime.dischargeRegimes),
            socCurve = socCurve,
            thermalMultipliers = thermalMultipliers,
            powerSaveMultiplier = powerSaveMultiplier,
            residualModel = residuals,
            capabilities = capabilities,
            maturity = maturity,
            globalDrain = globalDrainEstimate?.let(RateDistribution::from)
                ?: liveEstimate?.let {
                    RateDistribution(
                        mean = it.ratePerHour,
                        variance = (it.residualScale * it.residualScale).coerceAtLeast(MIN_LIVE_VARIANCE),
                        effectiveSamples = 1.0,
                        support = SupportLevel.SPARSE,
                    )
                },
            globalChargeRate = BayesianShrinkage.shrink(
                listOf(cell(ModelNamespace.CHARGE, "global")),
                nowMs,
            )?.let(RateDistribution::from),
            estimatedFullCapacityMilliAh = estimates.estimatedFullCapacityMilliAh,
        )
    }

    /**
     * Decides what the app is entitled to claim.
     *
     * Each threshold is about evidence, not elapsed time: a phone that sat untouched for six
     * hours has plenty of history and almost no information, and gets treated accordingly.
     */
    fun assessMaturity(
        cleaned: CleanedHistory,
        estimates: HorizonEstimates,
        cells: Map<Triple<ModelNamespace, String, DrainMetric>, EwmaCell>,
        nowMs: Long,
    ): DataMaturity {
        val observations = cleaned.observations
        if (observations.size < 2) {
            return if (estimates.instantaneous != null) DataMaturity.PRELIMINARY else DataMaturity.COLLECTING
        }

        val dischargeObserved = cleaned.dischargeSegments.sumOf { it.percentDrop.coerceAtLeast(0.0) }
        val dischargeHours = cleaned.dischargeSegments.sumOf { it.durationHours }
        val spanHours = (observations.last().timestampMs - observations.first().timestampMs) / 3_600_000.0

        val hasMeasurableChange = dischargeObserved >= MIN_PERCENT_FOR_BASIC ||
            estimates.chargeBasedPercentPerHour != null

        if (!hasMeasurableChange) {
            return if (estimates.instantaneous != null || estimates.blendedPercentPerHour != null) {
                DataMaturity.PRELIMINARY
            } else {
                DataMaturity.COLLECTING
            }
        }

        if (dischargeHours < MIN_HOURS_FOR_BASIC) return DataMaturity.PRELIMINARY

        val globalCell = cells[Triple(ModelNamespace.DRAIN, "global", DrainMetric.PERCENT_PER_HOUR)]
        val effectiveSamples = globalCell?.let { EwmaModel.effectiveWeight(it, nowMs) } ?: 0.0
        val distinctRegimeCells = cells.keys.count {
            it.first == ModelNamespace.DRAIN && it.second.startsWith("r=")
        }

        return when {
            spanHours >= MATURE_SPAN_HOURS && effectiveSamples >= MATURE_SAMPLES ->
                DataMaturity.MATURE

            spanHours >= PERSONALISED_SPAN_HOURS &&
                effectiveSamples >= PERSONALISED_SAMPLES &&
                distinctRegimeCells >= MIN_REGIME_CELLS ->
                DataMaturity.PERSONALISED

            else -> DataMaturity.BASIC
        }
    }

    private fun currentRegime(
        latest: BatteryObservation,
        estimates: HorizonEstimates,
        thresholds: RegimeThresholds,
    ): UsageRegime {
        val rate = estimates.byHorizon.values.firstOrNull()?.ratePerHour
            ?: estimates.blendedPercentPerHour?.ratePerHour
            ?: estimates.instantaneous?.ratePerHour
            ?: 0.0
        return classifier.classify(rate, latest, latest.isCharging, thresholds)
    }

    private fun buildDrainSummary(context: ForecastContext): DrainSummary {
        val estimates = context.estimates
        val best = estimates.chargeBasedPercentPerHour ?: estimates.blendedPercentPerHour
        val baseline = context.snapshot.drainByRegime[context.currentRegime]?.mean

        val milliAmps = context.latest.currentMicroA?.let { kotlin.math.abs(it) / 1000.0 }
        val watts = milliAmps?.let { mA ->
            context.latest.voltageVolts?.let { volts -> mA / 1000.0 * volts }
        }

        return DrainSummary(
            currentPercentPerHour = best?.ratePerHour,
            method = best?.method?.displayName,
            byHorizon = estimates.byHorizon.entries.associate { it.key.displayName to it.value.ratePerHour },
            milliAmpsNow = milliAmps,
            wattsNow = watts,
            baselinePercentPerHour = baseline,
            estimate = best,
        )
    }

    private fun buildDataQuality(context: ForecastContext, nowMs: Long): DataQualityReport {
        val cleaned = context.cleaned
        return DataQualityReport(
            observationCount = cleaned.observations.size + cleaned.rejected.size,
            usableObservationCount = cleaned.observations.size,
            observedSpanHours = cleaned.observedSpanHours,
            totalPercentObserved = cleaned.dischargeSegments.sumOf { it.percentDrop.coerceAtLeast(0.0) },
            capabilities = context.capabilities,
            unsupportedFields = context.capabilities.unsupportedFields(),
            rejectedCount = cleaned.rejected.size,
            newestObservationAgeMs = nowMs - context.latest.timestampMs,
            modelEffectiveSamples = context.snapshot.globalDrain?.effectiveSamples ?: 0.0,
        )
    }

    companion object {
        /** How much history the engine reads. Longer than the model's half-life, on purpose. */
        const val HISTORY_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

        const val RESIDUAL_WINDOW = 400

        /** Effective observations a live estimate is worth, by how well supported it is. */
        const val LIVE_WEIGHT_GOOD = 6.0
        const val LIVE_WEIGHT_MODERATE = 3.0
        const val LIVE_WEIGHT_LOW = 1.0

        /** Floor on live variance: no estimate from a handful of readings is that precise. */
        const val MIN_LIVE_VARIANCE = 0.25

        const val CURVE_PRIOR_STRENGTH = 8.0
        const val MIN_MULTIPLIER = 0.25
        const val MAX_MULTIPLIER = 4.0
        const val MIN_POWER_SAVE_EVIDENCE = 3.0

        const val MIN_PERCENT_FOR_BASIC = 2.0
        const val MIN_HOURS_FOR_BASIC = 0.75

        const val PERSONALISED_SPAN_HOURS = 24.0
        const val PERSONALISED_SAMPLES = 15.0
        const val MIN_REGIME_CELLS = 3

        const val MATURE_SPAN_HOURS = 7 * 24.0
        const val MATURE_SAMPLES = 60.0
    }
}

/** Shared inputs for one or more forecasts taken at the same instant. */
data class ForecastContext(
    val latest: BatteryObservation,
    val cleaned: CleanedHistory,
    val capabilities: SensorCapabilities,
    val estimates: HorizonEstimates,
    val regimeThresholds: RegimeThresholds,
    val currentRegime: UsageRegime,
    val snapshot: ForecastModelSnapshot,
    val maturity: DataMaturity,
    val nowMs: Long,
) {
    val thermalBucket: ThermalBucket
        get() = if (latest.thermalStatus == ThermalStatus.UNSUPPORTED) {
            ThermalBucket.forTemperature(latest.temperatureCelsius)
        } else {
            ThermalBucket.forStatus(latest.thermalStatus)
        }

    /** Battery percentage rounded the way the device itself reports it. */
    val displayPercent: Int get() = latest.batteryPercent.roundToInt()
}
