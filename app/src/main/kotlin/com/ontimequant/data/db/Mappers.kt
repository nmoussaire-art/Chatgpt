package com.ontimequant.data.db

import com.ontimequant.model.Appointment
import com.ontimequant.model.CompletedTrip
import com.ontimequant.model.DayType
import com.ontimequant.model.GeoPoint
import com.ontimequant.model.LocationKind
import com.ontimequant.model.SavedJourney
import com.ontimequant.model.SavedLocation
import com.ontimequant.model.TimeBucket
import com.ontimequant.model.TrafficRegime
import com.ontimequant.model.TripSource
import com.ontimequant.model.WeatherCategory
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Entity ↔ domain mapping.
 *
 * Enums are stored as their `name` rather than an ordinal so that reordering an enum can
 * never silently reinterpret historical data. Unknown values decode to a safe default
 * instead of throwing, because a forecast should degrade rather than crash.
 */

private inline fun <reified T : Enum<T>> decode(value: String, fallback: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

fun SavedLocationEntity.toDomain() = SavedLocation(
    id = id,
    label = label,
    address = address,
    point = GeoPoint(latitude, longitude),
    kind = decode(kind, LocationKind.OTHER),
    providerPlaceId = providerPlaceId,
    defaultParkingMinutes = defaultParkingMinutes,
    defaultWalkingMinutes = defaultWalkingMinutes,
)

fun SavedLocation.toEntity(createdAt: Instant = Instant.now()) = SavedLocationEntity(
    id = id,
    label = label,
    address = address,
    latitude = point.latitude,
    longitude = point.longitude,
    kind = kind.name,
    providerPlaceId = providerPlaceId,
    defaultParkingMinutes = defaultParkingMinutes,
    defaultWalkingMinutes = defaultWalkingMinutes,
    createdAt = createdAt.epochSecond,
)

fun SavedJourneyEntity.toDomain() = SavedJourney(
    id = id,
    name = name,
    originId = originId,
    destinationId = destinationId,
    defaultConfidence = defaultConfidence,
    defaultEntryBufferMinutes = defaultEntryBufferMinutes,
    createdAt = Instant.ofEpochSecond(createdAt),
)

fun SavedJourney.toEntity() = SavedJourneyEntity(
    id = id,
    name = name,
    originId = originId,
    destinationId = destinationId,
    defaultConfidence = defaultConfidence,
    defaultEntryBufferMinutes = defaultEntryBufferMinutes,
    createdAt = createdAt.epochSecond,
)

fun AppointmentEntity.toDomain(origin: SavedLocation, destination: SavedLocation) = Appointment(
    id = id,
    title = title,
    startTime = Instant.ofEpochSecond(startTimeEpoch),
    zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault()),
    origin = origin,
    destination = destination,
    entryBufferMinutes = entryBufferMinutes,
    confidenceTarget = confidenceTarget,
    journeyId = journeyId,
    calendarEventId = calendarEventId,
    calendarId = calendarId,
    remindersEnabled = remindersEnabled,
)

fun Appointment.toEntity(dismissed: Boolean = false, createdAt: Instant = Instant.now()) = AppointmentEntity(
    id = id,
    title = title,
    startTimeEpoch = startTime.epochSecond,
    zoneId = zone.id,
    originId = origin.id,
    destinationId = destination.id,
    entryBufferMinutes = entryBufferMinutes,
    confidenceTarget = confidenceTarget,
    journeyId = journeyId,
    calendarEventId = calendarEventId,
    calendarId = calendarId,
    remindersEnabled = remindersEnabled,
    dismissed = dismissed,
    createdAt = createdAt.epochSecond,
)

fun CompletedTripEntity.toDomain() = CompletedTrip(
    id = id,
    journeyId = journeyId,
    originId = originId,
    destinationId = destinationId,
    appointmentTitle = appointmentTitle,
    zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault()),
    requiredArrival = Instant.ofEpochSecond(requiredArrivalEpoch),
    recommendedDeparture = recommendedDepartureEpoch?.let(Instant::ofEpochSecond),
    actualDeparture = Instant.ofEpochSecond(actualDepartureEpoch),
    actualArrival = Instant.ofEpochSecond(actualArrivalEpoch),
    predictedTravelSeconds = predictedTravelSeconds,
    actualTravelSeconds = actualTravelSeconds,
    predictedArrival = predictedArrivalEpoch?.let(Instant::ofEpochSecond),
    onTimeProbabilityAtDeparture = onTimeProbabilityAtDeparture,
    preparationSeconds = preparationSeconds,
    parkingSeconds = parkingSeconds,
    walkingSeconds = walkingSeconds,
    weatherSeverity = weatherSeverity,
    weatherCategory = decode(weatherCategory, WeatherCategory.UNKNOWN),
    eventPressure = eventPressure,
    trafficRegime = decode(trafficRegime, TrafficRegime.UNKNOWN),
    timeBucket = decode(timeBucket, TimeBucket.MIDDAY),
    dayType = decode(dayType, DayType.WEEKDAY),
    dayOfWeek = decode(dayOfWeek, DayOfWeek.MONDAY),
    excludedFromLearning = excludedFromLearning,
    unusualCircumstances = unusualCircumstances,
    modelVersion = modelVersion,
    source = decode(source, TripSource.MANUAL),
)

fun CompletedTrip.toEntity() = CompletedTripEntity(
    id = id,
    journeyId = journeyId,
    originId = originId,
    destinationId = destinationId,
    appointmentTitle = appointmentTitle,
    zoneId = zone.id,
    requiredArrivalEpoch = requiredArrival.epochSecond,
    recommendedDepartureEpoch = recommendedDeparture?.epochSecond,
    actualDepartureEpoch = actualDeparture.epochSecond,
    actualArrivalEpoch = actualArrival.epochSecond,
    predictedTravelSeconds = predictedTravelSeconds,
    actualTravelSeconds = actualTravelSeconds,
    predictedArrivalEpoch = predictedArrival?.epochSecond,
    onTimeProbabilityAtDeparture = onTimeProbabilityAtDeparture,
    preparationSeconds = preparationSeconds,
    parkingSeconds = parkingSeconds,
    walkingSeconds = walkingSeconds,
    weatherSeverity = weatherSeverity,
    weatherCategory = weatherCategory.name,
    eventPressure = eventPressure,
    trafficRegime = trafficRegime.name,
    timeBucket = timeBucket.name,
    dayType = dayType.name,
    dayOfWeek = dayOfWeek.name,
    excludedFromLearning = excludedFromLearning,
    unusualCircumstances = unusualCircumstances,
    modelVersion = modelVersion,
    source = source.name,
)
