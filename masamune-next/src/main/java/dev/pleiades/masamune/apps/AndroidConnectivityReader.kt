package dev.pleiades.masamune.apps

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.provider.Settings
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real, device-backed [ConnectivityReader] — the Android glue that turns the plain-data contract into
 * reads of `ConnectivityManager`, `WifiManager`, `BluetoothAdapter`/`BluetoothManager`,
 * `TelephonyManager`, `NfcAdapter` and `Settings.Global`.
 *
 * This is the only file in the slice that touches `android.*`, and it is compile-only from the unit
 * tests' point of view: the blocks never see it, they see [ConnectivityReader]. Keeping every framework
 * call on this side of the seam is what lets
 * [dev.pleiades.masamune.flow.runtime.impl.ConnectivityBlocks] stay JVM-testable against a fake.
 *
 * ### Honest boundaries — a missing reading is `null`, never a fabricated state
 *  - **No manager of that kind is `null`, not a guess.** A device with no `WifiManager`/`TelephonyManager`/
 *    Bluetooth/NFC adapter returns `null`, which the block routes to a named Fail — never a fabricated
 *    "off".
 *  - **A permission-refused read is `null`.** Reads guarded by a runtime permission (`ACCESS_FINE_LOCATION`
 *    for the Wi-Fi SSID, `READ_PHONE_STATE` for the mobile network type, `BLUETOOTH_CONNECT` for the
 *    connected-device set) catch the `SecurityException` and return `null` — the block Fails "by name" on a
 *    missing grant rather than pretending to know the state.
 *  - **A real "off" / "not connected" is the value, not `null`.** A radio that is genuinely disabled is a
 *    real `false`; no active network is [NetworkStatus.Disconnected]; nothing connected is an empty list.
 *    These are successful reads the block routes to NO, kept distinct from the unreadable `null`.
 *  - **No stable public API is `null`.** `wifi_ap_enabled` (soft-AP state) has no stable public getter, so
 *    it is read best-effort and returns `null` when it cannot — an honest Fail, not a fabricated `false`.
 */
