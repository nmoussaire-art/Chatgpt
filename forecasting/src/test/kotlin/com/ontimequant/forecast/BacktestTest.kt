package com.ontimequant.forecast

import com.google.common.truth.Truth.assertThat
import com.ontimequant.forecast.demo.DemoScenario
import com.ontimequant.model.CompletedTrip
import com.ontimequant.model.DataProvenance
import org.junit.Test
import java.time.LocalDate

class BacktestTest {

    private val day: LocalDate = LocalDate.of(2025, 11, 4)
    private val trips: List<CompletedTrip> = DemoScenario.completedTrips(day, DemoScenario.ZONE, count = 40)

    @Test
    fun `the first trip is scored with no training data at all`() {
        val records = Backtester.run(trips)
        assertThat(records).isNotEmpty()
        assertThat(records.first().trainingSize).isEqualTo(0)
    }

    @Test
    fun `training data grows strictly monotonically and never sees the future`() {
        val records = Backtester.run(trips)
        records.forEachIndexed { i, r -> assertThat(r.trainingSize).isEqualTo(i) }
    }

    @Test
    fun `trips excluded from learning are still scored but never trained on`() {
        val marked = trips.mapIndexed { i, t -> if (i == 5) t.copy(excludedFromLearning = true) else t }
        val records = Backtester.run(marked)
        assertThat(records).hasSize(trips.size)
        // After the excluded trip, the training size lags the index by exactly one.
        assertThat(records[8].trainingSize).isEqualTo(7)
    }

