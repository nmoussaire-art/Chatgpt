package com.ontimequant.forecast

import com.google.common.truth.Truth.assertThat
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.PersonalizationLevel
import com.ontimequant.model.TrafficRegime
import org.junit.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp

class BiasCorrectionTest {

    @Test
    fun `with no history the bias equals the documented prior`() {
        val model = TravelModelFitter.fit(emptyList())
        assertThat(model.bias(Fixture.context()).value).isWithin(1e-9).of(ModelPriors.DEFAULT.logBias)
        assertThat(model.bias(Fixture.context()).personalWeight).isEqualTo(0.0)
    }

    @Test
    fun `a consistent under-estimate is learned`() {
        // Every trip took 20% longer than predicted.
        val model = TravelModelFitter.fit(Fixture.observations(40, kotlin.math.ln(1.2)))
        val bias = model.bias(Fixture.context())
        assertThat(exp(bias.value)).isWithin(0.04).of(1.2)
        assertThat(bias.personalWeight).isGreaterThan(0.75)
    }

    @Test
    fun `sparse personal data cannot dominate the routing baseline`() {
        // Two wild observations claiming the trip takes twice as long.
        val model = TravelModelFitter.fit(Fixture.observations(2, kotlin.math.ln(2.0)))
        val bias = model.bias(Fixture.context())
        // The prior is +3%; two observations must not drag it anywhere near +100%.
        assertThat(exp(bias.value)).isLessThan(1.35)
        assertThat(bias.personalWeight).isLessThan(0.5)
    }

    @Test
    fun `more observations shift weight toward personal history`() {
        val weights = listOf(1, 3, 8, 20, 60).map {
            TravelModelFitter.fit(Fixture.observations(it, 0.2)).bias(Fixture.context()).personalWeight
        }
        for (i in 1 until weights.size) assertThat(weights[i]).isGreaterThan(weights[i - 1])
        assertThat(weights.last()).isGreaterThan(0.85)
    }

    @Test
    fun `recent observations outweigh stale ones`() {
        val stale = Fixture.observations(20, 0.0).map { it.copy(at = it.at.minusSeconds(400 * 86_400L)) }
        val recent = Fixture.observations(6, kotlin.math.ln(1.35))
        val model = TravelModelFitter.fit(stale + recent)
        // With a 12-observation half-life, six fresh trips must move the estimate clearly.
        assertThat(exp(model.bias(Fixture.context()).value)).isGreaterThan(1.08)
    }

    @Test
    fun `route specific bias is separated when routes genuinely differ`() {
        val slow = Fixture.observations(30, kotlin.math.ln(1.40), routeKey = "slow", seed = 1L)
            .map { it.copy(pairKey = "slow") }
        val fast = Fixture.observations(30, kotlin.math.ln(1.00), routeKey = "fast", seed = 2L)
            .map { it.copy(pairKey = "fast") }
        val model = TravelModelFitter.fit(slow + fast)

        val slowBias = exp(model.bias(Fixture.context().copy(routeKey = "slow", pairKey = "slow")).value)
        val fastBias = exp(model.bias(Fixture.context().copy(routeKey = "fast", pairKey = "fast")).value)
        val unseenBias = exp(model.bias(Fixture.context().copy(routeKey = "new", pairKey = "new")).value)

        assertThat(slowBias).isGreaterThan(fastBias)
        // A route with no history of its own inherits the user's overall behaviour, which
        // sits between the two known routes rather than copying either.
        assertThat(unseenBias).isGreaterThan(fastBias)
        assertThat(unseenBias).isLessThan(slowBias)
    }

    @Test
    fun `nested levels do not count the same trip four times`() {
        // Every trip is on the same route, pair and time bucket, so all four levels hold
        // identical data. The estimate must reflect that evidence once, not four times.
        val two = TravelModelFitter.fit(Fixture.observations(2, kotlin.math.ln(2.0)))
        val single = Shrinkage.toward(
            prior = ModelPriors.DEFAULT.logBias,
            sampleMean = kotlin.math.ln(2.0),
            effectiveSampleSize = 2.0,
            priorStrength = ModelPriors.DEFAULT.kappaUserGlobal,
        )
        assertThat(two.bias(Fixture.context()).value).isWithin(0.02).of(single)
    }

