package com.ontimequant.forecast

import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Solves
 *
 * ```
 *   t* = max { t ∈ W : P(Arrival(t) ≤ RequiredArrival) ≥ c }
 * ```
 *
 * for a user-selected confidence target `c` over a departure window `W`.
 *
 * Strategy (three passes, cheapest first):
 *  1. **Coarse sweep** at [coarseStepMinutes] over the whole window, matching the
 *     resolution at which the routing API was queried.
 *  2. **Bracket** the crossing: the last coarse candidate that meets `c` and the first
 *     that does not.
 *  3. **Refine** at one-minute resolution inside that bracket only.
 *
 * Because every candidate is simulated with *common random numbers* (identical seed and
 * therefore identical shock sequence), the probability curve is already near-monotone
 * without any smoothing. The solver does not force monotonicity; it verifies it and
 * reports the largest violation so the UI can decide whether display smoothing is
 * warranted.
 */
class DepartureSolver(
    private val coarseStepMinutes: Int = 5,
    private val fineStepMinutes: Int = 1,
) {

    data class Request(
        val windowStart: Instant,
        val windowEnd: Instant,
        val confidenceTarget: Double,
        val inputs: SimulationInputs,
    )

    data class Solution(
        /** Latest departure meeting the target, or null when the window cannot meet it. */
        val recommended: CandidateForecast?,
        /** Best achievable candidate when [recommended] is null — always the earliest feasible. */
        val bestEffort: CandidateForecast,
        val feasible: Boolean,
        val confidenceTarget: Double,
        /** Every candidate evaluated, ordered by departure time. */
        val curve: List<CandidateForecast>,
        val evaluations: Int,
        /** Largest increase in on-time probability when departing *later*, in probability points. */
        val monotonicityViolation: Double,
    ) {
        /** The curve used for display, isotonically smoothed only if genuinely needed. */
        fun displayCurve(): DisplayCurve = DisplayCurve.from(this)
    }

    fun solve(request: Request): Solution {
        val cache = LinkedHashMap<Long, CandidateForecast>()
        fun evaluate(t: Instant): CandidateForecast =
            cache.getOrPut(t.epochSecond) { MonteCarlo.simulate(t, request.inputs) }

        val start = request.windowStart
        val end = request.windowEnd
        require(!end.isBefore(start)) { "empty departure window" }

        val coarse = ArrayList<Instant>()
        var t = start
        while (!t.isAfter(end)) {
            coarse += t
            t = t.plusSeconds(coarseStepMinutes * 60L)
        }
        if (coarse.isEmpty() || coarse.last() != end) coarse += end

        val target = request.confidenceTarget
        var lastFeasible: Instant? = null
        var firstInfeasible: Instant? = null

        for (candidate in coarse) {
            val forecast = evaluate(candidate)
            if (forecast.onTimeProbability >= target) {
                lastFeasible = candidate
            } else {
                firstInfeasible = candidate
                // Probability is decreasing in departure time; once it fails at a coarse
                // point it will keep failing. Evaluate one more for curve shape, then stop
                // scanning for feasibility but keep filling the curve to the window end.
                break
            }
        }

        // Fill in the rest of the coarse curve for display (cheap: cached, bounded window).
        for (candidate in coarse) evaluate(candidate)

        var refined: CandidateForecast? = lastFeasible?.let { evaluate(it) }

        if (lastFeasible != null && firstInfeasible != null && fineStepMinutes < coarseStepMinutes) {
            var probe = lastFeasible.plusSeconds(fineStepMinutes * 60L)
            var best = evaluate(lastFeasible)
            while (probe.isBefore(firstInfeasible)) {
                val f = evaluate(probe)
                if (f.onTimeProbability >= target) best = f
                probe = probe.plusSeconds(fineStepMinutes * 60L)
            }
            refined = best
        } else if (lastFeasible != null && firstInfeasible == null) {
            // The whole window meets the target — the answer is the window end.
            refined = evaluate(end)
        }

        val curve = cache.values.sortedBy { it.departure }
        val violation = Stats.monotonicityViolation(curve.map { it.onTimeProbability }.toDoubleArray())

        return Solution(
            recommended = refined,
            bestEffort = curve.first(),
            feasible = refined != null,
            confidenceTarget = target,
            curve = curve,
            evaluations = cache.size,
            monotonicityViolation = violation,
        )
    }

    companion object {
        /**
         * Derive a sensible departure window from the deadline and a rough duration guess.
         *
         * Opens early enough that a comfortable answer exists (deadline − 2.6× the
         * pessimistic total) and closes past the point where success is hopeless, so the
         * curve shows the user the full cost of waiting rather than stopping at the answer.
         */
        fun window(
            requiredArrival: Instant,
            typicalTotalSeconds: Double,
            earliestAllowed: Instant,
            maxWindowMinutes: Long = 120,
        ): Pair<Instant, Instant> {
            val span = max(15 * 60.0, typicalTotalSeconds)
            var start = requiredArrival.minusSeconds((span * 2.4).toLong())
            if (start.isBefore(earliestAllowed)) start = earliestAllowed
            var end = requiredArrival.minusSeconds((span * 0.55).toLong())
            if (!end.isAfter(start)) end = start.plusSeconds(20 * 60)
            val maxEnd = start.plusSeconds(maxWindowMinutes * 60)
            if (end.isAfter(maxEnd)) end = maxEnd
            // Align to whole minutes so the curve lands on readable clock times.
            return alignToMinute(start) to alignToMinute(end)
        }

        private fun alignToMinute(i: Instant): Instant =
            Instant.ofEpochSecond(i.epochSecond / 60 * 60)
    }
}