class AndroidConnectivityReader(private val context: Context) : ConnectivityReader {

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val wifiManager: WifiManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val telephonyManager: TelephonyManager?
        get() = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override suspend fun isAirplaneModeEnabled(): Boolean? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON) != 0
    } catch (_: Settings.SettingNotFoundException) {
        null
    }

    override suspend fun isWifiEnabled(): Boolean? = wifiManager?.isWifiEnabled

    override suspend fun isWifiHotspotEnabled(): Boolean? {
        // Soft-AP state has no stable public getter; read it best-effort via the historically-present
        // hidden `isWifiApEnabled` and return null (an honest Fail) when reflection cannot reach it.
        val manager = wifiManager ?: return null
        return try {
            val method = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(manager) as? Boolean
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun wifiConnection(): WifiConnection? {
        val cm = connectivityManager ?: return null
        val manager = wifiManager ?: return null
        val onWifi = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        if (!onWifi) return WifiConnection.Disconnected
        val info = try {
            @Suppress("DEPRECATION")
            manager.connectionInfo
        } catch (_: SecurityException) {
            return null
        } ?: return null
        @Suppress("DEPRECATION")
        val rawSsid = info.ssid
        // A hidden SSID ("<unknown ssid>") means the location grant is absent — an unreadable association.
        if (rawSsid == null || rawSsid == UNKNOWN_SSID) return null
        val ssid = rawSsid.trim('"').takeIf { it.isNotBlank() } ?: return null
        @Suppress("DEPRECATION")
        val ip = info.ipAddress.takeIf { it != 0 }?.let { raw ->
            "%d.%d.%d.%d".format(raw and 0xff, raw shr 8 and 0xff, raw shr 16 and 0xff, raw shr 24 and 0xff)
        }
        @Suppress("DEPRECATION")
        val speed = info.linkSpeed.takeIf { it >= 0 }
        @Suppress("DEPRECATION")
        val freq = info.frequency.takeIf { it > 0 }
        @Suppress("DEPRECATION")
        val bssid = info.bssid
        return WifiConnection.Connected(
            WifiConnectionInfo(
                ssid = ssid,
                bssid = bssid,
                linkSpeedMbps = speed,
                frequencyMhz = freq,
                capabilities = null,
                ipAddress = ip,
            ),
        )
    }

    override suspend fun wifiSignalLevel(): Int? {
        val manager = wifiManager ?: return null
        val info = try {
            @Suppress("DEPRECATION")
            manager.connectionInfo
        } catch (_: SecurityException) {
            return null
        } ?: return null
        @Suppress("DEPRECATION")
        val networkId = info.networkId
        if (networkId == -1) return null // not associated → no signal level to read
        @Suppress("DEPRECATION")
        return info.rssi
    }

    override suspend fun isBluetoothEnabled(): Boolean? = bluetoothAdapter?.isEnabled

    override suspend fun connectedBluetoothDevices(): List<BluetoothDeviceInfo>? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        manager.adapter ?: return null
        return try {
            val profiles = intArrayOf(BluetoothProfile.GATT, BluetoothProfile.HEADSET, BluetoothProfile.A2DP)
            profiles.asSequence()
                .flatMap { profile -> manager.getConnectedDevices(profile).asSequence() }
                .distinctBy { it.address }
                .map { it.toInfo() }
                .toList()
        } catch (_: SecurityException) {
            null // BLUETOOTH_CONNECT not granted — honest null, the block Fails by name
        }
    }

    override suspend fun isNfcEnabled(): Boolean? {
        @Suppress("DEPRECATION")
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return null
        return adapter.isEnabled
    }

    override suspend fun isMobileDataEnabled(): Boolean? {
        val tm = telephonyManager ?: return null
        return try {
            tm.isDataEnabled
        } catch (_: SecurityException) {
            null
        }
    }

    override suspend fun mobileDataNetworkType(): MobileNetworkGeneration? {
        val tm = telephonyManager ?: return null
        return try {
            @Suppress("DEPRECATION")
            tm.dataNetworkType.toGeneration()
        } catch (_: SecurityException) {
            null // READ_PHONE_STATE not granted — honest null, the block Fails by name
        }
    }

    override suspend fun activeNetwork(): NetworkStatus? = withContext(Dispatchers.IO) {
        val cm = connectivityManager ?: return@withContext null
        val active = cm.activeNetwork ?: return@withContext NetworkStatus.Disconnected
        val caps = cm.getNetworkCapabilities(active) ?: return@withContext NetworkStatus.Disconnected
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return@withContext NetworkStatus.Disconnected
        }
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.MOBILE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkTransport.BLUETOOTH
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            else -> NetworkTransport.OTHER
        }
        NetworkStatus.Connected(transport, transport.displayName)
    }

    /** Reduce an `android.bluetooth.BluetoothDevice` to the plain-data [BluetoothDeviceInfo]. */
    private fun BluetoothDevice.toInfo(): BluetoothDeviceInfo {
        val name = try {
            this.name
        } catch (_: SecurityException) {
            null
        }
        val bonded = try {
            bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }
        val deviceClass = try {
            bluetoothClass?.majorDeviceClass?.let { "0x%04X".format(it) }
        } catch (_: SecurityException) {
            null
        }
        return BluetoothDeviceInfo(
            address = address,
            name = name,
            deviceClass = deviceClass,
            paired = bonded,
        )
    }

    private companion object {
        /** The placeholder `WifiInfo.getSSID()` returns when the caller lacks location access. */
        const val UNKNOWN_SSID = "<unknown ssid>"
    }

    /** Map a `TelephonyManager.NETWORK_TYPE_*` value to a coarse [MobileNetworkGeneration]. */
    private fun Int.toGeneration(): MobileNetworkGeneration = when (this) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,
        TelephonyManager.NETWORK_TYPE_GSM -> MobileNetworkGeneration.GEN_2G
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> MobileNetworkGeneration.GEN_3G
        TelephonyManager.NETWORK_TYPE_LTE,
        TelephonyManager.NETWORK_TYPE_IWLAN -> MobileNetworkGeneration.GEN_4G
        TelephonyManager.NETWORK_TYPE_NR -> MobileNetworkGeneration.GEN_5G
        else -> MobileNetworkGeneration.UNKNOWN
    }
}
