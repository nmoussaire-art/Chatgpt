package com.batterycast.quant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.batterycast.quant.core.system.CalendarEventProvider
import com.batterycast.quant.core.system.UsageInsightProvider
import com.batterycast.quant.forecasting.ForecastEngine
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.repository.ObservationRepository
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The offline and optional-permission guarantees, checked on a real device.
 *
 * Two claims are load-bearing for this app and neither can be verified by reading the source
 * alone: that the installed package holds no network permission, and that the core forecast works
 * with every optional permission denied.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PermissionAndOfflineTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var observationRepository: ObservationRepository

    @Inject lateinit var forecastEngine: ForecastEngine

    @Inject lateinit var usageInsightProvider: UsageInsightProvider

    @Inject lateinit var calendarEventProvider: CalendarEventProvider

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = hiltRule.inject()

    @Test
    fun theInstalledPackageHoldsNoNetworkPermission() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toList().orEmpty()

        assertThat(requested).doesNotContain(Manifest.permission.INTERNET)
        assertThat(requested).doesNotContain("android.permission.ACCESS_WIFI_STATE")
        assertThat(requested).doesNotContain("android.permission.ACCESS_FINE_LOCATION")
        assertThat(requested).doesNotContain("android.permission.ACCESS_COARSE_LOCATION")
        assertThat(requested).doesNotContain("android.permission.READ_CONTACTS")
    }

    @Test
    fun theAppRequestsOnlyThePermissionsItCanJustify() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toSet().orEmpty()

        val allowed = setOf(
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE,
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            Manifest.permission.READ_CALENDAR,
            "android.permission.PACKAGE_USAGE_STATS",
        )

        assertThat(allowed).containsAtLeastElementsIn(requested)
    }

    @Test
    fun samplingAndForecastingWorkWithEveryOptionalPermissionDenied() = runTest {
        // The instrumentation package is installed without usage access or calendar permission
        // unless a test grants them, so this is the denied-by-default path.
        assertThat(usageInsightProvider.hasUsageAccess()).isFalse()
        assertThat(calendarEventProvider.hasPermission()).isFalse()

        val observation = observationRepository.recordSample(ObservationSource.APP_FOREGROUND)
        assertThat(observation).isNotNull()

        // The engine either produces a forecast or returns null while it is still collecting.
        // Either is correct; crashing or inventing data is not.
        val context = forecastEngine.buildContext()
        assertThat(context).isNotNull()
        assertThat(context!!.maturity).isNotNull()
    }

    @Test
    fun usageInsightsReturnNothingRatherThanZeroWhenAccessIsDenied() {
        val now = System.currentTimeMillis()

        val usage = usageInsightProvider.aggregateUsage(now - 30 * 60_000, now)

        // Null means "not observed"; a zero would be a claim that no apps were used.
        assertThat(usage).isNull()
    }

    @Test
    fun calendarTargetsAreEmptyRatherThanFabricatedWhenAccessIsDenied() = runTest {
        assertThat(calendarEventProvider.upcomingEvents()).isEmpty()
    }
}
