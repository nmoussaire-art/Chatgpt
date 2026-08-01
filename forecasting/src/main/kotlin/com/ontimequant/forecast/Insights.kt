package com.ontimequant.forecast

import com.ontimequant.model.CompletedTrip
import com.ontimequant.model.TimeBucket
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A statement about the user's own data, with the evidence attached.
 *
 * Every insight carries the sample size that produced it and is only emitted when that
 * sample clears a minimum. There is deliberately no "insight of the day" fallback: when
 * there is nothing defensible to say, the screen says so.
 */
data class Insight(
    val id: String,
    val headline: String,
    val detail: String,
    val sampleSize: Int,
    val category: InsightCategory,
    /** Rough confidence in the claim, used only to order the list. */
    val strength: Double,
)

enum class InsightCategory { TIMING, ROUTE, READINESS, PARKING, WEATHER, RELIABILITY }

object InsightEngine {

    private const val MIN_SAMPLE = 5
    private const val MIN_GROUP_SAMPLE = 4

    fun generate(
        trips: List<CompletedTrip>,
        accuracy: AccuracyReport?,
        preparation: FittedDuration?,
        locale: Locale = Locale.getDefault(),
    ): List<Insight> {
        val usable = trips.filter { !it.excludedFromLearning && it.predictedTravelSeconds > 0 }
        if (usable.size < MIN_SAMPLE) return emptyList()

        val candidates = listOfNotNull(
            dayOfWeekInsight(usable, locale),
            bucketInsight(usable),
            routeBiasInsight(usable),
            streakInsight(usable),
            weatherInsight(usable),
            parkingInsight(usable),
            readinessInsight(preparation),
            volatilityInsight(usable),
            accuracy?.let { coverageInsight(it) },
        )

        return candidates.sortedByDescending { it.strength }
    }

    private fun dayOfWeekInsight(trips: List<CompletedTrip>, locale: Locale): Insight? {
        val byDay = trips.groupBy { it.dayOfWeek }.filterValues { it.size >= MIN_GROUP_SAMPLE }
        if (byDay.size < 2) return null
        val overall = Stats.median(trips.map { it.actualTravelSeconds })
        val worst = byDay.maxByOrNull { Stats.median(it.value.map { t -> t.actualTravelSeconds }) } ?: return null
        val worstMedian = Stats.median(worst.value.map { it.actualTravelSeconds })
        val deltaMinutes = (worstMedian - overall) / 60.0
        if (deltaMinutes < 3.0) return null
        val name = worst.key.getDisplayName(TextStyle.FULL, locale)
        return Insight(
            id = "day_${worst.key}",
            headline = "$name journeys are typically ${deltaMinutes.roundToInt()} minutes slower",
            detail = "Across ${worst.value.size} $name trips the drive took a median of " +
                "${(worstMedian / 60).roundToInt()} minutes, against ${(overall / 60).roundToInt()} minutes overall.",
            sampleSize = worst.value.size,
            category = InsightCategory.TIMING,
            strength = 0.55 + 0.05 * worst.value.size,
        )
    }

    private fun bucketInsight(trips: List<CompletedTrip>): Insight? {
        val byBucket = trips.groupBy { it.timeBucket }.filterValues { it.size >= MIN_GROUP_SAMPLE }
        if (byBucket.size < 2) return null
        val worst = byBucket.maxByOrNull { entry ->
            Stats.median(entry.value.map { it.actualTravelSeconds / it.predictedTravelSeconds })
        } ?: return null
        val ratio = Stats.median(worst.value.map { it.actualTravelSeconds / it.predictedTravelSeconds })
        if (ratio < 1.08) return null
        return Insight(
            id = "bucket_${worst.key}",
            headline = "${label(worst.key)} trips run ${((ratio - 1) * 100).roundToInt()}% over the routing estimate",
            detail = "Based on ${worst.value.size} completed ${label(worst.key).lowercase()} journeys.",
            sampleSize = worst.value.size,
            category = InsightCategory.TIMING,
            strength = 0.6 + 0.04 * worst.value.size,
        )
    }

