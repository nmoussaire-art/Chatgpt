package com.ontimequant.forecast

import com.ontimequant.model.CompletedTrip
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.DayType
import com.ontimequant.model.TimeBucket
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Walk-forward backtesting.
 *
 * For each completed trip, in chronological order:
 *  1. Fit the model on **strictly earlier** trips only.
 *  2. Produce the arrival distribution that would have been available before departure,
 *     conditioned on the routing estimate that was actually recorded at the time.
 *  3. Score the realised arrival against that distribution.
 *  4. Only then feed the trip into the model state.
 *
 * That ordering is the whole point: any metric computed from a model that has already
 * seen the trip is meaningless. It is enforced structurally here — the fit happens before
 * the observation is appended, so there is no way to leak.
 */
object Backtester {

    data class ForecastRecord(
        val tripId: String,
        val routeKey: String,
        val bucket: TimeBucket,
        val dayType: DayType,
        /** Predicted travel seconds after bias correction. */
        val predictedTravelSeconds: Double,
        val actualTravelSeconds: Double,
        /** Signed error in seconds: actual − predicted. Positive = the model was optimistic. */
        val errorSeconds: Double,
        val predictedOnTimeProbability: Double,
        val arrivedOnTime: Boolean,
        val q10Seconds: Double,
        val q25Seconds: Double,
        val q75Seconds: Double,
        val q90Seconds: Double,
        val q05Seconds: Double,
        val q95Seconds: Double,
        val trainingSize: Int,
    ) {
        fun coveredBy(lower: Double, upper: Double): Boolean =
            actualTravelSeconds in lower..upper
    }

    /**
     * Runs the walk-forward pass. Trips excluded from learning are still *scored* (they
     * are real outcomes) but are not fed back into the model, matching what the live app does.
     */
    fun run(
        trips: List<CompletedTrip>,
        priors: ModelPriors = ModelPriors.DEFAULT,
        seed: Long = 424242L,
        drawsPerTrip: Int = 1500,
    ): List<ForecastRecord> {
        val ordered = trips.sortedBy { it.actualDeparture }
        val history = ArrayList<TravelObservation>()
        val records = ArrayList<ForecastRecord>()

        for (trip in ordered) {
            if (trip.predictedTravelSeconds <= 0 || trip.actualTravelSeconds <= 0) continue

            val model = TravelModelFitter.fit(history, priors)
            val routeKey = trip.journeyId ?: "${trip.originId}->${trip.destinationId}"
            val context = ForecastContext(
                routeKey = routeKey,
                pairKey = "${trip.originId}->${trip.destinationId}",
                bucket = trip.timeBucket,
                dayType = trip.dayType,
                dayOfWeek = trip.dayOfWeek,
                regime = trip.trafficRegime,
                weatherSeverity = trip.weatherSeverity,
                eventPressure = trip.eventPressure ?: 0.0,
                routingIsLive = true,
                routingAgeMinutes = 0,
            )
            val bundle = model.sampler(
                context = context,
                baselineSeconds = trip.predictedTravelSeconds,
                staticSeconds = trip.predictedTravelSeconds * 0.8,
            )

            // Simulate the *travel duration* distribution only. Preparation/parking/walking
            // are scored separately by their own models; mixing them here would confound
            // routing accuracy with readiness behaviour.
            val rng = Xoshiro256(seed + trip.id.hashCode())
            val draws = DoubleArray(drawsPerTrip)
            for (i in draws.indices) {
                draws[i] = trip.predictedTravelSeconds * bundle.sampler.sampleMultiplier(rng) +
                    bundle.sampler.sampleIncidentSeconds(rng)
            }
            draws.sort()

            val predicted = trip.predictedTravelSeconds * exp(bundle.bias.value + bundle.weatherLogAdjustment)
            val pOnTime = predictedOnTime(trip, draws)

            records += ForecastRecord(
                tripId = trip.id,
                routeKey = routeKey,
                bucket = trip.timeBucket,
                dayType = trip.dayType,
                predictedTravelSeconds = predicted,
                actualTravelSeconds = trip.actualTravelSeconds,
                errorSeconds = trip.actualTravelSeconds - predicted,
                predictedOnTimeProbability = pOnTime,
                arrivedOnTime = trip.arrivedOnTime,
                q05Seconds = Stats.quantileSorted(draws, 0.05),
                q10Seconds = Stats.quantileSorted(draws, 0.10),
                q25Seconds = Stats.quantileSorted(draws, 0.25),
                q75Seconds = Stats.quantileSorted(draws, 0.75),
                q90Seconds = Stats.quantileSorted(draws, 0.90),
                q95Seconds = Stats.quantileSorted(draws, 0.95),
                trainingSize = history.size,
            )

            if (!trip.excludedFromLearning) {
                history += TravelObservation(
                    at = trip.actualDeparture,
                    routeKey = routeKey,
                    pairKey = "${trip.originId}->${trip.destinationId}",
                    bucket = trip.timeBucket,
                    dayType = trip.dayType,
                    dayOfWeek = trip.dayOfWeek,
                    regime = trip.trafficRegime,
                    predictedSeconds = trip.predictedTravelSeconds,
                    actualSeconds = trip.actualTravelSeconds,
                    weatherSeverity = trip.weatherSeverity,
                    eventPressure = trip.eventPressure,
                )
            }
        }
        return records
    }

