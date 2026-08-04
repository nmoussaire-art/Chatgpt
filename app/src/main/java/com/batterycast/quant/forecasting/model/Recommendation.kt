package com.batterycast.quant.forecasting.model

import com.batterycast.quant.core.ui.theme.RiskTone
import kotlin.math.roundToInt

/**
 * The one sentence the home screen leads with.
 *
 * v1 showed the model's outputs and left the user to work out what to do about them. The single
 * biggest change in v2 is that the app answers in this order:
 *
 * 1. here is what will probably happen,
 * 2. here is what you should do,
 * 3. here is the evidence, when you ask for it.
 *
 * This type is step 2. It is derived entirely from simulated paths — there is no rule table of
 * generic advice — and it is deliberately allowed to say nothing when the evidence is thin.
 */
data class Recommendation(
    val tone: RiskTone,
    /** The action sentence. Short, plain, second person. */
    val headline: String,
    /** One clause of supporting reasoning, or null when the headline stands alone. */
    val detail: String?,
    val action: RecommendedAction,
)

enum class RecommendedAction {
    /** Nothing to do. */
    NONE,

    /** Worth opening the charge planner before leaving. */
    PLAN_CHARGE,

    /** Charge now; the target is not reachable otherwise. */
    CHARGE_NOW,

    /** Already charging and past the point where the target is safe. */
    CAN_UNPLUG,

    /** Not enough observed behaviour to advise. */
    STILL_LEARNING,
}

object Recommendations {

    /**
     * Builds the recommendation from the forecast alone.
     *
     * Every branch is reachable from real simulated outcomes, and the wording never overstates:
     * where the model is still learning it says so rather than offering confident advice.
     */
    fun forForecast(forecast: BatteryForecast): Recommendation {
        val target = forecast.target
        val maturity = forecast.maturity

        if (maturity <= DataMaturity.PRELIMINARY) {
            return Recommendation(
                tone = RiskTone.TIGHT,
                headline = "Early forecast",
                detail = "Accuracy improves as BatteryCast observes more of your battery.",
                action = RecommendedAction.STILL_LEARNING,
            )
        }

        if (target == null) {
            return Recommendation(
                tone = RiskTone.TIGHT,
                headline = "Pick a target to see whether you'll make it",
                detail = null,
                action = RecommendedAction.NONE,
            )
        }

        val probability = target.survivalProbability
        val tone = RiskTone.forProbability(probability)
        val reserve = target.reservePercent.roundToInt()

        if (forecast.isCharging) {
            return if (probability >= UNPLUG_CONFIDENCE) {
                Recommendation(
                    tone = RiskTone.SECURE,
                    headline = "You can unplug now",
                    detail = "Still a ${(probability * 100).roundToInt()}% chance of staying above " +
                        "$reserve% until then.",
                    action = RecommendedAction.CAN_UNPLUG,
                )
            } else {
                Recommendation(
                    tone = tone,
                    headline = "Keep charging for now",
                    detail = "Unplugging at this level leaves a " +
                        "${(probability * 100).roundToInt()}% chance of reaching your target.",
                    action = RecommendedAction.PLAN_CHARGE,
                )
            }
        }

        return when (tone) {
            RiskTone.SECURE -> Recommendation(
                tone = tone,
                headline = "You should be fine with normal use",
                detail = null,
                action = RecommendedAction.NONE,
            )

            RiskTone.TIGHT -> Recommendation(
                tone = tone,
                headline = "Likely to last, with limited margin",
                detail = "A short top-up would remove the doubt.",
                action = RecommendedAction.PLAN_CHARGE,
            )

            RiskTone.AT_RISK -> Recommendation(
                tone = tone,
                headline = "It could be close",
                detail = "Plan a charge, or ease off the screen, to reach your target comfortably.",
                action = RecommendedAction.PLAN_CHARGE,
            )

            RiskTone.CRITICAL -> Recommendation(
                tone = tone,
                headline = "Charge before you leave",
                detail = "On current behaviour the battery is unlikely to reach your target.",
                action = RecommendedAction.CHARGE_NOW,
            )
        }
    }

    /**
     * Plain-language summary of the forecast at the target — the "consumer layer" figure.
     *
     * Deliberately says "could reach empty" rather than printing 0 %. The zero is a real result
     * (simulated paths clamp at empty) but reads on screen as a broken calculation, and the phrase
     * carries the same information without the alarm.
     */
    fun expectedRange(target: TargetOutcome?): String? {
        if (target == null) return null
        val low = target.p10
        val high = target.p90
        return when {
            low < EMPTY_THRESHOLD && high < EMPTY_THRESHOLD -> "Could run out before then"
            low < EMPTY_THRESHOLD -> "Usually up to ${high.roundToInt()}%, but could reach empty"
            else -> "Usually between ${low.roundToInt()}% and ${high.roundToInt()}%"
        }
    }

    /** Below this the simulated outcome is "empty", and saying "0%" would be alarming noise. */
    const val EMPTY_THRESHOLD = 1.5

    /** Confidence at which unplugging is genuinely safe advice rather than optimism. */
    const val UNPLUG_CONFIDENCE = 0.90
}
