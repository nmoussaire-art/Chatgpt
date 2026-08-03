package com.batterycast.quant.telemetry.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.batterycast.quant.telemetry.repository.ObservationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports the raw observation history as CSV, on demand.
 *
 * This is the user's data and they are entitled to all of it in a form they can actually read.
 * The file is written to the app's own cache directory and shared through a `FileProvider` grant,
 * so it exists only where the user sends it — the app has no network permission and cannot upload
 * it anywhere itself.
 *
 * Unmeasured fields are written as empty cells, never as zero, so the export says exactly what
 * the model saw.
 */
@Singleton
class ObservationExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val observationRepository: ObservationRepository,
) {

    suspend fun exportToCache(): Uri? {
        val observations = observationRepository.since(0L)
        if (observations.isEmpty()) return null

        val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        val file = File(directory, FILE_NAME)

        file.bufferedWriter().use { writer ->
            writer.appendLine(HEADER)
            observations.forEach { observation ->
                writer.appendLine(
                    listOf(
                        observation.timestampMs.toString(),
                        observation.elapsedRealtimeMs.toString(),
                        observation.bootSessionId.toString(),
                        observation.batteryPercent.toString(),
                        observation.chargeCounterMicroAh?.toString().orEmpty(),
                        observation.currentMicroA?.toString().orEmpty(),
                        observation.averageCurrentMicroA?.toString().orEmpty(),
                        observation.energyNanoWh?.toString().orEmpty(),
                        observation.voltageMv?.toString().orEmpty(),
                        observation.temperatureDeciC?.toString().orEmpty(),
                        observation.isCharging.toString(),
                        observation.chargingStatus.name,
                        observation.plugType.name,
                        observation.batteryHealth.name,
                        observation.chargingPolicy?.name.orEmpty(),
                        observation.powerSaveEnabled.toString(),
                        observation.thermalStatus.name,
                        observation.screenInteractive.toString(),
                        observation.networkType.name,
                        observation.recentScreenTimeMs?.toString().orEmpty(),
                        observation.recentForegroundUsageMs?.toString().orEmpty(),
                        observation.dominantUsageCategory?.name.orEmpty(),
                        observation.quality.name,
                        observation.notes.joinToString(separator = " "),
                        observation.source.name,
                    ).joinToString(separator = ","),
                )
            }
        }

        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        }.getOrNull()
    }

    private companion object {
        const val EXPORT_DIRECTORY = "exports"
        const val FILE_NAME = "batterycast-observations.csv"
        const val HEADER = "timestamp_ms,elapsed_realtime_ms,boot_session_id,battery_percent," +
            "charge_counter_uah,current_ua,average_current_ua,energy_nwh,voltage_mv," +
            "temperature_dc,is_charging,charging_status,plug_type,battery_health,charging_policy," +
            "power_save,thermal_status,screen_interactive,network_type,recent_screen_time_ms," +
            "recent_foreground_usage_ms,dominant_usage_category,quality,notes,source"
    }
}
