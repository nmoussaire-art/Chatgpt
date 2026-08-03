package com.batterycast.quant.forecasting.ewma

import com.batterycast.quant.forecasting.model.DrainMetric
import com.batterycast.quant.forecasting.model.ModelNamespace
import com.batterycast.quant.forecasting.shrinkage.BayesianShrinkage
import com.batterycast.quant.forecasting.shrinkage.SupportLevel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EwmaModelTest {

    private val t0 = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun empty() = EwmaModel.emptyCell(
        ModelNamespace.DRAIN,
        "r=STANDBY",
        DrainMetric.PERCENT_PER_HOUR,
        t0,
    )

    @Test
    fun `the first observation becomes the mean exactly`() {
        val cell = EwmaModel.update(empty(), value = 7.5, observationWeight = 0.25, atMs = t0)

        assertThat(cell.mean).isEqualTo(7.5)
        assertThat(cell.variance).isEqualTo(0.0)
        assertThat(cell.rawSamples).isEqualTo(1)
    }

    @Test
    fun `repeated identical observations keep the mean and leave variance at zero`() {
        var cell = empty()
        repeat(10) { index ->
            cell = EwmaModel.update(cell, value = 4.0, observationWeight = 0.5, atMs = t0 + index * hour)
        }

        assertThat(cell.mean).isWithin(1e-9).of(4.0)
        assertThat(cell.variance).isWithin(1e-9).of(0.0)
        assertThat(cell.rawSamples).isEqualTo(10)
    }

    @Test
    fun `the mean converges towards a changed level`() {
        var cell = empty()
        repeat(10) { index -> cell = EwmaModel.update(cell, 4.0, 1.0, t0 + index * hour) }
        val before = cell.mean

        repeat(10) { index -> cell = EwmaModel.update(cell, 12.0, 1.0, t0 + (10 + index) * hour) }

        assertThat(before).isWithin(1e-6).of(4.0)
        assertThat(cell.mean).isGreaterThan(8.0)
        assertThat(cell.mean).isLessThan(12.0)
    }

    @Test
    fun `variance grows when observations disagree`() {
        var steady = empty()
        var noisy = empty()
        repeat(20) { index ->
            steady = EwmaModel.update(steady, 6.0, 1.0, t0 + index * hour)
            noisy = EwmaModel.update(noisy, if (index % 2 == 0) 2.0 else 10.0, 1.0, t0 + index * hour)
        }

        assertThat(noisy.variance).isGreaterThan(steady.variance)
        assertThat(noisy.mean).isWithin(1.5).of(6.0)
    }

    @Test
    fun `weight decays with the half-life`() {
        val oneHalfLife = EwmaModel.decayWeight(
            weight = 8.0,
            lastUpdateMs = t0,
            atMs = t0 + EwmaModel.DEFAULT_HALF_LIFE_MS,
            halfLifeMs = EwmaModel.DEFAULT_HALF_LIFE_MS,
        )
        val twoHalfLives = EwmaModel.decayWeight(
            weight = 8.0,
            lastUpdateMs = t0,
            atMs = t0 + 2 * EwmaModel.DEFAULT_HALF_LIFE_MS,
            halfLifeMs = EwmaModel.DEFAULT_HALF_LIFE_MS,
        )

        assertThat(oneHalfLife).isWithin(1e-9).of(4.0)
        assertThat(twoHalfLives).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `a very long interval cannot dominate the model`() {
        var capped = empty()
        // An eight-hour overnight interval is capped so it counts for at most two units of weight.
        capped = EwmaModel.update(capped, 1.0, observationWeight = 8.0, atMs = t0)

        assertThat(capped.weight).isEqualTo(EwmaModel.MAX_OBSERVATION_WEIGHT)
    }

    @Test
    fun `a stale cell yields to fresh observations`() {
        var cell = empty()
        repeat(20) { index -> cell = EwmaModel.update(cell, 5.0, 1.0, t0 + index * hour) }

        val muchLater = t0 + 40L * 24 * hour
        val effective = EwmaModel.effectiveWeight(cell, muchLater)

        assertThat(effective).isLessThan(cell.weight * 0.1)
    }

    @Test
    fun `entity round trip preserves the cell`() {
        var cell = empty()
        repeat(5) { index -> cell = EwmaModel.update(cell, 3.0 + index, 1.0, t0 + index * hour) }

        val restored = cell.toEntity().toDomain()

        assertThat(restored).isEqualTo(cell)
    }
}

class BayesianShrinkageTest {

    private val t0 = 1_700_000_000_000L

    private fun cell(key: String, mean: Double, variance: Double, weight: Double) = EwmaCell(
        namespace = ModelNamespace.DRAIN,
        key = key,
        metric = DrainMetric.PERCENT_PER_HOUR,
        mean = mean,
        variance = variance,
        weight = weight,
        rawSamples = weight.toLong(),
        lastUpdateMs = t0,
    )

    @Test
    fun `no evidence at any level yields no estimate`() {
        assertThat(BayesianShrinkage.shrink(listOf(null, null), t0)).isNull()
        assertThat(BayesianShrinkage.shrink(emptyList(), t0)).isNull()
    }

    @Test
    fun `a sparse specific cell barely moves the general estimate`() {
        val general = cell("global", mean = 6.0, variance = 1.0, weight = 60.0)
        val specific = cell("specific", mean = 30.0, variance = 1.0, weight = 0.4)

        val result = BayesianShrinkage.shrink(listOf(general, specific), t0)!!

        // Half an hour of unusual behaviour must not turn a 6 %/h phone into a 30 %/h one.
        assertThat(result.mean).isLessThan(9.0)
        assertThat(result.specificity).isLessThan(0.15)
        assertThat(result.supportLevel).isNotEqualTo(SupportLevel.NONE)
    }

    @Test
    fun `a well-supported specific cell takes over`() {
        val general = cell("global", mean = 6.0, variance = 1.0, weight = 60.0)
        val specific = cell("specific", mean = 18.0, variance = 1.0, weight = 45.0)

        val result = BayesianShrinkage.shrink(listOf(general, specific), t0)!!

        assertThat(result.mean).isGreaterThan(16.0)
        assertThat(result.specificity).isGreaterThan(0.85)
        assertThat(result.supportLevel).isEqualTo(SupportLevel.STRONG)
    }

    @Test
    fun `the transition from general to specific is gradual, not a switch`() {
        val general = cell("global", mean = 5.0, variance = 1.0, weight = 50.0)
        val means = listOf(0.5, 2.0, 5.0, 20.0, 100.0).map { weight ->
            BayesianShrinkage.shrink(
                listOf(general, cell("specific", mean = 20.0, variance = 1.0, weight = weight)),
                t0,
            )!!.mean
        }

        assertThat(means).isInOrder()
        assertThat(means.first()).isLessThan(8.0)
        assertThat(means.last()).isGreaterThan(19.0)
    }

    @Test
    fun `disagreement between levels widens the variance`() {
        val general = cell("global", mean = 6.0, variance = 1.0, weight = 20.0)
        val agreeing = cell("specific", mean = 6.2, variance = 1.0, weight = 6.0)
        val disagreeing = cell("specific", mean = 22.0, variance = 1.0, weight = 6.0)

        val calm = BayesianShrinkage.shrink(listOf(general, agreeing), t0)!!
        val conflicted = BayesianShrinkage.shrink(listOf(general, disagreeing), t0)!!

        assertThat(conflicted.variance).isGreaterThan(calm.variance * 5)
    }

    @Test
    fun `missing intermediate levels are skipped without penalty`() {
        val general = cell("global", mean = 6.0, variance = 1.0, weight = 30.0)
        val specific = cell("deep", mean = 12.0, variance = 1.0, weight = 30.0)

        val result = BayesianShrinkage.shrink(listOf(general, null, null, specific), t0)!!

        assertThat(result.deepestLevelUsed).isEqualTo(3)
        assertThat(result.mean).isGreaterThan(10.0)
    }

    @Test
    fun `a live estimate alone becomes the answer when no model exists`() {
        val result = BayesianShrinkage.blendWithLive(
            model = null,
            liveMean = 9.0,
            liveVariance = 4.0,
            liveWeight = 3.0,
        )!!

        assertThat(result.mean).isEqualTo(9.0)
        assertThat(result.effectiveSamples).isEqualTo(3.0)
    }

    @Test
    fun `a live estimate is tempered once history exists`() {
        val model = BayesianShrinkage.shrink(
            listOf(cell("global", mean = 5.0, variance = 1.0, weight = 40.0)),
            t0,
        )!!

        val blended = BayesianShrinkage.blendWithLive(
            model = model,
            liveMean = 25.0,
            liveVariance = 9.0,
            liveWeight = 3.0,
        )!!

        assertThat(blended.mean).isGreaterThan(5.0)
        assertThat(blended.mean).isLessThan(12.0)
    }

    @Test
    fun `with no live estimate the model is returned unchanged`() {
        val model = BayesianShrinkage.shrink(
            listOf(cell("global", mean = 5.0, variance = 1.0, weight = 40.0)),
            t0,
        )!!

        val blended = BayesianShrinkage.blendWithLive(model, liveMean = null, liveVariance = 1.0, liveWeight = 3.0)

        assertThat(blended).isEqualTo(model)
    }
}