    @Test
    fun `residual scale is learned and floored`() {
        val tight = TravelModelFitter.fit(Fixture.observations(60, 0.0, 0.001)).sigma(Fixture.context())
        val wide = TravelModelFitter.fit(Fixture.observations(60, 0.0, 0.5)).sigma(Fixture.context())
        assertThat(wide.value).isGreaterThan(tight.value)
        assertThat(tight.value).isAtLeast(ModelPriors.DEFAULT.minLogSigma)
        assertThat(wide.value).isAtMost(ModelPriors.DEFAULT.maxLogSigma)
    }

    @Test
    fun `structurally impossible ratios are discarded`() {
        val poison = Fixture.observations(1, 0.0).map {
            it.copy(actualSeconds = it.predictedSeconds * 5000)
        }
        val model = TravelModelFitter.fit(Fixture.observations(20, 0.05) + poison)
        assertThat(exp(model.bias(Fixture.context()).value)).isLessThan(1.20)
    }

    @Test
    fun `personalization level rises with evidence`() {
        fun level(n: Int) = TravelModelFitter.fit(Fixture.observations(n, 0.1, 0.15))
            .personalization(Fixture.context())
        assertThat(level(0)).isEqualTo(PersonalizationLevel.PRELIMINARY)
        assertThat(level(4)).isEqualTo(PersonalizationLevel.LEARNING)
        assertThat(level(12)).isEqualTo(PersonalizationLevel.PARTIALLY_PERSONALIZED)
        assertThat(level(40)).isEqualTo(PersonalizationLevel.PERSONALIZED)
    }

    @Test
    fun `heavier traffic widens the sampler scale`() {
        val model = TravelModelFitter.fit(Fixture.observations(30, 0.0, 0.2))
        val light = model.sampler(Fixture.context(regime = TrafficRegime.LIGHT), 1200.0, 900.0)
        val severe = model.sampler(Fixture.context(regime = TrafficRegime.SEVERE), 1200.0, 900.0)
        assertThat(severe.effectiveScale).isGreaterThan(light.effectiveScale)
        assertThat(severe.sampler.incidentP).isGreaterThan(light.sampler.incidentP)
    }

    @Test
    fun `cached routing data widens uncertainty relative to live data`() {
        val model = TravelModelFitter.fit(Fixture.observations(30, 0.0, 0.2))
        val live = model.sampler(Fixture.context(live = true), 1200.0, 900.0)
        val stale = model.sampler(
            Fixture.context(live = false).copy(routingAgeMinutes = 90), 1200.0, 900.0,
        )
        assertThat(stale.effectiveScale).isGreaterThan(live.effectiveScale)
    }

    @Test
    fun `an empirical sampler is used only once there is enough evidence`() {
        val sparse = TravelModelFitter.fit(Fixture.observations(3, 0.1, 0.2))
            .sampler(Fixture.context(), 1200.0, 900.0)
        val rich = TravelModelFitter.fit(Fixture.observations(50, 0.1, 0.2))
            .sampler(Fixture.context(), 1200.0, 900.0)
        assertThat(sparse.sampler.isEmpirical).isFalse()
        assertThat(rich.sampler.isEmpirical).isTrue()
    }
}

class WeatherAdjustmentTest {

    @Test
    fun `missing weather widens uncertainty but adds no delay`() {
        val model = TravelModelFitter.fit(Fixture.observations(30, 0.0, 0.2))
        val known = model.sampler(Fixture.context(weather = 0.0), 1200.0, 900.0)
        val missing = model.sampler(Fixture.context(weather = null), 1200.0, 900.0)
        assertThat(missing.weatherLogAdjustment).isEqualTo(0.0)
        assertThat(missing.effectiveScale).isGreaterThan(known.effectiveScale)
    }

