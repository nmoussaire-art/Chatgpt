package com.batterycast.quant.forecasting.clean

import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.ObservationQuality
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.QualityNote
import kotlin.math.abs

/** A run of observations in one continuous, comparable state. */
data class BatterySegment(
    val observations: List<BatteryObservation>,
    val kind: SegmentKind,
) {
    val startMs: Long get() = observations.first().timestampMs
    val endMs: Long get() = observations.last().timestampMs

    /**
     * Duration from monotonic time, so a clock change during the segment cannot stretch or
     * collapse it.
     */
    val durationHours: Double
        get() = (observations.last().elapsedRealtimeMs - observations.first().elapsedRealtimeMs) / 3_600_000.0

    val startPercent: Double get() = observations.first().batteryPercent
    val endPercent: Double get() = observations.last().batteryPercent

    /** Positive while discharging: how many percentage points were lost. */
    val percentDrop: Double get() = startPercent - endPercent

    val plugType: PlugType get() = observations.last().plugType

    val meanTemperatureCelsius: Double?
        get() = observations.mapNotNull { it.temperatureCelsius }.takeIf { it.isNotEmpty() }?.average()

    val screenOnFraction: Double
        get() = observations.count { it.screenInteractive }.toDouble() / observations.size

    val powerSaveFraction: Double
        get() = observations.count { it.powerSaveEnabled }.toDouble() / observations.size

    /** A segment can only support a rate estimate if it actually spans time and points. */
    fun isUsable(minimumHours: Double, minimumPoints: Int): Boolean =
        observations.size >= minimumPoints && durationHours >= minimumHours
}

enum class SegmentKind { DISCHARGE, CHARGE }

/** The result of cleaning, including what was discarded and why. */
data class CleanedHistory(
    val observations: List<BatteryObservation>,
    val segments: List<BatterySegment>,
    val rejected: List<RejectedObservation>,
) {
    val dischargeSegments: List<BatterySegment> get() = segments.filter { it.kind == SegmentKind.DISCHARGE }
    val chargeSegments: List<BatterySegment> get() = segments.filter { it.kind == SegmentKind.CHARGE }

    /** Monotonic time actually covered by usable observations, in hours. */
    val observedSpanHours: Double
        get() {
            if (observations.size < 2) return 0.0
            return segments.sumOf { it.durationHours }
        }
}

data class RejectedObservation(
    val observation: BatteryObservation,
    val reason: RejectionReason,
)

enum class RejectionReason {
    DUPLICATE_TIMESTAMP,
    FLAGGED_OUTLIER,
    IMPOSSIBLE_RATE,
    RECALIBRATION_JUMP,
    NON_MONOTONIC_TIME,
}

/**
 * Turns a raw observation history into something a model can be fitted to.
 *
 * Cleaning is where most of the real-world messiness of battery data is handled, and it is
 * deliberately conservative: a reading is dropped only when it is internally contradictory, never
 * because it is inconvenient. Nothing is ever inserted to replace what was dropped — a gap stays
 * a gap, and the segment simply ends.
 */
object ObservationCleaner {

    /**
     * Beyond this rate the reading is not describing discharge. Even sustained gaming rarely
     * exceeds 1 %/min; 3 %/min is a jump, a recalibration, or an unobserved reboot.
     */
    private const val MAX_PLAUSIBLE_PERCENT_PER_MINUTE = 3.0

    /** Gap after which the device's behaviour in between is genuinely unknown. */
    private const val SEGMENT_BREAK_GAP_MS = 2 * 60 * 60 * 1000L

