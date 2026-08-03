package com.batterycast.quant.telemetry

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.batterycast.quant.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.abs

class AndroidBatteryTelemetrySource @Inject constructor(
    @ApplicationContext private val context: Context
) : BatteryTelemetrySource {
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override suspend fun read(source: String): BatteryObservation? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null

        val percentage = (level * 100.0 / scale).coerceIn(0.0, 100.0)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val current = validCurrent(intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        val averageCurrent = validCurrent(intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE))
        val chargeCounter = validCounter(intProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
        val energy = validEnergy(longProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER))
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it in 2_500..5_500 }
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it in -200..900 }
        val thermalStatus = if (Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else null
        val screenInteractive = powerManager.isInteractive
        val usage = usageWindow()
        val network = networkType()
        val bluetooth = bluetoothState()
        val plugType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
            else -> if (Build.VERSION.SDK_INT >= 33 && plugged == BatteryManager.BATTERY_PLUGGED_DOCK) {
                PlugType.DOCK
            } else if (charging) {
                PlugType.UNKNOWN
            } else {
                PlugType.NONE
            }
        }
        // Charging policy has no public BatteryManager property constant in the compiled SDK,
        // so it is recorded as unknown rather than read through a non-SDK identifier.
        val chargingPolicy: Int? = null
        val quality = when {
            chargeCounter != null && (current != null || averageCurrent != null) -> DataQuality.HIGH
            chargeCounter != null || energy != null || (voltage != null && temperature != null) -> DataQuality.ACCEPTABLE
            else -> DataQuality.LOW_PRECISION
        }
        val regime = classify(
            charging = charging,
            screenInteractive = screenInteractive,
            usage = usage,
            currentMicroA = current ?: averageCurrent,
            percentage = percentage
        )

        return BatteryObservation(
            timestamp = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            bootCount = readBootCount(),
            batteryPercent = percentage,
            chargeCounterMicroAh = chargeCounter,
            currentMicroA = current,
            averageCurrentMicroA = averageCurrent,
            energyNanoWh = energy,
            voltageMv = voltage,
            temperatureDeciC = temperature,
            isCharging = charging,
            plugType = plugType,
            batteryHealth = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            chargingStatus = intProperty(BatteryManager.BATTERY_PROPERTY_STATUS)?.toInt(),
            chargingPolicy = chargingPolicy,
            powerSaveEnabled = powerManager.isPowerSaveMode,
            thermalStatus = thermalStatus,
            screenInteractive = screenInteractive,
            networkType = network,
            bluetoothEnabled = bluetooth,
            recentScreenTimeMs = usage?.screenTimeMs,
            recentForegroundUsageMs = usage?.foregroundTimeMs,
            usageRegime = regime,
            overallQuality = quality,
            source = source
        )
    }

    private fun intProperty(id: Int): Long? {
        val value = batteryManager.getIntProperty(id)
        return value.toLong().takeUnless { it == Int.MIN_VALUE.toLong() }
    }

    private fun longProperty(id: Int): Long? {
        val value = batteryManager.getLongProperty(id)
        return value.takeUnless { it == Long.MIN_VALUE }
    }

    private fun validCurrent(value: Long?): Long? = value?.takeIf { abs(it) <= 20_000_000L }
    private fun validCounter(value: Long?): Long? = value?.takeIf { it in 1_000L..30_000_000L }
    private fun validEnergy(value: Long?): Long? = value?.takeIf { it in 1L..1_000_000_000_000L }
    private fun readBootCount(): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()

    private fun networkType(): NetworkType {
        val network = connectivity.activeNetwork ?: return NetworkType.OFFLINE
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return NetworkType.UNKNOWN
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.OTHER
            else -> NetworkType.OFFLINE
        }
    }

    private fun bluetoothState(): Boolean? {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private data class UsageWindow(
        val screenTimeMs: Long?,
        val foregroundTimeMs: Long,
        val mapsTimeMs: Long,
        val mediaTimeMs: Long,
        val gameTimeMs: Long
    )

    private data class ActiveUsage(var startedAt: Long? = null, var totalMs: Long = 0L)

    private fun usageWindow(): UsageWindow? {
        if (!hasUsageAccess()) return null
        val end = System.currentTimeMillis()
        val start = end - 60 * 60_000L
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val events = runCatching { manager.queryEvents(start, end) }.getOrNull() ?: return null
        val event = UsageEvents.Event()
        val activeByPackage = mutableMapOf<String, ActiveUsage>()
        var screenStartedAt: Long? = null
        var screenDuration = 0L
        var sawScreenEvent = false

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val timestamp = event.timeStamp.coerceIn(start, end)
            when {
                isForegroundStart(event.eventType) -> {
                    val packageName = event.packageName ?: continue
                    val usage = activeByPackage.getOrPut(packageName) { ActiveUsage() }
                    if (usage.startedAt == null) usage.startedAt = timestamp
                }
                isForegroundEnd(event.eventType) -> {
                    val packageName = event.packageName ?: continue
                    val usage = activeByPackage.getOrPut(packageName) { ActiveUsage() }
                    usage.startedAt?.let { began -> usage.totalMs += (timestamp - began).coerceAtLeast(0L) }
                    usage.startedAt = null
                }
                Build.VERSION.SDK_INT >= 28 && event.eventType == UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    if (screenStartedAt == null) screenStartedAt = timestamp
                    sawScreenEvent = true
                }
                Build.VERSION.SDK_INT >= 28 && event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenStartedAt?.let { began -> screenDuration += (timestamp - began).coerceAtLeast(0L) }
                    screenStartedAt = null
                    sawScreenEvent = true
                }
            }
        }

        activeByPackage.values.forEach { usage ->
            usage.startedAt?.let { began -> usage.totalMs += (end - began).coerceAtLeast(0L) }
            usage.totalMs = usage.totalMs.coerceIn(0L, 60 * 60_000L)
        }
        screenStartedAt?.let { began -> screenDuration += (end - began).coerceAtLeast(0L) }

        var total = 0L
        var maps = 0L
        var media = 0L
        var games = 0L
        activeByPackage.forEach { (packageName, usage) ->
            val foreground = usage.totalMs
            if (foreground <= 0L) return@forEach
            total += foreground
            val category = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0).category
            }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
            when (category) {
                ApplicationInfo.CATEGORY_MAPS -> maps += foreground
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> media += foreground
                ApplicationInfo.CATEGORY_GAME -> games += foreground
            }
        }

        return UsageWindow(
            screenTimeMs = screenDuration.coerceAtMost(60 * 60_000L).takeIf { sawScreenEvent || it > 0L },
            foregroundTimeMs = total.coerceAtMost(60 * 60_000L),
            mapsTimeMs = maps.coerceAtMost(60 * 60_000L),
            mediaTimeMs = media.coerceAtMost(60 * 60_000L),
            gameTimeMs = games.coerceAtMost(60 * 60_000L)
        )
    }

    private fun isForegroundStart(eventType: Int): Boolean =
        eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= 29 && eventType == UsageEvents.Event.ACTIVITY_RESUMED)

    private fun isForegroundEnd(eventType: Int): Boolean =
        eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            (Build.VERSION.SDK_INT >= 29 &&
                (eventType == UsageEvents.Event.ACTIVITY_PAUSED || eventType == UsageEvents.Event.ACTIVITY_STOPPED))

    private fun classify(
        charging: Boolean,
        screenInteractive: Boolean,
        usage: UsageWindow?,
        currentMicroA: Long?,
        percentage: Double
    ): UsageRegime {
        if (charging) {
            return when {
                percentage >= 85.0 -> UsageRegime.CHARGING_TAPER
                currentMicroA != null && abs(currentMicroA) > 2_000_000L -> UsageRegime.FAST_CHARGING
                else -> UsageRegime.CHARGING
            }
        }
        if (usage != null) {
            if (usage.mapsTimeMs >= 15 * 60_000L) return UsageRegime.NAVIGATION
            if (usage.gameTimeMs >= 15 * 60_000L || usage.mediaTimeMs >= 25 * 60_000L) return UsageRegime.MEDIA_GAMING
            val minutes = usage.foregroundTimeMs / 60_000.0
            return when {
                minutes > 45.0 -> UsageRegime.HEAVY
                minutes > 20.0 -> UsageRegime.NORMAL
                minutes > 5.0 || screenInteractive -> UsageRegime.LIGHT
                else -> UsageRegime.STANDBY
            }
        }
        return if (screenInteractive) UsageRegime.LIGHT else UsageRegime.STANDBY
    }
}
