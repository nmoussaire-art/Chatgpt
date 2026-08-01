package com.ontimequant.forecast

import com.ontimequant.model.RiskLevel
import java.time.Instant
import kotlin.math.max

/**
 * A continuous, one-minute-resolution view of the routing provider's discrete answers.
 *
 * The API is queried every N minutes; the solver needs a duration for any minute in the
 * window. Between query points we interpolate linearly on duration, and outside the
 * queried range we hold the nearest endpoint flat rather than extrapolating a trend we
 * did not measure. Extrapolating traffic is exactly the kind of invented precision this
 * product is built to avoid; instead, [outsideQueriedRange] is reported so the engine
 * can widen uncertainty.
 */
class TravelBaseline(
    private val points: List<Point>,
) {
    data class Point(
        val epochSecond: Long,
        val durationSeconds: Double,
        val staticSeconds: Double,
        val congestionRatio: Double,
    )

    val isEmpty: Boolean get() = points.isEmpty()
    val first: Point? get() = points.firstOrNull()
    val last: Point? get() = points.lastOrNull()

    fun durationAt(departure: Instant): Double = sampleAt(departure).durationSeconds

    fun outsideQueriedRange(departure: Instant): Boolean {
        val f = points.firstOrNull() ?: return true
        val l = points.last()
        return departure.epochSecond < f.epochSecond || departure.epochSecond > l.epochSecond
    }

    fun sampleAt(departure: Instant): Point {
        require(points.isNotEmpty()) { "TravelBaseline has no points" }
        val t = departure.epochSecond
        if (t <= points.first().epochSecond) return points.first()
        if (t >= points.last().epochSecond) return points.last()
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].epochSecond <= t) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val span = (b.epochSecond - a.epochSecond).toDouble()
        val f = if (span <= 0) 0.0 else (t - a.epochSecond) / span
        return Point(
            epochSecond = t,
            durationSeconds = a.durationSeconds + f * (b.durationSeconds - a.durationSeconds),
            staticSeconds = a.staticSeconds + f * (b.staticSeconds - a.staticSeconds),
            congestionRatio = a.congestionRatio + f * (b.congestionRatio - a.congestionRatio),
        )
    }

    companion object {
        fun of(points: List<Point>) = TravelBaseline(points.sortedBy { it.epochSecond })
    }
}

/** Everything one Monte Carlo run needs. Pure data — no Android, no I/O. */
data class SimulationInputs(
    val baseline: TravelBaseline,
    val samplerBundle: SamplerBundle,
    val preparation: FittedDuration,
    val parking: FittedDuration,
    val walking: FittedDuration,
    val entryBufferSeconds: Double,
    val entryBufferLogSigma: Double = 0.0,
    val requiredArrival: Instant,
    val draws: Int = 4000,
    val seed: Long = 20250801L,
    /** Extra multiplicative widening when the departure falls outside the queried window. */
    val extrapolationSigmaGain: Double = 0.25,
)

/** The distribution of arrival times for one candidate departure. */
data class CandidateForecast(
    val departure: Instant,
    val requiredArrival: Instant,
    val onTimeProbability: Double,
    val probabilityMoreThan5LateSeconds: Double,
    val probabilityMoreThan10LateSeconds: Double,
    val expectedArrival: Instant,
    val medianArrival: Instant,
    val percentiles: Map<Int, Instant>,
    /** Mean lateness in seconds, conditional on being late. Zero when never late. */
    val expectedLatenessIfLateSeconds: Double,
    val intervalWidthSeconds: Double,
    val riskLevel: RiskLevel,
    val meanTotalSeconds: Double,
    val medianTotalSeconds: Double,
    val componentMeans: ComponentMeans,
    val draws: Int,
    val standardError: Double,
) {
    val safetyMarginSeconds: Double
        get() = (requiredArrival.epochSecond - medianArrival.epochSecond).toDouble()
}

data class ComponentMeans(
    val preparationSeconds: Double,
    val baselineTravelSeconds: Double,
    val correctedTravelSeconds: Double,
    val incidentSeconds: Double,
    val parkingSeconds: Double,
    val walkingSeconds: Double,
)

/**
 * The Monte Carlo core.
 *
 * ```
 *   Arrival = Departure + Prep + Travel(Departure + Prep) + Parking + Walk
 *   Deadline = EventStart − EntryBuffer          (RequiredArrival)
 * ```
 *
 * Note that the routing baseline is evaluated at `Departure + Prep`, not at `Departure`:
 * the user is not on the road while they are still finding their keys, so the traffic
 * they meet is the traffic at the time they actually start driving. Ignoring this is a
 * systematic under-estimate during a building peak.
 *
 * The entry buffer appears on the deadline side, which is mathematically identical to
 * adding it to the arrival: `P(A + b ≤ S) = P(A ≤ S − b)`.
 */
object MonteCarlo {

    private val REPORTED_PERCENTILES = intArrayOf(5, 10, 25, 50, 75, 80, 90, 95)

