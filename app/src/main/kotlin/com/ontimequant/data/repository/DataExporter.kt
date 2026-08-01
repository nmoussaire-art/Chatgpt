package com.ontimequant.data.repository

import android.content.Context
import com.ontimequant.data.db.OnTimeQuantDatabase
import com.ontimequant.forecast.MODEL_VERSION
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports everything the app holds about the user as readable JSON.
 *
 * Deliberately complete: if the app stores it, the export contains it. That is the only
 * honest way to answer "what do you have on me?".
 */
@Singleton
class DataExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: OnTimeQuantDatabase,
) {

    @Serializable
    data class Export(
        val exportedAt: String,
        val modelVersion: String,
        val places: List<PlaceExport>,
        val journeys: List<JourneyExport>,
        val appointments: List<AppointmentExport>,
        val trips: List<TripExport>,
        val learnedBias: List<BiasExport>,
        val calibration: CalibrationExport?,
    )

    @Serializable
    data class PlaceExport(
        val id: String, val label: String, val address: String,
        val latitude: Double, val longitude: Double, val kind: String,
    )

    @Serializable
    data class JourneyExport(val id: String, val name: String, val originId: String, val destinationId: String)

    @Serializable
    data class AppointmentExport(
        val id: String, val title: String, val startTimeEpoch: Long, val zoneId: String,
        val originId: String, val destinationId: String,
        val entryBufferMinutes: Int, val confidenceTarget: Double,
    )

    @Serializable
    data class TripExport(
        val id: String, val title: String, val zoneId: String,
        val requiredArrivalEpoch: Long, val actualDepartureEpoch: Long, val actualArrivalEpoch: Long,
        val predictedTravelSeconds: Double, val actualTravelSeconds: Double,
        val preparationSeconds: Double?, val parkingSeconds: Double?, val walkingSeconds: Double?,
        val weatherSeverity: Double?, val eventPressure: Double?,
        val excludedFromLearning: Boolean, val modelVersion: String, val source: String,
    )

    @Serializable
    data class BiasExport(
        val groupKey: String, val meanLogRatio: Double, val robustLogSigma: Double,
        val effectiveSampleSize: Double, val observationCount: Int,
    )

    @Serializable
    data class CalibrationExport(
        val sampleSize: Int, val medianAbsoluteErrorSeconds: Double,
        val medianBiasSeconds: Double, val coverage80: Double, val brierScore: Double,
    )

    suspend fun exportToFile(): File? = withContext(Dispatchers.IO) {
        runCatching {
            val export = Export(
                exportedAt = Instant.now().toString(),
                modelVersion = MODEL_VERSION,
                places = database.locationDao().all().map {
                    PlaceExport(it.id, it.label, it.address, it.latitude, it.longitude, it.kind)
                },
                journeys = database.journeyDao().allJourneys().map {
                    JourneyExport(it.id, it.name, it.originId, it.destinationId)
                },
                appointments = database.appointmentDao().allActive().map {
                    AppointmentExport(
                        it.id, it.title, it.startTimeEpoch, it.zoneId, it.originId, it.destinationId,
                        it.entryBufferMinutes, it.confidenceTarget,
                    )
                },
                trips = database.tripDao().allChronological().map {
                    TripExport(
                        it.id, it.appointmentTitle, it.zoneId, it.requiredArrivalEpoch,
                        it.actualDepartureEpoch, it.actualArrivalEpoch,
                        it.predictedTravelSeconds, it.actualTravelSeconds,
                        it.preparationSeconds, it.parkingSeconds, it.walkingSeconds,
                        it.weatherSeverity, it.eventPressure,
                        it.excludedFromLearning, it.modelVersion, it.source,
                    )
                },
                learnedBias = database.learningDao().allResiduals()
                    .groupBy { it.groupKey }
                    .map { (key, rows) ->
                        BiasExport(
                            groupKey = key,
                            meanLogRatio = rows.map { it.logRatio }.average(),
                            robustLogSigma = com.ontimequant.forecast.Stats.madSigma(rows.map { it.logRatio }),
                            effectiveSampleSize = rows.size.toDouble(),
                            observationCount = rows.size,
                        )
                    },
                calibration = database.calibrationDao().latest()?.let {
                    CalibrationExport(
                        it.sampleSize, it.medianAbsoluteErrorSeconds, it.medianBiasSeconds,
                        it.coverage80, it.brierScore,
                    )
                },
            )
            val json = Json { prettyPrint = true; encodeDefaults = true }
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "ontime-quant-export.json")
            file.writeText(json.encodeToString(Export.serializer(), export))
            file
        }.getOrNull()
    }
}
