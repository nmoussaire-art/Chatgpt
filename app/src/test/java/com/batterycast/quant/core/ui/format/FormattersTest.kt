package com.batterycast.quant.core.ui.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

class FormattersTest {

    private val utc = ZoneId.of("UTC")
    private val nowMs = 1_700_000_000_000L

    @Test
    fun `battery percentages never show a decimal the sensor cannot support`() {
        assertThat(Formatters.percent(46.7)).isEqualTo("47%")
        assertThat(Formatters.percent(17.0)).isEqualTo("17%")
        assertThat(Formatters.percent(null)).isEqualTo("—")
    }

    @Test
    fun `probabilities are whole percentages`() {
        assertThat(Formatters.probability(0.842)).isEqualTo("84%")
        assertThat(Formatters.probability(1.0)).isEqualTo("100%")
        assertThat(Formatters.probability(null)).isEqualTo("—")
    }

    @Test
    fun `rates carry one decimal because the regression supports it`() {
        assertThat(Formatters.ratePerHour(8.47)).isEqualTo("8.5%/h")
        assertThat(Formatters.ratePerHour(null)).isEqualTo("—")
    }

    @Test
    fun `durations are rounded to five minutes beyond an hour`() {
        assertThat(Formatters.duration(23 * 60_000L)).isEqualTo("23 min")
        assertThat(Formatters.duration(60 * 60_000L)).isEqualTo("1 hour")
        assertThat(Formatters.duration((3 * 60 + 22) * 60_000L)).isEqualTo("3 hr 20 min")
        assertThat(Formatters.duration((2 * 60 + 58) * 60_000L)).isEqualTo("3 hours")
        assertThat(Formatters.duration(20_000L)).isEqualTo("under a minute")
        assertThat(Formatters.duration(null)).isEqualTo("—")
        assertThat(Formatters.duration(-1L)).isEqualTo("—")
    }

    @Test
    fun `a time on another day is qualified so it cannot be misread`() {
        // 1 700 000 000 000 is 22:13 UTC, so six hours on lands early the next day.
        val tomorrow = nowMs + 6 * 3_600_000L

        val today = Formatters.clockTimeWithDay(nowMs, nowMs, utc)
        val later = Formatters.clockTimeWithDay(tomorrow, nowMs, utc)

        assertThat(today).doesNotContain("tomorrow")
        assertThat(later).contains("tomorrow")
    }

    @Test
    fun `relative age reads naturally at every scale`() {
        assertThat(Formatters.relativeAge(20_000L)).isEqualTo("just now")
        assertThat(Formatters.relativeAge(60_000L)).isEqualTo("1 minute ago")
        assertThat(Formatters.relativeAge(25 * 60_000L)).isEqualTo("25 minutes ago")
        assertThat(Formatters.relativeAge(90 * 60_000L)).isEqualTo("1 hour ago")
        assertThat(Formatters.relativeAge(5 * 3_600_000L)).isEqualTo("5 hours ago")
        assertThat(Formatters.relativeAge(3 * 24 * 3_600_000L)).isEqualTo("3 days ago")
    }

    @Test
    fun `a percentage range reads as a range`() {
        assertThat(Formatters.percentRange(11.2, 24.4)).isEqualTo("11%–24%")
        assertThat(Formatters.percentRange(null, 24.0)).isEqualTo("—")
    }

    @Test
    fun `signed differences carry their sign`() {
        assertThat(Formatters.signedPercentPoints(4.4)).isEqualTo("+4 pts")
        assertThat(Formatters.signedPercentPoints(-4.4)).isEqualTo("-4 pts")
        assertThat(Formatters.signedPercentPoints(0.1)).isEqualTo("0 pts")
    }

    @Test
    fun `relative change is expressed as faster or slower`() {
        assertThat(Formatters.relativeChange(1.28)).isEqualTo("28% faster")
        assertThat(Formatters.relativeChange(0.75)).isEqualTo("25% slower")
        assertThat(Formatters.relativeChange(1.0)).isEqualTo("unchanged")
        assertThat(Formatters.relativeChange(null)).isEqualTo("—")
    }

    @Test
    fun `probability words match the numeric thresholds used for colour`() {
        assertThat(Formatters.probabilityWords(0.97)).isEqualTo("almost certain")
        assertThat(Formatters.probabilityWords(0.88)).isEqualTo("very likely")
        assertThat(Formatters.probabilityWords(0.7)).isEqualTo("likely")
        assertThat(Formatters.probabilityWords(0.5)).isEqualTo("roughly even")
        assertThat(Formatters.probabilityWords(0.3)).isEqualTo("unlikely")
        assertThat(Formatters.probabilityWords(0.05)).isEqualTo("very unlikely")
    }

    @Test
    fun `electrical readings are formatted at the precision they are measured to`() {
        assertThat(Formatters.milliAmps(452.6)).isEqualTo("453 mA")
        assertThat(Formatters.watts(1.7432)).isEqualTo("1.74 W")
        assertThat(Formatters.temperature(31.24)).isEqualTo("31.2 °C")
        assertThat(Formatters.voltage(3.8512)).isEqualTo("3.85 V")
        assertThat(Formatters.milliAmpHours(3211.7)).isEqualTo("3212 mAh")
    }

    @Test
    fun `every formatter renders a missing measurement as an em dash rather than zero`() {
        assertThat(Formatters.milliAmps(null)).isEqualTo("—")
        assertThat(Formatters.watts(null)).isEqualTo("—")
        assertThat(Formatters.temperature(null)).isEqualTo("—")
        assertThat(Formatters.voltage(null)).isEqualTo("—")
        assertThat(Formatters.milliAmpHours(null)).isEqualTo("—")
        assertThat(Formatters.clockTime(null)).isEqualTo("—")
        assertThat(Formatters.dateAndTime(null)).isEqualTo("—")
    }
}
