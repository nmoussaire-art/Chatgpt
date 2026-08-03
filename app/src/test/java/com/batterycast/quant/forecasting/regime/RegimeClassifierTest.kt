package com.batterycast.quant.forecasting.regime

import com.batterycast.quant.fixtures.ObservationFixtures
import com.batterycast.quant.forecasting.clean.ObservationCleaner
import com.batterycast.quant.forecasting.model.UsageRegime
import com.batterycast.quant.telemetry.model.UsageCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

class RegimeClassifierTest {

    private val classifier = RegimeClassifier(ZoneId.of("UTC"))

    private fun bands(
        screenOffRates: List<Double> = List(20) { 0.5 },
        screenOnRates: List<Double> = (1..20).map { it.toDouble() },
        chargeRates: List<Double> = List(20) { 40.0 },
    ) = RegimeThresholds.learn(
        screenOffRates.map { RawInterval(it, screenOn = false, charging = false) } +
            screenOnRates.map { RawInterval(it, screenOn = true, charging = false) } +
            chargeRates.map { RawInterval(it, screenOn = false, charging = true) },
    )

    @Test
    fun `with no history the classifier uses broad categories only`() {
        val empty = RegimeThresholds.EMPTY

        val screenOff = classifier.classify(
            ratePerHour = 9.0,
            observation = ObservationFixtures.observation(screenOn = false),
            charging = false,
            thresholds = empty,
        )
        val screenOn = classifier.classify(
            ratePerHour = 25.0,
            observation = ObservationFixtures.observation(screenOn = true),
            charging = false,
            thresholds = empty,
        )

        assertThat(screenOff).isEqualTo(UsageRegime.STANDBY)
        assertThat(screenOn).isEqualTo(UsageRegime.INTERACTIVE)
    }

    @Test
    fun `bands are not learned until enough intervals exist`() {
        val thin = RegimeThresholds.learn(
            List(4) { RawInterval(5.0, screenOn = true, charging = false) },
        )

        assertThat(thin.hasScreenOnBands).isFalse()
        assertThat(thin.screenOnP25).isNull()
    }

    @Test
    fun `screen-on drain is banded against this device's own distribution`() {
        val thresholds = bands()
        val observation = ObservationFixtures.observation(screenOn = true)

        assertThat(classifier.classify(2.0, observation, false, thresholds)).isEqualTo(UsageRegime.LIGHT_USE)
        assertThat(classifier.classify(10.0, observation, false, thresholds)).isEqualTo(UsageRegime.INTERACTIVE)
        assertThat(classifier.classify(19.0, observation, false, thresholds)).isEqualTo(UsageRegime.HEAVY_USE)
    }

    @Test
    fun `elevated screen-off drain becomes background activity`() {
        val thresholds = bands(screenOffRates = List(20) { 0.4 })
        val observation = ObservationFixtures.observation(screenOn = false)

        assertThat(classifier.classify(0.3, observation, false, thresholds)).isEqualTo(UsageRegime.STANDBY)
        assertThat(classifier.classify(4.0, observation, false, thresholds))
            .isEqualTo(UsageRegime.BACKGROUND_ACTIVE)
    }

    @Test
    fun `a specific activity is never named without corroborating usage evidence`() {
        val thresholds = bands()
        // Top-band drain, but nothing tells us what kind of app was in the foreground.
        val withoutEvidence = ObservationFixtures.observation(screenOn = true, usageCategory = null)

        val regime = classifier.classify(20.0, withoutEvidence, false, thresholds)

        assertThat(regime).isEqualTo(UsageRegime.HEAVY_USE)
    }

