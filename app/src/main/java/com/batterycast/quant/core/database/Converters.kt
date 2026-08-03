package com.batterycast.quant.core.database

import androidx.room.TypeConverter
import com.batterycast.quant.forecasting.model.DrainMetric
import com.batterycast.quant.forecasting.model.ModelNamespace
import com.batterycast.quant.forecasting.model.UsageRegime
import com.batterycast.quant.telemetry.model.BatteryHealth
import com.batterycast.quant.telemetry.model.BluetoothState
import com.batterycast.quant.telemetry.model.ChargingPolicy
import com.batterycast.quant.telemetry.model.ChargingStatus
import com.batterycast.quant.telemetry.model.NetworkType
import com.batterycast.quant.telemetry.model.ObservationQuality
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.QualityNote
import com.batterycast.quant.telemetry.model.ThermalStatus
import com.batterycast.quant.telemetry.model.UsageCategory

/**
 * Enums are stored by name rather than ordinal so the database stays readable and stable when
 * enum constants are reordered. Unknown names decode to the enum's own unknown member, which
 * matters because a database written by a newer build must still open on an older one.
 */
class Converters {

    @TypeConverter fun chargingStatusToString(value: ChargingStatus): String = value.name

    @TypeConverter fun stringToChargingStatus(value: String): ChargingStatus =
        enumValueOrNull<ChargingStatus>(value) ?: ChargingStatus.UNKNOWN

    @TypeConverter fun plugTypeToString(value: PlugType): String = value.name

    @TypeConverter fun stringToPlugType(value: String): PlugType =
        enumValueOrNull<PlugType>(value) ?: PlugType.UNKNOWN

    @TypeConverter fun healthToString(value: BatteryHealth): String = value.name

    @TypeConverter fun stringToHealth(value: String): BatteryHealth =
        enumValueOrNull<BatteryHealth>(value) ?: BatteryHealth.UNKNOWN

    @TypeConverter fun chargingPolicyToString(value: ChargingPolicy?): String? = value?.name

    @TypeConverter fun stringToChargingPolicy(value: String?): ChargingPolicy? =
        value?.let { enumValueOrNull<ChargingPolicy>(it) ?: ChargingPolicy.UNKNOWN }

    @TypeConverter fun thermalToString(value: ThermalStatus): String = value.name

    @TypeConverter fun stringToThermal(value: String): ThermalStatus =
        enumValueOrNull<ThermalStatus>(value) ?: ThermalStatus.UNSUPPORTED

    @TypeConverter fun networkToString(value: NetworkType): String = value.name

    @TypeConverter fun stringToNetwork(value: String): NetworkType =
        enumValueOrNull<NetworkType>(value) ?: NetworkType.UNKNOWN

    @TypeConverter fun bluetoothToString(value: BluetoothState): String = value.name

    @TypeConverter fun stringToBluetooth(value: String): BluetoothState =
        enumValueOrNull<BluetoothState>(value) ?: BluetoothState.UNKNOWN

    @TypeConverter fun usageCategoryToString(value: UsageCategory?): String? = value?.name

    @TypeConverter fun stringToUsageCategory(value: String?): UsageCategory? =
        value?.let { enumValueOrNull<UsageCategory>(it) ?: UsageCategory.UNKNOWN }

    @TypeConverter fun qualityToString(value: ObservationQuality): String = value.name

    @TypeConverter fun stringToQuality(value: String): ObservationQuality =
        enumValueOrNull<ObservationQuality>(value) ?: ObservationQuality.LOW_PRECISION

    @TypeConverter fun sourceToString(value: ObservationSource): String = value.name

    @TypeConverter fun stringToSource(value: String): ObservationSource =
        enumValueOrNull<ObservationSource>(value) ?: ObservationSource.PERIODIC_WORK

    @TypeConverter fun notesToString(value: Set<QualityNote>): String =
        value.joinToString(separator = ",") { it.name }

    @TypeConverter fun stringToNotes(value: String): Set<QualityNote> =
        if (value.isBlank()) {
            emptySet()
        } else {
            value.split(",").mapNotNull { enumValueOrNull<QualityNote>(it.trim()) }.toSet()
        }

    @TypeConverter fun regimeToString(value: UsageRegime): String = value.name

    @TypeConverter fun stringToRegime(value: String): UsageRegime =
        enumValueOrNull<UsageRegime>(value) ?: UsageRegime.UNCLASSIFIED_DISCHARGE

    @TypeConverter fun metricToString(value: DrainMetric): String = value.name

    @TypeConverter fun stringToMetric(value: String): DrainMetric =
        enumValueOrNull<DrainMetric>(value) ?: DrainMetric.PERCENT_PER_HOUR

    @TypeConverter fun namespaceToString(value: ModelNamespace): String = value.name

    @TypeConverter fun stringToNamespace(value: String): ModelNamespace =
        enumValueOrNull<ModelNamespace>(value) ?: ModelNamespace.DRAIN
}

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }
