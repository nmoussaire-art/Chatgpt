package com.batterycast.quant.forecast

import com.batterycast.quant.model.BatteryObservation
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

class DrainEstimator {
    data class Estimate(
        val percentPerHour: Double,
        val microAhPerHour: Double?,
        val milliWhPerHour: Double?,
        val uncertaintyPerHour: Double,
        val residuals: List<Double>,
        val sampleCount: Int,
        val specificStateCount: Int,
        val horizonRates: Map<String, Double>,
        val typicalStatePercentPerHour: Double?,
        val generalPercentPerHour: Double?,
        val preliminary: Boolean
    )

    fun estimate(observations: List<BatteryObservation>, now: Long): Estimate? {
        val segments = BatterySegmenter().detect(
            observations.filter { it.timestamp <= now }.sortedBy { it.timestamp }
        ).filter { it.type == BatterySegmentType.DISCHARGING }
        val discharge = segments.flatMap { it.observations }
        if (discharge.size < 2) return preliminaryFromCurrent(discharge.lastOrNull())

        val latestSegment = segments.lastOrNull()?.observations.orEmpty()
        val recent3h = latestSegment.filter { it.timestamp >= now - 3 * 60 * 60_000L }
        val allRates = segments.flatMap { roundedPercentRates(it.observations) }
        val recentRates = segments.flatMap { segment ->
            roundedPercentRates(segment.observations.filter { it.timestamp >= now - 3 * 60 * 60_000L })
        }
        val slope = robustSlope(recent3h)?.let { if (it < 0.0) -it else null }
        val recent = ewma(recentRates.takeLast(30), 0.35)
        val general = allRates.median().takeUnless { it.isNaN() }
        val current = discharge.last()
        val historicalSegments = segments.mapNotNull { segment ->
            segment.observations
                .filter { it.timestamp < now - 3 * 60 * 60_000L }
                .takeIf { it.size >= 2 }
                ?.let { BatterySegment(BatterySegmentType.DISCHARGING, it) }
        }
        val specific = ratesForState(historicalSegments, current).takeLast(80)
        val specificRate = specific.median().takeUnless { it.isNaN() }

        val liveCandidates = listOfNotNull(slope, recent).filter { it in 0.01..80.0 }
        val broadCandidates = listOfNotNull(general, specificRate).filter { it in 0.01..80.0 }
        if (liveCandidates.isEmpty() && broadCandidates.isEmpty()) return preliminaryFromCurrent(current)

        val live = liveCandidates.average().takeUnless { it.isNaN() } ?: broadCandidates.first()
        val broadPrior = specificRate ?: general ?: live
        val specificWeight = specific.size.toDouble() / (specific.size + 10.0)
        val liveEvidence = recentRates.size + if (slope != null) 2 else 0
        val liveWeight = liveEvidence.toDouble() / (liveEvidence + 6.0)
        val priorBlend = specificWeight * broadPrior + (1.0 - specificWeight) * (general ?: broadPrior)
        val shrunk = (liveWeight * live + (1.0 - liveWeight) * priorBlend).coerceIn(0.01, 80.0)

        val residualBase = (recentRates.ifEmpty { allRates }).map { it - shrunk }
        val robustSigma = mad(residualBase).takeUnless { it.isNaN() }?.times(1.4826) ?: 0.0
        val ewVol = ewma(residualBase.map(::abs).takeLast(40), 0.25) ?: 0.0
        val roundingFloor = if (recent3h.size < 4) 1.2 else 0.35
        val uncertainty = max(roundingFloor, max(robustSigma, ewVol * 1.25))

        val horizon = linkedMapOf<String, Double>()
        rateForWindow(segments, now, 30)?.let { horizon["30m"] = it }
        rateForWindow(segments, now, 60)?.let { horizon["60m"] = it }
        rateForWindow(segments, now, 180)?.let { horizon["3h"] = it }

        return Estimate(
            percentPerHour = shrunk,
            microAhPerHour = sensorDrainRate(segments) { it.chargeCounterMicroAh },
            milliWhPerHour = sensorDrainRate(segments) { it.energyNanoWh }?.div(1_000_000.0),
            uncertaintyPerHour = uncertainty,
            residuals = residualBase,
            sampleCount = discharge.size,
            specificStateCount = specific.size,
            horizonRates = horizon,
            typicalStatePercentPerHour = specificRate,
            generalPercentPerHour = general,
            preliminary = false
        )
    }

    private fun robustSlope(observations: List<BatteryObservation>): Double? {
        if (observations.size < 3) return null
        val firstTime = observations.first().timestamp
        return theilSenSlope(
            observations.map { (it.timestamp - firstTime) / 3_600_000.0 to it.batteryPercent }
        )
    }

    private fun rateForWindow(
        segments: List<BatterySegment>,
        now: Long,
        minutes: Int
    ): Double? {
        val from = now - minutes * 60_000L
        val runs = segments.mapNotNull { segment ->
            segment.observations.filter { it.timestamp >= from }.takeIf { it.size >= 2 }
        }
        val latest = runs.lastOrNull().orEmpty()
        val slopeRate = robustSlope(latest)?.let { -it }?.takeIf { it in 0.0..80.0 }
        val rates = runs.flatMap(::roundedPercentRates)
        return slopeRate ?: rates.median().takeUnless { it.isNaN() }
    }

