package com.batterycast.quant.data

import com.batterycast.quant.forecast.AccuracyCalculator
import com.batterycast.quant.model.AccuracySummary
import com.batterycast.quant.model.ForecastEvaluation
import javax.inject.Inject

class AccuracyRepository @Inject constructor(
    private val forecastDao: ForecastDao,
    private val calculator: AccuracyCalculator
) {
    suspend fun summary(): AccuracySummary {
        val evaluations = forecastDao.recent().mapNotNull { row ->
            val actual = row.actualAtTarget ?: return@mapNotNull null
            val median = row.medianAtTarget ?: return@mapNotNull null
            ForecastEvaluation(
                reservePercent = row.reservePercent,
                medianAtTarget = median,
                p05 = row.p05,
                p10 = row.p10,
                p25 = row.p25,
                p75 = row.p75,
                p90 = row.p90,
                p95 = row.p95,
                survivalProbability = row.survivalProbability,
                predictedTimeTo20Median = row.predictedTimeTo20Median,
                actualAtTarget = actual,
                actualTimeTo20 = row.actualTimeTo20
            )
        }
        return calculator.calculate(evaluations)
    }
}
