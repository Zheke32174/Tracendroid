package dev.pleiades.masamune.apps

/**
 * The seam between the Connectivity-category block impls and the real device radio/network stack.
 *
 * Every way an unprivileged, one-shot Connectivity block can *read* the device's connection state — is
 * Wi-Fi/Bluetooth/NFC/mobile-data/airplane-mode on, what network is currently active and of what type,
 * what Wi-Fi network is joined and how strong its signal, which Bluetooth devices are connected — is one
 * method here, and — exactly like [AppInspector] does for the Apps blocks, [SystemSettings] for the
 * Settings blocks, [PowerState] for the Battery&Power blocks, [SensorReader] for the Sensor blocks and
 * [LocationReader] for the Location blocks — there is deliberately nothing `android.*` on this interface.
 * That single constraint is what buys the whole slice its JVM-testability:
 * [dev.pleiades.masamune.flow.runtime.impl.ConnectivityBlocks] depend on this plain-data contract, never
 * on `ConnectivityManager`, `WifiManager`, `BluetoothAdapter`, `TelephonyManager` or `NfcAdapter`, so
 * every block and all its branch logic can be exercised against a fake on an ordinary unit-test JVM. A
 * device is needed to *run* these blocks, never to *test* their logic.
 *
 * ### The honest gate has one clean shape here too
 * When the app process (the only thing that can hand out a real [AndroidConnectivityReader]) is not wired
 * in, there is simply no seam, and a block that cannot get one fails visibly by name
 * ([dev.pleiades.masamune.flow.runtime.impl.CONNECTIVITY_ABSENT]) rather than reporting a radio state it
 * never actually read.
 *
 * ### Honest failure shapes: not-readable vs. a real "off / not connected"
 * The reads model two genuinely different "negative" cases, and keeping them distinct is the whole point
 * of the honest-gating rule:
 *  - **`null` means "could not be read".** No manager of that kind exists on the device, or the read
 *    needs a runtime permission the process was not granted (the real impl catches the `SecurityException`
 *    and returns `null`). The block routes `null` to a visible
 *    [dev.pleiades.masamune.flow.runtime.Outcome.Fail] **by name** — it never fabricates a `false`/`0`/an
 *    empty connection a downstream block would trust as a real reading.
 *  - **A real `false` / [WifiConnection.Disconnected] / [NetworkStatus.Disconnected] / an empty device
 *    list means "read fine, and the answer is off / nothing connected".** A radio that is genuinely off
 *    is a successful read with a NO answer, distinct from a radio whose state could not be determined at
 *    all. A `Boolean?` here is a real three-valued read: `false` routes NO, `null` routes a named Fail.
 *
 * ### Runtime permissions shape the *run-time* failure, never keep a read unregistered
 * Some reads carry a dangerous-but-ordinary runtime permission the Connectivity category is predicated on:
 * `wifi_network_connected`'s SSID needs `ACCESS_FINE_LOCATION`, and `mobile_data_network_type` needs
 * `READ_PHONE_STATE`. Exactly as `location_get`/`location_at` are registered despite carrying
 * `ACCESS_FINE_LOCATION`, these reads *are* registered: the honest gate for a missing grant is the seam
 * returning `null` and the block failing **by name**, not a fabricated value and not leaving the block
 * unregistered. A `Requirement.Uid2000` (SHELL) tag is the opposite — those blocks have no method here and
 * are gated by omission (see [dev.pleiades.masamune.flow.runtime.impl.connectivityLookup]'s KDoc), exactly
 * as the SHELL writes are gated in the Battery&Power slice.
 *
 * This slice is entirely read-only: everything it can touch is unprivileged connection *state*, so there
 * is no write result-type here. Every catalog block that *toggles* a radio (enable/disable Wi-Fi,
 * Bluetooth, NFC, mobile data, tethering, hotspot), *connects/disconnects/pairs* a device, or *triggers a
 * scan* has no method here and is gated by omission — a read-only seam does not turn radios on.
 *
 * Every method is `suspend` because a radio/network read can touch a blocking system service; the real
 * impl does so off the caller's thread without the contract changing shape, and the fake simply returns.
 */
interface ConnectivityReader {

    /**
     * Whether airplane mode is currently on (all radios commanded off), or `null` when the state cannot
     * be determined. A real `Boolean?`: `false` ("airplane mode is off") routes the
     * `airplane_mode_enabled` decision to NO, `null` routes it to a named Fail.
     */
    suspend fun isAirplaneModeEnabled(): Boolean?