    @Test
    fun `severe weather widens uncertainty and shifts the centre`() {
        val model = TravelModelFitter.fit(Fixture.observations(30, 0.0, 0.2))
        val clear = model.sampler(Fixture.context(weather = 0.0), 1200.0, 900.0)
        val storm = model.sampler(Fixture.context(weather = 1.0), 1200.0, 900.0)
        assertThat(storm.weatherLogAdjustment).isGreaterThan(clear.weatherLogAdjustment)
        assertThat(storm.effectiveScale).isGreaterThan(clear.effectiveScale)
    }

    @Test
    fun `with no weather-varying history the coefficient stays at the prior`() {
        val model = TravelModelFitter.fit(Fixture.observations(30, 0.0, 0.2, weather = null))
        assertThat(model.weatherCoefficient).isWithin(1e-9).of(ModelPriors.DEFAULT.weatherLogCoefficient)
    }

    @Test
    fun `a genuine weather effect is learned from data`() {
        val rng = Xoshiro256(3L)
        val obs = (0 until 60).map { i ->
            val severity = if (i % 2 == 0) 0.85 else 0.05
            val logRatio = 0.02 + 0.30 * severity + 0.03 * rng.nextGaussian()
            Fixture.observations(1, 0.0, seed = i.toLong()).first().copy(
                at = Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i * 86_400L),
                actualSeconds = 1200.0 * exp(logRatio),
                weatherSeverity = severity,
            )
        }
        val model = TravelModelFitter.fit(obs)
        assertThat(model.weatherCoefficient).isGreaterThan(ModelPriors.DEFAULT.weatherLogCoefficient)
        assertThat(model.weatherCoefficient).isLessThan(0.45)
    }

    @Test
    fun `an absent weather effect keeps the coefficient near zero`() {
        val rng = Xoshiro256(9L)
        val obs = (0 until 60).map { i ->
            val severity = if (i % 2 == 0) 0.85 else 0.05
            Fixture.observations(1, 0.0, seed = i.toLong()).first().copy(
                at = Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i * 86_400L),
                actualSeconds = 1200.0 * exp(0.05 * rng.nextGaussian()),
                weatherSeverity = severity,
            )
        }
        val model = TravelModelFitter.fit(obs)
        assertThat(abs(model.weatherCoefficient)).isLessThan(0.06)
    }
}

class ShrinkageTest {

    @Test
    fun `the posterior mean is the documented weighted average`() {
        val posterior = Shrinkage.toward(prior = 0.0, sampleMean = 1.0, effectiveSampleSize = 3.0, priorStrength = 6.0)
        assertThat(posterior).isWithin(1e-12).of(3.0 / 9.0)
    }

    @Test
    fun `a hierarchy folds from general toward specific`() {
        val estimate = Shrinkage.hierarchical(
            root = 0.0,
            levels = listOf(
                Shrinkage.Level("global", 0.10, 30.0, 4.0),
                Shrinkage.Level("route", 0.30, 25.0, 6.0),
            ),
        )
        // Route level dominates but is still pulled toward the global level.
        assertThat(estimate.value).isLessThan(0.30)
        assertThat(estimate.value).isGreaterThan(0.10)
        assertThat(estimate.levelName).isEqualTo("route")
    }

    @Test
    fun `zero evidence returns the prior exactly`() {
        val estimate = Shrinkage.hierarchical(0.07, listOf(Shrinkage.Level("route", 5.0, 0.0, 6.0)))
        assertThat(estimate.value).isWithin(1e-12).of(0.07)
        assertThat(estimate.personalWeight).isEqualTo(0.0)
    }

