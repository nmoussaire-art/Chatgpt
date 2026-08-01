package com.ontimequant.forecast

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class StatsTest {

    @Test
    fun `quantiles interpolate and stay ordered`() {
        val sample = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
        val qs = listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0).map { Stats.quantile(sample, it) }
        for (i in 1 until qs.size) assertThat(qs[i]).isAtLeast(qs[i - 1])
        assertThat(Stats.quantile(sample, 0.0)).isEqualTo(1.0)
        assertThat(Stats.quantile(sample, 1.0)).isEqualTo(10.0)
        assertThat(Stats.quantile(sample, 0.5)).isWithin(1e-9).of(5.5)
    }

    @Test
    fun `quantile clamps out of range inputs`() {
        val s = doubleArrayOf(4.0, 1.0, 9.0)
        assertThat(Stats.quantile(s, -3.0)).isEqualTo(1.0)
        assertThat(Stats.quantile(s, 7.0)).isEqualTo(9.0)
    }

    @Test
    fun `mad sigma is robust to a single extreme outlier`() {
        val clean = listOf(10.0, 11.0, 9.0, 10.5, 9.5, 10.2, 9.8)
        val polluted = clean + 400.0
        val a = Stats.madSigma(clean)
        val b = Stats.madSigma(polluted)
        assertThat(abs(b - a)).isLessThan(0.6)
    }

    @Test
    fun `winsorise clamps tails without changing the centre`() {
        val values = (1..20).map { it.toDouble() } + listOf(1000.0)
        val w = Stats.winsorise(values, 0.05)
        assertThat(w.max()).isLessThan(1000.0)
        assertThat(Stats.median(w)).isWithin(1.5).of(Stats.median(values))
    }

    @Test
    fun `isotonic regression enforces a non-increasing sequence`() {
        val noisy = doubleArrayOf(0.99, 0.94, 0.96, 0.80, 0.83, 0.61, 0.40, 0.42, 0.10)
        val fitted = Stats.isotonicDecreasing(noisy)
        for (i in 1 until fitted.size) assertThat(fitted[i]).isAtMost(fitted[i - 1] + 1e-12)
        // Mass is preserved: PAVA is a projection, so the totals match.
        assertThat(fitted.sum()).isWithin(1e-9).of(noisy.sum())
    }

    @Test
    fun `isotonic regression leaves an already decreasing sequence untouched`() {
        val clean = doubleArrayOf(0.95, 0.88, 0.70, 0.45, 0.20)
        val fitted = Stats.isotonicDecreasing(clean)
        clean.indices.forEach { assertThat(fitted[it]).isWithin(1e-12).of(clean[it]) }
    }

    @Test
    fun `brier score rewards a confident correct forecast`() {
        val confidentRight = Stats.brierScore(listOf(0.95, 0.9, 0.92), listOf(true, true, true))
        val hedged = Stats.brierScore(listOf(0.5, 0.5, 0.5), listOf(true, true, true))
        assertThat(confidentRight).isLessThan(hedged)
        assertThat(Stats.brierScore(listOf(1.0), listOf(true))).isEqualTo(0.0)
        assertThat(Stats.brierScore(listOf(1.0), listOf(false))).isEqualTo(1.0)
    }

    @Test
    fun `ewma effective sample size saturates rather than growing without bound`() {
        var acc = EwmaAccumulator(halfLifeCount = 10.0)
        repeat(5) { acc = acc.update(1.0) }
        val nAfter5 = acc.effectiveSampleSize
        repeat(200) { acc = acc.update(1.0) }
        val nAfter205 = acc.effectiveSampleSize
        assertThat(nAfter5).isLessThan(5.01)
        assertThat(nAfter205).isGreaterThan(nAfter5)
        // With a 10-observation half-life the asymptote is (1+d)/(1-d) ≈ 29.
        assertThat(nAfter205).isLessThan(32.0)
    }

    @Test
    fun `ewma weights recent observations more heavily`() {
        var acc = EwmaAccumulator(halfLifeCount = 4.0)
        repeat(20) { acc = acc.update(0.0) }
        repeat(4) { acc = acc.update(1.0) }
        // Four recent 1s against twenty older 0s should pull the mean above one third.
        assertThat(acc.mean).isGreaterThan(0.33)
        assertThat(acc.mean).isLessThan(0.75)
    }

    @Test
    fun `weighted median respects weights`() {
        val values = listOf(1.0, 2.0, 3.0, 100.0)
        val weights = listOf(1.0, 1.0, 1.0, 0.01)
        assertThat(Stats.weightedMedian(values, weights)).isLessThan(4.0)
    }

    @Test
    fun `normal cdf matches known values`() {
        assertThat(Stats.normalCdf(0.0)).isWithin(1e-6).of(0.5)
        assertThat(Stats.normalCdf(1.6448536)).isWithin(1e-4).of(0.95)
        assertThat(Stats.normalCdf(-1.6448536)).isWithin(1e-4).of(0.05)
    }

    @Test
    fun `clamp01 handles NaN and out of range values`() {
        assertThat(Stats.clamp01(Double.NaN)).isEqualTo(0.0)
        assertThat(Stats.clamp01(-4.0)).isEqualTo(0.0)
        assertThat(Stats.clamp01(4.0)).isEqualTo(1.0)
    }
}

class RngTest {

    @Test
    fun `same seed produces an identical stream`() {
        val a = Xoshiro256(99L)
        val b = Xoshiro256(99L)
        repeat(500) { assertThat(a.nextDouble()).isEqualTo(b.nextDouble()) }
    }

    @Test
    fun `different seeds diverge`() {
        val a = Xoshiro256(1L)
        val b = Xoshiro256(2L)
        val diffs = (0 until 100).count { a.nextDouble() != b.nextDouble() }
        assertThat(diffs).isGreaterThan(95)
    }

    @Test
    fun `uniform draws stay inside the unit interval and look uniform`() {
        val rng = Xoshiro256(7L)
        val buckets = IntArray(10)
        repeat(100_000) {
            val u = rng.nextDouble()
            assertThat(u).isAtLeast(0.0)
            assertThat(u).isLessThan(1.0)
            buckets[(u * 10).toInt()]++
        }
        buckets.forEach { assertThat(it).isIn(9_000..11_000) }
    }

    @Test
    fun `gaussian draws have the right moments`() {
        val rng = Xoshiro256(13L)
        val n = 200_000
        var sum = 0.0
        var sumSq = 0.0
        repeat(n) { val z = rng.nextGaussian(); sum += z; sumSq += z * z }
        val mean = sum / n
        val sd = kotlin.math.sqrt(sumSq / n - mean * mean)
        assertThat(abs(mean)).isLessThan(0.02)
        assertThat(sd).isWithin(0.02).of(1.0)
    }

    @Test
    fun `exponential draws are positive with the requested mean`() {
        val rng = Xoshiro256(21L)
        val n = 200_000
        var sum = 0.0
        repeat(n) { val x = rng.nextExponential(4.0); assertThat(x).isAtLeast(0.0); sum += x }
        assertThat(sum / n).isWithin(0.08).of(4.0)
    }

    @Test
    fun `log normal draws are strictly positive with the requested median`() {
        val rng = Xoshiro256(31L)
        val draws = DoubleArray(100_000) { rng.nextLogNormal(600.0, 0.4) }
        assertThat(draws.min()).isGreaterThan(0.0)
        assertThat(Stats.quantile(draws, 0.5)).isWithin(12.0).of(600.0)
    }
}
