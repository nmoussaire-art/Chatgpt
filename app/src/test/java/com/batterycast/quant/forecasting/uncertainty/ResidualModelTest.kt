package com.batterycast.quant.forecasting.uncertainty

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ResidualModelTest {

    private val now = 1_700_000_000_000L

    private fun residuals(values: List<Double>, spacingMs: Long = 60_000L) =
        values.mapIndexed { index, value -> TimedResidual(value, now - index * spacingMs) }

    @Test
    fun `an empty history yields the empty model`() {
        val model = ResidualModel.from(emptyList(), now)

        assertThat(model.sampleCount).isEqualTo(0)
        assertThat(model.hasEmpiricalSupport).isFalse()
        assertThat(model.sample(Random(1))).isEqualTo(0.0)
    }

    @Test
    fun `median and scale describe the residual distribution`() {
        val model = ResidualModel.from(residuals(listOf(-2.0, -1.0, 0.0, 1.0, 2.0)), now)

        assertThat(model.median).isWithin(1e-9).of(0.0)
        assertThat(model.scale).isGreaterThan(0.0)
    }

    @Test
    fun `a persistent lean shows up as a non-zero median`() {
        val model = ResidualModel.from(residuals(List(40) { -3.0 }), now)

        assertThat(model.median).isWithin(1e-9).of(-3.0)
    }

    @Test
    fun `bootstrap resampling only draws values that actually occurred`() {
        val values = List(40) { if (it % 2 == 0) -4.0 else 2.0 }
        val model = ResidualModel.from(residuals(values), now)
        assertThat(model.hasEmpiricalSupport).isTrue()

        val random = Random(42)
        val draws = List(500) { model.sample(random) }

        assertThat(draws.toSet()).containsExactly(-4.0, 2.0)
    }

    @Test
    fun `with too few residuals a heavy-tailed fallback is used instead of the bootstrap`() {
        val model = ResidualModel.from(residuals(listOf(-2.0, 1.0, 0.5)), now)
        assertThat(model.hasEmpiricalSupport).isFalse()

        val random = Random(7)
        val draws = List(400) { model.sample(random) }

        // A continuous distribution: the draws are not confined to the three observed values.
        assertThat(draws.toSet().size).isGreaterThan(100)
    }

    @Test
    fun `the fallback has heavier tails than a normal distribution`() {
        val model = ResidualModel.from(residuals(listOf(-1.0, 0.0, 1.0)), now)
        val random = Random(11)

        val draws = List(20_000) { model.sample(random) }
        val spread = model.scale.takeIf { it > 0 } ?: model.volatility
        val beyondThreeSigma = draws.count { abs(it - model.median) > 3 * spread }

        // A Gaussian puts about 0.27 % beyond three sigma; a t(4) puts noticeably more, which is
        // the whole point of not assuming normality.
        assertThat(beyondThreeSigma.toDouble() / draws.size).isGreaterThan(0.005)
    }

    @Test
    fun `sampling is reproducible for a fixed seed`() {
        val model = ResidualModel.from(residuals(List(40) { it.toDouble() - 20 }), now)

        val first = List(100) { model.sample(Random(99)) }
        val second = List(100) { model.sample(Random(99)) }

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `recent volatility outweighs old calm behaviour`() {
        // Calm long ago, erratic recently.
        val timed = List(30) { index ->
            TimedResidual(
                value = if (index < 10) (if (index % 2 == 0) -12.0 else 12.0) else 0.2,
                atMs = now - index * 6 * 3_600_000L,
            )
        }

        val model = ResidualModel.from(timed, now)

        assertThat(model.volatility).isGreaterThan(model.scale)
    }

    @Test
    fun `empirical quantiles are ordered`() {
        val model = ResidualModel.from(residuals((0 until 60).map { it.toDouble() - 30 }), now)

        val p10 = model.quantile(0.10)!!
        val p50 = model.quantile(0.50)!!
        val p90 = model.quantile(0.90)!!

        assertThat(p10).isLessThan(p50)
        assertThat(p50).isLessThan(p90)
    }

    @Test
    fun `gaussian draws are centred and reproducible`() {
        val random = Random(5)
        val draws = List(20_000) { ResidualModel.gaussian(random) }

        assertThat(draws.average()).isWithin(0.05).of(0.0)
        assertThat(List(10) { ResidualModel.gaussian(Random(3)) })
            .isEqualTo(List(10) { ResidualModel.gaussian(Random(3)) })
    }

    @Test
    fun `diagnostics summarise bias and typical error`() {
        val diagnostics = ResidualDiagnostics.from(listOf(-4.0, -2.0, -3.0, -5.0))!!

        assertThat(diagnostics.bias).isLessThan(0.0)
        assertThat(diagnostics.meanAbsoluteError).isWithin(1e-9).of(3.5)
        assertThat(ResidualDiagnostics.from(emptyList())).isNull()
    }
}
