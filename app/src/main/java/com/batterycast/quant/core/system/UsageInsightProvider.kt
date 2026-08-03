package com.batterycast.quant.core.system

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.getSystemService
import com.batterycast.quant.telemetry.model.UsageCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional, aggregate-only usage signal.
 *
 * Usage access is a strong permission, so BatteryCast asks for the least it can make use of: how
 * much foreground time happened in a recent window, and which broad *category* dominated it. It
 * never records which apps ran, never stores package names, and never attributes a specific
 * amount of battery drain to a specific app — Android does not provide evidence that would
 * support such a claim, and inventing one would be dishonest.
 *
 * Every forecast in the app works without this permission.
 */
@Singleton
class UsageInsightProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val usageStatsManager: UsageStatsManager? = context.getSystemService()
    private val packageManager: PackageManager = context.packageManager

    /** Whether the user has granted usage access in system settings. */
    fun hasUsageAccess(): Boolean = runCatching {
        val appOps = context.getSystemService<AppOpsManager>() ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT ->
                context.checkPermission(
                    android.Manifest.permission.PACKAGE_USAGE_STATS,
                    Process.myPid(),
                    Process.myUid(),
                ) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }.getOrDefault(false)

    /**
     * Aggregate foreground time in `[fromMs, toMs)`, plus the dominant broad category.
     *
     * Returns null when the permission is absent or the platform returns nothing usable; the
     * caller records that as an unobserved field rather than as zero usage.
     */
    fun aggregateUsage(fromMs: Long, toMs: Long): AggregateUsage? {
        if (!hasUsageAccess()) return null
        val manager = usageStatsManager ?: return null
        if (toMs <= fromMs) return null

        return runCatching {
            val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, fromMs, toMs)
            if (stats.isNullOrEmpty()) return null

            val windowMs = toMs - fromMs
            var totalForegroundMs = 0L
            val categoryTotals = mutableMapOf<UsageCategory, Long>()

            stats.forEach { entry ->
                // `queryUsageStats` buckets can extend outside the requested window, so clamp the
                // contribution instead of reporting more foreground time than the window contains.
                val foregroundMs = entry.totalTimeInForeground.coerceAtLeast(0L)
                if (foregroundMs == 0L) return@forEach
                if (entry.packageName == context.packageName) return@forEach
                totalForegroundMs += foregroundMs
                val category = categoryOf(entry.packageName)
                categoryTotals[category] = (categoryTotals[category] ?: 0L) + foregroundMs
            }

            if (totalForegroundMs == 0L && categoryTotals.isEmpty()) return null

            val clamped = totalForegroundMs.coerceAtMost(windowMs)
            AggregateUsage(
                foregroundMs = clamped,
                dominantCategory = categoryTotals.maxByOrNull { it.value }?.key ?: UsageCategory.UNKNOWN,
                windowMs = windowMs,
            )
        }.getOrNull()
    }

    /**
     * Maps a package to a coarse category using the Play Store category the system already
     * exposes. Only the category is retained; the package name never leaves this function.
     */
    private fun categoryOf(packageName: String): UsageCategory = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return UsageCategory.UNKNOWN
        val info = packageManager.getApplicationInfo(packageName, 0)
        when (info.category) {
            ApplicationInfo.CATEGORY_GAME -> UsageCategory.GAME
            ApplicationInfo.CATEGORY_VIDEO -> UsageCategory.VIDEO
            ApplicationInfo.CATEGORY_SOCIAL -> UsageCategory.SOCIAL
            ApplicationInfo.CATEGORY_MAPS -> UsageCategory.MAPS_NAVIGATION
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> UsageCategory.PRODUCTIVITY
            ApplicationInfo.CATEGORY_NEWS, ApplicationInfo.CATEGORY_AUDIO -> UsageCategory.OTHER
            else -> UsageCategory.OTHER
        }
    }.getOrDefault(UsageCategory.UNKNOWN)
}

data class AggregateUsage(
    val foregroundMs: Long,
    val dominantCategory: UsageCategory,
    val windowMs: Long,
) {
    /** Share of the window spent in a foreground app, 0..1. */
    val intensity: Double get() = if (windowMs > 0) (foregroundMs.toDouble() / windowMs).coerceIn(0.0, 1.0) else 0.0
}
