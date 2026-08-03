package com.batterycast.quant.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.batterycast.quant.core.system.DeviceStateProvider
import com.batterycast.quant.di.IoDispatcher
import com.batterycast.quant.core.system.UsageInsightProvider
import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.model.RawBatteryReading
import com.batterycast.quant.telemetry.model.SensorCapabilities
import com.batterycast.quant.telemetry.validation.CapabilityLearner
import com.batterycast.quant.telemetry.validation.ObservationFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one and only production implementation of [BatteryTelemetrySource].
 *
 * Everything it emits comes from `ACTION_BATTERY_CHANGED` and `BatteryManager.getLongProperty`
 * on the device this code is running on. Where a property is missing it stays missing:
 * unsupported fields become nulls carrying an explicit reason, never substitute numbers.
 */
@Singleton
class AndroidBatteryTelemetrySource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceStateProvider: DeviceStateProvider,
    private val usageInsightProvider: UsageInsightProvider,
    private val capabilityStore: CapabilityStore,
    private val lastObservationCache: LastObservationCache,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BatteryTelemetrySource {

    private val batteryManager: BatteryManager? = context.getSystemService()

    /** Serialises capability learning so concurrent samples cannot interleave their updates. */
    private val capabilityMutex = Mutex()

    override suspend fun sample(source: ObservationSource): BatteryObservation? = withContext(ioDispatcher) {
        val intent = readStickyBatteryIntent() ?: return@withContext null
        buildObservation(intent, source)
    }

    override fun observations(): Flow<BatteryObservation> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent == null) return
                // A broadcast receiver callback must return promptly, so the intent is handed to
                // the flow and all the reading work happens downstream.
                trySend(intent)
            }
        }

        // Registering with a null receiver returns the sticky intent, so the flow always starts
        // from the current state rather than waiting for the battery to change.
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) trySend(sticky)

        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }
        .conflate()
        .mapNotNull { intent -> buildObservation(intent, ObservationSource.BATTERY_BROADCAST) }

    override suspend fun capabilities(): SensorCapabilities =
        CapabilityLearner.toCapabilities(capabilityStore.evidence())

    private fun readStickyBatteryIntent(): Intent? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    private suspend fun buildObservation(intent: Intent, source: ObservationSource): BatteryObservation? =
        withContext(ioDispatcher) {
            val raw = readRaw(intent) ?: return@withContext null

            val capabilities = capabilityMutex.withLock {
                val previousEvidence = capabilityStore.evidence()
                val updated = CapabilityLearner.accumulate(previousEvidence, raw)
                capabilityStore.update(updated)
                CapabilityLearner.toCapabilities(updated)
            }

            ObservationFactory.create(
                raw = raw,
                capabilities = capabilities,
                previous = lastObservationCache.get(),
                source = source,
            )
        }

    private fun readRaw(intent: Intent): RawBatteryReading? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        val nowMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()

        val recentWindowMs = RECENT_CONTEXT_WINDOW_MS
        val usage = usageInsightProvider.aggregateUsage(nowMs - recentWindowMs, nowMs)

        return RawBatteryReading(
            timestampMs = nowMs,
            elapsedRealtimeMs = elapsedMs,
            level = level,
            scale = scale,
            statusRaw = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            pluggedRaw = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            healthRaw = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            voltageMvRaw = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, ABSENT_INT).nullIfAbsent(),
            temperatureDeciCRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, ABSENT_INT).nullIfAbsent(),
            currentNowRaw = longProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAverageRaw = longProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeCounterRaw = longProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            energyCounterRaw = longProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            chargingPolicyRaw = chargingPolicyRaw(),
            powerSaveEnabled = deviceStateProvider.isPowerSaveMode(),
            thermalStatusRaw = deviceStateProvider.thermalStatusRaw(),
            screenInteractive = deviceStateProvider.isScreenInteractive(),
            networkType = deviceStateProvider.networkType(),
            bluetoothState = deviceStateProvider.bluetoothState(),
            // Screen time over the recent window is reconstructed from stored observations by the
            // repository, which is the only component that can see the history.
            recentScreenTimeMs = null,
            recentForegroundUsageMs = usage?.foregroundMs,
            dominantUsageCategory = usage?.dominantCategory,
            usageAccessGranted = usageInsightProvider.hasUsageAccess(),
        )
    }

    /**
     * Reads a `BatteryManager` long property, mapping every "not implemented" signal onto null.
     *
     * Devices signal absence in at least four different ways — `Long.MIN_VALUE`,
     * `Integer.MIN_VALUE` widened to a long, exact zero, or a thrown exception — and each is
     * treated as an absent measurement rather than as a value.
     */
    private fun longProperty(property: Int): Long? {
        val manager = batteryManager ?: return null
        val value = runCatching { manager.getLongProperty(property) }.getOrNull() ?: return null
        return when (value) {
            Long.MIN_VALUE, Long.MAX_VALUE, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), 0L -> null
            else -> value
        }
    }

    /**
     * Charging policy — adaptive charging, long-life mode and so on.
     *
     * Android added `BATTERY_PROPERTY_CHARGING_POLICY` in API 34, but it is not part of the
     * public SDK: it is annotated `@SystemApi` and a normal application cannot read it. Rather
     * than reach for the hidden constant and present whatever came back as a measurement, this
     * returns null, the capability is reported as unsupported in Settings, and the charging model
     * relies on the charging *rates* it can actually observe instead.
     *
     * The plumbing for the field is retained so that a future public API drops straight in.
     */
    private fun chargingPolicyRaw(): Int? = null

    private fun Int.nullIfAbsent(): Int? = takeIf { it != ABSENT_INT }

    private companion object {
        const val ABSENT_INT = Int.MIN_VALUE

        /** Window used for the aggregate usage figure attached to each reading. */
        const val RECENT_CONTEXT_WINDOW_MS = 30 * 60 * 1000L
    }
}
