package com.batterycast.quant.core.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.batterycast.quant.telemetry.model.BluetoothState
import com.batterycast.quant.telemetry.model.NetworkType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the privacy-safe slice of device state that actually explains battery drain.
 *
 * Every call is a plain system-service query — no polling, no listeners held open, no wake locks.
 * Nothing here reads message contents, browsing history, notifications, typed text, or documents.
 */
@Singleton
class DeviceStateProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val powerManager: PowerManager? = context.getSystemService()
    private val connectivityManager: ConnectivityManager? = context.getSystemService()

    fun isScreenInteractive(): Boolean = runCatching {
        powerManager?.isInteractive ?: false
    }.getOrDefault(false)

    fun isPowerSaveMode(): Boolean = runCatching {
        powerManager?.isPowerSaveMode ?: false
    }.getOrDefault(false)

    fun isDeviceIdleMode(): Boolean = runCatching {
        powerManager?.isDeviceIdleMode ?: false
    }.getOrDefault(false)

    /**
     * Thermal status, available from API 29.
     *
     * Returns null rather than a neutral value on older or non-compliant devices, so the model
     * can widen its uncertainty instead of assuming the device is cool.
     */
    fun thermalStatusRaw(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { powerManager?.currentThermalStatus }.getOrNull()
    }

    /**
     * Coarse transport type of the active network.
     *
     * Only the transport is read. No SSID, no addresses, no traffic, no permission beyond the
     * normal `ACCESS_NETWORK_STATE`.
     */
    fun networkType(): NetworkType {
        val manager = connectivityManager ?: return NetworkType.UNKNOWN
        return runCatching {
            val network = manager.activeNetwork ?: return NetworkType.OFFLINE
            val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkType.OFFLINE
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkType.BLUETOOTH_TETHER
                else -> NetworkType.UNKNOWN
            }
        }.getOrDefault(NetworkType.UNKNOWN)
    }

    /**
     * Whether the Bluetooth radio is on.
     *
     * From API 31 `BluetoothAdapter.isEnabled` needs `BLUETOOTH_CONNECT`, which this app does not
     * request — a radio on/off flag is not worth a runtime permission prompt. The state falls
     * back to [BluetoothState.UNKNOWN] and the model treats it as an unobserved dimension.
     */
    fun bluetoothState(): BluetoothState = runCatching {
        val adapter: BluetoothAdapter? = context.getSystemService<BluetoothManager>()?.adapter
        when {
            adapter == null -> BluetoothState.UNKNOWN
            adapter.isEnabled -> BluetoothState.ON
            else -> BluetoothState.OFF
        }
    }.getOrDefault(BluetoothState.UNKNOWN)
}
