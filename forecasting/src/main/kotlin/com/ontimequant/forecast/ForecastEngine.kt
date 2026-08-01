package com.ontimequant.forecast

import com.ontimequant.model.Appointment
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.DayType
import com.ontimequant.model.EventPressure
import com.ontimequant.model.PersonalizationLevel
import com.ontimequant.model.ProviderNotice
import com.ontimequant.model.RiskLevel
import com.ontimequant.model.RouteMatrix
import com.ontimequant.model.TimeBucket
import com.ontimequant.model.TrafficRegime
import com.ontimequant.model.WeatherSnapshot
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt

/** Model identity stored with every forecast so historical predictions stay reproducible. */
const val MODEL_VERSION: String = "otq-forecast-1.0.0"

/** One row of the "why this recommendation" breakdown. */
data class ForecastComponent(
    val id: String,
    val label: String,
    val valueSeconds: Double,
    /** Signed contribution relative to the raw routing baseline; 0 for the baseline itself. */
    val deltaSeconds: Double,
    val provenance: DataProvenance,
    val sourceLabel: String,
    val explanation: String,
    val uncertaintySeconds: Double = 0.0,
)

data class JourneyForecast(
    val appointment: Appointment,
    val generatedAt: Instant,
    val recommendedDeparture: Instant?,
    val recommended: CandidateForecast?,
    val bestEffort: CandidateForecast,
    val feasible: Boolean,
    val confidenceTarget: Double,
    val curve: DisplayCurve,
    val comparisons: List<DepartureComparison.Row>,
    val components: List<ForecastComponent>,
    val personalization: PersonalizationLevel,
    val personalizationDetail: String,
    val routeProvenance: DataProvenance,
    val weatherProvenance: DataProvenance,
    val eventProvenance: DataProvenance,
    val notices: List<ProviderNotice>,
    val trafficRisk: RiskLevel,
    /** Bounded weather severity actually used, or null when weather was unavailable. */
    val weatherSeverity: Double?,
    /** Bounded event-pressure score actually used. */
    val eventPressure: Double,
    /** The traffic-aware routing duration at the recommended departure, in seconds. */
    val baselineTravelSeconds: Double,
    val trafficRegime: TrafficRegime,
    val recommendedSafetyMarginSeconds: Double,
    val modelVersion: String = MODEL_VERSION,
    val simulationDraws: Int,
    val seed: Long,
    val summary: String,
) {
    val onTimeProbability: Double get() = (recommended ?: bestEffort).onTimeProbability
    val isDemo: Boolean get() = routeProvenance == DataProvenance.DEMO
}

/** Settings the engine needs; supplied by the app layer from DataStore. */
data class ForecastSettings(
    val confidenceTarget: Double = 0.90,
    val simulationDraws: Int = 4000,
    val preparationLearningEnabled: Boolean = true,
    val defaultParkingSeconds: Double? = null,
    val defaultWalkingSeconds: Double? = null,
    val defaultPreparationSeconds: Double? = null,
    val seed: Long = 20250801L,
)

/** All learned history, already loaded by the repository layer. */
data class LearningHistory(
    val travel: List<TravelObservation> = emptyList(),
    val preparation: List<DurationObservation> = emptyList(),
    val parking: List<DurationObservation> = emptyList(),
    val walking: List<DurationObservation> = emptyList(),
)

/** Optional external signals. Any of them may be absent. */
data class ExternalSignals(
    val weather: WeatherSnapshot? = null,
    val weatherProvenance: DataProvenance = DataProvenance.UNAVAILABLE,
    val eventPressure: EventPressure = EventPressure.NONE,
    val notices: List<ProviderNotice> = emptyList(),
)

/**
 * Orchestrates one full forecast: builds the baseline, fits every sub-model, runs the
 * solver, and assembles a human-readable breakdown in which every displayed number is
 * traceable to an API response, a stored observation, a user setting or a documented prior.
 */
