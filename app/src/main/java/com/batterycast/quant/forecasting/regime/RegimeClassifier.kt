package com.batterycast.quant.forecasting.regime

import com.batterycast.quant.forecasting.clean.BatterySegment
import com.batterycast.quant.forecasting.model.DayType
import com.batterycast.quant.forecasting.model.DrainStateKey
import com.batterycast.quant.forecasting.model.NetworkBucket
import com.batterycast.quant.forecasting.model.ThermalBucket
import com.batterycast.quant.forecasting.model.TimeBucket
import com.batterycast.quant.forecasting.model.UsageRegime
import com.batterycast.quant.forecasting.stats.RobustStats
import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.ThermalStatus
import com.batterycast.quant.telemetry.model.UsageCategory
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One interval between consecutive observations, with the rate measured across it.
 *
 * Intervals — not individual readings — are the unit the model learns from, because a rate needs
 * two endpoints.
 */
data class RegimeInterval(
    val from: BatteryObservation,
    val to: BatteryObservation,
    /** Percentage points per hour. Positive means charge was lost. */
    val ratePerHour: Double,
    val durationHours: Double,
    val regime: UsageRegime,
    val stateKey: DrainStateKey,
) {
    val midpointMs: Long get() = (from.timestampMs + to.timestampMs) / 2
    val meanPercent: Double get() = (from.batteryPercent + to.batteryPercent) / 2.0
}

/**
 * Device-relative bands separating light from heavy use.
 *
 * These are quantiles of *this phone's own* observed drain rates. Using absolute thresholds would
 * mean deciding in advance what "heavy use" costs on hardware we have never seen, which is
 * precisely the kind of invented universal constant this app avoids. Until enough intervals exist
 * the bands stay null and the classifier falls back to broader categories.
 */
data class RegimeThresholds(
    val screenOffMedian: Double?,
    val screenOffUpper: Double?,
    val screenOnP25: Double?,
    val screenOnP50: Double?,
    val screenOnP75: Double?,
    val screenOnP90: Double?,
    val chargeMedian: Double?,
    val intervalCount: Int,
) {
    val hasScreenOnBands: Boolean get() = screenOnP25 != null && screenOnP75 != null
    val hasScreenOffBands: Boolean get() = screenOffMedian != null

    companion object {
        /** Below this many intervals a quantile is not describing a distribution. */
        const val MIN_INTERVALS_FOR_BANDS = 12

        val EMPTY = RegimeThresholds(null, null, null, null, null, null, null, 0)

        fun learn(rates: List<RawInterval>): RegimeThresholds {
            val screenOff = rates.filter { !it.charging && !it.screenOn }.map { it.ratePerHour }
            val screenOn = rates.filter { !it.charging && it.screenOn }.map { it.ratePerHour }
            val charging = rates.filter { it.charging }.map { it.ratePerHour }

            fun quantileIfEnough(values: List<Double>, p: Double): Double? =
                if (values.size >= MIN_INTERVALS_FOR_BANDS) RobustStats.quantile(values, p) else null

            return RegimeThresholds(
                screenOffMedian = quantileIfEnough(screenOff, 0.5),
                screenOffUpper = quantileIfEnough(screenOff, 0.85),
                screenOnP25 = quantileIfEnough(screenOn, 0.25),
                screenOnP50 = quantileIfEnough(screenOn, 0.5),
                screenOnP75 = quantileIfEnough(screenOn, 0.75),
                screenOnP90 = quantileIfEnough(screenOn, 0.9),
                chargeMedian = quantileIfEnough(charging, 0.5),
                intervalCount = rates.size,
            )
        }
    }
}

/** Minimal shape needed to learn thresholds, before regimes have been assigned. */
data class RawInterval(
    val ratePerHour: Double,
    val screenOn: Boolean,
    val charging: Boolean,
)

/**
 * Assigns an interpretable regime to each measured interval.
 *
 * The classifier will not name a specific activity without corroboration. "Navigation-like" is
 * only assigned when usage access is granted *and* a maps-category app dominated the foreground
 * *and* the drain sits in the device's upper band; without all three the interval stays
 * [UsageRegime.HEAVY_USE] or broader. The app would rather say "heavy use" than assert something
 * about the user's activity it cannot support.
 */
class RegimeClassifier(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    /** Turns a segment into classified intervals, given learned bands. */
    fun classifySegment(segment: BatterySegment, thresholds: RegimeThresholds): List<RegimeInterval> {
        val observations = segment.observations
        if (observations.size < 2) return emptyList()

        return (0 until observations.size - 1).mapNotNull { index ->
            val from = observations[index]
            val to = observations[index + 1]
            val durationHours = (to.elapsedRealtimeMs - from.elapsedRealtimeMs) / 3_600_000.0
            if (durationHours <= 0.0) return@mapNotNull null

            // Percentage changes are quantised, so a very short interval carries almost no
            // information about the rate and would only add noise to the model.
            if (durationHours < MIN_INTERVAL_HOURS) return@mapNotNull null

            val delta = from.batteryPercent - to.batteryPercent
            val ratePerHour = delta / durationHours
            val charging = from.isCharging && to.isCharging

            val regime = classify(
                ratePerHour = if (charging) -ratePerHour else ratePerHour,
                observation = from,
                charging = charging,
                thresholds = thresholds,
            )

            RegimeInterval(
                from = from,
                to = to,
                ratePerHour = if (charging) -ratePerHour else ratePerHour,
                durationHours = durationHours,
                regime = regime,
                stateKey = stateKeyFor(from, regime),
            )
        }
    }

    /**
     * Chooses a regime for one interval.
     *
     * [ratePerHour] is positive in the direction the battery is moving: percentage points lost
     * per hour while discharging, gained per hour while charging.
     */
    fun classify(
        ratePerHour: Double,
        observation: BatteryObservation,
        charging: Boolean,
        thresholds: RegimeThresholds,
    ): UsageRegime {
        if (charging) return classifyCharging(ratePerHour, observation, thresholds)

        if (!observation.screenInteractive) {
            val median = thresholds.screenOffMedian
            val upper = thresholds.screenOffUpper
            return when {
                median == null || upper == null -> UsageRegime.STANDBY
                ratePerHour > upper -> UsageRegime.BACKGROUND_ACTIVE
                else -> UsageRegime.STANDBY
            }
        }

        if (!thresholds.hasScreenOnBands) {
            // Broad category: the screen was on and the battery was draining. True, and all the
            // evidence supports.
            return UsageRegime.INTERACTIVE
        }

        val p25 = thresholds.screenOnP25!!
        val p75 = thresholds.screenOnP75!!
        val p90 = thresholds.screenOnP90

        val broad = when {
            ratePerHour <= p25 -> UsageRegime.LIGHT_USE
            ratePerHour <= p75 -> UsageRegime.INTERACTIVE
            else -> UsageRegime.HEAVY_USE
        }

        if (broad != UsageRegime.HEAVY_USE) return broad

        // Only refine "heavy use" into a named activity when the platform actually told us what
        // kind of app was in the foreground, and only at the top of this device's own range.
        val inTopBand = p90 == null || ratePerHour >= p90
        if (!inTopBand) return broad

        return when (observation.dominantUsageCategory) {
            UsageCategory.GAME -> UsageRegime.GAMING_LIKE
            UsageCategory.VIDEO -> UsageRegime.MEDIA_LIKE
            UsageCategory.MAPS_NAVIGATION -> UsageRegime.NAVIGATION_LIKE
            else -> UsageRegime.HEAVY_USE
        }
    }

    private fun classifyCharging(
        ratePerHour: Double,
        observation: BatteryObservation,
        thresholds: RegimeThresholds,
    ): UsageRegime {
        // Above 80 % essentially every charger tapers, and the taper is visible in the data
        // regardless of how much history exists.
        if (observation.batteryPercent >= TAPER_START_PERCENT) return UsageRegime.CHARGING_TAPER

        val median = thresholds.chargeMedian ?: return UsageRegime.CHARGING
        return if (ratePerHour > median * FAST_CHARGE_MULTIPLE) UsageRegime.FAST_CHARGING else UsageRegime.CHARGING
    }

    fun stateKeyFor(observation: BatteryObservation, regime: UsageRegime): DrainStateKey {
        val zoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(observation.timestampMs), zoneId)
        val thermal = if (observation.thermalStatus == ThermalStatus.UNSUPPORTED) {
            ThermalBucket.forTemperature(observation.temperatureCelsius)
        } else {
            ThermalBucket.forStatus(observation.thermalStatus)
        }
        return DrainStateKey(
            regime = regime,
            timeBucket = TimeBucket.forHour(zoned.hour),
            dayType = if (zoned.dayOfWeek.value >= 6) DayType.WEEKEND else DayType.WEEKDAY,
            screenOn = observation.screenInteractive,
            powerSave = observation.powerSaveEnabled,
            thermal = thermal,
            network = NetworkBucket.forType(observation.networkType),
        )
    }

    /** Raw intervals used to learn the bands, before any regime is assigned. */
    fun rawIntervals(segments: List<BatterySegment>): List<RawInterval> =
        segments.flatMap { segment ->
            val observations = segment.observations
            (0 until observations.size - 1).mapNotNull { index ->
                val from = observations[index]
                val to = observations[index + 1]
                val durationHours = (to.elapsedRealtimeMs - from.elapsedRealtimeMs) / 3_600_000.0
                if (durationHours < MIN_INTERVAL_HOURS) return@mapNotNull null
                val charging = from.isCharging && to.isCharging
                val signedRate = (from.batteryPercent - to.batteryPercent) / durationHours
                RawInterval(
                    ratePerHour = if (charging) -signedRate else signedRate,
                    screenOn = from.screenInteractive,
                    charging = charging,
                )
            }
        }

    companion object {
        /** Five minutes: below this, one rounding step dominates the computed rate. */
        const val MIN_INTERVAL_HOURS = 5.0 / 60.0

        const val TAPER_START_PERCENT = 80.0

        /** How far above the device's median charge rate counts as fast charging. */
        const val FAST_CHARGE_MULTIPLE = 1.6
    }
}