    @Test
    fun `scale shrinkage blends on the variance scale and stays positive`() {
        val blended = Shrinkage.scaleToward(0.2, 0.4, effectiveSampleSize = 10.0, priorStrength = 10.0)
        val expected = kotlin.math.sqrt((10 * 0.16 + 10 * 0.04) / 20.0)
        assertThat(blended).isWithin(1e-9).of(expected)
        assertThat(Shrinkage.scaleToward(0.2, 0.0, 0.0, 5.0)).isWithin(1e-9).of(0.2)
    }
}

class DurationModelTest {

    private fun obs(n: Int, seconds: Double, key: String = "dest:A", spread: Double = 0.0, seed: Long = 1L) =
        Xoshiro256(seed).let { rng ->
            (0 until n).map { i ->
                DurationObservation(
                    at = Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i * 86_400L),
                    key = key, parentKey = "all",
                    seconds = if (spread == 0.0) seconds else rng.nextLogNormal(seconds, spread),
                )
            }
        }

    private fun fit(observations: List<DurationObservation>, key: String = "dest:A") =
        DurationModelFitter.fit(
            observations = observations, key = key, parentKey = "all",
            priorMedianSeconds = 240.0, priorLogSigma = 0.5, priorStrength = 5.0, halfLifeCount = 12.0,
        )

    @Test
    fun `no history returns the documented prior`() {
        val f = fit(emptyList())
        assertThat(f.medianSeconds).isEqualTo(240.0)
        assertThat(f.provenance).isEqualTo(DataProvenance.DEFAULT)
        assertThat(f.personalWeight).isEqualTo(0.0)
    }

    @Test
    fun `plenty of history overrides the prior`() {
        val f = fit(obs(40, 600.0))
        assertThat(f.medianSeconds).isWithin(40.0).of(600.0)
        assertThat(f.provenance).isEqualTo(DataProvenance.LEARNED)
    }

    @Test
    fun `one observation barely moves the prior`() {
        val f = fit(obs(1, 1800.0))
        assertThat(f.medianSeconds).isLessThan(600.0)
    }

    @Test
    fun `a destination with no history inherits the users overall behaviour`() {
        val f = fit(obs(30, 700.0, key = "dest:B"), key = "dest:A")
        assertThat(f.medianSeconds).isWithin(120.0).of(700.0)
        assertThat(f.sourceLabel).contains("overall")
    }

    @Test
    fun `a user override wins outright`() {
        val f = DurationModelFitter.fit(
            observations = obs(50, 900.0), key = "dest:A", parentKey = "all",
            priorMedianSeconds = 240.0, priorLogSigma = 0.5, priorStrength = 5.0, halfLifeCount = 12.0,
            destinationOverrideSeconds = 300.0,
        )
        assertThat(f.medianSeconds).isEqualTo(300.0)
        assertThat(f.provenance).isEqualTo(DataProvenance.USER_DEFINED)
    }

    @Test
    fun `disabling the model zeroes the component`() {
        val f = DurationModelFitter.fit(
            observations = obs(50, 900.0), key = "dest:A", parentKey = "all",
            priorMedianSeconds = 240.0, priorLogSigma = 0.5, priorStrength = 5.0, halfLifeCount = 12.0,
            enabled = false,
        )
        assertThat(f.medianSeconds).isEqualTo(0.0)
        assertThat(f.sample(Xoshiro256(1))).isEqualTo(0.0)
    }

    @Test
    fun `quantiles are ordered and the mean exceeds the median for a skewed fit`() {
        val f = fit(obs(40, 600.0, spread = 0.5))
        assertThat(f.quantile(0.1)).isLessThan(f.quantile(0.5))
        assertThat(f.quantile(0.5)).isLessThan(f.quantile(0.9))
        assertThat(f.meanSeconds).isAtLeast(f.medianSeconds)
    }

    @Test
    fun `samples are non-negative and bounded`() {
        val f = fit(obs(40, 600.0, spread = 0.6))
        val rng = Xoshiro256(11L)
        repeat(20_000) {
            val s = f.sample(rng)
            assertThat(s).isAtLeast(0.0)
            assertThat(s).isAtMost(f.medianSeconds * 12)
        }
    }
}
