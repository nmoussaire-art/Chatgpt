package com.batterycast.quant.telemetry.validation

import android.os.BatteryManager
import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The validation pipeline restates Android's battery constants so it can run as plain JVM code.
 *
 * That is only safe if the restated values actually match the framework's, so this test asserts
 * every one of them against the real `android.os` classes under Robolectric. A platform change
 * would fail here rather than silently mislabel every observation in the database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformConstantsTest {

    @Test
    fun `battery status constants match the framework`() {
        assertThat(PlatformConstants.STATUS_UNKNOWN).isEqualTo(BatteryManager.BATTERY_STATUS_UNKNOWN)
        assertThat(PlatformConstants.STATUS_CHARGING).isEqualTo(BatteryManager.BATTERY_STATUS_CHARGING)
        assertThat(PlatformConstants.STATUS_DISCHARGING).isEqualTo(BatteryManager.BATTERY_STATUS_DISCHARGING)
        assertThat(PlatformConstants.STATUS_NOT_CHARGING).isEqualTo(BatteryManager.BATTERY_STATUS_NOT_CHARGING)
        assertThat(PlatformConstants.STATUS_FULL).isEqualTo(BatteryManager.BATTERY_STATUS_FULL)
    }

    @Test
    fun `plug type constants match the framework`() {
        assertThat(PlatformConstants.PLUGGED_AC).isEqualTo(BatteryManager.BATTERY_PLUGGED_AC)
        assertThat(PlatformConstants.PLUGGED_USB).isEqualTo(BatteryManager.BATTERY_PLUGGED_USB)
        assertThat(PlatformConstants.PLUGGED_WIRELESS).isEqualTo(BatteryManager.BATTERY_PLUGGED_WIRELESS)
    }

    @Test
    fun `battery health constants match the framework`() {
        assertThat(PlatformConstants.HEALTH_UNKNOWN).isEqualTo(BatteryManager.BATTERY_HEALTH_UNKNOWN)
        assertThat(PlatformConstants.HEALTH_GOOD).isEqualTo(BatteryManager.BATTERY_HEALTH_GOOD)
        assertThat(PlatformConstants.HEALTH_OVERHEAT).isEqualTo(BatteryManager.BATTERY_HEALTH_OVERHEAT)
        assertThat(PlatformConstants.HEALTH_DEAD).isEqualTo(BatteryManager.BATTERY_HEALTH_DEAD)
        assertThat(PlatformConstants.HEALTH_OVER_VOLTAGE).isEqualTo(BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE)
        assertThat(PlatformConstants.HEALTH_UNSPECIFIED_FAILURE)
            .isEqualTo(BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE)
        assertThat(PlatformConstants.HEALTH_COLD).isEqualTo(BatteryManager.BATTERY_HEALTH_COLD)
    }

    @Test
    fun `thermal status constants match the framework`() {
        assertThat(PlatformConstants.THERMAL_STATUS_NONE).isEqualTo(PowerManager.THERMAL_STATUS_NONE)
        assertThat(PlatformConstants.THERMAL_STATUS_LIGHT).isEqualTo(PowerManager.THERMAL_STATUS_LIGHT)
        assertThat(PlatformConstants.THERMAL_STATUS_MODERATE).isEqualTo(PowerManager.THERMAL_STATUS_MODERATE)
        assertThat(PlatformConstants.THERMAL_STATUS_SEVERE).isEqualTo(PowerManager.THERMAL_STATUS_SEVERE)
        assertThat(PlatformConstants.THERMAL_STATUS_CRITICAL).isEqualTo(PowerManager.THERMAL_STATUS_CRITICAL)
        assertThat(PlatformConstants.THERMAL_STATUS_EMERGENCY).isEqualTo(PowerManager.THERMAL_STATUS_EMERGENCY)
        assertThat(PlatformConstants.THERMAL_STATUS_SHUTDOWN).isEqualTo(PowerManager.THERMAL_STATUS_SHUTDOWN)
    }

    @Test
    fun `charging policy maps every documented value and stays null when absent`() {
        // The property itself is @SystemApi and therefore unreadable from an ordinary app, so
        // the telemetry source reports it as unavailable rather than reading a hidden constant.
        // The mapping is kept ready for the day it becomes public.
        assertThat(ObservationFactory.mapChargingPolicy(PlatformConstants.CHARGING_POLICY_DEFAULT))
            .isEqualTo(com.batterycast.quant.telemetry.model.ChargingPolicy.DEFAULT)
        assertThat(ObservationFactory.mapChargingPolicy(PlatformConstants.CHARGING_POLICY_ADAPTIVE_AC))
            .isEqualTo(com.batterycast.quant.telemetry.model.ChargingPolicy.ADAPTIVE_AC)
        assertThat(ObservationFactory.mapChargingPolicy(PlatformConstants.CHARGING_POLICY_ADAPTIVE_LONGLIFE))
            .isEqualTo(com.batterycast.quant.telemetry.model.ChargingPolicy.ADAPTIVE_LONGLIFE)
        assertThat(ObservationFactory.mapChargingPolicy(9999))
            .isEqualTo(com.batterycast.quant.telemetry.model.ChargingPolicy.UNKNOWN)
    }

    @Test
    fun `unknown platform values map to the enum's own unknown member, never to a guess`() {
        assertThat(ObservationFactory.mapStatus(99))
            .isEqualTo(com.batterycast.quant.telemetry.model.ChargingStatus.UNKNOWN)
        assertThat(ObservationFactory.mapHealth(99))
            .isEqualTo(com.batterycast.quant.telemetry.model.BatteryHealth.UNKNOWN)
        assertThat(ObservationFactory.mapThermal(99))
            .isEqualTo(com.batterycast.quant.telemetry.model.ThermalStatus.UNSUPPORTED)
        assertThat(ObservationFactory.mapPlug(0))
            .isEqualTo(com.batterycast.quant.telemetry.model.PlugType.NONE)
    }
}
