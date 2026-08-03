package com.batterycast.quant.forecasting.planner

import com.batterycast.quant.forecasting.ForecastContext
import com.batterycast.quant.forecasting.ForecastEngine
import com.batterycast.quant.forecasting.ForecastRequest
import com.batterycast.quant.forecasting.model.SocBucket
import com.batterycast.quant.forecasting.shrinkage.SupportLevel
import com.batterycast.quant.telemetry.model.PlugType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** What the user wants to be true at the event. */
data class ChargePlanRequest(
    val eventMs: Long,
    val eventLabel: String,
    val targetPercent: Double,
    val confidence: Double,
    val plugType: PlugType = PlugType.AC,
)

data class ChargePlan(
    val request: ChargePlanRequest,
    val outcome: PlanOutcome,
    /** Latest moment charging can begin and still meet the target with the wanted confidence. */
    val latestStartMs: Long?,
    /** Charging time implied by starting then and staying plugged in until the event. */
    val requiredDurationMs: Long?,
    /** Median battery at the event if the plan is followed. */
    val expectedPercentAtEvent: Double?,
    /** 10th percentile at the event: the number to plan around. */
    val conservativePercentAtEvent: Double?,
    /** Probability actually achieved by the recommended plan. */
    val achievedProbability: Double?,
    /** Probability of meeting the target with no charging at all. */
    val probabilityWithoutCharging: Double,
    /** Whether this charger's speed has been observed on this device. */
    val chargerBehaviourKnown: Boolean,
    val chargerSupport: SupportLevel,
    val notes: List<String>,
)

enum class PlanOutcome {
    /** The target is already met without charging at the desired confidence. */
    NO_CHARGING_NEEDED,

    /** A charging window exists that meets the target. */
    PLAN_FOUND,

    /** Even charging from now until the event does not reach the target with that confidence. */
    NOT_ACHIEVABLE,

    /** The event is in the past or beyond the forecast horizon. */
    OUT_OF_RANGE,

    /** No charging behaviour has been observed yet, so no honest plan can be produced. */
    CHARGER_UNKNOWN,
}

/**
 * Answers "how long should I charge, and what is the latest I can leave it?"
 *
 * The question is solved rather than approximated. Starting to charge later means less charge
 * delivered before the event, so the probability of meeting the target is monotonically
 * non-increasing in the start time — which makes a bisection over start times exactly correct.
 *
 * The search runs at reduced path counts because it only needs to bracket the crossing; the
 * recommended plan is then re-simulated at full resolution so the numbers shown to the user come
 * from a full-quality run.
 *
 * When this device has never been observed charging, no plan is produced. A charging estimate
 * without an observed charging rate would be an invention, and this is exactly the situation the
 * app refuses to paper over.
 */
