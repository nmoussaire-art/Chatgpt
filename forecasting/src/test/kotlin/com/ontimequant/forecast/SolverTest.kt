package com.ontimequant.forecast

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DepartureSolverTest {

    private val solver = DepartureSolver()

    private fun solve(
        target: Double,
        inputs: SimulationInputs = Fixture.inputs(),
        windowStart: Instant = Fixture.NOW,
        windowEnd: Instant = Fixture.DEADLINE.minusSeconds(600),
    ) = solver.solve(DepartureSolver.Request(windowStart, windowEnd, target, inputs))

    @Test
    fun `the solver returns the latest minute that still meets the target`() {
        val target = 0.90
        val solution = solve(target)
        val recommended = requireNotNull(solution.recommended)
        assertThat(recommended.onTimeProbability).isAtLeast(target)

        // One minute later must fail, otherwise it was not the latest.
        val oneLater = MonteCarlo.simulate(recommended.departure.plusSeconds(60), Fixture.inputs())
        assertThat(oneLater.onTimeProbability).isLessThan(target)
    }

    @Test
    fun `a higher confidence target produces an earlier departure`() {
        val relaxed = solve(0.70).recommended!!.departure
        val balanced = solve(0.85).recommended!!.departure
        val safe = solve(0.90).recommended!!.departure
        val verySafe = solve(0.95).recommended!!.departure
        assertThat(balanced).isAtMost(relaxed)
        assertThat(safe).isAtMost(balanced)
        assertThat(verySafe).isAtMost(safe)
    }

    @Test
    fun `a larger safety buffer produces an earlier departure`() {
        val noBuffer = solve(0.90, Fixture.inputs(entryBufferMinutes = 0)).recommended!!.departure
        val buffered = solve(0.90, Fixture.inputs(entryBufferMinutes = 15)).recommended!!.departure
        assertThat(buffered).isLessThan(noBuffer)
        assertThat(Duration.between(buffered, noBuffer).toMinutes()).isAtLeast(10L)
    }

    @Test
    fun `more uncertainty produces an earlier departure for the same target`() {
        val tight = Fixture.inputs(bundle = Fixture.bundle(history = Fixture.observations(40, 0.0, 0.05)))
        val loose = Fixture.inputs(bundle = Fixture.bundle(history = Fixture.observations(40, 0.0, 0.40)))
        assertThat(solve(0.90, loose).recommended!!.departure)
            .isLessThan(solve(0.90, tight).recommended!!.departure)
    }

    @Test
    fun `an infeasible window reports honestly instead of inventing an answer`() {
        // Window opens only 12 minutes before the deadline: no chance of a 95% outcome.
        val start = Fixture.DEADLINE.minusSeconds(12 * 60)
        val solution = solve(0.95, windowStart = start, windowEnd = Fixture.DEADLINE.minusSeconds(60))
        assertThat(solution.feasible).isFalse()
        assertThat(solution.recommended).isNull()
        assertThat(solution.bestEffort.onTimeProbability).isLessThan(0.95)
        assertThat(solution.bestEffort.departure).isEqualTo(start)
    }

    @Test
    fun `the curve is effectively monotone thanks to common random numbers`() {
        val solution = solve(0.90)
        val worstStandardError = solution.curve.maxOf { it.standardError }
        // Any wrong-way step must be explainable by simulation noise alone.
        assertThat(solution.monotonicityViolation).isLessThan(4.0 * worstStandardError)
    }

    @Test
    fun `display smoothing is not applied when the raw curve is already clean`() {
        val curve = solve(0.90).displayCurve()
        assertThat(curve.smoothingApplied).isFalse()
        assertThat(curve.probabilities).isEqualTo(curve.rawProbabilities)
    }

    @Test
    fun `display smoothing enforces monotonicity when it is switched on`() {
        val solution = solve(0.90)
        val noisy = doubleArrayOf(0.9, 0.95, 0.7, 0.75, 0.4)
        val fixed = Stats.isotonicDecreasing(noisy)
        for (i in 1 until fixed.size) assertThat(fixed[i]).isAtMost(fixed[i - 1] + 1e-12)
        // And the real solution's curve is bounded in [0,1] regardless.
        solution.displayCurve().probabilities.forEach {
            assertThat(it).isAtLeast(0.0); assertThat(it).isAtMost(1.0)
        }
    }

    @Test
    fun `every candidate on the curve is evaluated at one minute resolution near the answer`() {
        val solution = solve(0.90)
        val recommended = solution.recommended!!.departure
        val nearby = solution.curve.filter {
            kotlin.math.abs(Duration.between(it.departure, recommended).toMinutes()) <= 5
        }
        // Coarse step is 5 minutes; refinement must have added intermediate minutes.
        assertThat(nearby.size).isAtLeast(4)
    }

    @Test
    fun `probabilities across the whole curve stay in the unit interval`() {
        solve(0.85).curve.forEach {
            assertThat(it.onTimeProbability).isAtLeast(0.0)
            assertThat(it.onTimeProbability).isAtMost(1.0)
            assertThat(it.probabilityMoreThan5LateSeconds).isAtLeast(0.0)
            assertThat(it.probabilityMoreThan10LateSeconds).isAtMost(1.0)
        }
    }

    @Test
    fun `the derived window opens before the deadline and has positive length`() {
        val (start, end) = DepartureSolver.window(
            requiredArrival = Fixture.DEADLINE,
            typicalTotalSeconds = 30 * 60.0,
            earliestAllowed = Fixture.NOW.minusSeconds(7200),
        )
        assertThat(start).isLessThan(end)
        assertThat(end).isLessThan(Fixture.DEADLINE)
        assertThat(start.epochSecond % 60).isEqualTo(0L)
        assertThat(end.epochSecond % 60).isEqualTo(0L)
    }

    @Test
    fun `the window never opens before the earliest allowed departure`() {
        val (start, _) = DepartureSolver.window(
            requiredArrival = Fixture.DEADLINE,
            typicalTotalSeconds = 30 * 60.0,
            earliestAllowed = Fixture.DEADLINE.minusSeconds(1800),
        )
        assertThat(start).isAtLeast(Fixture.DEADLINE.minusSeconds(1800 + 60))
    }

    @Test
    fun `comparison rows are ordered and decreasing in probability`() {
        val inputs = Fixture.inputs()
        val solution = solve(0.90, inputs)
        val rows = DepartureComparison.rows(solution, solution.recommended!!.departure, listOf(0L, 5L, 10L, 15L), inputs)
        assertThat(rows).hasSize(4)
        for (i in 1 until rows.size) {
            assertThat(rows[i].departure).isGreaterThan(rows[i - 1].departure)
            assertThat(rows[i].onTimeProbability).isAtMost(rows[i - 1].onTimeProbability + 0.02)
        }
    }
}
