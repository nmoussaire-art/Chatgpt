package com.batterycast.quant.forecast

import com.batterycast.quant.model.AccuracySummary
import com.batterycast.quant.model.ForecastEvaluation
import kotlin.math.abs
import kotlin.math.floor

class AccuracyCalculator {
    fun calculate(evaluations: List<ForecastEvaluation>): AccuracySummary {
        if (evaluations.isEmpty()) {
            return AccuracySummary(0, null, null, null, null, null, null, null)
        }

        val errors = evaluations.map { it.medianAtTarget - it.actualAtTarget }
        val timeErrors = evaluations.mapNotNull { evaluation ->
            val predicted = evaluation.predictedTimeTo20Median ?: return@mapNotNull null
            val actual = evaluation.actualTimeTo20 ?: return@mapNotNull null
            abs(actual - predicted) / 60_000.0
        }

        fun coverage(
            lower: (ForecastEvaluation) -> Double?,
            upper: (ForecastEvaluation) -> Double?
        ): Double? {
            val eligible = evaluations.mapNotNull { evaluation ->
                val low = lower(evaluation) ?: return@mapNotNull null
                val high = upper(evaluation) ?: return@mapNotNull null
                Triple(evaluation.actualAtTarget, low, high)
            }
            if (eligible.isEmpty()) return null
            return eligible.count { (actual, low, high) -> actual in low..high }.toDouble() / eligible.size
        }

        return AccuracySummary(
            evaluatedForecasts = evaluations.size,
            medianAbsoluteErrorPercent = median(errors.map(::abs)),
            medianTimeTo20ErrorMinutes = timeErrors.takeIf { it.isNotEmpty() }?.let(::median),
            biasPercent = errors.average(),
            coverage50 = coverage({ it.p25 }, { it.p75 }),
            coverage80 = coverage({ it.p10 }, { it.p90 }),
            coverage90 = coverage({ it.p05 }, { it.p95 }),
            calibrationScore = calibrationError(evaluations)
        )
    }

    private fun calibrationError(evaluations: List<ForecastEvaluation>): Double? {
        val eligible = evaluations.filter { it.survivalProbability != null }
        if (eligible.size < 10) return null
        val bins = eligible.groupBy { evaluation ->
            floor(evaluation.survivalProbability!!.coerceIn(0.0, 0.999999) * 5.0).toInt()
        }
        return bins.values.sumOf { bin ->
            val predicted = bin.map { it.survivalProbability!! }.average()
            val observed = bin.count { it.actualAtTarget > it.reservePercent }.toDouble() / bin.size
            abs(predicted - observed) * bin.size / eligible.size
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }
}