    /**
     * Probability the model would have assigned to arriving on time, given the slack the
     * trip actually had between its departure and its deadline.
     */
    private fun predictedOnTime(trip: CompletedTrip, sortedDraws: DoubleArray): Double {
        val slack = (trip.requiredArrival.epochSecond - trip.actualDeparture.epochSecond).toDouble() -
            (trip.preparationSeconds ?: 0.0) - (trip.parkingSeconds ?: 0.0) - (trip.walkingSeconds ?: 0.0)
        if (sortedDraws.isEmpty()) return 0.5
        var below = 0
        for (d in sortedDraws) if (d <= slack) below++ else break
        return Stats.clamp01(below.toDouble() / sortedDraws.size)
    }
}

// ---------------------------------------------------------------------------
// Calibration & accuracy reporting
// ---------------------------------------------------------------------------

data class CalibrationBucket(
    val lowerProbability: Double,
    val upperProbability: Double,
    val count: Int,
    val meanPredicted: Double,
    val observedFrequency: Double,
) {
    val label: String get() = "${(lowerProbability * 100).roundToInt()}–${(upperProbability * 100).roundToInt()}%"
}

data class RoutePerformance(
    val routeKey: String,
    val count: Int,
    val medianAbsoluteErrorSeconds: Double,
    val medianBiasSeconds: Double,
)

data class AccuracyReport(
    val sampleSize: Int,
    val hasEnoughData: Boolean,
    val minimumSample: Int,
    val meanAbsoluteErrorSeconds: Double,
    val medianAbsoluteErrorSeconds: Double,
    val rmseSeconds: Double,
    val meanBiasSeconds: Double,
    val medianBiasSeconds: Double,
    val coverage50: Double,
    val coverage80: Double,
    val coverage90: Double,
    val brierScore: Double,
    val calibration: List<CalibrationBucket>,
    val medianIntervalWidthSeconds: Double,
    val routePerformance: List<RoutePerformance>,
    val bucketPerformance: Map<TimeBucket, Double>,
    val recentMedianAbsoluteErrorSeconds: Double?,
    val longRunMedianAbsoluteErrorSeconds: Double?,
    val recentIntervalWidthSeconds: Double?,
    val provenance: DataProvenance,
) {
    val onTimeRateAmongHighConfidence: Double?
        get() = calibration.lastOrNull { it.lowerProbability >= 0.85 && it.count >= 3 }?.observedFrequency
}

object CalibrationAnalyser {

    /** Below this many scored trips we show a warning instead of headline metrics. */
    const val MINIMUM_SAMPLE = 8

    private val BUCKET_EDGES = doubleArrayOf(0.0, 0.5, 0.7, 0.8, 0.9, 0.95, 1.0001)