    /**
     * Whether Wi-Fi is currently enabled, or `null` when there is no `WifiManager` to ask. `false` is a
     * real "Wi-Fi is off" (NO); `null` is "could not read" (a named Fail).
     */
    suspend fun isWifiEnabled(): Boolean?

    /**
     * Whether the Wi-Fi hotspot (soft AP) is currently enabled, or `null` when the state cannot be read.
     *
     * Reading soft-AP state has no stable public API on modern Android, so the real impl reads it
     * best-effort and returns `null` when it cannot — the honest "could not be read" that the
     * `wifi_ap_enabled` decision routes to a named Fail, never a fabricated `false`.
     */
    suspend fun isWifiHotspotEnabled(): Boolean?

    /**
     * The device's current Wi-Fi association: [WifiConnection.Connected] with the readable connection
     * info, [WifiConnection.Disconnected] when Wi-Fi is not joined to a network, or `null` when the
     * connection cannot be read — most often because the SSID needs `ACCESS_FINE_LOCATION` that the
     * process was not granted (the real impl returns `null` rather than a `"<unknown ssid>"` placeholder).
     *
     * Three-valued for the same reason [LocationReader.reverseGeocode] is: "joined this network" is YES,
     * "not joined" is NO, and "cannot read the association" is a named Fail — distinctions a plain nullable
     * info object could not carry.
     */
    suspend fun wifiConnection(): WifiConnection?

    /**
     * The current Wi-Fi signal strength as the value the `wifi_signal_level` band compares (the joined
     * network's RSSI in dBm), or `null` when Wi-Fi is not connected or the level cannot be read. `null`
     * routes a named Fail; a real level is bound to `varLevel` and compared against the requested band.
     */
    suspend fun wifiSignalLevel(): Int?

    /**
     * Whether Bluetooth is currently enabled, or `null` when the device has no Bluetooth adapter. `false`
     * routes the `bluetooth_enabled` decision to NO; `null` routes a named Fail.
     */
    suspend fun isBluetoothEnabled(): Boolean?

    /**
     * The Bluetooth devices currently connected to this device, as plain data, or `null` when the adapter
     * is absent or the read needs a `BLUETOOTH_CONNECT` grant the process does not hold (the real impl
     * catches the `SecurityException` and returns `null`).
     *
     * An **empty list** is a real read: "nothing is connected" — the `bluetooth_device_connected` decision
     * routes it to NO. `null` is "could not read the connected set" — a named Fail. The block filters this
     * list by its `deviceAddress`/`deviceName`/`deviceClass`/`paired` arguments and routes YES on the first
     * match.
     */
    suspend fun connectedBluetoothDevices(): List<BluetoothDeviceInfo>?

    /**
     * Whether NFC is currently enabled, or `null` when the device has no NFC adapter. `false` routes the
     * `nfc_enabled` decision to NO; `null` routes a named Fail.
     */
    suspend fun isNfcEnabled(): Boolean?

    /**
     * Whether mobile data is currently enabled for the default data subscription, or `null` when there is
     * no `TelephonyManager` or the state cannot be read. `false` routes `mobile_data_enabled` to NO; `null`
     * routes a named Fail.
     */
    suspend fun isMobileDataEnabled(): Boolean?

    /**
     * The active mobile-data network generation (2G/3G/4G/5G) for the default subscription, or `null` when
     * it cannot be read — most often because it needs the `READ_PHONE_STATE` grant the process was not
     * given (the real impl catches the `SecurityException` and returns `null`).
     *
     * [MobileNetworkGeneration.UNKNOWN] is a real read: "there is no active mobile data network / its type
     * is not recognized" — the `mobile_data_network_type` decision routes it to NO. `null` is "could not
     * read the type at all" — a named Fail. The two must not be conflated, so the unknown case is a real
     * enum member, not `null`.
     */
    suspend fun mobileDataNetworkType(): MobileNetworkGeneration?

    /**
     * The device's currently active internet network: [NetworkStatus.Connected] with its transport and a
     * human name, [NetworkStatus.Disconnected] when nothing is connected, or `null` when the network state
     * cannot be read (no `ConnectivityManager`). Backs both `network_connected` (YES/NO on connected) and
     * `network_type` (YES/NO plus the bound type/name). Three-valued so "not connected" (NO) is distinct
     * from "cannot read" (a named Fail).
     */
    suspend fun activeNetwork(): NetworkStatus?
}