    @Test
    fun `reordering the input does not change the walk-forward result`() {
        val shuffled = trips.reversed()
        val a = Backtester.run(trips).map { it.tripId }
        val b = Backtester.run(shuffled).map { it.tripId }
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `an empty history produces an empty report with the minimum-sample warning`() {
        val report = CalibrationAnalyser.analyse(emptyList(), DataProvenance.LEARNED)
        assertThat(report.sampleSize).isEqualTo(0)
        assertThat(report.hasEnoughData).isFalse()
        assertThat(report.minimumSample).isEqualTo(CalibrationAnalyser.MINIMUM_SAMPLE)
    }

    @Test
    fun `a short history is flagged as below the minimum sample`() {
        val records = Backtester.run(trips.take(4))
        val report = CalibrationAnalyser.analyse(records, DataProvenance.LEARNED)
        assertThat(report.sampleSize).isEqualTo(4)
        assertThat(report.hasEnoughData).isFalse()
    }

    @Test
    fun `calibration metrics are well formed`() {
        val report = CalibrationAnalyser.analyse(Backtester.run(trips), DataProvenance.DEMO)
        assertThat(report.hasEnoughData).isTrue()
        assertThat(report.brierScore).isAtLeast(0.0)
        assertThat(report.brierScore).isAtMost(1.0)
        listOf(report.coverage50, report.coverage80, report.coverage90).forEach {
            assertThat(it).isAtLeast(0.0)
            assertThat(it).isAtMost(1.0)
        }
        assertThat(report.coverage90).isAtLeast(report.coverage80 - 1e-9)
        assertThat(report.coverage80).isAtLeast(report.coverage50 - 1e-9)
        assertThat(report.medianAbsoluteErrorSeconds).isAtLeast(0.0)
        assertThat(report.medianIntervalWidthSeconds).isGreaterThan(0.0)
    }

    @Test
    fun `calibration buckets are ordered, non-overlapping and sum to the sample`() {
        val report = CalibrationAnalyser.analyse(Backtester.run(trips), DataProvenance.DEMO)
        assertThat(report.calibration).isNotEmpty()
        assertThat(report.calibration.sumOf { it.count }).isEqualTo(report.sampleSize)
        for (i in 1 until report.calibration.size) {
            assertThat(report.calibration[i].lowerProbability)
                .isAtLeast(report.calibration[i - 1].upperProbability - 1e-9)
        }
        report.calibration.forEach {
            assertThat(it.observedFrequency).isAtLeast(0.0)
            assertThat(it.observedFrequency).isAtMost(1.0)
            assertThat(it.meanPredicted).isAtLeast(it.lowerProbability - 1e-9)
        }
    }

    @Test
    fun `the demo forecasts are reasonably well calibrated`() {
        val report = CalibrationAnalyser.analyse(Backtester.run(trips), DataProvenance.DEMO)
        // The 80% interval should contain the outcome roughly 80% of the time. A generous
        // tolerance is used because the sample is only 40 trips.
        assertThat(report.coverage80).isAtLeast(0.55)
        assertThat(report.coverage80).isAtMost(0.98)
        // A Brier score of 0.25 is what a permanent 50/50 hedge scores; we must beat it.
        assertThat(report.brierScore).isLessThan(0.25)
    }

    @Test
    fun `route level performance is reported per route`() {
        val report = CalibrationAnalyser.analyse(Backtester.run(trips), DataProvenance.DEMO)
        assertThat(report.routePerformance).isNotEmpty()
        assertThat(report.routePerformance.first().count).isEqualTo(trips.size)
        assertThat(report.bucketPerformance).isNotEmpty()
    }

    @Test
    fun `recent and long-run error are both reported when the history is long enough`() {
        val report = CalibrationAnalyser.analyse(Backtester.run(trips), DataProvenance.DEMO)
        assertThat(report.recentMedianAbsoluteErrorSeconds).isNotNull()
        assertThat(report.longRunMedianAbsoluteErrorSeconds).isNotNull()
    }

    @Test
    fun `the walk-forward pass shows the model learning a systematic bias`() {
        // Every trip takes 50% longer than the routing profile predicted.
        val optimistic = trips.map { it.copy(actualTravelSeconds = it.predictedTravelSeconds * 1.5) }
        val records = Backtester.run(optimistic)

        // The very first forecast has no history, so it must be badly and positively wrong.
        assertThat(records.first().errorSeconds).isGreaterThan(200.0)
        // By the end the correction has been learned and the error has largely gone.
        val early = Stats.median(records.take(8).map { kotlin.math.abs(it.errorSeconds) })
        val late = Stats.median(records.takeLast(8).map { kotlin.math.abs(it.errorSeconds) })
        assertThat(late).isLessThan(early * 0.5)
    }

    @Test
    fun `backtesting is deterministic`() {
        val a = Backtester.run(trips).map { it.predictedOnTimeProbability }
        val b = Backtester.run(trips).map { it.predictedOnTimeProbability }
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `trips with unusable durations are skipped rather than crashing`() {
        val broken = trips.take(6).map { it.copy(predictedTravelSeconds = 0.0) } + trips.drop(6)
        val records = Backtester.run(broken)
        assertThat(records).hasSize(trips.size - 6)
    }
}

class InsightsTest {

    private val day: LocalDate = LocalDate.of(2025, 11, 4)

    @Test
    fun `no insights are produced from an empty history`() {
        assertThat(InsightEngine.generate(emptyList(), null, null)).isEmpty()
    }

    @Test
    fun `no insights are produced from a very short history`() {
        val trips = DemoScenario.completedTrips(day, DemoScenario.ZONE, count = 3)
        assertThat(InsightEngine.generate(trips, null, null)).isEmpty()
    }

    @Test
    fun `a rich history produces evidenced insights`() {
        val trips = DemoScenario.completedTrips(day, DemoScenario.ZONE, count = 40)
        val accuracy = CalibrationAnalyser.analyse(Backtester.run(trips), DataProvenance.DEMO)
        val prep = HistoryMapper.preparationFor(trips, DemoScenario.HOME.id)
        val insights = InsightEngine.generate(trips, accuracy, prep)

        assertThat(insights).isNotEmpty()
        insights.forEach {
            assertThat(it.sampleSize).isAtLeast(4)
            assertThat(it.headline).isNotEmpty()
            assertThat(it.detail).isNotEmpty()
        }
        // Sorted by strength, strongest first.
        for (i in 1 until insights.size) {
            assertThat(insights[i].strength).isAtMost(insights[i - 1].strength)
        }
    }

    @Test
    fun `excluded trips do not feed insights`() {
        val trips = DemoScenario.completedTrips(day, DemoScenario.ZONE, count = 40)
            .map { it.copy(excludedFromLearning = true) }
        assertThat(InsightEngine.generate(trips, null, null)).isEmpty()
    }

    @Test
    fun `a readiness insight appears once preparation is learned`() {
        val trips = DemoScenario.completedTrips(day, DemoScenario.ZONE, count = 30)
        val prep = HistoryMapper.preparationFor(trips, DemoScenario.HOME.id)
        val insights = InsightEngine.generate(trips, null, prep)
        assertThat(insights.map { it.id }).contains("readiness")
    }
}