    fun analyse(
        records: List<Backtester.ForecastRecord>,
        provenance: DataProvenance,
        recentWindow: Int = 10,
    ): AccuracyReport {
        if (records.isEmpty()) {
            return AccuracyReport(
                sampleSize = 0, hasEnoughData = false, minimumSample = MINIMUM_SAMPLE,
                meanAbsoluteErrorSeconds = 0.0, medianAbsoluteErrorSeconds = 0.0, rmseSeconds = 0.0,
                meanBiasSeconds = 0.0, medianBiasSeconds = 0.0,
                coverage50 = 0.0, coverage80 = 0.0, coverage90 = 0.0, brierScore = 0.0,
                calibration = emptyList(), medianIntervalWidthSeconds = 0.0,
                routePerformance = emptyList(), bucketPerformance = emptyMap(),
                recentMedianAbsoluteErrorSeconds = null, longRunMedianAbsoluteErrorSeconds = null,
                recentIntervalWidthSeconds = null, provenance = provenance,
            )
        }

        val errors = records.map { it.errorSeconds }
        val absErrors = errors.map { abs(it) }

        val cov50 = records.count { it.coveredBy(it.q25Seconds, it.q75Seconds) }.toDouble() / records.size
        val cov80 = records.count { it.coveredBy(it.q10Seconds, it.q90Seconds) }.toDouble() / records.size
        val cov90 = records.count { it.coveredBy(it.q05Seconds, it.q95Seconds) }.toDouble() / records.size

        val brier = Stats.brierScore(
            records.map { it.predictedOnTimeProbability },
            records.map { it.arrivedOnTime },
        )

        val buckets = ArrayList<CalibrationBucket>()
        for (i in 0 until BUCKET_EDGES.size - 1) {
            val lo = BUCKET_EDGES[i]
            val hi = BUCKET_EDGES[i + 1]
            val inBucket = records.filter { it.predictedOnTimeProbability >= lo && it.predictedOnTimeProbability < hi }
            if (inBucket.isEmpty()) continue
            buckets += CalibrationBucket(
                lowerProbability = lo,
                upperProbability = minOf(hi, 1.0),
                count = inBucket.size,
                meanPredicted = inBucket.map { it.predictedOnTimeProbability }.average(),
                observedFrequency = inBucket.count { it.arrivedOnTime }.toDouble() / inBucket.size,
            )
        }

        val routePerf = records.groupBy { it.routeKey }.map { (key, rs) ->
            RoutePerformance(
                routeKey = key,
                count = rs.size,
                medianAbsoluteErrorSeconds = Stats.median(rs.map { abs(it.errorSeconds) }),
                medianBiasSeconds = Stats.median(rs.map { it.errorSeconds }),
            )
        }.sortedByDescending { it.count }

        val bucketPerf = records.groupBy { it.bucket }
            .mapValues { (_, rs) -> Stats.median(rs.map { abs(it.errorSeconds) }) }

        val recent = records.takeLast(recentWindow)
        val longRun = records.dropLast(recentWindow)

        return AccuracyReport(
            sampleSize = records.size,
            hasEnoughData = records.size >= MINIMUM_SAMPLE,
            minimumSample = MINIMUM_SAMPLE,
            meanAbsoluteErrorSeconds = Stats.mae(errors),
            medianAbsoluteErrorSeconds = Stats.median(absErrors),
            rmseSeconds = Stats.rmse(errors),
            meanBiasSeconds = Stats.mean(errors),
            medianBiasSeconds = Stats.median(errors),
            coverage50 = cov50,
            coverage80 = cov80,
            coverage90 = cov90,
            brierScore = brier,
            calibration = buckets,
            medianIntervalWidthSeconds = Stats.median(records.map { it.q90Seconds - it.q10Seconds }),
            routePerformance = routePerf,
            bucketPerformance = bucketPerf,
            recentMedianAbsoluteErrorSeconds = if (recent.size >= 3) Stats.median(recent.map { abs(it.errorSeconds) }) else null,
            longRunMedianAbsoluteErrorSeconds = if (longRun.size >= 3) Stats.median(longRun.map { abs(it.errorSeconds) }) else null,
            recentIntervalWidthSeconds = if (recent.size >= 3) Stats.median(recent.map { it.q90Seconds - it.q10Seconds }) else null,
            provenance = provenance,
        )
    }
}
