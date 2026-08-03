package com.batterycast.quant.forecasting.clean

import com.batterycast.quant.fixtures.ObservationFixtures
import com.batterycast.quant.telemetry.model.ObservationQuality
import com.batterycast.quant.telemetry.model.QualityNote
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ObservationCleanerTest {

    @Test
    fun `an empty history produces an empty result rather than an error`() {
        val cleaned = ObservationCleaner.clean(emptyList())

        assertThat(cleaned.observations).isEmpty()
        assertThat(cleaned.segments).isEmpty()
        assertThat(cleaned.observedSpanHours).isEqualTo(0.0)
    }

    @Test
    fun `observations are sorted chronologically`() {
        val shuffled = ObservationFixtures.steadyDischarge(count = 6).shuffled(java.util.Random(7))

        val cleaned = ObservationCleaner.clean(shuffled)

        assertThat(cleaned.observations.map { it.timestampMs })
            .isInOrder()
    }

    @Test
    fun `duplicate timestamps are collapsed keeping the better reading`() {
        val base = ObservationFixtures.observation(percent = 70.0, quality = ObservationQuality.LOW_PRECISION)
        val better = base.copy(quality = ObservationQuality.HIGH_QUALITY, chargeCounterMicroAh = 2_800_000)

        val cleaned = ObservationCleaner.clean(listOf(base, better))

        assertThat(cleaned.observations).hasSize(1)
        assertThat(cleaned.observations.single().quality).isEqualTo(ObservationQuality.HIGH_QUALITY)
        assertThat(cleaned.rejected.single().reason).isEqualTo(RejectionReason.DUPLICATE_TIMESTAMP)
    }

    @Test
    fun `flagged outliers are removed and reported`() {
        val history = ObservationFixtures.steadyDischarge(count = 6).toMutableList()
        history[3] = history[3].copy(
            quality = ObservationQuality.SUSPECTED_OUTLIER,
            notes = setOf(QualityNote.SUSPECTED_RECALIBRATION),
        )

        val cleaned = ObservationCleaner.clean(history)

        assertThat(cleaned.observations).hasSize(5)
        assertThat(cleaned.rejected.map { it.reason }).contains(RejectionReason.RECALIBRATION_JUMP)
    }

    @Test
    fun `a physically impossible rate between surviving readings is rejected`() {
        val history = listOf(
            ObservationFixtures.observation(offsetMinutes = 0.0, percent = 80.0),
            // 40 points in two minutes: 20 %/min, far past anything a phone can do.
            ObservationFixtures.observation(offsetMinutes = 2.0, percent = 40.0),
            ObservationFixtures.observation(offsetMinutes = 20.0, percent = 78.0),
        )

        val cleaned = ObservationCleaner.clean(history)

        assertThat(cleaned.rejected.map { it.reason }).contains(RejectionReason.IMPOSSIBLE_RATE)
        assertThat(cleaned.observations.map { it.batteryPercent }).containsExactly(80.0, 78.0).inOrder()
    }

    @Test
    fun `discharging runs become discharge segments`() {
        val cleaned = ObservationCleaner.clean(ObservationFixtures.steadyDischarge(count = 8))

        assertThat(cleaned.segments).hasSize(1)
        assertThat(cleaned.segments.single().kind).isEqualTo(SegmentKind.DISCHARGE)
        assertThat(cleaned.dischargeSegments.single().percentDrop).isGreaterThan(0.0)
    }

    @Test
    fun `charging runs become charge segments`() {
        val cleaned = ObservationCleaner.clean(ObservationFixtures.steadyCharge(count = 8))

        assertThat(cleaned.chargeSegments).hasSize(1)
        assertThat(cleaned.chargeSegments.single().kind).isEqualTo(SegmentKind.CHARGE)
    }

    @Test
    fun `a plug event splits the history into separate segments`() {
        val discharge = ObservationFixtures.steadyDischarge(startPercent = 60.0, count = 5, intervalMinutes = 15.0)
        val charge = ObservationFixtures.steadyCharge(
            startPercent = 52.0,
            count = 5,
            intervalMinutes = 15.0,
            startOffsetMinutes = 75.0,
        )

        val cleaned = ObservationCleaner.clean(discharge + charge)

        assertThat(cleaned.dischargeSegments).hasSize(1)
        assertThat(cleaned.chargeSegments).hasSize(1)
    }

    @Test
    fun `a reboot breaks the segment rather than being differenced across`() {
        val before = ObservationFixtures.steadyDischarge(count = 4, intervalMinutes = 15.0)
        // After a reboot elapsedRealtime restarts near zero while the wall clock carries on.
        val after = (0 until 4).map { index ->
            ObservationFixtures.observation(
                offsetMinutes = 60.0 + index * 15.0,
                percent = 70.0 - index,
                bootSessionId = ObservationFixtures.BOOT_SESSION + 999,
                elapsedOverrideMs = 30_000L + index * 15 * 60_000L,
            )
        }

        val cleaned = ObservationCleaner.clean(before + after)

        assertThat(cleaned.segments).hasSize(2)
        cleaned.segments.forEach { segment ->
            assertThat(segment.observations.map { it.bootSessionId }.distinct()).hasSize(1)
            assertThat(segment.durationHours).isGreaterThan(0.0)
        }
    }

    @Test
    fun `a long unobserved gap breaks the segment`() {
        val before = ObservationFixtures.steadyDischarge(count = 4, intervalMinutes = 15.0)
        // Doze deferred everything for four hours; what happened in between is unknown.
        val after = (0 until 4).map { index ->
            ObservationFixtures.observation(offsetMinutes = 285.0 + index * 15.0, percent = 40.0 - index)
        }

        val cleaned = ObservationCleaner.clean(before + after)

        assertThat(cleaned.segments).hasSize(2)
    }

    @Test
    fun `a charge counter reset breaks the segment`() {
        val history = ObservationFixtures.steadyDischarge(count = 6, withChargeCounter = true).toMutableList()
        history[3] = history[3].copy(notes = setOf(QualityNote.CHARGE_COUNTER_RESET))

        val cleaned = ObservationCleaner.clean(history)

        assertThat(cleaned.segments.size).isAtLeast(2)
    }

    @Test
    fun `segment duration comes from monotonic time so a clock change cannot distort it`() {
        val history = listOf(
            ObservationFixtures.observation(offsetMinutes = 0.0, percent = 80.0),
            ObservationFixtures.observation(offsetMinutes = 15.0, percent = 78.0),
            ObservationFixtures.observation(offsetMinutes = 30.0, percent = 76.0),
            // Wall clock jumps forward an hour; monotonic time still says 45 minutes.
            ObservationFixtures.observation(offsetMinutes = 45.0, percent = 74.0, clockSkewMs = 3_600_000L),
        )

        val cleaned = ObservationCleaner.clean(history)
        val segment = cleaned.segments.single()

        assertThat(segment.durationHours).isWithin(1e-6).of(0.75)
    }

    @Test
    fun `a single observation cannot form a segment`() {
        val cleaned = ObservationCleaner.clean(listOf(ObservationFixtures.observation()))

        assertThat(cleaned.observations).hasSize(1)
        assertThat(cleaned.segments).isEmpty()
    }

    @Test
    fun `screen-on fraction reflects what was actually observed`() {
        val history = listOf(
            ObservationFixtures.observation(offsetMinutes = 0.0, percent = 80.0, screenOn = true),
            ObservationFixtures.observation(offsetMinutes = 15.0, percent = 77.0, screenOn = true),
            ObservationFixtures.observation(offsetMinutes = 30.0, percent = 75.0, screenOn = false),
            ObservationFixtures.observation(offsetMinutes = 45.0, percent = 74.0, screenOn = false),
        )

        val segment = ObservationCleaner.clean(history).segments.single()

        assertThat(segment.screenOnFraction).isWithin(1e-9).of(0.5)
    }
}
