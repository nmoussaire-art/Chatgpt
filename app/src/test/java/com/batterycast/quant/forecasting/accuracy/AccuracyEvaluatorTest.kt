package com.batterycast.quant.forecasting.accuracy

import com.batterycast.quant.core.database.dao.ForecastRecordDao
import com.batterycast.quant.core.database.dao.ResidualDao
import com.batterycast.quant.core.database.entity.ForecastRecordEntity
import com.batterycast.quant.core.database.entity.ResidualEntity
import com.batterycast.quant.fixtures.ObservationFixtures
import com.batterycast.quant.telemetry.repository.ObservationRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccuracyEvaluatorTest {

    private val forecastRecordDao = mockk<ForecastRecordDao>(relaxed = true)
    private val residualDao = mockk<ResidualDao>(relaxed = true)
    private val observationRepository = mockk<ObservationRepository>(relaxed = true)

    private val evaluator = AccuracyEvaluator(
        forecastRecordDao = forecastRecordDao,
        residualDao = residualDao,
        observationRepository = observationRepository,
        defaultDispatcher = UnconfinedTestDispatcher(),
    )

    private val nowMs = ObservationFixtures.BASE_TIMESTAMP_MS

    private fun record(
        id: Long = 1L,
        horizonMinutes: Double = 120.0,
        predictedMedian: Double = 40.0,
        p10: Double = 30.0,
        p25: Double = 36.0,
        p75: Double = 45.0,
        p90: Double = 50.0,
        survival: Double = 0.8,
        reserve: Double = 10.0,
        actual: Double? = null,
        wasChargingAtIssue: Boolean = false,
    ) = ForecastRecordEntity(
        id = id,
        issuedAtMs = nowMs - (horizonMinutes * 60_000).toLong(),
        targetMs = nowMs,
        horizonMinutes = horizonMinutes,
        batteryPercentAtIssue = 60.0,
        predictedMedian = predictedMedian,
        predictedP10 = p10,
        predictedP25 = p25,
        predictedP75 = p75,
        predictedP90 = p90,
        predictedSurvivalProbability = survival,
        reservePercent = reserve,
        maturity = "PERSONALISED",
        wasChargingAtIssue = wasChargingAtIssue,
        actualPercent = actual,
        evaluatedAtMs = if (actual != null) nowMs else null,
    )

    @Test
    fun `a forecast with no nearby observation is never scored against a guess`() = runTest {
        coEvery { forecastRecordDao.pendingEvaluation(any()) } returns listOf(record())
        coEvery { observationRepository.nearest(any(), any()) } returns null

        val scored = evaluator.evaluatePending(nowMs)

        assertThat(scored).isEqualTo(0)
        coVerify(exactly = 0) { forecastRecordDao.recordOutcome(any(), any(), any(), any()) }
        coVerify(exactly = 0) { residualDao.insert(any()) }
    }

    @Test
    fun `an unscoreable forecast is eventually discarded rather than kept forever`() = runTest {
        val stale = record(id = 7L)
        coEvery { forecastRecordDao.pendingEvaluation(any()) } returns listOf(stale)
        coEvery { observationRepository.nearest(any(), any()) } returns null

        evaluator.evaluatePending(nowMs + AccuracyEvaluator.ABANDON_AFTER_MS + 1)

        coVerify { forecastRecordDao.delete(7L) }
    }

    @Test
    fun `a scored forecast records both an outcome and a residual`() = runTest {
        coEvery { forecastRecordDao.pendingEvaluation(any()) } returns listOf(record(predictedMedian = 40.0))
        coEvery { observationRepository.nearest(any(), any()) } returns
            ObservationFixtures.observation(percent = 34.0)

        val residual = slot<ResidualEntity>()
        coEvery { residualDao.insert(capture(residual)) } returns Unit

        val scored = evaluator.evaluatePending(nowMs)

        assertThat(scored).isEqualTo(1)
        coVerify { forecastRecordDao.recordOutcome(1L, 34.0, false, nowMs) }
        // Actual below predicted means the phone drained faster, so the rate residual is positive.
        assertThat(residual.captured.residualPercentPoints).isWithin(1e-9).of(-6.0)
        assertThat(residual.captured.residualPercentPerHour).isGreaterThan(0.0)
    }

    @Test
    fun `outcomes involving charging are scored but excluded from the discharge residual pool`() = runTest {
        coEvery { forecastRecordDao.pendingEvaluation(any()) } returns listOf(record())
        coEvery { observationRepository.nearest(any(), any()) } returns
            ObservationFixtures.observation(percent = 80.0, charging = true)

        evaluator.evaluatePending(nowMs)

        coVerify { forecastRecordDao.recordOutcome(any(), any(), true, any()) }
        coVerify(exactly = 0) { residualDao.insert(any()) }
    }

    @Test
    fun `no report is produced before any forecast has been scored`() = runTest {
        coEvery { forecastRecordDao.evaluated(any()) } returns emptyList()

        assertThat(evaluator.report()).isNull()
    }

    @Test
    fun `median error is reported per horizon band`() = runTest {
        val records = listOf(
            record(id = 1, horizonMinutes = 30.0, predictedMedian = 50.0, actual = 52.0),
            record(id = 2, horizonMinutes = 45.0, predictedMedian = 50.0, actual = 48.0),
            record(id = 3, horizonMinutes = 150.0, predictedMedian = 40.0, actual = 34.0),
            record(id = 4, horizonMinutes = 170.0, predictedMedian = 40.0, actual = 46.0),
        )
        coEvery { forecastRecordDao.evaluated(any()) } returns records

        val report = evaluator.report()!!

        assertThat(report.evaluatedForecasts).isEqualTo(4)
        assertThat(report.medianErrorByHorizon[HorizonBucket.UNDER_ONE_HOUR]).isWithin(1e-9).of(2.0)
        assertThat(report.medianErrorByHorizon[HorizonBucket.ONE_TO_THREE_HOURS]).isWithin(1e-9).of(6.0)
    }

    @Test
    fun `a consistent lean shows up as bias`() = runTest {
        val pessimistic = (1..10).map { index ->
            record(id = index.toLong(), predictedMedian = 40.0, actual = 46.0)
        }
        coEvery { forecastRecordDao.evaluated(any()) } returns pessimistic

        val report = evaluator.report()!!

        assertThat(report.bias!!).isWithin(1e-9).of(6.0)
    }

    @Test
    fun `interval coverage counts how often the outcome landed inside`() = runTest {
        val records = (1..10).map { index ->
            // Half the outcomes land inside the 50 % interval, all inside the 80 %.
            val actual = if (index % 2 == 0) 40.0 else 32.0
            record(id = index.toLong(), predictedMedian = 40.0, p25 = 36.0, p75 = 45.0, p10 = 30.0, p90 = 50.0, actual = actual)
        }
        coEvery { forecastRecordDao.evaluated(any()) } returns records

        val report = evaluator.report()!!

        assertThat(report.intervalCoverage[IntervalBand.FIFTY]!!).isWithin(1e-9).of(0.5)
        assertThat(report.intervalCoverage[IntervalBand.EIGHTY]!!).isWithin(1e-9).of(1.0)
        report.intervalCoverage.values.forEach {
            assertThat(it).isAtLeast(0.0)
            assertThat(it).isAtMost(1.0)
        }
    }

    @Test
    fun `calibration bins compare stated probability against observed frequency`() = runTest {
        // Ten forecasts that each said 90 %; nine of them came true.
        val records = (1..10).map { index ->
            record(
                id = index.toLong(),
                survival = 0.9,
                reserve = 10.0,
                predictedMedian = 30.0,
                actual = if (index == 1) 5.0 else 30.0,
            )
        }
        coEvery { forecastRecordDao.evaluated(any()) } returns records

        val report = evaluator.report()!!
        val bin = report.calibrationBins.single()

        assertThat(bin.count).isEqualTo(10)
        assertThat(bin.predictedMean).isWithin(1e-9).of(0.9)
        assertThat(bin.observedFrequency).isWithin(1e-9).of(0.9)
    }

    @Test
    fun `a probability bin with too few forecasts is not reported`() = runTest {
        coEvery { forecastRecordDao.evaluated(any()) } returns listOf(
            record(id = 1, survival = 0.9, actual = 30.0),
            record(id = 2, survival = 0.9, actual = 30.0),
        )

        val report = evaluator.report()!!

        assertThat(report.calibrationBins).isEmpty()
    }

    @Test
    fun `a report below the reporting threshold is marked as not having enough data`() = runTest {
        coEvery { forecastRecordDao.evaluated(any()) } returns listOf(record(actual = 40.0))

        val report = evaluator.report()!!

        assertThat(report.hasEnoughData).isFalse()
    }
}