class ForecastEngine(
    private val priors: ModelPriors = ModelPriors.DEFAULT,
    private val solver: DepartureSolver = DepartureSolver(),
) {

    fun forecast(
        appointment: Appointment,
        routeMatrix: RouteMatrix,
        signals: ExternalSignals,
        history: LearningHistory,
        settings: ForecastSettings,
        now: Instant,
    ): JourneyForecast {
        require(!routeMatrix.isEmpty) { "routing matrix is empty; caller must handle this" }

        val baseline = TravelBaseline.of(
            routeMatrix.estimates.map {
                TravelBaseline.Point(
                    epochSecond = it.departureTime.epochSecond,
                    durationSeconds = it.durationSeconds,
                    staticSeconds = it.staticDurationSeconds,
                    congestionRatio = it.congestionRatio,
                )
            },
        )

        val zoned = appointment.requiredArrival.atZone(appointment.zone)
        val bucket = TimeBucket.of(zoned.toLocalTime())
        val dayType = DayType.of(zoned.dayOfWeek)
        val regime = dominantRegime(routeMatrix)
        val routeKey = appointment.journeyId ?: "${appointment.origin.id}->${appointment.destination.id}"
        val pairKey = "${appointment.origin.id}->${appointment.destination.id}"
        val ageMinutes = max(0L, Duration.between(routeMatrix.fetchedAt, now).toMinutes())

        val context = ForecastContext(
            routeKey = routeKey,
            pairKey = pairKey,
            bucket = bucket,
            dayType = dayType,
            dayOfWeek = zoned.dayOfWeek,
            regime = regime,
            weatherSeverity = signals.weather?.severityIndex(),
            eventPressure = signals.eventPressure.score,
            routingIsLive = routeMatrix.provenance == DataProvenance.LIVE ||
                routeMatrix.provenance == DataProvenance.DEMO,
            routingAgeMinutes = ageMinutes,
        )

        val travelModel = TravelModelFitter.fit(history.travel, priors)

        val preparation = DurationModelFitter.fit(
            observations = history.preparation,
            key = "origin:${appointment.origin.id}",
            parentKey = "all",
            priorMedianSeconds = priors.preparationMedianMinutes * 60,
            priorLogSigma = priors.preparationLogSigma,
            priorStrength = priors.kappaDuration,
            halfLifeCount = priors.halfLifeCount,
            userDefaultSeconds = settings.defaultPreparationSeconds,
            enabled = settings.preparationLearningEnabled,
        )
        val parking = DurationModelFitter.fit(
            observations = history.parking,
            key = "dest:${appointment.destination.id}",
            parentKey = "all",
            priorMedianSeconds = priors.parkingMedianMinutes * 60,
            priorLogSigma = priors.parkingLogSigma,
            priorStrength = priors.kappaDuration,
            halfLifeCount = priors.halfLifeCount,
            userDefaultSeconds = settings.defaultParkingSeconds,
            destinationOverrideSeconds = appointment.destination.defaultParkingMinutes?.times(60),
        )
        val walking = DurationModelFitter.fit(
            observations = history.walking,
            key = "dest:${appointment.destination.id}",
            parentKey = "all",
            priorMedianSeconds = priors.walkingMedianMinutes * 60,
            priorLogSigma = priors.walkingLogSigma,
            priorStrength = priors.kappaDuration,
            halfLifeCount = priors.halfLifeCount,
            userDefaultSeconds = settings.defaultWalkingSeconds,
            destinationOverrideSeconds = appointment.destination.defaultWalkingMinutes?.times(60),
        )

        val midpointDuration = baseline.sampleAt(
            Instant.ofEpochSecond(
                (baseline.first!!.epochSecond + baseline.last!!.epochSecond) / 2,
            ),
        )
        val bundle = travelModel.sampler(
            context = context,
            baselineSeconds = midpointDuration.durationSeconds,
            staticSeconds = midpointDuration.staticSeconds,
        )

        val typicalTotal = preparation.meanSeconds + midpointDuration.durationSeconds *
            kotlin.math.exp(bundle.sampler.effectiveLocation) + parking.meanSeconds + walking.meanSeconds

        val (windowStart, windowEnd) = DepartureSolver.window(
            requiredArrival = appointment.requiredArrival,
            typicalTotalSeconds = typicalTotal,
            earliestAllowed = now.minusSeconds(now.epochSecond % 60),
        )

        val inputs = SimulationInputs(
            baseline = baseline,
            samplerBundle = bundle,
            preparation = preparation,
            parking = parking,
            walking = walking,
            entryBufferSeconds = appointment.entryBufferMinutes * 60.0,
            requiredArrival = appointment.requiredArrival,
            draws = settings.simulationDraws,
            seed = settings.seed,
        )

        val solution = solver.solve(
            DepartureSolver.Request(
                windowStart = windowStart,
                windowEnd = windowEnd,
                confidenceTarget = settings.confidenceTarget,
                inputs = inputs,
            ),
        )

        val chosen = solution.recommended ?: solution.bestEffort
        val anchor = chosen.departure
        val comparisons = DepartureComparison.rows(solution, anchor, listOf(0L, 5L, 10L, 15L), inputs)

        val level = travelModel.personalization(context)
        val components = buildComponents(
            baselinePoint = baseline.sampleAt(anchor.plusSeconds(preparation.meanSeconds.toLong())),
            bundle = bundle,
            preparation = preparation,
            parking = parking,
            walking = walking,
            appointment = appointment,
            forecast = chosen,
            signals = signals,
            travelModel = travelModel,
            context = context,
            routeProvenance = routeMatrix.provenance,
        )

        val notices = buildList {
            routeMatrix.notice?.let { add(it) }
            addAll(signals.notices)
            if (signals.weather == null) {
                add(
                    ProviderNotice(
                        "Weather", DataProvenance.UNAVAILABLE,
                        "Weather unavailable. No weather adjustment was applied; uncertainty was widened slightly instead.",
                    ),
                )
            }
            if (signals.eventPressure.provenance == DataProvenance.UNAVAILABLE) {
                add(
                    ProviderNotice(
                        "Events", DataProvenance.UNAVAILABLE,
                        "Event data unavailable. Event-related risk was excluded from this forecast.",
                    ),
                )
            }
            if (solution.curve.isNotEmpty() && solution.monotonicityViolation > 0.02) {
                add(
                    ProviderNotice(
                        "Model", DataProvenance.DEFAULT,
                        "The simulated curve was not perfectly monotone; the displayed line has been " +
                            "isotonically smoothed. Underlying probabilities are unchanged.",
                    ),
                )
            }
        }

        return JourneyForecast(
            appointment = appointment,
            generatedAt = now,
            recommendedDeparture = solution.recommended?.departure,
            recommended = solution.recommended,
            bestEffort = solution.bestEffort,
            feasible = solution.feasible,
            confidenceTarget = settings.confidenceTarget,
            curve = solution.displayCurve(),
            comparisons = comparisons,
            components = components,
            personalization = level,
            personalizationDetail = personalizationDetail(level, travelModel, context, bundle),
            routeProvenance = routeMatrix.provenance,
            weatherProvenance = signals.weatherProvenance,
            eventProvenance = signals.eventPressure.provenance,
            notices = notices,
            trafficRisk = chosen.riskLevel,
            weatherSeverity = signals.weather?.severityIndex(),
            eventPressure = signals.eventPressure.score,
            baselineTravelSeconds = chosen.componentMeans.baselineTravelSeconds,
            trafficRegime = regime,
            recommendedSafetyMarginSeconds = max(0.0, chosen.safetyMarginSeconds),
            simulationDraws = settings.simulationDraws,
            seed = settings.seed,
            summary = summarise(solution, appointment, settings.confidenceTarget),
        )
    }

    private fun dominantRegime(matrix: RouteMatrix): TrafficRegime {
        val known = matrix.estimates.map { it.trafficRegime }.filter { it != TrafficRegime.UNKNOWN }
        if (known.isNotEmpty()) {
            return known.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        }
        // Derive from congestion ratio when the provider does not label it.
        val ratio = matrix.estimates.map { it.congestionRatio }.average()
        return when {
            ratio < 1.10 -> TrafficRegime.LIGHT
            ratio < 1.35 -> TrafficRegime.MODERATE
            ratio < 1.70 -> TrafficRegime.HEAVY
            else -> TrafficRegime.SEVERE
        }
    }

    private fun buildComponents(
        baselinePoint: TravelBaseline.Point,
        bundle: SamplerBundle,
        preparation: FittedDuration,
        parking: FittedDuration,
        walking: FittedDuration,
        appointment: Appointment,
        forecast: CandidateForecast,
        signals: ExternalSignals,
        travelModel: FittedTravelModel,
        context: ForecastContext,
        routeProvenance: DataProvenance,
    ): List<ForecastComponent> {
        val base = baselinePoint.durationSeconds
        val free = baselinePoint.staticSeconds
        val trafficDelta = base - free
        val biasDelta = base * (kotlin.math.exp(bundle.bias.value) - 1.0)
        val weatherDelta = base * (kotlin.math.exp(bundle.weatherLogAdjustment) - 1.0)
        val eventDelta = base * (kotlin.math.exp(bundle.eventLogAdjustment) - 1.0)
        val incident = forecast.componentMeans.incidentSeconds

        val routeObs = travelModel.observationsFor(context)

        return listOf(
            ForecastComponent(
                id = "free_flow",
                label = "Free-flow driving time",
                valueSeconds = free,
                deltaSeconds = 0.0,
                provenance = routeProvenance,
                sourceLabel = if (routeProvenance == DataProvenance.DEMO) "Demo route data" else "Routing provider",
                explanation = "How long the drive takes with no traffic at all. This is the floor.",
            ),
            ForecastComponent(
                id = "traffic",
                label = "Traffic at your departure time",
                valueSeconds = trafficDelta,
                deltaSeconds = trafficDelta,
                provenance = routeProvenance,
                sourceLabel = when (routeProvenance) {
                    DataProvenance.LIVE -> "Live traffic-aware routing"
                    DataProvenance.CACHED -> "Most recent cached route estimate"
                    DataProvenance.DEMO -> "Demo route data"
                    else -> "Routing provider"
                },
                explanation = "Extra time the routing provider expects because of traffic when you " +
                    "actually reach the road, ${(baselinePoint.congestionRatio * 100).roundToInt() - 100}% above free flow.",
            ),
            ForecastComponent(
                id = "bias",
                label = "Your personal correction",
                valueSeconds = biasDelta,
                deltaSeconds = biasDelta,
                provenance = if (bundle.bias.personalWeight > 0.25) DataProvenance.LEARNED else DataProvenance.DEFAULT,
                sourceLabel = if (bundle.bias.personalWeight > 0.25) {
                    "${bundle.bias.levelName} · $routeObs trips"
                } else {
                    "Standard assumption (little history yet)"
                },
                explanation = if (bundle.bias.personalWeight > 0.25) {
                    "On this route the routing estimate has historically been off by " +
                        "${signedPercent(kotlin.math.exp(bundle.bias.value) - 1.0)}. That correction is applied here."
                } else {
                    "Not enough of your own trips yet, so a small conservative correction is used instead."
                },
            ),
            ForecastComponent(
                id = "uncertainty",
                label = "Travel-time uncertainty",
                valueSeconds = 0.0,
                deltaSeconds = 0.0,
                uncertaintySeconds = base * bundle.effectiveScale,
                provenance = if (bundle.sigma.personalWeight > 0.25) DataProvenance.LEARNED else DataProvenance.DEFAULT,
                sourceLabel = if (bundle.sigma.personalWeight > 0.25) bundle.sigma.levelName else "Standard assumption",
                explanation = "Typical spread of the drive around its central estimate — about " +
                    "${(base * bundle.effectiveScale / 60).roundToInt()} minutes either way, plus a " +
                    "${(bundle.sampler.incidentP * 100).roundToInt()}% chance of a real disruption.",
            ),
            ForecastComponent(
                id = "weather",
                label = "Weather",
                valueSeconds = weatherDelta,
                deltaSeconds = weatherDelta,
                provenance = if (signals.weather == null) DataProvenance.UNAVAILABLE else signals.weatherProvenance,
                sourceLabel = signals.weather?.let { "${it.category().name.lowercase().replaceFirstChar(Char::titlecase)} conditions" }
                    ?: "Unavailable",
                explanation = when {
                    signals.weather == null ->
                        "No weather data. Nothing was added; the forecast range was widened a little instead."
                    weatherDelta < 30 ->
                        "Conditions are not expected to change your journey materially."
                    else ->
                        "Conditions add roughly ${(weatherDelta / 60).roundToInt()} minutes and widen the range."
                },
            ),
            ForecastComponent(
                id = "events",
                label = "Nearby event risk",
                valueSeconds = eventDelta,
                deltaSeconds = eventDelta,
                provenance = signals.eventPressure.provenance,
                sourceLabel = if (signals.eventPressure.contributors.isEmpty()) "No relevant events" else
                    signals.eventPressure.contributors.first().venueName,
                explanation = when {
                    signals.eventPressure.provenance == DataProvenance.UNAVAILABLE ->
                        "Event data was not available and was excluded."
                    signals.eventPressure.score < 0.05 ->
                        "No events near your route are expected to matter."
                    eventDelta < 30 ->
                        "Events nearby add uncertainty rather than a predictable delay."
                    else ->
                        "A large nearby event is likely to press on your route around this time."
                },
            ),
            ForecastComponent(
                id = "preparation",
                label = "Getting ready and setting off",
                valueSeconds = preparation.medianSeconds,
                deltaSeconds = preparation.medianSeconds,
                uncertaintySeconds = preparation.quantile(0.9) - preparation.medianSeconds,
                provenance = preparation.provenance,
                sourceLabel = preparation.sourceLabel,
                explanation = if (preparation.provenance == DataProvenance.LEARNED) {
                    "Your recent trips suggest you usually begin moving about " +
                        "${(preparation.medianSeconds / 60).roundToInt()} minutes after deciding to leave."
                } else {
                    "A standard allowance for gathering your things and reaching the vehicle."
                },
            ),
            ForecastComponent(
                id = "parking",
                label = "Parking",
                valueSeconds = parking.medianSeconds,
                deltaSeconds = parking.medianSeconds,
                uncertaintySeconds = parking.quantile(0.9) - parking.medianSeconds,
                provenance = parking.provenance,
                sourceLabel = parking.sourceLabel,
                explanation = "Time to find a space, park and get out of the car at this destination.",
            ),
            ForecastComponent(
                id = "walking",
                label = "Walking to the door",
                valueSeconds = walking.medianSeconds,
                deltaSeconds = walking.medianSeconds,
                uncertaintySeconds = walking.quantile(0.9) - walking.medianSeconds,
                provenance = walking.provenance,
                sourceLabel = walking.sourceLabel,
                explanation = "Time on foot from where you park to the entrance.",
            ),
            ForecastComponent(
                id = "incident",
                label = "Disruption allowance",
                valueSeconds = incident,
                deltaSeconds = incident,
                provenance = DataProvenance.DEFAULT,
                sourceLabel = "Modelled right tail",
                explanation = "Averaged across all simulations, allowing for the small chance of an " +
                    "incident or closure. Most journeys contribute nothing here.",
            ),
            ForecastComponent(
                id = "entry_buffer",
                label = "Arrive-early buffer",
                valueSeconds = appointment.entryBufferMinutes * 60.0,
                deltaSeconds = appointment.entryBufferMinutes * 60.0,
                provenance = DataProvenance.USER_DEFINED,
                sourceLabel = "Your setting",
                explanation = if (appointment.entryBufferMinutes == 0) {
                    "You have asked to arrive exactly at the start time."
                } else {
                    "You have asked to be at the door ${appointment.entryBufferMinutes} minutes before the start."
                },
            ),
        )
    }

    private fun personalizationDetail(
        level: PersonalizationLevel,
        model: FittedTravelModel,
        context: ForecastContext,
        bundle: SamplerBundle,
    ): String {
        val routeObs = model.observationsFor(context)
        val bucketObs = model.bucketObservationsFor(context)
        val biasPercent = kotlin.math.exp(bundle.bias.value) - 1.0
        return when (level) {
            PersonalizationLevel.PRELIMINARY ->
                "Preliminary forecast based on current routing conditions and conservative uncertainty assumptions. " +
                    "It will sharpen as you complete journeys."
            PersonalizationLevel.LEARNING ->
                "Learning from your first ${max(routeObs, model.totalObservations)} completed trips. " +
                    "The routing baseline still does most of the work."
            PersonalizationLevel.PARTIALLY_PERSONALIZED ->
                "Based on $routeObs completed trips on this route ($bucketObs at this time of day), the routing " +
                    "estimate has historically been off by ${signedPercent(biasPercent)}."
            PersonalizationLevel.PERSONALIZED ->
                "Based on $routeObs completed trips on this route ($bucketObs at this time of day). Your personal " +
                    "correction of ${signedPercent(biasPercent)} and your own spread of outcomes drive this forecast."
        }
    }

    private fun summarise(
        solution: DepartureSolver.Solution,
        appointment: Appointment,
        target: Double,
    ): String {
        val chosen = solution.recommended
        return if (chosen == null) {
            val best = solution.bestEffort
            "Even leaving now, the chance of arriving by the deadline is about " +
                "${(best.onTimeProbability * 100).roundToInt()}%, below your ${(target * 100).roundToInt()}% target."
        } else {
            "Leaving at this time keeps you at or above your ${(target * 100).roundToInt()}% target for " +
                "arriving by the deadline. Waiting ${appointment.entryBufferMinutes.coerceAtLeast(5)} more minutes costs real probability."
        }
    }

    private fun signedPercent(fraction: Double): String {
        val pct = (fraction * 100)
        val rounded = kotlin.math.abs(pct).roundToInt()
        return when {
            pct >= 0.5 -> "+$rounded%"
            pct <= -0.5 -> "−$rounded%"
            else -> "under 1%"
        }
    }
}
