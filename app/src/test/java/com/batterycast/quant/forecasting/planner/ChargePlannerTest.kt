package com.batterycast.quant.forecasting.planner

import com.batterycast.quant.fixtures.ObservationFixtures
import com.batterycast.quant.fixtures.SnapshotFixtures
import com.batterycast.quant.forecasting.ForecastContextFixtures
import com.batterycast.quant.telemetry.model.PlugType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChargePlannerTest {

    private val planner = ChargePlanner(ForecastContextFixtures.engine())
    private val nowMs = ObservationFixtures.BASE_TIMESTAMP_MS + 3 * 3_600_000L
    private val hour = 3_600_000L

    private fun request(
        hoursAhead: Long = 5,
        targetPercent: Double = 50.0,
        confidence: Double = 0.9,
        plugType: PlugType = PlugType.AC,
    ) = ChargePlanRequest(
        eventMs = nowMs + hoursAhead * hour,
        eventLabel = "your event",
        targetPercent = targetPercent,
        confidence = confidence,
        plugType = plugType,
    )

    @Test
    fun `a comfortable target needs no charging at all`() = runTest {
        val context = ForecastContextFixtures.context(
            nowMs = nowMs,
            latest = ObservationFixtures.observation(offsetMinutes = 180.0, percent = 95.0),
            snapshot = SnapshotFixtures.snapshot(globalDrainPerHour = 2.0),
        )

        val plan = planner.plan(context, request(hoursAhead = 2, targetPercent = 40.0))

        assertThat(plan.outcome).isEqualTo(PlanOutcome.NO_CHARGING_NEEDED)
        assertThat(plan.probabilityWithoutCharging).isAtLeast(0.9)
        assertThat(plan.latestStartMs).isNull()
    }

    @Test
    fun `a reachable target yields a latest safe start and a charging duration`() = runTest {
        val context = ForecastContextFixtures.context(
            nowMs = nowMs,
            latest = ObservationFixtures.observation(offsetMinutes = 180.0, percent = 25.0),
            snapshot = SnapshotFixtures.snapshot(globalDrainPerHour = 8.0, chargeRatePerHour = 45.0),
        )

        val plan = planner.plan(context, request(hoursAhead = 5, targetPercent = 60.0, confidence = 0.9))

        assertThat(plan.outcome).isEqualTo(PlanOutcome.PLAN_FOUND)
        assertThat(plan.latestStartMs!!).isAtLeast(nowMs)
        assertThat(plan.latestStartMs!!).isAtMost(context.nowMs + 5 * hour)
        assertThat(plan.requiredDurationMs!!).isGreaterThan(0L)
        assertThat(plan.achievedProbability!!).isAtLeast(0.9)
        assertThat(plan.expectedPercentAtEvent!!).isAtLeast(60.0)
    }

    @Test
    fun `the recommended start meets the confidence and starting later does not`() = runTest {
        val context = ForecastContextFixtures.context(
            nowMs = nowMs,
            latest = ObservationFixtures.observation(offsetMinutes = 180.0, percent = 30.0),
            snapshot = SnapshotFixtures.snapshot(globalDrainPerHour = 8.0, chargeRatePerHour = 40.0),
        )
        val planRequest = request(hoursAhead = 6, targetPercent = 70.0, confidence = 0.9)

        val plan = planner.plan(context, planRequest)
        assertThat(plan.outcome).isEqualTo(PlanOutcome.PLAN_FOUND)

        // Starting half an hour later than recommended must fall short; that is precisely what
        // "latest safe start" means.
        val later = ForecastContextFixtures.engine().simulate(
            context,
            com.batterycast.quant.forecasting.ForecastRequest(
                horizonMs = planRequest.eventMs - nowMs,
                reservePercent = planRequest.targetPercent,
                chargingStartsAtMs = plan.latestStartMs!! + 30 * 60_000L,
                chargingEndsAtMs = planRequest.eventMs,
                chargingPlugType = PlugType.AC,
            ),
        )
        val laterProbability = later.probabilityAbove(planRequest.eventMs, planRequest.targetPercent)!!

        assertThat(plan.achievedProbability!!).isAtLeast(0.9)
        assertThat(laterProbability).isLessThan(plan.achievedProbability!!)
    }

    @Test
    fun `a higher confidence requirement never permits a later start`() = runTest {
        val context = ForecastContextFixtures.context(
            nowMs = nowMs,
            latest = ObservationFixtures.observation(offsetMinutes = 180.0, percent = 30.0),
            snapshot = SnapshotFixtures.snapshot(globalDrainPerHour = 8.0, chargeRatePerHour = 40.0),
        )

        val relaxed = planner.plan(context, request(hoursAhead = 6, targetPercent = 65.0, confidence = 0.6))
        val strict = planner.plan(context, request(hoursAhead = 6, targetPercent = 65.0, confidence = 0.95))

        assertThat(relaxed.outcome).isEqualTo(PlanOutcome.PLAN_FOUND)
        if (strict.outcome == PlanOutcome.PLAN_FOUND) {
            assertThat(strict.latestStartMs!!).isAtMost(relaxed.latestStartMs!!)
        }
    }

    @Test
    fun `a higher target percentage never permits a later start`() = runTest {
        val context = ForecastContextFixtures.context(
            nowMs = nowMs,
            latest = ObservationFixtures.observation(offsetMinutes = 180.0, percent = 30.0),
            snapshot = SnapshotFixtures.snapshot(globalDrainPerHour = 8.0, chargeRatePerHour = 40.0),
        )

        val modest = planner.plan(context, request(hoursAhead = 6, targetPercent = 50.0))
        val ambitious = planner.plan(context, request(hoursAhead = 6, targetPercent = 75.0))

        if (modest.outcome == PlanOutcome.PLAN_FOUND && ambitious.outcome == PlanOutcome.PLAN_FOUND) {
            assertThat(ambitious.latestStartMs!!).isAtMost(modest.latestStartMs!!)
        }
    }

    @Test
    fun `a slower charger never permits a later start than a fast one`() = runTest {
        val context = ForecastContextFixtures.context(
            nowMs = nowMs,
            latest = ObservationFixtures.observation(offsetMinutes = 180.0, percent = 30.0),
            snapshot = SnapshotFixtures.snapshot(globalDrainPerHour = 8.0, chargeRatePerHour = 45.0),
        )

        val fast = planner.plan(context, request(hoursAhead = 6, targetPercent = 60.0, plugType = PlugType.AC))
        val slow = planner.plan(context, request(hoursAhead = 6, targetPercent = 60.0, plugType = PlugType.USB))

        if (fast.outcome == PlanOutcome.PLAN_FOUND && slow.outcome == PlanOutcome.PLAN_FOUND) {
            assertThat(slow.latestStartMs!!).isAtMost(fast.latestStartMs!!)
        }
    }

    @Test
    fun `an unreachable target is reported honestly rather than approximated`() = runTest {
        val context = ForecastContextFixtures.context(
            nowMs = nowMs,
            latest = ObservationFixtures.observation(offsetMinutes = 180.0, percent = 10.0),
            snapshot = SnapshotFixtures.snapshot(globalDrainPerHour = 10.0, chargeRatePerHour = 8.0),
        )

        val plan = planner.plan(context, request(hoursAhead = 1, targetPercent = 95.0, confidence = 0.9))

        assertThat(plan.outcome).isEqualTo(PlanOutcome.NOT_ACHIEVABLE)
        assertThat(plan.achievedProbability!!).isLessThan(0.9)
        assertThat(plan.notes.single()).contains("faster charger")
    }

    @Test
    fun `no plan is offered when this phone has never been observed charging`() = runTest {
        val context = ForecastContextFixtures.context(
            nowMs = nowMs,
            latest = ObservationFixtures.observation(offsetMinutes = 180.0, percent = 25.0),
            snapshot = SnapshotFixtures.snapshot(globalDrainPerHour = 8.0, chargeRatePerHour = null),
        )

        val plan = planner.plan(context, request(hoursAhead = 4, targetPercent = 60.0))

        assertThat(plan.outcome).isEqualTo(PlanOutcome.CHARGER_UNKNOWN)
        assertThat(plan.latestStartMs).isNull()
        assertThat(plan.requiredDurationMs).isNull()
        assertThat(plan.chargerBehaviourKnown).isFalse()
        assertThat(plan.notes.single()).contains("not yet observed")
    }

    @Test
    fun `an event in the past is rejected`() = runTest {
        val context = ForecastContextFixtures.context(nowMs = nowMs)

        val plan = planner.plan(
            context,
            ChargePlanRequest(
                eventMs = nowMs - hour,
                eventLabel = "yesterday",
                targetPercent = 50.0,
                confidence = 0.9,
            ),
        )

        assertThat(plan.outcome).isEqualTo(PlanOutcome.OUT_OF_RANGE)
        assertThat(plan.notes.single()).contains("already passed")
    }

    @Test
    fun `an event beyond the planning horizon is rejected`() = runTest {
        val context = ForecastContextFixtures.context(nowMs = nowMs)

        val plan = planner.plan(context, request(hoursAhead = 48))

        assertThat(plan.outcome).isEqualTo(PlanOutcome.OUT_OF_RANGE)
    }

    @Test
    fun `the conservative outcome is never above the expected one`() = runTest {
        val context = ForecastContextFixtures.context(
            nowMs = nowMs,
            latest = ObservationFixtures.observation(offsetMinutes = 180.0, percent = 30.0),
            snapshot = SnapshotFixtures.snapshot(globalDrainPerHour = 8.0, chargeRatePerHour = 40.0),
        )

        val plan = planner.plan(context, request(hoursAhead = 5, targetPercent = 60.0))

        assertThat(plan.conservativePercentAtEvent!!).isAtMost(plan.expectedPercentAtEvent!!)
    }
}