/**
 * The curve as shown to the user.
 *
 * Monotone smoothing is applied **only** when the raw curve's largest wrong-way step
 * exceeds three Monte Carlo standard errors — i.e. only when the wiggle is bigger than
 * simulation noise can explain and would mislead a reader. When that happens it is
 * recorded in [smoothingApplied] and surfaced in the UI, never hidden.
 */
data class DisplayCurve(
    val departures: List<Instant>,
    val probabilities: List<Double>,
    val rawProbabilities: List<Double>,
    val smoothingApplied: Boolean,
    val points: List<CandidateForecast>,
) {
    fun probabilityAt(index: Int): Double = probabilities[index.coerceIn(0, probabilities.lastIndex)]

    fun nearestIndex(instant: Instant): Int {
        if (departures.isEmpty()) return 0
        var best = 0
        var bestDelta = Long.MAX_VALUE
        departures.forEachIndexed { i, d ->
            val delta = kotlin.math.abs(Duration.between(d, instant).seconds)
            if (delta < bestDelta) { bestDelta = delta; best = i }
        }
        return best
    }

    companion object {
        fun from(solution: DepartureSolver.Solution): DisplayCurve {
            val points = solution.curve
            val raw = points.map { it.onTimeProbability }
            if (points.isEmpty()) {
                return DisplayCurve(emptyList(), emptyList(), emptyList(), false, emptyList())
            }
            val worstSe = points.maxOf { it.standardError }
            val needsSmoothing = solution.monotonicityViolation > 3.0 * worstSe
            val values = if (needsSmoothing) {
                Stats.isotonicDecreasing(raw.toDoubleArray()).toList()
            } else raw
            return DisplayCurve(
                departures = points.map { it.departure },
                probabilities = values.map { Stats.clamp01(it) },
                rawProbabilities = raw,
                smoothingApplied = needsSmoothing,
                points = points,
            )
        }
    }
}

/** Simple comparison rows: "what if I leave 5 / 10 / 15 minutes later?" */
object DepartureComparison {

    data class Row(
        val departure: Instant,
        val offsetMinutes: Long,
        val onTimeProbability: Double,
        val probabilityMoreThan10LateSeconds: Double,
        val expectedArrival: Instant,
        val isRecommended: Boolean,
    )

    fun rows(
        solution: DepartureSolver.Solution,
        anchor: Instant,
        offsets: List<Long> = listOf(-5, 0, 5, 10, 15),
        inputs: SimulationInputs,
    ): List<Row> {
        val byMinute = solution.curve.associateBy { it.departure.epochSecond / 60 }
        return offsets.map { offset ->
            val t = anchor.plusSeconds(offset * 60)
            val f = byMinute[t.epochSecond / 60] ?: MonteCarlo.simulate(t, inputs)
            Row(
                departure = t,
                offsetMinutes = offset,
                onTimeProbability = f.onTimeProbability,
                probabilityMoreThan10LateSeconds = f.probabilityMoreThan10LateSeconds,
                expectedArrival = f.expectedArrival,
                isRecommended = offset == 0L,
            )
        }
    }

    fun minutesUntil(from: Instant, to: Instant): Long =
        max(0L, min(Duration.between(from, to).toMinutes(), 60L * 24))
}