    fun simulate(departure: Instant, inputs: SimulationInputs): CandidateForecast {
        require(inputs.draws > 0) { "draws must be positive" }
        require(!inputs.baseline.isEmpty) { "no routing baseline available" }

        // Common random numbers: the seed depends only on the *inputs*, never on the
        // candidate departure time. Every candidate therefore sees the same sequence of
        // shocks, which removes simulation noise from candidate-to-candidate comparisons
        // and is what makes the departure curve smooth without post-hoc manipulation.
        val rng = Xoshiro256(inputs.seed)

        val n = inputs.draws
        val totals = DoubleArray(n)
        val arrivals = DoubleArray(n)

        var sumPrep = 0.0
        var sumBase = 0.0
        var sumCorrected = 0.0
        var sumIncident = 0.0
        var sumPark = 0.0
        var sumWalk = 0.0

        val sampler = inputs.samplerBundle.sampler
        val departureEpoch = departure.epochSecond.toDouble()
        val requiredEpoch = inputs.requiredArrival.epochSecond.toDouble()

        for (i in 0 until n) {
            val prep = inputs.preparation.sample(rng)
            val roadStart = Instant.ofEpochSecond((departureEpoch + prep).toLong())
            val point = inputs.baseline.sampleAt(roadStart)

            var multiplier = sampler.sampleMultiplier(rng)
            if (inputs.baseline.outsideQueriedRange(roadStart) && inputs.extrapolationSigmaGain > 0) {
                // We are holding the nearest measured point flat; acknowledge that with a
                // small independent widening rather than pretending we measured it.
                multiplier *= kotlin.math.exp(inputs.extrapolationSigmaGain * 0.4 * rng.nextGaussian())
            }
            val travelCore = point.durationSeconds * multiplier
            val incident = sampler.sampleIncidentSeconds(rng)
            val travel = travelCore + incident

            val park = inputs.parking.sample(rng)
            val walk = inputs.walking.sample(rng)
            val entry = if (inputs.entryBufferLogSigma > 0.0 && inputs.entryBufferSeconds > 0.0) {
                rng.nextLogNormal(inputs.entryBufferSeconds, inputs.entryBufferLogSigma) - inputs.entryBufferSeconds
            } else 0.0

            val total = prep + travel + park + walk + entry
            totals[i] = total
            arrivals[i] = departureEpoch + total

            sumPrep += prep
            sumBase += point.durationSeconds
            sumCorrected += travelCore
            sumIncident += incident
            sumPark += park
            sumWalk += walk
        }

        arrivals.sort()

        var onTime = 0
        var late5 = 0
        var late10 = 0
        var latenessSum = 0.0
        var lateCount = 0
        for (a in arrivals) {
            val lateness = a - requiredEpoch
            if (lateness <= 0.0) {
                onTime++
            } else {
                lateCount++
                latenessSum += lateness
                if (lateness > 300.0) late5++
                if (lateness > 600.0) late10++
            }
        }

        val pOnTime = Stats.clamp01(onTime.toDouble() / n)
        val percentiles = REPORTED_PERCENTILES.associateWith { p ->
            Instant.ofEpochSecond(Stats.quantileSorted(arrivals, p / 100.0).toLong())
        }
        val meanArrival = arrivals.average()
        val medianArrivalEpoch = Stats.quantileSorted(arrivals, 0.5)
        val p10 = Stats.quantileSorted(arrivals, 0.10)
        val p90 = Stats.quantileSorted(arrivals, 0.90)

        val meanTotal = totals.average()
        val medianTotal = Stats.quantile(totals, 0.5)

        return CandidateForecast(
            departure = departure,
            requiredArrival = inputs.requiredArrival,
            onTimeProbability = pOnTime,
            probabilityMoreThan5LateSeconds = Stats.clamp01(late5.toDouble() / n),
            probabilityMoreThan10LateSeconds = Stats.clamp01(late10.toDouble() / n),
            expectedArrival = Instant.ofEpochSecond(meanArrival.toLong()),
            medianArrival = Instant.ofEpochSecond(medianArrivalEpoch.toLong()),
            percentiles = percentiles,
            expectedLatenessIfLateSeconds = if (lateCount == 0) 0.0 else latenessSum / lateCount,
            intervalWidthSeconds = max(0.0, p90 - p10),
            riskLevel = classifyRisk(pOnTime, inputs.samplerBundle),
            meanTotalSeconds = meanTotal,
            medianTotalSeconds = medianTotal,
            componentMeans = ComponentMeans(
                preparationSeconds = sumPrep / n,
                baselineTravelSeconds = sumBase / n,
                correctedTravelSeconds = sumCorrected / n,
                incidentSeconds = sumIncident / n,
                parkingSeconds = sumPark / n,
                walkingSeconds = sumWalk / n,
            ),
            draws = n,
            standardError = Stats.monteCarloStdError(pOnTime, n),
        )
    }

    /**
     * Risk blends *how likely* lateness is with *how unpredictable* the journey is, so a
     * volatile route never shows as low risk purely because the median is comfortable.
     */
    private fun classifyRisk(pOnTime: Double, bundle: SamplerBundle): RiskLevel {
        val volatility = bundle.effectiveScale
        return when {
            pOnTime >= 0.92 && volatility < 0.30 -> RiskLevel.LOW
            pOnTime >= 0.85 -> RiskLevel.MODERATE
            pOnTime >= 0.65 -> RiskLevel.ELEVATED
            else -> RiskLevel.HIGH
        }
    }
}