    fun clean(raw: List<BatteryObservation>): CleanedHistory {
        if (raw.isEmpty()) return CleanedHistory(emptyList(), emptyList(), emptyList())

        val rejected = mutableListOf<RejectedObservation>()

        // 1. Chronological order, with duplicate timestamps collapsed. When two readings share a
        //    timestamp the higher-quality one is kept; a tie keeps the first.
        val sorted = raw.sortedWith(compareBy({ it.timestampMs }, { it.id }))
        val deduplicated = mutableListOf<BatteryObservation>()
        for (observation in sorted) {
            val previous = deduplicated.lastOrNull()
            if (previous != null && previous.timestampMs == observation.timestampMs) {
                val keepExisting = previous.quality <= observation.quality
                if (keepExisting) {
                    rejected += RejectedObservation(observation, RejectionReason.DUPLICATE_TIMESTAMP)
                    continue
                }
                rejected += RejectedObservation(previous, RejectionReason.DUPLICATE_TIMESTAMP)
                deduplicated.removeAt(deduplicated.lastIndex)
            }
            deduplicated += observation
        }

        // 2. Drop readings the telemetry layer already judged untrustworthy.
        val plausible = mutableListOf<BatteryObservation>()
        for (observation in deduplicated) {
            when {
                observation.quality == ObservationQuality.SUSPECTED_OUTLIER &&
                    QualityNote.SUSPECTED_RECALIBRATION in observation.notes ->
                    rejected += RejectedObservation(observation, RejectionReason.RECALIBRATION_JUMP)

                observation.quality == ObservationQuality.SUSPECTED_OUTLIER ->
                    rejected += RejectedObservation(observation, RejectionReason.FLAGGED_OUTLIER)

                else -> plausible += observation
            }
        }

        // 3. Second pass over what survived: a pair can still imply an impossible rate once the
        //    reading in between has been removed.
        val accepted = mutableListOf<BatteryObservation>()
        for (observation in plausible) {
            val previous = accepted.lastOrNull()
            if (previous == null) {
                accepted += observation
                continue
            }
            val elapsedMinutes = (observation.elapsedRealtimeMs - previous.elapsedRealtimeMs) / 60_000.0
            if (elapsedMinutes < 0) {
                // The monotonic clock restarted, so the device rebooted. The reading is perfectly
                // good; it simply cannot be differenced against the one before it, and the
                // segmenter breaks the run at this point instead.
                accepted += observation
                continue
            }
            if (elapsedMinutes > 0) {
                val rate = abs(observation.batteryPercent - previous.batteryPercent) / elapsedMinutes
                if (rate > MAX_PLAUSIBLE_PERCENT_PER_MINUTE) {
                    rejected += RejectedObservation(observation, RejectionReason.IMPOSSIBLE_RATE)
                    continue
                }
            }
            accepted += observation
        }

        return CleanedHistory(
            observations = accepted,
            segments = segment(accepted),
            rejected = rejected,
        )
    }

    /**
     * Splits a cleaned history into comparable runs.
     *
     * A segment breaks when the charging state flips, when the device reboots, when the fuel
     * gauge resets its counter, or when the gap between readings is long enough that the app has
     * no idea what happened in between. Each of those is a point past which two readings simply
     * cannot be differenced.
     */
    fun segment(observations: List<BatteryObservation>): List<BatterySegment> {
        if (observations.size < 2) return emptyList()

        val segments = mutableListOf<BatterySegment>()
        var current = mutableListOf(observations.first())

        fun flush() {
            if (current.size >= 2) {
                val charging = current.count { it.isCharging } * 2 >= current.size
                segments += BatterySegment(
                    observations = current.toList(),
                    kind = if (charging) SegmentKind.CHARGE else SegmentKind.DISCHARGE,
                )
            }
            current = mutableListOf()
        }

        for (index in 1 until observations.size) {
            val previous = observations[index - 1]
            val observation = observations[index]

            val stateFlipped = previous.isCharging != observation.isCharging
            // A monotonic clock reset is the reliable reboot signal; the derived boot-session id
            // also moves on a wall-clock change, which must not break a segment.
            val rebooted = observation.elapsedRealtimeMs < previous.elapsedRealtimeMs
            val counterReset = QualityNote.CHARGE_COUNTER_RESET in observation.notes
            val longGap = (observation.elapsedRealtimeMs - previous.elapsedRealtimeMs) > SEGMENT_BREAK_GAP_MS

            if (stateFlipped || rebooted || counterReset || longGap) {
                flush()
                // The reading that caused the break starts the next segment, so no data is lost.
                current = mutableListOf(observation)
            } else {
                current += observation
            }
        }
        flush()

        return segments
    }
}