    private fun routeBiasInsight(trips: List<CompletedTrip>): Insight? {
        val byRoute = trips.groupBy { it.journeyId ?: "${it.originId}->${it.destinationId}" }
            .filterValues { it.size >= MIN_GROUP_SAMPLE }
        val target = byRoute.maxByOrNull { it.value.size } ?: return null
        val ratios = target.value.map { it.actualTravelSeconds / it.predictedTravelSeconds }
        val median = Stats.median(ratios)
        val minutes = Stats.median(target.value.map { it.actualTravelSeconds - it.predictedTravelSeconds }) / 60.0
        if (abs(minutes) < 1.5) return null
        val direction = if (minutes > 0) "underestimated" else "overestimated"
        return Insight(
            id = "route_bias",
            headline = "The routing estimate has $direction this journey by " +
                "${abs(minutes).roundToInt()} minutes",
            detail = "Median across ${target.value.size} completed trips: the drive takes " +
                "${((median - 1) * 100).roundToInt()}% ${if (median > 1) "longer" else "less"} than predicted. " +
                "This correction is already built into your recommended departure time.",
            sampleSize = target.value.size,
            category = InsightCategory.ROUTE,
            strength = 0.8 + 0.03 * target.value.size,
        )
    }

    private fun streakInsight(trips: List<CompletedTrip>): Insight? {
        val recent = trips.sortedBy { it.actualDeparture }.takeLast(8)
        if (recent.size < 6) return null
        val over = recent.count { it.actualTravelSeconds > it.predictedTravelSeconds * 1.05 }
        if (over < recent.size - 2) return null
        return Insight(
            id = "streak",
            headline = "The routing estimate has come in short on $over of the last ${recent.size} trips",
            detail = "A persistent run in one direction is what drives the personal correction upward.",
            sampleSize = recent.size,
            category = InsightCategory.RELIABILITY,
            strength = 0.75,
        )
    }

    private fun weatherInsight(trips: List<CompletedTrip>): Insight? {
        val withWeather = trips.filter { it.weatherSeverity != null }
        if (withWeather.size < 8) return null
        val wet = withWeather.filter { it.weatherSeverity!! >= 0.35 }
        val dry = withWeather.filter { it.weatherSeverity!! < 0.35 }
        if (wet.size < MIN_GROUP_SAMPLE || dry.size < MIN_GROUP_SAMPLE) return null
        val wetRatio = Stats.median(wet.map { it.actualTravelSeconds / it.predictedTravelSeconds })
        val dryRatio = Stats.median(dry.map { it.actualTravelSeconds / it.predictedTravelSeconds })
        val delta = (wetRatio - dryRatio) * 100
        return if (abs(delta) < 4) {
            Insight(
                id = "weather_neutral",
                headline = "Wet weather has not meaningfully changed your travel duration so far",
                detail = "Across ${wet.size} journeys in poor conditions and ${dry.size} in clear conditions, " +
                    "the difference is under 4%. No weather penalty is being applied beyond a wider range.",
                sampleSize = withWeather.size,
                category = InsightCategory.WEATHER,
                strength = 0.5,
            )
        } else {
            Insight(
                id = "weather_effect",
                headline = "Poor conditions add about ${delta.roundToInt()}% to your journeys",
                detail = "Median over-run is ${((wetRatio - 1) * 100).roundToInt()}% in poor conditions " +
                    "versus ${((dryRatio - 1) * 100).roundToInt()}% in clear conditions " +
                    "(${wet.size} vs ${dry.size} trips).",
                sampleSize = withWeather.size,
                category = InsightCategory.WEATHER,
                strength = 0.7,
            )
        }
    }

    private fun parkingInsight(trips: List<CompletedTrip>): Insight? {
        val withParking = trips.filter { (it.parkingSeconds ?: 0.0) > 0 }
        if (withParking.size < 8) return null
        val late = withParking.filter { it.timeBucket == TimeBucket.MORNING_PEAK || it.timeBucket == TimeBucket.MIDDAY }
        val early = withParking - late.toSet()
        if (late.size < MIN_GROUP_SAMPLE || early.size < MIN_GROUP_SAMPLE) return null
        val lateMedian = Stats.median(late.map { it.parkingSeconds!! })
        val earlyMedian = Stats.median(early.map { it.parkingSeconds!! })
        val delta = (lateMedian - earlyMedian) / 60.0
        if (delta < 1.5) return null
        return Insight(
            id = "parking_time",
            headline = "Parking takes about ${delta.roundToInt()} minutes longer later in the morning",
            detail = "Median ${(lateMedian / 60).roundToInt()} minutes across ${late.size} later arrivals versus " +
                "${(earlyMedian / 60).roundToInt()} minutes across ${early.size} earlier ones.",
            sampleSize = withParking.size,
            category = InsightCategory.PARKING,
            strength = 0.6,
        )
    }

