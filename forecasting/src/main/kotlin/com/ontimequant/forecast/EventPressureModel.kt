package com.ontimequant.forecast

import com.ontimequant.model.DataProvenance
import com.ontimequant.model.EventPressure
import com.ontimequant.model.EventPressureContribution
import com.ontimequant.model.GeoPoint
import com.ontimequant.model.NearbyEvent
import com.ontimequant.model.distanceTo
import com.ontimequant.model.distanceToPath
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Converts nearby events into a single bounded **risk** score in `[0, 1]`.
 *
 * Explicitly *not* a delay estimate. A concert 400 m from the route that lets out
 * fifteen minutes before you pass is a reason to be less certain, not a reason to
 * promise a twelve-minute delay. The score therefore feeds uncertainty first and only
 * shifts the central estimate once it is unambiguous (see [ModelPriors.eventMeanThreshold]).
 *
 * Score for one event is the product of four bounded kernels:
 *
 * ```
 *   proximity = max( exp(−d_route/1200 m), 0.8 · exp(−d_dest/900 m) )
 *   timing    = triangular window around start (inbound pressure) and end (outbound)
 *   size      = 0.35 + 0.65 · ln(1+capacity) / ln(1+60000)
 *   e_i       = proximity · timing · size
 * ```
 *
 * Overlapping events combine with a noisy-OR, which saturates rather than summing past 1:
 * `E = 1 − Π(1 − e_i)`.
 */
object EventPressureModel {

    private const val ROUTE_DECAY_METRES = 1200.0
    private const val DEST_DECAY_METRES = 900.0
    private const val MAX_CAPACITY_REFERENCE = 60_000.0
    private const val INBOUND_WINDOW_MINUTES = 75.0
    private const val OUTBOUND_WINDOW_MINUTES = 50.0
    private const val ASSUMED_EVENT_MINUTES = 150L

    fun compute(
        events: List<NearbyEvent>,
        routePolyline: List<GeoPoint>,
        destination: GeoPoint,
        travelWindowStart: Instant,
        travelWindowEnd: Instant,
        provenance: DataProvenance,
    ): EventPressure {
        if (events.isEmpty()) {
            return EventPressure(0.0, emptyList(), provenance)
        }
        val contributions = ArrayList<EventPressureContribution>()
        var noisyOr = 0.0
        val midpoint = Instant.ofEpochSecond(
            (travelWindowStart.epochSecond + travelWindowEnd.epochSecond) / 2,
        )

        for (event in events) {
            val dRoute = if (routePolyline.size >= 2) {
                event.venuePoint.distanceToPath(routePolyline)
            } else {
                event.venuePoint.distanceTo(destination)
            }
            val dDest = event.venuePoint.distanceTo(destination)

            val proximity = max(
                exp(-dRoute / ROUTE_DECAY_METRES),
                0.8 * exp(-dDest / DEST_DECAY_METRES),
            ).coerceIn(0.0, 1.0)
            if (proximity < 0.02) continue

            val timing = timingKernel(event, travelWindowStart, travelWindowEnd)
            if (timing <= 0.0) continue

            val capacity = event.venueCapacity?.toDouble()
            val sizeFromCapacity = if (capacity != null && capacity > 0) {
                ln(1 + capacity) / ln(1 + MAX_CAPACITY_REFERENCE)
            } else {
                // No capacity published: use provider importance if present, else a neutral 0.45.
                event.importance ?: 0.45
            }
            val size = (0.35 + 0.65 * sizeFromCapacity.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)

            val e = (proximity * timing * size).coerceIn(0.0, 1.0)
            if (e < 0.01) continue

            noisyOr = 1.0 - (1.0 - noisyOr) * (1.0 - e)
            contributions += EventPressureContribution(
                eventName = event.name,
                venueName = event.venueName,
                metresFromRoute = dRoute,
                metresFromDestination = dDest,
                minutesToStart = Duration.between(midpoint, event.startTime).toMinutes(),
                contribution = e,
            )
        }

        return EventPressure(
            score = noisyOr.coerceIn(0.0, 1.0),
            contributors = contributions.sortedByDescending { it.contribution }.take(5),
            provenance = provenance,
        )
    }

    /**
     * Traffic pressure peaks just *before* an event starts (inbound arrivals) and just
     * *after* it ends (outbound egress, which is sharper and shorter). Both are modelled
     * as triangular kernels evaluated at the moment the user is on the road.
     */
    private fun timingKernel(event: NearbyEvent, windowStart: Instant, windowEnd: Instant): Double {
        val onRoad = Instant.ofEpochSecond((windowStart.epochSecond + windowEnd.epochSecond) / 2)
        val minutesToStart = Duration.between(onRoad, event.startTime).toMinutes().toDouble()
        val end = event.endTime ?: event.startTime.plusSeconds(ASSUMED_EVENT_MINUTES * 60)
        val minutesToEnd = Duration.between(onRoad, end).toMinutes().toDouble()

        // Inbound: pressure rises from 75 minutes before to the start, then falls quickly.
        val inbound = when {
            minutesToStart in 0.0..INBOUND_WINDOW_MINUTES -> 1.0 - minutesToStart / INBOUND_WINDOW_MINUTES
            minutesToStart in -20.0..0.0 -> 1.0 + minutesToStart / 20.0
            else -> 0.0
        }
        // Outbound: sharp spike in the 50 minutes after the end.
        val outbound = when {
            minutesToEnd in -OUTBOUND_WINDOW_MINUTES..0.0 -> 1.0 + minutesToEnd / OUTBOUND_WINDOW_MINUTES
            else -> 0.0
        }
        return min(1.0, max(abs(inbound), abs(outbound)))
    }
}