    private fun roundedPercentRates(observations: List<BatteryObservation>): List<Double> {
        if (observations.size < 2) return emptyList()
        val rates = ArrayList<Double>()
        for (i in 0 until observations.lastIndex) {
            val a = observations[i]
            for (j in i + 1 until observations.size) {
                val b = observations[j]
                val hours = (b.timestamp - a.timestamp) / 3_600_000.0
                if (hours > 3.0) break
                if (hours < 2.0 / 60.0 || !ObservationCleaner.sameBootSession(a, b)) continue
                val drop = a.batteryPercent - b.batteryPercent
                if (drop >= 0.9 || j == observations.lastIndex) {
                    val rate = drop / hours
                    if (rate in 0.0..80.0) rates += rate
                    break
                }
                if (drop < -0.5) break
            }
        }
        if (rates.isNotEmpty()) return rates
        return adjacentRates(observations)
    }

    private fun adjacentRates(observations: List<BatteryObservation>): List<Double> = buildList {
        for (i in 1 until observations.size) {
            val a = observations[i - 1]
            val b = observations[i]
            val hours = (b.timestamp - a.timestamp) / 3_600_000.0
            if (hours in (2.0 / 60.0)..3.0 && ObservationCleaner.sameBootSession(a, b)) {
                val drop = a.batteryPercent - b.batteryPercent
                if (drop >= -0.25) {
                    val rate = max(0.0, drop / hours)
                    if (rate <= 80.0) add(rate)
                }
            }
        }
    }

    private fun ratesForState(
        segments: List<BatterySegment>,
        current: BatteryObservation
    ): List<Double> {
        val zone = ZoneId.systemDefault()
        val currentTime = Instant.ofEpochMilli(current.timestamp).atZone(zone)
        val exactPredicate: (BatteryObservation) -> Boolean = { observation ->
            val time = Instant.ofEpochMilli(observation.timestamp).atZone(zone)
            val hourDistance = minOf(abs(time.hour - currentTime.hour), 24 - abs(time.hour - currentTime.hour))
            observation.usageRegime == current.usageRegime &&
                observation.screenInteractive == current.screenInteractive &&
                observation.powerSaveEnabled == current.powerSaveEnabled &&
                observation.networkType == current.networkType &&
                time.dayOfWeek == currentTime.dayOfWeek && hourDistance <= 2
        }
        val exact = matchingRates(segments, exactPredicate)
        if (exact.size >= 4) return exact
        return matchingRates(segments) { observation ->
            observation.usageRegime == current.usageRegime &&
                observation.screenInteractive == current.screenInteractive &&
                observation.powerSaveEnabled == current.powerSaveEnabled
        }
    }

    private fun matchingRates(
        segments: List<BatterySegment>,
        predicate: (BatteryObservation) -> Boolean
    ): List<Double> = segments.flatMap { segment ->
        contiguousRuns(segment.observations, predicate).flatMap(::roundedPercentRates)
    }

    private fun contiguousRuns(
        observations: List<BatteryObservation>,
        predicate: (BatteryObservation) -> Boolean
    ): List<List<BatteryObservation>> {
        val runs = mutableListOf<List<BatteryObservation>>()
        var current = mutableListOf<BatteryObservation>()
        observations.forEach { observation ->
            if (predicate(observation)) {
                current += observation
            } else if (current.isNotEmpty()) {
                runs += current.toList()
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) runs += current.toList()
        return runs
    }

    private fun sensorDrainRate(
        segments: List<BatterySegment>,
        value: (BatteryObservation) -> Long?
    ): Double? {
        val rates = segments.flatMap { segment ->
            buildList {
                val observations = segment.observations
                for (i in 1 until observations.size) {
                    val a = observations[i - 1]
                    val b = observations[i]
                    val firstValue = value(a) ?: continue
                    val secondValue = value(b) ?: continue
                    val hours = (b.timestamp - a.timestamp) / 3_600_000.0
                    if (hours !in (2.0 / 60.0)..3.0) continue
                    val drop = firstValue - secondValue
                    if (drop >= 0L) add(drop / hours)
                }
            }
        }
        return rates.median().takeUnless { it.isNaN() || it <= 0.0 }
    }

    private fun preliminaryFromCurrent(observation: BatteryObservation?): Estimate? {
        if (observation == null || observation.isCharging) return null
        val current = observation.currentMicroA ?: observation.averageCurrentMicroA ?: return null
        val counter = observation.chargeCounterMicroAh ?: return null
        if (observation.batteryPercent <= 5 || counter <= 0 || current == 0L) return null
        val estimatedFullCapacity = counter / (observation.batteryPercent / 100.0)
        val rate = abs(current.toDouble()) / estimatedFullCapacity * 100.0
        if (rate !in 0.05..80.0) return null
        return Estimate(
            percentPerHour = rate,
            microAhPerHour = abs(current.toDouble()),
            milliWhPerHour = observation.voltageMv?.let { abs(current.toDouble()) * it / 1_000_000.0 },
            uncertaintyPerHour = max(1.5, rate * 0.65),
            residuals = emptyList(),
            sampleCount = 1,
            specificStateCount = 0,
            horizonRates = emptyMap(),
            typicalStatePercentPerHour = null,
            generalPercentPerHour = null,
            preliminary = true
        )
    }
}
