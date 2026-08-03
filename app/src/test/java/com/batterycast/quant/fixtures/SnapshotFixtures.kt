package com.batterycast.quant.fixtures

import com.batterycast.quant.forecasting.model.ChargeCellKey
import com.batterycast.quant.forecasting.model.DataMaturity
import com.batterycast.quant.forecasting.model.ForecastModelSnapshot
import com.batterycast.quant.forecasting.model.RateDistribution
import com.batterycast.quant.forecasting.model.RegimeTransitionMatrix
import com.batterycast.quant.forecasting.model.SocBucket
import com.batterycast.quant.forecasting.model.SocDecile
import com.batterycast.quant.forecasting.model.ThermalBucket
import com.batterycast.quant.forecasting.model.UsageRegime
import com.batterycast.quant.forecasting.shrinkage.SupportLevel
import com.batterycast.quant.forecasting.uncertainty.ResidualModel
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.SensorCapabilities

/**
 * Deterministic model snapshots for testing the simulator.
 *
 * Test source only. Nothing in `src/main` may construct a snapshot from anything but real learned
 * cells; the production-purity gates enforce that mechanically.
 */
object SnapshotFixtures {

    fun rate(mean: Double, variance: Double = 1.0, samples: Double = 40.0) = RateDistribution(
        mean = mean,
        variance = variance,
        effectiveSamples = samples,
        support = when {
            samples >= 20 -> SupportLevel.STRONG
            samples >= 6 -> SupportLevel.MODERATE
            samples > 0 -> SupportLevel.SPARSE
            else -> SupportLevel.NONE
        },
    )

    fun snapshot(
        globalDrainPerHour: Double = 8.0,
        variance: Double = 1.0,
        samples: Double = 40.0,
        drainOverrides: Map<UsageRegime, RateDistribution> = emptyMap(),
        chargeRatePerHour: Double? = 40.0,
        socCurve: Map<SocDecile, Double> = emptyMap(),
        thermalMultipliers: Map<ThermalBucket, Double> = emptyMap(),
        powerSaveMultiplier: Double? = null,
        residualModel: ResidualModel = ResidualModel.EMPTY,
        maturity: DataMaturity = DataMaturity.PERSONALISED,
        transitionCounts: Map<Pair<UsageRegime, UsageRegime>, Double> = emptyMap(),
    ): ForecastModelSnapshot {
        val baseDrain = UsageRegime.dischargeRegimes.associateWith { regime ->
            val multiplier = when (regime) {
                UsageRegime.STANDBY -> 0.15
                UsageRegime.BACKGROUND_ACTIVE -> 0.4
                UsageRegime.LIGHT_USE -> 0.8
                UsageRegime.INTERACTIVE -> 1.0
                UsageRegime.HEAVY_USE -> 1.9
                UsageRegime.MEDIA_LIKE -> 2.1
                UsageRegime.GAMING_LIKE -> 3.0
                UsageRegime.NAVIGATION_LIKE -> 2.4
                UsageRegime.UNCLASSIFIED_DISCHARGE -> 1.0
                else -> 1.0
            }
            rate(globalDrainPerHour * multiplier, variance, samples)
        } + drainOverrides

        val chargeRates = if (chargeRatePerHour == null) {
            emptyMap()
        } else {
            buildMap {
                PlugType.entries.forEach { plug ->
                    SocBucket.entries.forEach { bucket ->
                        // A real charger tapers sharply above 80 %; the piecewise buckets are how
                        // the model represents that.
                        val taper = when (bucket) {
                            SocBucket.VERY_LOW, SocBucket.LOW -> 1.0
                            SocBucket.MID -> 0.85
                            SocBucket.HIGH -> 0.45
                            SocBucket.VERY_HIGH -> 0.2
                        }
                        val plugFactor = when (plug) {
                            PlugType.AC -> 1.0
                            PlugType.USB -> 0.4
                            PlugType.WIRELESS -> 0.6
                            else -> 0.8
                        }
                        put(
                            ChargeCellKey(plug, bucket),
                            rate(chargeRatePerHour * taper * plugFactor, variance, samples),
                        )
                    }
                }
            }
        }

        return ForecastModelSnapshot(
            drainByRegime = baseDrain,
            chargeRates = chargeRates,
            transitions = RegimeTransitionMatrix.of(transitionCounts, UsageRegime.dischargeRegimes),
            socCurve = socCurve,
            thermalMultipliers = thermalMultipliers,
            powerSaveMultiplier = powerSaveMultiplier,
            residualModel = residualModel,
            capabilities = SensorCapabilities(),
            maturity = maturity,
            globalDrain = rate(globalDrainPerHour, variance, samples),
            globalChargeRate = chargeRatePerHour?.let { rate(it, variance, samples) },
            estimatedFullCapacityMilliAh = 4000.0,
        )
    }
}