@Singleton
class ChargePlanner @Inject constructor(
    private val forecastEngine: ForecastEngine,
) {

    suspend fun plan(context: ForecastContext, request: ChargePlanRequest): ChargePlan {
        val nowMs = context.nowMs
        val horizonMs = request.eventMs - nowMs

        if (horizonMs <= 0 || horizonMs > MAX_PLAN_HORIZON_MS) {
            return emptyPlan(request, PlanOutcome.OUT_OF_RANGE, listOf(outOfRangeNote(horizonMs)))
        }

        val chargeDistribution = context.snapshot.chargeRateFor(
            request.plugType,
            SocBucket.forPercent(context.latest.batteryPercent),
        )
        val chargerKnown = chargeDistribution != null &&
            chargeDistribution.support != SupportLevel.NONE &&
            chargeDistribution.mean > 0.0

        // Baseline: what happens with no charging at all.
        val baseline = forecastEngine.simulate(
            context,
            ForecastRequest(
                horizonMs = horizonMs,
                reservePercent = request.targetPercent,
                paths = FULL_PATHS,
            ),
        )
        val probabilityWithoutCharging = baseline.probabilityAbove(request.eventMs, request.targetPercent)
            ?: 0.0

        if (probabilityWithoutCharging >= request.confidence) {
            val point = baseline.pointAt(request.eventMs)
            return ChargePlan(
                request = request,
                outcome = PlanOutcome.NO_CHARGING_NEEDED,
                latestStartMs = null,
                requiredDurationMs = null,
                expectedPercentAtEvent = point?.median,
                conservativePercentAtEvent = point?.p10,
                achievedProbability = probabilityWithoutCharging,
                probabilityWithoutCharging = probabilityWithoutCharging,
                chargerBehaviourKnown = chargerKnown,
                chargerSupport = chargeDistribution?.support ?: SupportLevel.NONE,
                notes = listOf(
                    "No charging needed: the target is already met with " +
                        "${probabilityText(probabilityWithoutCharging)} confidence.",
                ),
            )
        }

        if (!chargerKnown) {
            return emptyPlan(
                request,
                PlanOutcome.CHARGER_UNKNOWN,
                listOf(
                    "BatteryCast has not yet observed this phone charging, so it cannot estimate " +
                        "how long charging would take. Plug in once and the planner will work from " +
                        "the real charging speed.",
                ),
                probabilityWithoutCharging,
                chargeDistribution?.support ?: SupportLevel.NONE,
            )
        }

        // Charging from right now is the most favourable case. If even that misses the target,
        // no later start can reach it.
        val bestCase = probabilityWith(context, request, startMs = nowMs, horizonMs = horizonMs, paths = FULL_PATHS)
        if (bestCase < request.confidence) {
            val point = forecastEngine.simulate(
                context,
                chargeRequest(request, nowMs, horizonMs, FULL_PATHS),
            ).pointAt(request.eventMs)
            return ChargePlan(
                request = request,
                outcome = PlanOutcome.NOT_ACHIEVABLE,
                latestStartMs = nowMs,
                requiredDurationMs = horizonMs,
                expectedPercentAtEvent = point?.median,
                conservativePercentAtEvent = point?.p10,
                achievedProbability = bestCase,
                probabilityWithoutCharging = probabilityWithoutCharging,
                chargerBehaviourKnown = true,
                chargerSupport = chargeDistribution!!.support,
                notes = listOf(
                    "Charging from now until ${request.eventLabel} still only reaches " +
                        "${request.targetPercent.roundToInt()}% with " +
                        "${probabilityText(bestCase)} confidence. A faster charger, or a lower " +
                        "target, would be needed.",
                ),
            )
        }

        // Bisection on the start time. `low` always satisfies the confidence, `high` never does.
        var low = nowMs
        var high = request.eventMs
        repeat(BISECTION_STEPS) {
            val mid = low + (high - low) / 2
            val probability = probabilityWith(context, request, mid, horizonMs, SEARCH_PATHS)
            if (probability >= request.confidence) low = mid else high = mid
        }

        val latestStart = low
        val finalResult = forecastEngine.simulate(context, chargeRequest(request, latestStart, horizonMs, FULL_PATHS))
        val achieved = finalResult.probabilityAbove(request.eventMs, request.targetPercent) ?: 0.0
        val point = finalResult.pointAt(request.eventMs)
        val duration = request.eventMs - latestStart

        return ChargePlan(
            request = request,
            outcome = PlanOutcome.PLAN_FOUND,
            latestStartMs = latestStart,
            requiredDurationMs = duration,
            expectedPercentAtEvent = point?.median,
            conservativePercentAtEvent = point?.p10,
            achievedProbability = achieved,
            probabilityWithoutCharging = probabilityWithoutCharging,
            chargerBehaviourKnown = true,
            chargerSupport = chargeDistribution!!.support,
            notes = buildList {
                add(
                    "Based on this phone's observed charging speed on a " +
                        "${request.plugType.name.lowercase()} charger.",
                )
                if (chargeDistribution.support == SupportLevel.SPARSE) {
                    add("Only a little charging has been observed so far, so treat the timing as approximate.")
                }
            },
        )
    }

    private fun probabilityWith(
        context: ForecastContext,
        request: ChargePlanRequest,
        startMs: Long,
        horizonMs: Long,
        paths: Int,
    ): Double {
        val result = forecastEngine.simulate(context, chargeRequest(request, startMs, horizonMs, paths))
        return result.probabilityAbove(request.eventMs, request.targetPercent) ?: 0.0
    }

    private fun chargeRequest(
        request: ChargePlanRequest,
        startMs: Long,
        horizonMs: Long,
        paths: Int,
    ) = ForecastRequest(
        horizonMs = horizonMs,
        reservePercent = request.targetPercent,
        paths = paths,
        chargingStartsAtMs = startMs,
        chargingEndsAtMs = request.eventMs,
        chargingPlugType = request.plugType,
    )

    private fun emptyPlan(
        request: ChargePlanRequest,
        outcome: PlanOutcome,
        notes: List<String>,
        probabilityWithoutCharging: Double = 0.0,
        support: SupportLevel = SupportLevel.NONE,
    ) = ChargePlan(
        request = request,
        outcome = outcome,
        latestStartMs = null,
        requiredDurationMs = null,
        expectedPercentAtEvent = null,
        conservativePercentAtEvent = null,
        achievedProbability = null,
        probabilityWithoutCharging = probabilityWithoutCharging,
        chargerBehaviourKnown = false,
        chargerSupport = support,
        notes = notes,
    )

    private fun outOfRangeNote(horizonMs: Long): String = if (horizonMs <= 0) {
        "That time has already passed."
    } else {
        "BatteryCast forecasts up to ${MAX_PLAN_HORIZON_MS / 3_600_000} hours ahead."
    }

    private fun probabilityText(probability: Double): String =
        "${(probability * 100).roundToInt()}%"

    companion object {
        const val MAX_PLAN_HORIZON_MS = 24L * 60 * 60 * 1000

        /**
         * Twelve halvings of a 24-hour window resolve the start time to under 30 seconds, which
         * is far finer than the minute the app displays.
         */
        const val BISECTION_STEPS = 12

        /** Enough to bracket the crossing; the recommendation is re-run at full resolution. */
        const val SEARCH_PATHS = 600

        const val FULL_PATHS = 2_400
    }
}
