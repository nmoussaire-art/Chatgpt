package com.batterycast.quant.forecast

import com.batterycast.quant.model.BatteryObservation

enum class BatterySegmentType { CHARGING, DISCHARGING }

data class BatterySegment(
    val type: BatterySegmentType,
    val observations: List<BatteryObservation>
)

class BatterySegmenter(
    private val maximumGapMs: Long = 3 * 60 * 60_000L
) {
    fun detect(observations: List<BatteryObservation>): List<BatterySegment> {
        val sorted = observations.sortedBy { it.timestamp }
        if (sorted.isEmpty()) return emptyList()

        val segments = mutableListOf<BatterySegment>()
        var currentType = typeOf(sorted.first())
        var current = mutableListOf(sorted.first())

        for (observation in sorted.drop(1)) {
            val previous = current.last()
            val nextType = typeOf(observation)
            val boundary = nextType != currentType ||
                !ObservationCleaner.sameBootSession(previous, observation) ||
                observation.timestamp <= previous.timestamp ||
                observation.timestamp - previous.timestamp > maximumGapMs

            if (boundary) {
                segments += BatterySegment(currentType, current.toList())
                currentType = nextType
                current = mutableListOf(observation)
            } else {
                current += observation
            }
        }
        segments += BatterySegment(currentType, current.toList())
        return segments
    }

    private fun typeOf(observation: BatteryObservation): BatterySegmentType =
        if (observation.isCharging) BatterySegmentType.CHARGING else BatterySegmentType.DISCHARGING
}
