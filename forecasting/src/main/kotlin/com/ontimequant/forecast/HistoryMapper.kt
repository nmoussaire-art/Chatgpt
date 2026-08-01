package com.ontimequant.forecast

import com.ontimequant.model.CompletedTrip

/**
 * Turns stored trips into the observation streams each sub-model consumes.
 *
 * Kept in one place so the live app, the demo scenario and the backtester all learn from
 * exactly the same derivation — there is no second, subtly different code path.
 */
object HistoryMapper {

    fun toLearningHistory(trips: List<CompletedTrip>): LearningHistory {
        val usable = trips.filter { !it.excludedFromLearning }
        return LearningHistory(
            travel = usable.mapNotNull { trip ->
                if (trip.predictedTravelSeconds <= 0 || trip.actualTravelSeconds <= 0) null
                else TravelObservation(
                    at = trip.actualDeparture,
                    routeKey = trip.journeyId ?: "${trip.originId}->${trip.destinationId}",
                    pairKey = "${trip.originId}->${trip.destinationId}",
                    bucket = trip.timeBucket,
                    dayType = trip.dayType,
                    dayOfWeek = trip.dayOfWeek,
                    regime = trip.trafficRegime,
                    predictedSeconds = trip.predictedTravelSeconds,
                    actualSeconds = trip.actualTravelSeconds,
                    weatherSeverity = trip.weatherSeverity,
                    eventPressure = trip.eventPressure,
                )
            },
            preparation = usable.mapNotNull { trip ->
                trip.preparationSeconds?.takeIf { it > 0 }?.let {
                    DurationObservation(trip.actualDeparture, "origin:${trip.originId}", "all", it)
                }
            },
            parking = usable.mapNotNull { trip ->
                trip.parkingSeconds?.takeIf { it > 0 }?.let {
                    DurationObservation(trip.actualArrival, "dest:${trip.destinationId}", "all", it)
                }
            },
            walking = usable.mapNotNull { trip ->
                trip.walkingSeconds?.takeIf { it > 0 }?.let {
                    DurationObservation(trip.actualArrival, "dest:${trip.destinationId}", "all", it)
                }
            },
        )
    }

    /** The preparation model for a given origin, fitted from stored trips. */
    fun preparationFor(
        trips: List<CompletedTrip>,
        originId: String,
        priors: ModelPriors = ModelPriors.DEFAULT,
        userDefaultSeconds: Double? = null,
        enabled: Boolean = true,
    ): FittedDuration = DurationModelFitter.fit(
        observations = toLearningHistory(trips).preparation,
        key = "origin:$originId",
        parentKey = "all",
        priorMedianSeconds = priors.preparationMedianMinutes * 60,
        priorLogSigma = priors.preparationLogSigma,
        priorStrength = priors.kappaDuration,
        halfLifeCount = priors.halfLifeCount,
        userDefaultSeconds = userDefaultSeconds,
        enabled = enabled,
    )
}