/**
 * The device's Wi-Fi association state that the seam *could* read. Distinct from the seam method's `null`
 * (the association could not be read at all), so the `wifi_network_connected` decision tells "not joined"
 * (NO) apart from "cannot read" (a named Fail).
 */
sealed interface WifiConnection {
    /** Wi-Fi is joined to a network — the decision's YES branch, carrying the readable [info]. */
    data class Connected(val info: WifiConnectionInfo) : WifiConnection

    /** Wi-Fi is not joined to any network — the decision's NO branch. */
    data object Disconnected : WifiConnection
}

/**
 * One Wi-Fi association, reduced to the values the catalog's `wifi_network_connected` outputs expect, as
 * plain data. Every field is nullable because a real association fills only the fields the platform and
 * the process's permissions expose; the block binds each present field and leaves the absent ones
 * **unbound** rather than a fabricated blank — never an empty SSID a flow would treat as a real network.
 *
 *  - [ssid] — the joined network name (`varConnectedSsid`).
 *  - [bssid] — the access point's MAC address (`varConnectedBssid`).
 *  - [linkSpeedMbps] — the current link speed in Mbps (`varConnectedLinkSpeed`).
 *  - [frequencyMhz] — the channel frequency in MHz (`varConnectedFrequency`).
 *  - [capabilities] — the network's security description (`varConnectedCapabilities`).
 *  - [ipAddress] — the device's IP address on this network (`varConnectedIpAddress`).
 */
data class WifiConnectionInfo(
    val ssid: String? = null,
    val bssid: String? = null,
    val linkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val capabilities: String? = null,
    val ipAddress: String? = null,
)

/**
 * One connected Bluetooth device, reduced to the values the catalog's `bluetooth_device_connected`
 * filters and outputs use, as plain data.
 *
 *  - [address] — the device's Bluetooth MAC address (filter/`varConnectedDeviceAddress`).
 *  - [name] — the device's friendly name (filter/`varConnectedDeviceName`).
 *  - [deviceClass] — the device's major class as a human string (filter/`varConnectedDeviceClass`).
 *  - [paired] — whether the device is bonded/paired, for the block's `paired` flag filter.
 */
data class BluetoothDeviceInfo(
    val address: String? = null,
    val name: String? = null,
    val deviceClass: String? = null,
    val paired: Boolean = false,
)

/**
 * The transport carrying the device's active internet network, as plain data — a real enum rather than a
 * leaked `NetworkCapabilities.TRANSPORT_*` int. The mapping from an Android transport to this enum lives
 * entirely in [AndroidConnectivityReader], so nothing `android.*` crosses the seam. [OTHER] carries any
 * transport this minimal enum does not name.
 */
enum class NetworkTransport(val displayName: String) {
    WIFI("Wi-Fi"),
    MOBILE("Mobile"),
    ETHERNET("Ethernet"),
    BLUETOOTH("Bluetooth"),
    VPN("VPN"),
    OTHER("Other"),
}

/**
 * The device's active-internet-network state that the seam *could* read. Distinct from the seam method's
 * `null` (the network state could not be read), so `network_connected`/`network_type` tell "nothing
 * connected" (NO) apart from "cannot read" (a named Fail).
 */
sealed interface NetworkStatus {
    /**
     * An internet network is active — the YES branch. [transport] is the coarse carrier and [typeName] a
     * human-readable name for it (bound to `varNetworkType` / `varNetworkTypeName`).
     */
    data class Connected(val transport: NetworkTransport, val typeName: String) : NetworkStatus

    /** No internet network is active — the NO branch. */
    data object Disconnected : NetworkStatus
}

/**
 * The active mobile-data network generation, as plain data — a real enum rather than a leaked
 * `TelephonyManager.NETWORK_TYPE_*` int. The mapping from an Android network type to a generation lives
 * entirely in [AndroidConnectivityReader], so nothing `android.*` crosses the seam.
 *
 * [UNKNOWN] is a real, successful read meaning "no active mobile data / an unrecognized type", distinct
 * from the seam method's `null` (the type could not be read at all) — the `mobile_data_network_type`
 * decision routes [UNKNOWN] to NO and `null` to a named Fail.
 */
enum class MobileNetworkGeneration(val label: String) {
    UNKNOWN("Unknown"),
    GEN_2G("2G"),
    GEN_3G("3G"),
    GEN_4G("4G"),
    GEN_5G("5G"),
}
