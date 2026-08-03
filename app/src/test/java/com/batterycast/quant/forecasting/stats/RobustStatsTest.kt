package com.batterycast.quant.forecasting.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RobustStatsTest {

    @Test
    fun `median returns null for empty input rather than a stand-in`() {
        assertThat(RobustStats.median(emptyList())).isNull()
    }

    @Test
    fun `median handles odd and even lengths`() {
        assertThat(RobustStats.median(listOf(3.0, 1.0, 2.0))).isEqualTo(2.0)
        assertThat(RobustStats.median(listOf(4.0, 1.0, 3.0, 2.0))).isEqualTo(2.5)
    }

    @Test
    fun `median absolute deviation is scaled to match a standard deviation`() {
        // For a symmetric set the raw MAD is 2.0; scaled it should be 2 * 1.4826.
        val values = listOf(1.0, 3.0, 5.0, 7.0, 9.0)
        val mad = RobustStats.medianAbsoluteDeviation(values)!!
        assertThat(mad).isWithin(1e-9).of(2.0 * RobustStats.MAD_TO_SIGMA)
    }

    @Test
    fun `median absolute deviation ignores extreme contamination`() {
        val clean = listOf(10.0, 11.0, 12.0, 13.0, 14.0)
        val contaminated = clean + listOf(900.0)

        val cleanScale = RobustStats.medianAbsoluteDeviation(clean)!!
        val contaminatedScale = RobustStats.medianAbsoluteDeviation(contaminated)!!

        // A single wild point moves the standard deviation by orders of magnitude; the MAD
        // barely notices, which is why every scale in this app is a MAD.
        assertThat(contaminatedScale).isLessThan(cleanScale * 2)
    }

    @Test
    fun `quantiles are ordered and bracket the data`() {
        val values = (1..100).map { it.toDouble() }
        val p10 = RobustStats.quantile(values, 0.10)!!
        val p50 = RobustStats.quantile(values, 0.50)!!
        val p90 = RobustStats.quantile(values, 0.90)!!

        assertThat(p10).isLessThan(p50)
        assertThat(p50).isLessThan(p90)
        assertThat(p10).isAtLeast(1.0)
        assertThat(p90).isAtMost(100.0)
    }

    @Test
    fun `quantile clamps probabilities outside zero to one`() {
        val values = listOf(1.0, 2.0, 3.0)
        assertThat(RobustStats.quantile(values, -5.0)).isEqualTo(1.0)
        assertThat(RobustStats.quantile(values, 5.0)).isEqualTo(3.0)
    }

    @Test
    fun `theil sen recovers the slope of a clean line`() {
        val xs = (0..10).map { it.toDouble() }
        val ys = xs.map { 100.0 - 8.0 * it }

        val slope = RobustStats.theilSenSlope(xs, ys)!!

        assertThat(slope).isWithin(1e-9).of(-8.0)
    }

    @Test
    fun `theil sen resists a corrupted point that would wreck least squares`() {
        val xs = (0..10).map { it.toDouble() }
        val ys = xs.map { 100.0 - 5.0 * it }.toMutableList()
        // A battery recalibration event: one reading jumps by 40 points.
        ys[5] = ys[5] - 40.0

        val slope = RobustStats.theilSenSlope(xs, ys)!!

        assertThat(slope).isWithin(0.5).of(-5.0)
    }

    @Test
    fun `theil sen ignores pairs closer together than the minimum separation`() {
        // Quantised percentages: many pairs share the same y, which would imply zero slope, and
        // adjacent pairs can imply an enormous one. Requiring separation removes both artefacts.
        val xs = listOf(0.0, 0.01, 0.02, 1.0, 2.0, 3.0)
        val ys = listOf(100.0, 100.0, 100.0, 95.0, 90.0, 85.0)

        val unfiltered = RobustStats.theilSenSlope(xs, ys, minimumSeparation = 0.0)!!
        val filtered = RobustStats.theilSenSlope(xs, ys, minimumSeparation = 0.5)!!

        assertThat(filtered).isWithin(0.6).of(-5.0)
        assertThat(filtered).isNotEqualTo(unfiltered)
    }

    @Test
    fun `theil sen returns null when there are not two separated points`() {
        assertThat(RobustStats.theilSenSlope(listOf(1.0), listOf(1.0))).isNull()
        assertThat(
            RobustStats.theilSenSlope(listOf(1.0, 1.001), listOf(50.0, 49.0), minimumSeparation = 0.1),
        ).isNull()
    }

    @Test
    fun `theil sen fit reports span and point count for weighting`() {
        val xs = (0..5).map { it.toDouble() }
        val ys = xs.map { 60.0 - 3.0 * it }

        val fit = RobustStats.theilSenFit(xs, ys)!!

        assertThat(fit.slope).isWithin(1e-9).of(-3.0)
        assertThat(fit.intercept).isWithin(1e-9).of(60.0)
        assertThat(fit.pointCount).isEqualTo(6)
        assertThat(fit.xSpan).isEqualTo(5.0)
        assertThat(fit.residualScale).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `weighted mean respects the weights`() {
        val result = RobustStats.weightedMean(listOf(10.0, 20.0), listOf(3.0, 1.0))!!
        assertThat(result).isWithin(1e-9).of(12.5)
    }

    @Test
    fun `weighted mean returns null when all weight is zero`() {
        assertThat(RobustStats.weightedMean(listOf(1.0, 2.0), listOf(0.0, 0.0))).isNull()
    }

    @Test
    fun `proportion above counts strictly greater values`() {
        val values = doubleArrayOf(1.0, 5.0, 10.0, 10.0, 20.0)
        assertThat(RobustStats.proportionAbove(values, 10.0)).isWithin(1e-9).of(0.2)
        assertThat(RobustStats.proportionAbove(values, 0.0)).isWithin(1e-9).of(1.0)
        assertThat(RobustStats.proportionAbove(values, 100.0)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `quantile of sorted matches the general quantile`() {
        val values = listOf(4.0, 8.0, 15.0, 16.0, 23.0, 42.0)
        val sorted = values.sorted().toDoubleArray()

        listOf(0.05, 0.25, 0.5, 0.75, 0.95).forEach { p ->
            assertThat(RobustStats.quantileOfSorted(sorted, p))
                .isWithin(1e-9)
                .of(RobustStats.quantile(values, p)!!)
        }
    }
}
