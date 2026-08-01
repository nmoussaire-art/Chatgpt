package com.ontimequant.forecast

/**
 * Every number the engine uses before it has learned anything lives here, in one place,
 * with a stated justification. Nothing in the UI is allowed to invent a value: if a
 * component has no observations behind it, it is one of these documented priors and is
 * labelled `DEFAULT` in the forecast breakdown.
 */
data class ModelPriors(
    /**
     * Prior mean of `log(actual travel / routing-predicted travel)`.
     *
     * Traffic-aware routing APIs measure road segment traversal. Real door-to-door road
     * time additionally contains pulling out of a parking space, the last-100-metres
     * crawl and the queue at the destination approach. A small positive prior (+3%)
     * is the conservative choice; it is quickly overwritten by personal data.
     */
    val logBias: Double = 0.03,

    /**
     * Prior scale of `log(actual / predicted)` in moderate traffic — a 20% typical
     * relative error, which is in line with published traffic-ETA error studies and is
     * deliberately on the wide side so that a day-one forecast is not over-confident.
     */
    val logSigma: Double = 0.20,

    /** Probability that a journey hits a genuine disruption (incident, closure, event let-out). */
    val incidentProbability: Double = 0.06,

    /**
     * Mean extra minutes given that a disruption occurred, as a fraction of the
     * *traffic-aware* duration with an absolute floor. Scaling on the congested rather
     * than the free-flow duration is deliberate: an incident on an already-loaded network
     * costs far more than the same incident at 3 a.m. Modelled as an exponential right
     * tail, which is what makes the arrival distribution skewed rather than Gaussian.
     */
    val incidentMeanFraction: Double = 0.45,
    val incidentMeanFloorMinutes: Double = 4.0,

    /** Preparation delay: median minutes between "I should leave" and actually moving. */
    val preparationMedianMinutes: Double = 5.0,
    val preparationLogSigma: Double = 0.45,

    /** Parking search + park + exit vehicle. */
    val parkingMedianMinutes: Double = 4.0,
    val parkingLogSigma: Double = 0.50,

    /** Walk from parking to the destination door. */
    val walkingMedianMinutes: Double = 3.0,
    val walkingLogSigma: Double = 0.35,

    /**
     * Prior sensitivity of log travel time to the bounded weather severity index.
     * +7% at maximum severity. Deliberately modest: the engine must not assume "rain
     * adds N minutes", it must learn whether this user's journeys are weather sensitive.
     */
    val weatherLogCoefficient: Double = 0.07,
    /** Ridge precision on the weather coefficient — equivalent to ~10 pseudo-observations. */
    val weatherPriorPrecision: Double = 10.0,
    /** Multiplicative widening of residual scale at maximum weather severity. */
    val weatherSigmaGain: Double = 0.30,

    /** Widening applied when weather could not be fetched at all. Widen, never invent. */
    val missingWeatherSigmaGain: Double = 0.06,

    /** Event pressure: widening of residual scale at maximum pressure. */
    val eventSigmaGain: Double = 0.45,
    /** Extra incident probability at maximum event pressure. */
    val eventIncidentGain: Double = 0.12,
    /**
     * Event pressure only shifts the *mean* once it is unambiguous. Below the threshold it
     * is pure uncertainty. This keeps event data a risk feature, not a guaranteed delay.
     */
    val eventMeanThreshold: Double = 0.55,
    val eventMeanLogCoefficient: Double = 0.06,

    /**
     * Widening applied when routing data is stale/cached rather than live, scaled by how
     * stale it is (see [StalenessPolicy]).
     */
    val cachedRoutingSigmaGain: Double = 0.35,

    // -- shrinkage prior strengths (κ), in equivalent observations -------------------
    val kappaUserGlobal: Double = 4.0,
    val kappaPair: Double = 5.0,
    val kappaRoute: Double = 6.0,
    val kappaRouteBucket: Double = 8.0,
    val kappaDuration: Double = 5.0,

    /** EWMA half-life, in observations, for bias/volatility recency weighting. */
    val halfLifeCount: Double = 12.0,

    /** Minimum effective sample size before the empirical residual sampler is trusted. */
    val minEffectiveSampleForEmpirical: Double = 8.0,

    /** Floor on the residual scale so the model never claims implausible precision. */
    val minLogSigma: Double = 0.07,
    val maxLogSigma: Double = 0.75,
) {
    companion object {
        val DEFAULT = ModelPriors()
    }
}

/** How much to widen uncertainty when routing data is not live. */
object StalenessPolicy {
    /** Returns a multiplier ≥ 1 applied to the residual scale. */
    fun sigmaMultiplier(ageMinutes: Long, gain: Double): Double {
        if (ageMinutes <= 5) return 1.0
        val saturating = 1.0 - kotlin.math.exp(-(ageMinutes - 5).toDouble() / 45.0)
        return 1.0 + gain * saturating
    }
}