    private fun readinessInsight(preparation: FittedDuration?): Insight? {
        if (preparation == null || preparation.effectiveSampleSize < 4) return null
        val minutes = (preparation.medianSeconds / 60).roundToInt()
        if (minutes <= 0) return null
        return Insight(
            id = "readiness",
            headline = "You usually begin moving about $minutes minutes after deciding to leave",
            detail = "OnTime Quant includes this preparation time in your recommended departure so the " +
                "number on the home screen is when to start, not when to be in the car.",
            sampleSize = preparation.effectiveSampleSize.roundToInt(),
            category = InsightCategory.READINESS,
            strength = 0.65,
        )
    }

    private fun volatilityInsight(trips: List<CompletedTrip>): Insight? {
        val ordered = trips.sortedBy { it.actualDeparture }
        if (ordered.size < 12) return null
        val recent = ordered.takeLast(6).map { kotlin.math.ln(it.actualTravelSeconds / it.predictedTravelSeconds) }
        val older = ordered.dropLast(6).takeLast(12).map { kotlin.math.ln(it.actualTravelSeconds / it.predictedTravelSeconds) }
        if (older.size < 6) return null
        val recentSigma = Stats.madSigma(recent)
        val olderSigma = Stats.madSigma(older)
        if (olderSigma <= 0.0) return null
        val change = recentSigma / olderSigma
        return when {
            change > 1.4 -> Insight(
                id = "volatility_up",
                headline = "Traffic on this route has recently become less predictable",
                detail = "The spread of outcomes over the last ${recent.size} trips is about " +
                    "${((change - 1) * 100).roundToInt()}% wider than the ${older.size} before them. " +
                    "Your recommended departure has moved earlier to compensate.",
                sampleSize = recent.size + older.size,
                category = InsightCategory.RELIABILITY,
                strength = 0.72,
            )
            change < 0.7 -> Insight(
                id = "volatility_down",
                headline = "This route has become more predictable recently",
                detail = "The spread over the last ${recent.size} trips is about " +
                    "${((1 - change) * 100).roundToInt()}% narrower, so the forecast range has tightened.",
                sampleSize = recent.size + older.size,
                category = InsightCategory.RELIABILITY,
                strength = 0.6,
            )
            else -> null
        }
    }

    private fun coverageInsight(report: AccuracyReport): Insight? {
        if (!report.hasEnoughData) return null
        val pct = (report.coverage80 * 100).roundToInt()
        return Insight(
            id = "coverage",
            headline = "The 80% forecast range contained the actual journey ${pct}% of the time",
            detail = if (abs(report.coverage80 - 0.80) < 0.08) {
                "That is close to the 80% it should be, which means the forecast ranges are honest."
            } else if (report.coverage80 < 0.80) {
                "That is below 80%, so the ranges have been slightly too narrow. Uncertainty estimates widen automatically as this is observed."
            } else {
                "That is above 80%, so the ranges have been a little wider than strictly necessary."
            },
            sampleSize = report.sampleSize,
            category = InsightCategory.RELIABILITY,
            strength = 0.68,
        )
    }

    private fun label(bucket: TimeBucket) = when (bucket) {
        TimeBucket.EARLY_MORNING -> "Early morning"
        TimeBucket.MORNING_PEAK -> "Morning peak"
        TimeBucket.MIDDAY -> "Midday"
        TimeBucket.EVENING_PEAK -> "Evening peak"
        TimeBucket.EVENING -> "Evening"
    }

    @Suppress("unused")
    private fun dayName(d: DayOfWeek, locale: Locale) = d.getDisplayName(TextStyle.FULL, locale)
}