    @Test
    fun `usage evidence plus top-band drain refines the regime`() {
        val thresholds = bands()

        val navigation = classifier.classify(
            ratePerHour = 20.0,
            observation = ObservationFixtures.observation(
                screenOn = true,
                usageCategory = UsageCategory.MAPS_NAVIGATION,
            ),
            charging = false,
            thresholds = thresholds,
        )
        val video = classifier.classify(
            ratePerHour = 20.0,
            observation = ObservationFixtures.observation(screenOn = true, usageCategory = UsageCategory.VIDEO),
            charging = false,
            thresholds = thresholds,
        )
        val game = classifier.classify(
            ratePerHour = 20.0,
            observation = ObservationFixtures.observation(screenOn = true, usageCategory = UsageCategory.GAME),
            charging = false,
            thresholds = thresholds,
        )

        assertThat(navigation).isEqualTo(UsageRegime.NAVIGATION_LIKE)
        assertThat(video).isEqualTo(UsageRegime.MEDIA_LIKE)
        assertThat(game).isEqualTo(UsageRegime.GAMING_LIKE)
    }

    @Test
    fun `usage evidence does not upgrade mid-band drain`() {
        val thresholds = bands()
        // A maps app open at ordinary drain is not a navigation session.
        val regime = classifier.classify(
            ratePerHour = 10.0,
            observation = ObservationFixtures.observation(
                screenOn = true,
                usageCategory = UsageCategory.MAPS_NAVIGATION,
            ),
            charging = false,
            thresholds = thresholds,
        )

        assertThat(regime).isEqualTo(UsageRegime.INTERACTIVE)
    }

    @Test
    fun `charging above eighty percent is always taper regardless of history`() {
        val regime = classifier.classify(
            ratePerHour = 30.0,
            observation = ObservationFixtures.observation(percent = 85.0, charging = true),
            charging = true,
            thresholds = RegimeThresholds.EMPTY,
        )

        assertThat(regime).isEqualTo(UsageRegime.CHARGING_TAPER)
    }

    @Test
    fun `fast charging is relative to this device's observed charging speed`() {
        val thresholds = bands(chargeRates = List(20) { 30.0 })
        val observation = ObservationFixtures.observation(percent = 40.0, charging = true)

        assertThat(classifier.classify(30.0, observation, true, thresholds)).isEqualTo(UsageRegime.CHARGING)
        assertThat(classifier.classify(70.0, observation, true, thresholds)).isEqualTo(UsageRegime.FAST_CHARGING)
    }

    @Test
    fun `intervals shorter than five minutes are discarded as rounding noise`() {
        val history = listOf(
            ObservationFixtures.observation(offsetMinutes = 0.0, percent = 80.0),
            ObservationFixtures.observation(offsetMinutes = 1.0, percent = 79.0),
            ObservationFixtures.observation(offsetMinutes = 2.0, percent = 79.0),
            ObservationFixtures.observation(offsetMinutes = 20.0, percent = 77.0),
        )
        val segment = ObservationCleaner.clean(history).segments.single()

        val intervals = classifier.classifySegment(segment, RegimeThresholds.EMPTY)

        assertThat(intervals).hasSize(1)
        assertThat(intervals.single().durationHours).isWithin(1e-6).of(18.0 / 60.0)
    }

    @Test
    fun `classified intervals carry a state key that includes the regime`() {
        val history = ObservationFixtures.steadyDischarge(count = 6, intervalMinutes = 15.0)
        val segment = ObservationCleaner.clean(history).segments.single()

        val intervals = classifier.classifySegment(segment, RegimeThresholds.EMPTY)

        assertThat(intervals).isNotEmpty()
        intervals.forEach { interval ->
            assertThat(interval.stateKey.regime).isEqualTo(interval.regime)
            assertThat(interval.stateKey.hierarchy().first()).isEqualTo("global")
            assertThat(interval.stateKey.hierarchy()).hasSize(6)
        }
    }

    @Test
    fun `charging intervals report a positive rate in the charging direction`() {
        val history = ObservationFixtures.steadyCharge(count = 6, intervalMinutes = 15.0)
        val segment = ObservationCleaner.clean(history).chargeSegments.single()

        val intervals = classifier.classifySegment(segment, RegimeThresholds.EMPTY)

        assertThat(intervals).isNotEmpty()
        intervals.forEach { assertThat(it.ratePerHour).isGreaterThan(0.0) }
    }
}
